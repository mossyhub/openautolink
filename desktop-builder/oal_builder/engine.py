"""Local identity, managed build orchestration and encrypted identity backups.

The same-user native Gradle process is NOT a security sandbox. Only the fixed
upstream repository is executed; secrets are never passed as Gradle properties
or environment variables. Backups intentionally exclude CarPlay credentials.
"""
from __future__ import annotations

import datetime as dt
import io
import json
import os
import re
import secrets
import signal
import subprocess
import time
import xml.etree.ElementTree as ET
import shutil
import tempfile
import threading
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from cryptography import x509
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

from .toolchain import BuilderError, Toolchain, file_digest, remove_managed
from .releases import MAX_DESCRIPTOR_SIZE, resolve_latest_release, resolve_template, verify_template_download


@dataclass(frozen=True)
class BuildConfig:
    workspace: Path
    app_id: str
    carplay_key: Path | None = None
    carplay_certificate: Path | None = None
    accept_licenses: bool = False
    mode: str = 'personalize'


def suggest_app_id() -> str:
    return 'com.openautolink.personal.u' + secrets.token_hex(8)


# Shared with the clean-interpreter template publication verifier.
from .app_identity import validate_app_id, validate_personal_app_id


def atomic_write(path, data, mode=0o600):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise BuilderError('Refusing to replace a managed symlink')
    fd, name = tempfile.mkstemp(prefix='.' + path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(name, mode)
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def atomic_json(path, value):
    atomic_write(path, (json.dumps(value, indent=2, sort_keys=True) + '\n').encode())


def managed_workspace(workspace):
    workspace = Path(workspace).expanduser()
    if workspace.is_symlink():
        raise BuilderError('Managed workspace must not be a symlink')
    workspace = workspace.resolve()
    if workspace == Path(workspace.anchor) or workspace == Path.home().resolve():
        raise BuilderError('Choose a dedicated managed workspace, not your home or filesystem root')
    workspace.mkdir(parents=True, exist_ok=True)
    marker = workspace / '.oal-workspace.json'
    if marker.is_symlink():
        raise BuilderError('Unsafe managed workspace marker')
    if not marker.exists():
        if any(workspace.iterdir()):
            raise BuilderError('Choose an empty dedicated workspace; refusing unmanaged existing files')
        atomic_json(marker, {'schema': 1, 'owner': 'openautolink-desktop-builder'})
    else:
        try:
            if marker.stat().st_size > 1024 or json.loads(marker.read_text()) != {'schema': 1, 'owner': 'openautolink-desktop-builder'}:
                raise ValueError()
        except (ValueError, OSError) as exc:
            raise BuilderError('Invalid managed workspace marker; choose a new dedicated workspace') from exc
    for name in ('tools', 'source', 'cache', 'private', 'output'):
        path = workspace / name
        if path.is_symlink():
            raise BuilderError('Managed directories must not be symlinks')
        path.mkdir(exist_ok=True)
    (workspace / 'private').chmod(0o700)
    return workspace


@contextmanager
def workspace_lock(workspace):
    workspace = managed_workspace(workspace)
    path = workspace / '.builder.lock'
    if path.is_symlink():
        raise BuilderError('Unsafe workspace lock')
    with path.open('a+b') as handle:
        # Windows byte locks are mandatory even for reads by a contender.
        # _locking permits a range past EOF, so neither initialization nor
        # reading a sentinel byte is needed before acquiring ownership.
        handle.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            raise BuilderError('Another builder is already using this workspace') from exc
        try:
            yield workspace
        finally:
            handle.seek(0)
            if os.name == 'nt':
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def validate_state(state):
    if not isinstance(state, dict) or state.get('schema') != 1:
        raise BuilderError('Invalid saved identity state')
    validate_app_id(state.get('applicationId'))
    code = state.get('lastVersionCode')
    if type(code) is not int or not 0 <= code <= 2100000000:
        raise BuilderError('Invalid saved version code')
    if not re.fullmatch('[0-9a-f]{64}', state.get('signingCertificateSha256', '')):
        raise BuilderError('Invalid saved signing identity fingerprint')
    if state.get('carplayBackupPolicy') != 'excluded-reselect-files-after-restore':
        raise BuilderError('Invalid identity backup policy')
    return state


def load_state(workspace) -> dict | None:
    identity = Path(workspace).expanduser() / 'private/identity'
    if identity.is_symlink() or identity.parent.is_symlink():
        raise BuilderError('Unsafe identity directory')
    path = identity / 'state.json'
    if not path.exists():
        if identity.exists() and any(identity.iterdir()):
            raise BuilderError('Saved identity state is missing; restore your encrypted backup')
        return None
    try:
        if path.is_symlink() or path.stat().st_size > 65536:
            raise ValueError()
        return validate_state(json.loads(path.read_text('utf-8')))
    except (OSError, ValueError, TypeError) as exc:
        raise BuilderError('Saved identity is damaged; restore your encrypted backup') from exc


def read_identity(identity, state):
    key_path, password_path = identity / 'upload-key.p12', identity / 'password'
    if any(p.is_symlink() or not p.is_file() for p in (key_path, password_path)):
        raise BuilderError('Signing key or password is missing; restore your encrypted backup, do not generate a replacement')
    try:
        if key_path.stat().st_size > 1024**2 or password_path.stat().st_size > 1024:
            raise ValueError()
        key, cert, extras = pkcs12.load_key_and_certificates(key_path.read_bytes(), password_path.read_bytes().strip())
        if key is None or cert is None or extras or cert.fingerprint(hashes.SHA256()).hex() != state['signingCertificateSha256']:
            raise ValueError()
        return key, cert
    except (ValueError, TypeError, OSError) as exc:
        raise BuilderError('Saved signing key does not match this immutable identity') from exc


class Builder:
    def __init__(self, config: BuildConfig, progress: Callable[[str, str], None], cancel_event: threading.Event | None = None):
        self.config = config
        self.progress = progress
        self.cancel_event = cancel_event if cancel_event is not None else threading.Event()
        self.workspace = Path(config.workspace).expanduser().resolve()

    def _run(self, argv, phase, cwd=None):
        if self.cancel_event.is_set():
            raise BuilderError('Build cancelled')
        output = run_process(argv, cwd or self.workspace, self.tools['env'], self.cancel_event, self.progress, phase)
        if self.cancel_event.is_set():
            raise BuilderError('Build cancelled')
        return output

    def _verify(self, bundle, expected, state, carplay):
        java = [self.tools['java'], '-Duser.language=en', '-Duser.country=US', '-jar', self.tools['bundletool']]
        self._run(java + ['validate', '--bundle=' + str(bundle)], 'verify')
        report = self._run([self.tools['jarsigner'], '-J-Duser.language=en', '-J-Duser.country=US', '-verify', str(bundle)], 'verify')
        if 'jar verified.' not in report or 'unsigned entries' in report.lower() or 'jar is unsigned' in report.lower():
            raise BuilderError('AAB signature verification failed or bundle has unsigned entries')
        manifest = self._run(java + ['dump', 'manifest', '--bundle=' + str(bundle), '--module=base'], 'verify')
        try:
            root = ET.fromstring(manifest)
            ns = '{http://schemas.android.com/apk/res/android}'
            actual = (root.get('package'), root.get(ns + 'versionCode'), root.get(ns + 'versionName'))
            if actual != (expected['applicationId'], str(expected['versionCode']), expected['versionName']):
                raise BuilderError('AAB manifest application ID or version does not match requested identity')
        except ET.ParseError as exc:
            raise BuilderError('Could not parse verified bundle manifest') from exc
        pem = self._run([self.tools['keytool'], '-J-Duser.language=en', '-printcert', '-rfc', '-jarfile', str(bundle)], 'verify')
        certs = re.findall(r'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', pem, re.S)
        try:
            fingerprints = {x509.load_pem_x509_certificate(cert.encode()).fingerprint(hashes.SHA256()).hex() for cert in certs}
        except ValueError as exc:
            raise BuilderError('Cannot read AAB signing certificate') from exc
        if fingerprints != {state['signingCertificateSha256']}:
            raise BuilderError('AAB signing certificate does not match the saved upload identity')
        verify_bundle_entries(bundle, carplay)

    def build(self) -> dict:
        validate_app_id(self.config.app_id)
        if self.config.mode not in {'personalize', 'source'}:
            raise BuilderError('Unknown build mode; choose personalization or source compilation')
        if self.config.mode == 'source' and not self.config.accept_licenses:
            raise BuilderError('Android SDK license acceptance is required')
        if self.cancel_event.is_set():
            raise BuilderError('Build cancelled')
        # Read-only preflight: reject incompatible new IDs before the lock creates
        # a workspace. Existing source identities retain their update path.
        if self.config.mode == 'personalize' or load_state(self.config.workspace) is None:
            validate_personal_app_id(self.config.app_id)
        with workspace_lock(self.config.workspace) as self.workspace:
            state = self._prepare_identity()
            carplay = self._prepare_carplay()
            pending = None
            reserved = False
            personalization = self.workspace / 'private/personalization'
            try:
                self.progress('identity', 'Local upload identity ready; back it up before publishing')
                manager = Toolchain(self.workspace, self.progress, self.cancel_event)
                self.progress('release', 'Resolving the latest stable upstream release')
                upstream = resolve_latest_release(manager.downloader.json)
                manager.downloader.check_cancel()
                template = None
                if self.config.mode == 'personalize':
                    template = resolve_template(upstream, lambda url: manager.downloader.metadata(url, max_bytes=MAX_DESCRIPTOR_SIZE))
                    item = template['templateAsset']
                    downloaded = manager.downloader.download(template['url'], item['sha256'], size=item['size'])
                    downloaded = verify_template_download(downloaded, template)
                    self.tools = manager.install_personalization()
                else:
                    self.tools = manager.install(self.config.accept_licenses)
                    upstream = manager.prepare_source(upstream)
                source = self.workspace / 'source'
                if self.config.mode == 'source':
                    wrapper_properties = (source / 'gradle/wrapper/gradle-wrapper.properties').read_text()
                    if not re.search(r'^distributionSha256Sum=[0-9a-f]{64}\s*$', wrapper_properties, re.M):
                        raise BuilderError('Upstream Gradle wrapper is missing a pinned SHA-256 checksum')
                    if not (source / 'gradle/verification-metadata.xml').is_file():
                        raise BuilderError('Upstream dependency verification metadata is missing')
                    script = (source / 'app/build.gradle.kts').read_text()
                    required = ('oalBuilderUnsigned', 'oalVersionCode', 'oalVersionName', 'appId') + (('oalCarPlayMfiAssetsDir',) if carplay else ())
                    if any(value not in script for value in required):
                        raise BuilderError('Latest upstream release does not support this managed builder; wait for a compatible release')
                if state['lastVersionCode'] >= 2100000000:
                    raise BuilderError('Android version code is exhausted')
                code = state['lastVersionCode'] + 1
                # Reserve before compilation/signing. Crashes consume a code, never reuse one.
                state.update(lastVersionCode=code, lastAttemptStatus='building', lastVersionName=upstream['versionName'], lastSourceCommit=upstream['sourceCommit'])
                atomic_json(self.workspace / 'private/identity/state.json', state)
                reserved = True
                result = {'applicationId': self.config.app_id, 'versionCode': code, 'versionName': upstream['versionName'], 'sourceCommit': upstream['sourceCommit'], 'sourceRef': upstream['sourceRef'], 'sourceOverlays': upstream.get('sourceOverlays', []), 'signingCertificateSha256': state['signingCertificateSha256'], 'carplayIncluded': bool(carplay)}
                result['mode'] = self.config.mode
                if template is not None:
                    from .personalize import personalize_bundle
                    remove_managed(personalization)
                    personalization.mkdir(mode=0o700)
                    unsigned = personalization / 'unsigned.aab'
                    self.progress('build', 'Personalizing the verified release template without compiling the app')
                    personalize_bundle(downloaded, unsigned,
                        original_app_id=template['templateApplicationId'], app_id=self.config.app_id,
                        version_code=code, java=self.tools['java'], javac=self.tools['javac'],
                        bundletool=self.tools['bundletool'], workdir=personalization,
                        run=self._run, carplay=carplay)
                    result['templateSha256'] = template['templateAsset']['sha256']
                else:
                    wrapper = source / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
                    if os.name != 'nt':
                        wrapper.chmod(0o755)
                    argv = [str(wrapper), ':app:bundleRelease', '--no-daemon', '--no-build-cache', '--no-configuration-cache', '--max-workers=2', '--dependency-verification=strict', '-Dorg.gradle.parallel=false', '-Dorg.gradle.caching=false', '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8', '-Pkotlin.compiler.execution.strategy=in-process', '-PoalBuilderUnsigned=true', '-PappId=' + self.config.app_id, '-PoalVersionCode=' + str(code), '-PoalVersionName=' + upstream['versionName']]
                    if carplay:
                        argv.append('-PoalCarPlayMfiAssetsDir=' + str(carplay))
                    self.progress('build', 'Compiling both native ABIs and the release app; first build can take a while')
                    self._run(argv, 'build', source)
                    unsigned = source / 'app/build/outputs/bundle/release/app-release.aab'
                if not unsigned.is_file() or not unsigned.stat().st_size:
                    raise BuilderError('Gradle did not produce an Android App Bundle')
                verify_bundle_entries(unsigned, bool(carplay))
                self._run([self.tools['java'], '-jar', self.tools['bundletool'], 'validate', '--bundle=' + str(unsigned)], 'validate')
                filename = 'openautolink-' + str(code) + '-' + upstream['sourceCommit'][:12] + '.aab'
                artifact = self.workspace / 'output' / filename
                if artifact.exists():
                    raise BuilderError('Refusing to overwrite an existing versioned artifact')
                pending = self.workspace / 'output' / ('.' + filename + '.pending')
                shutil.copyfile(unsigned, pending)
                pending.chmod(0o600)
                identity = self.workspace / 'private/identity'
                read_identity(identity, state)
                self.progress('sign', 'Signing locally with the saved upload identity')
                self._run([self.tools['jarsigner'], '-J-Duser.language=en', '-keystore', str(identity / 'upload-key.p12'), '-storetype', 'PKCS12', '-storepass:file', str(identity / 'password'), '-keypass:file', str(identity / 'password'), '-sigalg', 'SHA256withRSA', '-digestalg', 'SHA-256', str(pending), 'upload'], 'sign')
                self.progress('verify', 'Verifying signature, signer, application ID, versions, native ABIs and assets')
                self._verify(pending, result, state, bool(carplay))
                result.update(artifact=str(artifact), sha256=file_digest(pending))
                manager.downloader.check_cancel()
                os.replace(pending, artifact)
                pending = None
                atomic_json(artifact.with_suffix('.json'), result)
                atomic_write(artifact.with_suffix('.aab.sha256'), (result['sha256'] + '  ' + filename + '\n').encode())
                atomic_json(self.workspace / 'output/latest.json', result)
                state['lastAttemptStatus'] = 'complete'
                atomic_json(identity / 'state.json', state)
                self.progress('complete', 'Verified signed AAB is ready in the output folder')
                return result
            except Exception as exc:
                if reserved:
                    state['lastAttemptStatus'] = 'cancelled' if self.cancel_event.is_set() else 'failed'
                    atomic_json(self.workspace / 'private/identity/state.json', state)
                if self.cancel_event.is_set():
                    raise BuilderError('Build cancelled') from None
                if isinstance(exc, BuilderError):
                    raise
                raise BuilderError('Build failed while preparing or verifying managed inputs; identity and prior outputs were preserved') from exc
            finally:
                if pending is not None:
                    pending.unlink(missing_ok=True)
                remove_managed(personalization)
                if carplay:
                    # AAB asset intermediates inherently contain the supplied pair.
                    # No build cache is enabled; remove intermediates after either outcome.
                    for relative in ('source/app/build', 'source/.gradle'):
                        remove_managed(self.workspace / relative)
                    remove_managed(self.workspace / 'private/carplay')

    def _prepare_carplay(self):
        key_path, cert_path = self.config.carplay_key, self.config.carplay_certificate
        root = self.workspace / 'private/carplay'
        if key_path is None and cert_path is None:
            remove_managed(root)
            return None
        if key_path is None or cert_path is None:
            raise BuilderError('Select both CarPlay authentication files or neither')
        data = []
        for path in (Path(key_path).expanduser(), Path(cert_path).expanduser()):
            if path.is_symlink() or not path.is_file() or not 1 <= path.stat().st_size <= 16384:
                raise BuilderError('CarPlay inputs must be bounded regular files, not symlinks (1–16384 bytes)')
            if path.resolve().is_relative_to(self.workspace / 'source'):
                raise BuilderError('CarPlay inputs must remain outside the source tree')
            data.append(path.read_bytes())
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives.serialization import pkcs7
        try:
            key = serialization.load_der_private_key(data[0], password=None)
            if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
                raise ValueError()
            if key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()) != data[0]:
                raise ValueError()
            try:
                certs = pkcs7.load_der_pkcs7_certificates(data[1])
            except ValueError:
                certs = [x509.load_der_x509_certificate(data[1])]
                if certs[0].public_bytes(serialization.Encoding.DER) != data[1]:
                    raise ValueError()
            if len(certs) != 1:
                raise ValueError()
            public = lambda k: k.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
            if public(certs[0].public_key()) != public(key.public_key()):
                raise ValueError()
        except (ValueError, TypeError) as exc:
            raise BuilderError('CarPlay files must be a matching DER PKCS8 P-256 key and exactly one certificate in DER X.509 or DER PKCS7 format') from exc
        # Validation establishes format and key pairing, not Apple authorization.
        stage = self.workspace / 'private/carplay.preparing'
        remove_managed(stage)
        (stage / 'offline-mfi').mkdir(parents=True, mode=0o700)
        atomic_write(stage / 'offline-mfi/identity.pk8', data[0])
        atomic_write(stage / 'offline-mfi/certificate.p7b', data[1])
        remove_managed(root)
        os.replace(stage, root)
        return root

    def _prepare_identity(self):
        validate_app_id(self.config.app_id)
        state = load_state(self.config.workspace)
        if self.config.mode == 'personalize' or state is None:
            validate_personal_app_id(self.config.app_id)
        self.workspace = managed_workspace(self.config.workspace)
        identity = self.workspace / 'private/identity'
        if state is not None:
            if state['applicationId'] != self.config.app_id:
                raise BuilderError('Application ID is immutable; choose another workspace for another app')
            read_identity(identity, state)
            return state
        if identity.exists():
            raise BuilderError('Incomplete existing identity; restore a backup rather than replacing keys')
        stage = self.workspace / 'private/identity.creating'
        # An interrupted initial identity could have reached disk. Never replace it silently.
        if stage.exists():
            raise BuilderError('Interrupted identity creation found; preserve private/identity.creating before recovery')
        stage.mkdir(mode=0o700)
        key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'OpenAutoLink Personal Upload')])
        now = dt.datetime.now(dt.timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                .serial_number(x509.random_serial_number()).not_valid_before(now - dt.timedelta(days=1))
                .not_valid_after(now + dt.timedelta(days=365 * 30)).sign(key, hashes.SHA256()))
        password = secrets.token_hex(32).encode()
        key_bytes = pkcs12.serialize_key_and_certificates(b'upload', key, cert, None, serialization.BestAvailableEncryption(password))
        atomic_write(stage / 'upload-key.p12', key_bytes)
        atomic_write(stage / 'password', password + b'\n')
        state = {'schema': 1, 'applicationId': self.config.app_id, 'lastVersionCode': 0,
                 'signingCertificateSha256': cert.fingerprint(hashes.SHA256()).hex(),
                 'carplayBackupPolicy': 'excluded-reselect-files-after-restore'}
        atomic_json(stage / 'state.json', state)
        os.replace(stage, identity)
        return state


def verify_bundle_entries(bundle, carplay):
    try:
        with zipfile.ZipFile(bundle) as archive:
            members = archive.infolist()
            names = [m.filename for m in members]
            if len(names) != len(set(names)):
                raise BuilderError('AAB contains duplicate ZIP entries')
            abis = {name.split('/')[2] for name in names if name.startswith('base/lib/') and name.endswith('.so')}
            expected = {'arm64-v8a', 'x86_64'}
            if abis != expected or any('base/lib/' + abi + '/libopenautolink-jni.so' not in names for abi in expected):
                raise BuilderError('AAB native ABI payload is missing or unexpected')
            assets = {name for name in names if 'offline-mfi/' in name and not name.endswith('/')}
            wanted = {'base/assets/offline-mfi/identity.pk8', 'base/assets/offline-mfi/certificate.p7b'} if carplay else set()
            if assets != wanted:
                raise BuilderError('AAB CarPlay assets do not match the explicit build choice')
            if any(not 1 <= archive.getinfo(name).file_size <= 16384 for name in assets):
                raise BuilderError('AAB CarPlay assets have invalid sizes')
    except zipfile.BadZipFile as exc:
        raise BuilderError('Invalid AAB ZIP container') from exc


# Only fixed, reviewed summaries leave the process boundary on failure. A regex
# redaction denylist cannot safely scrub arbitrary key bytes, compiler source
# excerpts, URL credentials, or a secret split across lines/terminal escapes.
MAX_DIAGNOSTIC_CHARS = 1800
_FAILURE_DIAGNOSTICS = (
    (r'dependency verification failed|checksum (?:verification )?(?:failed|mismatch)|failed verification',
     'Dependency verification failed',
     'Update the desktop builder and retry. If it persists, report the source version and this diagnostic. Do not disable strict verification or regenerate checksums.'),
    (r'no space left on device|not enough space on (?:the )?disk|disk (?:is )?full|disk quota exceeded',
     'Disk space exhausted',
     'Free disk space on the workspace drive and retry. Keep the private identity and prior output folders; never delete the upload key.'),
    (r'could not (?:get|head)|unknownhostexception|unable to resolve host|connection (?:timed out|reset|refused)|read timed out|network is unreachable|sslhandshakeexception|pkix path building failed',
     'Network download failed',
     'Check connectivity, proxy settings and the system clock, then retry. Do not bypass TLS or checksum verification.'),
    (r'compilation (?:error|failed)|unresolved reference|cannot find symbol|fatal error:|cmake error|ninja: build stopped|error: (?:expected|incompatible|undefined)',
     'Compiler failure',
     'Retry with the latest stable source and desktop builder. If it persists, report the source version, operating system and this diagnostic; keep the saved upload identity.'),
)


def process_failure(output, phase, returncode):
    """Summarize recognized output, never echo free-form subprocess text."""
    # Bound parsing as well as emitted text. Strip terminal styling/formatting
    # before recognition; output is built exclusively from constants below.
    text = output[-4 * 1024**2:].decode('utf-8', errors='replace').lower()
    text = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', text)
    text = re.sub(r'[^\x20-\x7e\n\r\t]', '', text)
    text = re.sub(r'\s+', ' ', text)
    phase = phase if phase in {'build', 'sign', 'verify', 'validate', 'integration', 'test'} else 'tool'
    diagnostics = [label + '. Recovery: ' + action for pattern, label, action in _FAILURE_DIAGNOSTICS
                   if re.search(pattern, text)]
    if not diagnostics:
        diagnostics = ['Unrecognized tool failure (raw output withheld for privacy). Recovery: Retry with the latest desktop builder; if it persists, report this phase, exit code, source version and operating system. Keep the saved upload identity.']
    return (phase + ': tool failed with exit code ' + str(returncode) + '. ' + ' '.join(diagnostics))[:MAX_DIAGNOSTIC_CHARS]


# A dead launcher may leave children holding stdout open indefinitely. Give
# normal tail output a short drain window, but keep cancellation live throughout.
PROCESS_DRAIN_SECONDS = 1.0
PROCESS_STOP_SECONDS = 1.0
MAX_PROCESS_OUTPUT = 4 * 1024**2


class _WindowsAPI:
    """Small native adapter; imported only on Windows (CPython >= 3.12)."""
    def __init__(self):
        import ctypes as c
        from ctypes import wintypes as w
        import _winapi
        self.c, self.winapi = c, _winapi

        class Limits(c.Structure):
            _fields_ = [('process_time', c.c_longlong), ('job_time', c.c_longlong),
                        ('flags', w.DWORD), ('min_working_set', c.c_size_t),
                        ('max_working_set', c.c_size_t), ('active_limit', w.DWORD),
                        ('affinity', c.c_size_t), ('priority', w.DWORD), ('scheduling', w.DWORD)]

        class IO(c.Structure):
            _fields_ = [(name, c.c_ulonglong) for name in
                        ('reads', 'writes', 'other', 'read_bytes', 'write_bytes', 'other_bytes')]

        class ExtendedLimits(c.Structure):
            _fields_ = [('basic', Limits), ('io', IO), ('process_memory', c.c_size_t),
                        ('job_memory', c.c_size_t), ('peak_process', c.c_size_t), ('peak_job', c.c_size_t)]

        class Accounting(c.Structure):
            _fields_ = [(name, c.c_longlong) for name in
                        ('user_time', 'kernel_time', 'period_user', 'period_kernel')] + [
                        ('faults', w.DWORD), ('total', w.DWORD), ('active', w.DWORD), ('terminated', w.DWORD)]

        self.ExtendedLimits, self.Accounting = ExtendedLimits, Accounting
        self.kernel = c.WinDLL('kernel32', use_last_error=True)
        for name, result, args in (
            ('CreateJobObjectW', w.HANDLE, [c.c_void_p, w.LPCWSTR]),
            ('SetInformationJobObject', w.BOOL, [w.HANDLE, c.c_int, c.c_void_p, w.DWORD]),
            ('QueryInformationJobObject', w.BOOL, [w.HANDLE, c.c_int, c.c_void_p, w.DWORD, c.c_void_p]),
            ('AssignProcessToJobObject', w.BOOL, [w.HANDLE, w.HANDLE]),
            ('TerminateJobObject', w.BOOL, [w.HANDLE, w.UINT]),
            ('ResumeThread', w.DWORD, [w.HANDLE]),
        ):
            function = getattr(self.kernel, name)
            function.restype, function.argtypes = result, args

    def checked(self, result):
        if not result:
            raise self.c.WinError(self.c.get_last_error())
        return result

    def create_job(self):
        job = self.checked(self.kernel.CreateJobObjectW(None, None))
        try:
            limits = self.ExtendedLimits()
            # KILL_ON_JOB_CLOSE; do not permit child breakaway from this Job.
            limits.basic.flags = 0x2000
            self.checked(self.kernel.SetInformationJobObject(
                job, 9, self.c.byref(limits), self.c.sizeof(limits)))
            return job
        except BaseException:
            self.close(job)
            raise

    def start_suspended(self, argv, cwd, env):
        import msvcrt
        # Use the same CPython CreateProcess adapter as subprocess, but retain
        # the initial thread handle so assignment precedes *any* tool code.
        read_fd, write_fd = os.pipe()
        stdout = os.fdopen(read_fd, 'rb', buffering=0)
        try:
            with os.fdopen(write_fd, 'wb', buffering=0) as writer, open(os.devnull, 'rb', buffering=0) as stdin:
                os.set_inheritable(writer.fileno(), True)
                os.set_inheritable(stdin.fileno(), True)
                startup = subprocess.STARTUPINFO()
                startup.dwFlags = subprocess.STARTF_USESTDHANDLES | subprocess.STARTF_USESHOWWINDOW
                startup.wShowWindow = subprocess.SW_HIDE
                startup.hStdInput = msvcrt.get_osfhandle(stdin.fileno())
                startup.hStdOutput = startup.hStdError = msvcrt.get_osfhandle(writer.fileno())
                startup.lpAttributeList = {'handle_list': [startup.hStdInput, startup.hStdOutput]}
                # Only the validated batch-wrapper branch supplies a raw cmd
                # command line. Re-encoding it with CRT rules escapes its quotes
                # with backslashes, which cmd.exe treats as literal characters.
                commandline = argv if isinstance(argv, str) else subprocess.list2cmdline(argv)
                handle, thread, pid, _ = self.winapi.CreateProcess(
                    None, commandline, None, None, True,
                    0x00000004 | subprocess.CREATE_NEW_PROCESS_GROUP,  # CREATE_SUSPENDED
                    env, str(cwd), startup)
            return handle, thread, pid, stdout
        except BaseException:
            stdout.close()
            raise

    def assign(self, job, process):
        self.checked(self.kernel.AssignProcessToJobObject(job, process))

    def resume(self, thread):
        if self.kernel.ResumeThread(thread) == 0xffffffff:
            raise self.c.WinError(self.c.get_last_error())

    def close(self, handle):
        self.winapi.CloseHandle(handle)

    def terminate_process(self, process):
        self.winapi.TerminateProcess(process, 1)

    def poll_process(self, process):
        if self.winapi.WaitForSingleObject(process, 0) == self.winapi.WAIT_OBJECT_0:
            return self.winapi.GetExitCodeProcess(process)
        return None

    def wait_process(self, process, timeout):
        result = self.winapi.WaitForSingleObject(process, max(0, int(timeout * 1000)))
        if result == self.winapi.WAIT_TIMEOUT:
            raise subprocess.TimeoutExpired('managed tool', timeout)
        return self.winapi.GetExitCodeProcess(process)

    def terminate_job(self, job):
        self.checked(self.kernel.TerminateJobObject(job, 1))

    def active_processes(self, job):
        accounting = self.Accounting()
        self.checked(self.kernel.QueryInformationJobObject(
            job, 1, self.c.byref(accounting), self.c.sizeof(accounting), None))
        return accounting.active


class _WindowsProcess:
    """Job ownership survives launcher exit; taskkill /T cannot guarantee that."""
    def __init__(self, argv, cwd, env, *, api=None):
        self.api = api if api is not None else _WindowsAPI()
        self.job = self.api.create_job()
        self.handle = self.stdout = thread = None
        try:
            self.handle, thread, self.pid, self.stdout = self.api.start_suspended(argv, cwd, env)
            self.api.assign(self.job, self.handle)
            self.api.resume(thread)
        except BaseException:
            try:
                if self.handle is not None:
                    self.api.terminate_process(self.handle)
                    self.api.wait_process(self.handle, PROCESS_STOP_SECONDS)
            finally:
                if self.stdout is not None:
                    self.stdout.close()
                self.close()
            raise
        finally:
            if thread is not None:
                self.api.close(thread)

    def poll(self):
        return self.api.poll_process(self.handle)

    def wait(self, timeout):
        return self.api.wait_process(self.handle, timeout)

    def kill_tree(self):
        self.api.terminate_job(self.job)
        deadline = time.monotonic() + PROCESS_STOP_SECONDS
        while self.api.active_processes(self.job):
            if time.monotonic() >= deadline:
                raise BuilderError('Timed out stopping managed tool processes; do not reuse the work folder until they have stopped.')
            time.sleep(0.01)

    def close(self):
        # Closing the Job is a second kill guarantee even when termination or
        # accounting failed. Close the process handle independently of it.
        try:
            if self.job is not None:
                self.api.close(self.job)
                self.job = None
        finally:
            if self.handle is not None:
                self.api.close(self.handle)
                self.handle = None


def run_process(argv, cwd, env, cancel_event, progress, phase):
    """Capture a bounded tail and own the tool tree until cleanup completes.

    POSIX tools run in a private process group. Windows tools are assigned to a
    kill-on-close Job before their initial thread is resumed. Neither parent
    exit nor pipe EOF alone means descendants have stopped.
    """
    if cancel_event.is_set():
        raise BuilderError('Build cancelled')
    argv = [str(v) for v in argv]
    if os.name == 'nt' and argv[0].lower().endswith('.bat'):
        # cmd.exe is only used for the checked-in Windows Gradle wrapper.
        if any(re.search(r'[&|<>^%!\r\n"]', value) for value in argv):
            raise BuilderError('Windows build paths must not contain shell metacharacters')
        comspec = env.get('COMSPEC', str(Path(env.get('SystemRoot', 'C:/Windows')) / 'System32/cmd.exe'))
        # /s strips the enclosing quote pair; preserve the inner argument quotes
        # verbatim all the way to CreateProcess, not as a CRT-quoted argument.
        argv = subprocess.list2cmdline([comspec, '/d', '/s', '/c']) + ' "' + subprocess.list2cmdline(argv) + '"'
    try:
        process = (_WindowsProcess(argv, cwd, env) if os.name == 'nt' else
                   subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    shell=False, start_new_session=True, bufsize=0))
    except OSError as exc:
        raise BuilderError(phase + ': managed tool could not start (on Apple Silicon, legacy SDK tools may require Rosetta)') from exc
    chunks = bytearray()
    last_notice = time.monotonic()
    exited_at = None
    eof = False
    try:
        # Windows pipe nonblocking mode requires the packaged Python >= 3.12.
        # No reader thread / BufferedReader: close cannot wait on a reader lock,
        # even if a POSIX child deliberately escapes our group with setsid().
        os.set_blocking(process.stdout.fileno(), False)
        while True:
            if cancel_event.is_set():
                raise BuilderError('Build cancelled')
            returncode = process.poll()
            now = time.monotonic()
            if returncode is not None:
                if exited_at is None:
                    exited_at = now
                if eof:
                    break
                if now - exited_at >= PROCESS_DRAIN_SECONDS:
                    raise BuilderError(phase + ': tool output did not close after exit; incomplete output discarded. Check for leftover tools before retrying.')
            block = None
            if not eof:
                try:
                    block = os.read(process.stdout.fileno(), 65536)
                except BlockingIOError:
                    pass
                if block == b'':
                    eof = True
                elif block:
                    chunks.extend(block)
                    if len(chunks) > MAX_PROCESS_OUTPUT:
                        del chunks[:-MAX_PROCESS_OUTPUT]
            if now - last_notice > 15:
                progress(phase, 'Still working; cancellation is available')
                last_notice = now
            if not block:
                cancel_event.wait(0.05)
        if cancel_event.is_set():
            raise BuilderError('Build cancelled')
    finally:
        try:
            # Do this even after the immediate parent exited successfully, and
            # even if a descendant redirected stdout (EOF is not tree exit).
            if os.name == 'nt':
                process.kill_tree()
            else:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            process.wait(timeout=PROCESS_STOP_SECONDS)
        finally:
            process.stdout.close()
            if os.name == 'nt':
                process.close()
    # Cancellation can also arrive during the bounded final tree cleanup.
    if cancel_event.is_set():
        raise BuilderError('Build cancelled')
    if returncode:
        raise BuilderError(process_failure(chunks, phase, returncode))
    return chunks.decode('utf-8', errors='replace')


BACKUP_MAGIC = b'OALIDENTITY1\x00'
BACKUP_NAMES = {'state.json', 'upload-key.p12', 'password', 'backup.json'}


def backup_key(password, salt):
    if not isinstance(password, str) or len(password) < 12:
        raise BuilderError('Use an encrypted backup password of at least 12 characters')
    return Scrypt(salt=salt, length=32, n=32768, r=8, p=1).derive(password.encode('utf-8'))


def backup_identity(workspace, destination, password):
    """Encrypt only upload identity/version state. CarPlay files are never backed up."""
    destination = Path(destination).expanduser()
    if destination.exists():
        raise BuilderError('Refusing to overwrite an existing backup')
    with workspace_lock(workspace) as workspace:
        state = load_state(workspace)
        if state is None:
            raise BuilderError('No saved identity to back up')
        identity = workspace / 'private/identity'
        read_identity(identity, state)
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name in sorted(BACKUP_NAMES - {'backup.json'}):
                archive.writestr(name, (identity / name).read_bytes())
            archive.writestr('backup.json', json.dumps({'schema': 1, 'carplay': 'excluded-reselect-files-after-restore'}))
        salt, nonce = secrets.token_bytes(16), secrets.token_bytes(12)
        header = BACKUP_MAGIC + salt + nonce
        encrypted = AESGCM(backup_key(password, salt)).encrypt(nonce, buffer.getvalue(), header)
        atomic_write(destination, header + encrypted)
    return {'backup': str(destination), 'carplayIncluded': False}


def restore_identity(workspace, archive, password):
    archive = Path(archive).expanduser()
    if not archive.is_file() or archive.stat().st_size > 4 * 1024**2:
        raise BuilderError('Invalid or oversized identity backup')
    content = archive.read_bytes()
    size = len(BACKUP_MAGIC)
    if not content.startswith(BACKUP_MAGIC) or len(content) < size + 44:
        raise BuilderError('Unsupported or damaged identity backup')
    salt, nonce = content[size:size + 16], content[size + 16:size + 28]
    try:
        plain = AESGCM(backup_key(password, salt)).decrypt(nonce, content[size + 28:], content[:size + 28])
    except InvalidTag as exc:
        raise BuilderError('Incorrect backup password or damaged archive') from exc
    with workspace_lock(workspace) as workspace:
        identity = workspace / 'private/identity'
        if identity.exists() or (workspace / 'private/identity.creating').exists():
            raise BuilderError('Refusing to overwrite an existing identity; restore into a new workspace')
        stage = workspace / 'private/identity.restoring'
        if stage.exists():
            raise BuilderError('Interrupted restore exists; use a fresh workspace')
        try:
            with zipfile.ZipFile(io.BytesIO(plain)) as backup:
                entries = backup.infolist()
                if len(entries) != len(BACKUP_NAMES) or {p.filename for p in entries} != BACKUP_NAMES or any(p.file_size > 1024**2 or ((p.external_attr >> 16) & 0o170000) == 0o120000 for p in entries):
                    raise BuilderError('Unsafe identity backup paths or entries')
                policy = json.loads(backup.read('backup.json'))
                if policy != {'schema': 1, 'carplay': 'excluded-reselect-files-after-restore'}:
                    raise BuilderError('Unsupported CarPlay backup policy')
                state = validate_state(json.loads(backup.read('state.json')))
                stage.mkdir(mode=0o700)
                for name in BACKUP_NAMES - {'backup.json'}:
                    atomic_write(stage / name, backup.read(name))
            read_identity(stage, state)
            os.replace(stage, identity)
        except (ValueError, KeyError, zipfile.BadZipFile) as exc:
            raise BuilderError('Damaged identity backup') from exc
        finally:
            remove_managed(stage)
    return state

"""Unsigned, base-only AAB personalization; never compiles application code.

The caller verifies publisher provenance, validates the optional CarPlay pair,
reserves the version, and signs/verifies the returned bundle. ``run`` is the
engine's bounded/cancellable ``run(argv, phase, cwd=None)`` callable.
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile
import zipfile

from .app_identity import validate_app_id
from .toolchain import BuilderError, file_digest

MANIFEST = 'base/manifest/AndroidManifest.xml'
RESOURCES = 'base/resources.pb'
PROTO_MEMBERS = {MANIFEST: 'manifest.pb', RESOURCES: 'resources.pb'}
MAX_MEMBERS = 10000
MAX_EXPANDED_BYTES = 2 * 1024**3
MAX_MEMBER_BYTES = 512 * 1024**2
MAX_PROTO_BYTES = 32 * 1024**2
MAX_ARCHIVE_BYTES = 1024**3
MAX_COMPRESSION_RATIO = 1000


def _fail(code):
    raise BuilderError('OAL_PERSONALIZE_' + code) from None


def _validate_zip(source):
    """Validate every raw central-directory name and budget before any writes."""
    infos = source.infolist()
    if not infos or len(infos) > MAX_MEMBERS:
        _fail('ZIP_LIMIT')
    names, files, total = set(), set(), 0
    spellings = {}
    devices = {'con', 'prn', 'aux', 'nul'} | {p + str(i) for p in ('com', 'lpt') for i in range(1, 10)}
    for info in infos:
        name = info.orig_filename  # filename has already undergone NUL truncation
        parts = name.rstrip('/').split('/')
        if (not name or len(name) > 1024 or len(parts) > 32 or '//' in name
                or name != info.filename or '\\' in name or ':' in name
                or any(ord(c) < 32 or ord(c) > 126 for c in name)
                or any(p in ('', '.', '..') or p.endswith((' ', '.'))
                       or p.split('.')[0].lower() in devices for p in parts)):
            _fail('ZIP_NAME')
        key = name.rstrip('/').casefold()
        if key in names:
            _fail('ZIP_ALIAS')
        names.add(key)
        for i in range(1, len(parts) + 1):
            prefix = '/'.join(parts[:i])
            if spellings.setdefault(prefix.casefold(), prefix) != prefix:
                _fail('ZIP_ALIAS')
        mode = stat.S_IFMT(info.external_attr >> 16)
        if mode not in (0, stat.S_IFREG, stat.S_IFDIR) or (mode == stat.S_IFDIR and not info.is_dir()):
            _fail('ZIP_TYPE')
        if info.flag_bits & (1 | 0x40) or info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            _fail('ZIP_ENCODING')
        total += info.file_size
        if (info.file_size > MAX_MEMBER_BYTES or total > MAX_EXPANDED_BYTES
                or info.file_size > max(1, info.compress_size) * MAX_COMPRESSION_RATIO
                or (info.is_dir() and info.file_size)):
            _fail('ZIP_LIMIT')
        if 'offline-mfi' in key.split('/') or key.endswith(('.pk8', '.p7b', '.p12', '.pfx', '.jks', '.keystore', '.pem', '.key')):
            _fail('CREDENTIAL_INPUT')
        if not info.is_dir():
            files.add(key)
        # Only one base application module; metadata is copied, never extracted.
        allowed = (name == 'BundleConfig.pb' or name.startswith(('META-INF/', 'BUNDLE-METADATA/'))
                   or name in (MANIFEST, RESOURCES, 'base/assets.pb', 'base/native.pb')
                   or name.startswith(('base/dex/', 'base/lib/', 'base/res/', 'base/assets/', 'base/root/'))
                   or (info.is_dir() and name in ('base/', 'base/manifest/')))
        if not allowed:
            _fail('MODULE_SHAPE')
        if not info.is_dir():
            if name.startswith('base/dex/') and not re.fullmatch(r'base/dex/classes(?:[2-9]|[1-9][0-9]+)?[.]dex', name):
                _fail('MODULE_SHAPE')
            if name.startswith('base/lib/') and not re.fullmatch(r'base/lib/(?:arm64-v8a|x86_64)/lib[A-Za-z0-9_.+-]+[.]so', name):
                _fail('MODULE_SHAPE')
        if name in PROTO_MEMBERS and info.file_size > MAX_PROTO_BYTES:
            _fail('PROTO_LIMIT')
    for name in names:
        if any('/'.join(name.split('/')[:i]) in files for i in range(1, len(name.split('/')))):
            _fail('ZIP_ALIAS')
    if not {MANIFEST.casefold(), RESOURCES.casefold(), 'bundleconfig.pb'} <= files:
        _fail('MODULE_SHAPE')
    if not any(n.startswith('base/dex/') and n.endswith('.dex') for n in files):
        _fail('MODULE_SHAPE')
    return infos



def _signature(name):
    upper = name.upper()
    if not upper.startswith('META-INF/') or upper.count('/') != 1:
        return False
    leaf = upper.split('/')[1]
    return leaf == 'MANIFEST.MF' or leaf.startswith('SIG-') or leaf.endswith(('.SF', '.RSA', '.DSA', '.EC'))


def _run_checked(run, argv, cwd):
    try:
        run(argv, 'personalize', cwd=cwd)
    except Exception:
        # The injected engine runner owns cancellation and bounded process IO.
        # Never surface arbitrary subprocess output or paths from another runner.
        _fail('HELPER_FAILED')


def _inspect_payloads(source, original_app_id=None):
    """Bounded CRC/hash pass; reject baked template IDs, never rewrite payloads."""
    needles = ([original_app_id.encode(e) for e in ('utf-8', 'utf-16le', 'utf-16be')]
               if original_app_id is not None else [])
    overlap = max((len(n) for n in needles), default=1) - 1
    hashes = {}
    for info in source.infolist():
        check_identity = needles and info.filename not in PROTO_MEMBERS and not _signature(info.filename)
        if check_identity and original_app_id in info.filename:
            _fail('BAKED_IDENTITY')
        digest, count, tail = hashlib.sha256(), 0, b''
        with source.open(info) as handle:
            while chunk := handle.read(1024 * 1024):
                count += len(chunk)
                if count > min(MAX_MEMBER_BYTES, info.file_size):
                    _fail('ZIP_LIMIT')
                digest.update(chunk)
                if check_identity:
                    window = tail + chunk
                    if any(n in window for n in needles):
                        _fail('BAKED_IDENTITY')
                    tail = window[-overlap:]
        if count != info.file_size:
            _fail('ZIP_SIZE')
        hashes[info.filename] = digest.hexdigest()
    return hashes


def _check_options(template, output, workdir, original_app_id, app_id, version_code):
    try:
        validate_app_id(original_app_id)
        validate_app_id(app_id)
    except BuilderError:
        _fail('IDENTITY')
    if (original_app_id in app_id or app_id in original_app_id
            or original_app_id.startswith('com.openautolink.app.')
            or original_app_id.startswith('com.openautolink.carplaylab')):
        _fail('IDENTITY')
    if type(version_code) is not int or not 1 <= version_code <= 2100000000:
        _fail('VERSION')
    if (template.is_symlink() or not template.is_file() or output.exists() or output.is_symlink()
            or any(p.is_symlink() for p in (workdir, *workdir.parents, *output.parents))
            or (workdir.exists() and not workdir.is_dir())):
        _fail('PATH')
    if template.stat().st_size > MAX_ARCHIVE_BYTES:
        _fail('ZIP_LIMIT')


def _carplay_assets(carplay):
    """Accept validated bytes or the existing engine's private staging directory.

    Cryptographic matching and authorization remain the caller's responsibility;
    this boundary enforces only the bounded two-file transport contract.
    """
    if carplay is None:
        return {}
    if isinstance(carplay, Path):
        assets = carplay / 'offline-mfi'
        paths = [assets / name for name in ('identity.pk8', 'certificate.p7b')]
        if (any(p.is_symlink() for p in (carplay, *carplay.parents, assets, *paths))
                or not assets.is_dir() or set(p.name for p in assets.iterdir()) != {'identity.pk8', 'certificate.p7b'}
                or any(not p.is_file() or not 1 <= p.stat().st_size <= 16384 for p in paths)):
            _fail('CARPLAY_INPUT')
        carplay = tuple(p.read_bytes() for p in paths)
    if (not isinstance(carplay, tuple) or len(carplay) != 2
            or any(not isinstance(data, bytes) or not 1 <= len(data) <= 16384 for data in carplay)):
        _fail('CARPLAY_INPUT')
    return dict(zip(('base/assets/offline-mfi/identity.pk8', 'base/assets/offline-mfi/certificate.p7b'), carplay))


def personalize_bundle(template, output, *, original_app_id, app_id, version_code,
                       java, javac, bundletool, workdir, run, carplay=None):
    """Create an unsigned AAB without replacing any existing file.

    Returned JSON-safe metadata contains public identity, artifact hashes,
    constant changed-member names, counts and unchanged DEX/native hashes.
    Caller must verify template provenance and sign/validate the result.
    """
    template, output, workdir = Path(template).absolute(), Path(output).absolute(), Path(workdir).absolute()
    published = False
    try:
        _check_options(template, output, workdir, original_app_id, app_id, version_code)
        template_hash = file_digest(template)
        with zipfile.ZipFile(template) as source:
            infos = _validate_zip(source)
            before = _inspect_payloads(source, original_app_id)
            additions = _carplay_assets(carplay)
            # Nothing has been written until ALL ZIP headers and payloads pass.
            workdir.mkdir(parents=True, exist_ok=True)
            with tempfile.TemporaryDirectory(prefix='personalize-', dir=workdir) as temporary:
                root = Path(temporary)
                incoming, rewritten, classes = (root / n for n in ('input', 'rewritten', 'classes'))
                for directory in (incoming, rewritten, classes):
                    directory.mkdir(mode=0o700)
                for member, local in PROTO_MEMBERS.items():
                    (incoming / local).write_bytes(source.read(member))
                helper = Path(__file__).with_name('resources') / 'ProtoPersonalizer.java'
                jar = str(Path(bundletool).absolute())
                _run_checked(run, [str(javac), '-J-Xmx256m', '-cp', jar, '-d', str(classes), str(helper)], root)
                _run_checked(run, [str(java), '-Xmx256m', '-cp', str(classes) + os.pathsep + jar,
                     'ProtoPersonalizer', str(incoming), str(rewritten), original_app_id, app_id,
                     str(version_code)], root)
                changes = {}
                for member, local in PROTO_MEMBERS.items():
                    path = rewritten / local
                    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_PROTO_BYTES:
                        _fail('HELPER_OUTPUT')
                    changes[member] = path.read_bytes()
                candidate = root / 'unsigned.aab'
                expected = {n: h for n, h in before.items() if not _signature(n)}
                expected.update({n: hashlib.sha256(data).hexdigest() for n, data in {**changes, **additions}.items()})
                with zipfile.ZipFile(candidate, 'x', compression=zipfile.ZIP_DEFLATED) as target:
                    for info in infos:
                        if _signature(info.filename):
                            continue
                        # Do not carry unreviewed ZIP extras/comments into the rebuilt container.
                        clean = zipfile.ZipInfo(info.filename, info.date_time)
                        clean.compress_type = info.compress_type
                        clean.external_attr = info.external_attr
                        if info.filename in changes:
                            target.writestr(clean, changes[info.filename])
                        else:
                            with source.open(info) as src, target.open(clean, 'w') as dst:
                                shutil.copyfileobj(src, dst, 1024 * 1024)
                    for member, data in additions.items():
                        target.writestr(member, data)
                candidate.chmod(0o600)
                with zipfile.ZipFile(candidate) as check:
                    if _inspect_payloads(check) != expected:
                        _fail('PAYLOAD_CHANGED')
                if file_digest(template) != template_hash:
                    _fail('INPUT_CHANGED')
                candidate_hash = file_digest(candidate)
                output.parent.mkdir(parents=True, exist_ok=True)
                # O_EXCL also rejects an intervening symlink/hardlink/existing file.
                fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                published = True
                with os.fdopen(fd, 'wb') as dst, candidate.open('rb') as src:
                    shutil.copyfileobj(src, dst, 1024 * 1024)
                    dst.flush()
                    os.fsync(dst.fileno())
                if file_digest(output) != candidate_hash:
                    _fail('OUTPUT_CHANGED')
        return {'schema': 1, 'applicationId': app_id, 'originalApplicationId': original_app_id,
                'versionCode': version_code, 'templateSha256': template_hash,
                'unsignedSha256': candidate_hash, 'changedMembers': sorted(PROTO_MEMBERS),
                'removedSignatureCount': sum(_signature(n) for n in before),
                'preservedMemberCount': len(expected) - len(changes) - len(additions),
                'addedMembers': sorted(additions), 'carplayIncluded': bool(additions),
                'codeNativeSha256': {n: h for n, h in before.items() if n.startswith(('base/dex/', 'base/lib/'))}}
    except BuilderError:
        if published:
            output.unlink(missing_ok=True)
        raise
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, EOFError):
        if published:
            output.unlink(missing_ok=True)
        _fail('IO_OR_ZIP')

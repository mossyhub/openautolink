"""CI-only, real managed first/update AAB gate; unit fixtures are not native proof.

No host JDK/SDK/NDK/Gradle setup, credential inputs, identity export, or fallback.
The supported source is asserted, not substituted for Toolchain's latest release:
release drift fails closed and requires an explicitly reviewed gate update.
"""
from __future__ import annotations

import json
import os
import platform
import re
import shutil
import threading
import time
from enum import Enum
from pathlib import Path

from oal_builder.engine import BuildConfig, Builder, BuilderError, atomic_json, suggest_app_id, verify_bundle_entries
from oal_builder.native_verification import OVERLAY_ID, SOURCE_COMMIT, SOURCE_REF
from oal_builder.toolchain import file_digest


METADATA = ('applicationId', 'versionCode', 'versionName', 'sourceCommit',
            'sourceRef', 'sourceOverlays', 'signingCertificateSha256',
            'carplayIncluded', 'sha256')


class FailureCode(Enum):
    SOURCE_COMMIT = 'source-commit-mismatch'
    SOURCE_REF = 'source-ref-mismatch'
    SOURCE_VERSION_NAME = 'source-version-name-mismatch'
    RESULT_INCOMPLETE = 'build-result-incomplete'
    APP_ID = 'application-id-mismatch'
    VERSION_CODE = 'version-code-invalid'
    OVERLAYS = 'source-overlays-invalid'
    HASH_FORMAT = 'artifact-hash-invalid'
    SIGNER_FORMAT = 'signing-fingerprint-invalid'
    CARPLAY = 'carplay-not-absent'
    ARTIFACT_PATH = 'artifact-path-invalid'
    ARTIFACT_HASH = 'artifact-hash-mismatch'
    BUNDLE_ENTRIES = 'bundle-entries-invalid'
    SIDECARS = 'artifact-sidecars-invalid'
    METADATA_READ = 'artifact-metadata-unreadable'
    METADATA_TYPE = 'artifact-metadata-invalid'
    METADATA_MATCH = 'artifact-metadata-mismatch'
    CHECKSUM_MATCH = 'artifact-checksum-mismatch'
    HOST_UNSUPPORTED = 'native-host-unsupported'
    CI_CONSENT = 'ci-license-consent-required'
    HOST_MISMATCH = 'native-host-mismatch'
    BUILDER_COMMIT = 'builder-commit-invalid'
    RUNNER_TEMP = 'runner-temp-required'
    PATH_SYMLINK = 'qualification-path-symlink'
    PATH_FRESH = 'qualification-path-not-fresh'
    PATH_SEPARATE = 'export-path-not-separate'
    PAIR_APP_ID = 'pair-application-id-mismatch'
    PAIR_SIGNER = 'pair-signer-mismatch'
    PAIR_SOURCE_COMMIT = 'pair-source-commit-mismatch'
    PAIR_SOURCE_REF = 'pair-source-ref-mismatch'
    PAIR_OVERLAYS = 'pair-source-overlays-mismatch'
    PAIR_VERSION_NAME = 'pair-version-name-mismatch'
    PAIR_VERSION = 'update-version-not-increased'
    PAIR_PATH = 'pair-artifact-path-reused'
    PAIR_HASH = 'pair-artifact-hash-reused'
    MANIFEST_XML = 'manifest-xml-invalid'
    MANIFEST_APP = 'manifest-application-mismatch'
    MANIFEST_COMPONENTS = 'manifest-components-mismatch'
    MANIFEST_AFFINITY = 'manifest-cluster-affinity-mismatch'
    TOOL_PATH = 'verification-tool-not-managed'
    TOOL_ENV = 'build-environment-not-managed'
    TOOL_JDK = 'jdk-not-managed'
    TOOL_PROVENANCE = 'managed-tool-provenance-invalid'
    BUDGET_INVALID = 'time-budget-invalid'
    BUDGET_EXCEEDED = 'time-budget-exceeded'
    EXPORT_HASH = 'export-hash-mismatch'


class QualificationError(BuilderError):
    """Only enumerated harness failures may cross the report boundary."""
    def __init__(self, code):
        if not isinstance(code, FailureCode):
            raise TypeError('Qualification diagnostic must be a fixed code')
        self.code = code
        super().__init__(code.value)


def require(condition, code):
    if not condition:
        raise QualificationError(code)


def native_platform():
    system = {'Linux': 'linux', 'Windows': 'windows', 'Darwin': 'macos'}.get(platform.system())
    arch = {'amd64': 'x86_64', 'x86_64': 'x86_64', 'arm64': 'arm64', 'aarch64': 'arm64'}.get(platform.machine().lower())
    name = f'{system}-{arch}'
    require(name in {'linux-x86_64', 'windows-x86_64', 'macos-x86_64', 'macos-arm64'}, FailureCode.HOST_UNSUPPORTED)
    return name


def prepare_ci(workspace, destination, *, accept_ci_android_licenses, expected_platform, builder_commit):
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and accept_ci_android_licenses is True,
            FailureCode.CI_CONSENT)
    require(native_platform() == expected_platform, FailureCode.HOST_MISMATCH)
    require(isinstance(builder_commit, str) and re.fullmatch('[0-9a-f]{40}', builder_commit), FailureCode.BUILDER_COMMIT)
    require(bool(os.environ.get('RUNNER_TEMP')), FailureCode.RUNNER_TEMP)
    root = Path(os.environ['RUNNER_TEMP']).resolve()
    paths = []
    for value in (workspace, destination):
        path = Path(value).absolute()
        require(not any(p.is_symlink() for p in (path, *path.parents) if p != root and root in p.parents),
                FailureCode.PATH_SYMLINK)
        path = path.resolve()
        require(path != root and path.is_relative_to(root) and not path.exists(),
                FailureCode.PATH_FRESH)
        paths.append(path)
    workspace, destination = paths
    require(not workspace.is_relative_to(destination) and not destination.is_relative_to(workspace),
            FailureCode.PATH_SEPARATE)
    destination.mkdir(parents=True)
    return workspace, destination


def validate_pair(first, update):
    for key, code in (('applicationId', FailureCode.PAIR_APP_ID),
                      ('signingCertificateSha256', FailureCode.PAIR_SIGNER),
                      ('sourceCommit', FailureCode.PAIR_SOURCE_COMMIT),
                      ('sourceRef', FailureCode.PAIR_SOURCE_REF),
                      ('sourceOverlays', FailureCode.PAIR_OVERLAYS),
                      ('versionName', FailureCode.PAIR_VERSION_NAME)):
        require(first[key] == update[key], code)
    require(update['versionCode'] > first['versionCode'], FailureCode.PAIR_VERSION)
    require(first['artifact'] != update['artifact'], FailureCode.PAIR_PATH)
    require(first['sha256'] != update['sha256'], FailureCode.PAIR_HASH)


def validate_artifact(result, workspace, app_id):
    """Check on-disk bytes/sidecars; return only bounded public metadata.

    Cryptographic signer and binary manifest verification is additionally run
    with the real Builder verifier by qualify(), not simulated here.
    """
    require(isinstance(result, dict) and all(key in result for key in (*METADATA, 'artifact')),
            FailureCode.RESULT_INCOMPLETE)
    require(result['applicationId'] == app_id, FailureCode.APP_ID)
    require(type(result['versionCode']) is int and 1 <= result['versionCode'] <= 2100000000,
            FailureCode.VERSION_CODE)
    require(result['sourceCommit'] == SOURCE_COMMIT, FailureCode.SOURCE_COMMIT)
    require(result['sourceRef'] == SOURCE_REF, FailureCode.SOURCE_REF)
    require(result['versionName'] == SOURCE_REF.removeprefix('v'), FailureCode.SOURCE_VERSION_NAME)
    overlays = result['sourceOverlays']
    require(isinstance(overlays, list) and all(isinstance(v, str) for v in overlays)
            and len(overlays) == len(set(overlays))
            and set(overlays) <= {'personal-cluster-task-affinity', OVERLAY_ID}, FailureCode.OVERLAYS)
    for key, code in (('sha256', FailureCode.HASH_FORMAT), ('signingCertificateSha256', FailureCode.SIGNER_FORMAT)):
        require(isinstance(result[key], str) and re.fullmatch('[0-9a-f]{64}', result[key]), code)
    require(result['carplayIncluded'] is False, FailureCode.CARPLAY)
    path = Path(result['artifact'])
    output = Path(workspace).resolve() / 'output'
    require(not output.is_symlink() and not path.is_symlink()
            and path.resolve().parent == output and path.suffix == '.aab'
            and re.fullmatch('[A-Za-z0-9._-]+\\.aab', path.name)
            and path.is_file() and path.stat().st_size > 0, FailureCode.ARTIFACT_PATH)
    require(file_digest(path) == result['sha256'], FailureCode.ARTIFACT_HASH)
    try:
        verify_bundle_entries(path, False)
    except Exception:
        raise QualificationError(FailureCode.BUNDLE_ENTRIES) from None
    sidecar = path.with_suffix('.json')
    checksum = path.with_suffix('.aab.sha256')
    require(not sidecar.is_symlink() and not checksum.is_symlink()
            and sidecar.is_file() and checksum.is_file()
            and sidecar.stat().st_size <= 65536 and checksum.stat().st_size <= 1024, FailureCode.SIDECARS)
    try:
        saved = json.loads(sidecar.read_text('utf-8'))
    except (OSError, ValueError):
        raise QualificationError(FailureCode.METADATA_READ) from None
    require(isinstance(saved, dict), FailureCode.METADATA_TYPE)
    require(all(saved.get(key) == result[key] for key in METADATA)
            and saved.get('artifact') == result['artifact'], FailureCode.METADATA_MATCH)
    require(checksum.read_text('utf-8') == result['sha256'] + '  ' + path.name + '\n', FailureCode.CHECKSUM_MATCH)
    return {**{key: result[key] for key in METADATA}, 'artifact': path.name,
            'sizeBytes': path.stat().st_size, 'abis': ['arm64-v8a', 'x86_64']}


def validate_manifest(xml, app_id):
    import xml.etree.ElementTree as ET
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        raise QualificationError(FailureCode.MANIFEST_XML) from None
    ns = '{http://schemas.android.com/apk/res/android}'
    app = root.find('application')
    require(root.get('package') == app_id and app is not None
            and app.get(ns + 'name') == 'com.openautolink.app.OalApplication',
            FailureCode.MANIFEST_APP)
    names = {node.get(ns + 'name') for node in app}
    require({'com.openautolink.app.MainActivity', 'com.openautolink.app.cluster.OalClusterService'} <= names,
            FailureCode.MANIFEST_COMPONENTS)
    cluster = [node for node in app.findall('activity')
               if node.get(ns + 'name') == 'androidx.car.app.activity.CarAppActivity']
    require(len(cluster) == 1 and cluster[0].get(ns + 'taskAffinity') == app_id + '.templates',
            FailureCode.MANIFEST_AFFINITY)


def validate_tools(tools, workspace):
    """Assert the engine actually selected managed tools, not runner SDK/JDK."""
    from oal_builder.toolchain import SDK_PACKAGES
    workspace = Path(workspace).resolve()
    root = workspace / 'tools'
    for key in ('java', 'jarsigner', 'keytool', 'bundletool'):
        path = Path(tools[key])
        require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(root),
                FailureCode.TOOL_PATH)
    env = tools['env']
    for key, expected in (('ANDROID_HOME', root / 'android-sdk'), ('ANDROID_SDK_ROOT', root / 'android-sdk'),
                          ('ANDROID_NDK_HOME', root / 'android-sdk/ndk/28.2.13676358'),
                          ('GRADLE_USER_HOME', workspace / 'cache/gradle')):
        require(Path(env[key]).resolve() == expected, FailureCode.TOOL_ENV)
    java_home = Path(env['JAVA_HOME']).resolve()
    require(java_home.is_relative_to(root / 'jdk-17')
            and all(Path(tools[k]).resolve().parent == java_home / 'bin' for k in ('java', 'jarsigner', 'keytool')),
            FailureCode.TOOL_JDK)
    for directory in [root / 'jdk-17', *(root / 'android-sdk' / p.replace(';', '/') for p in SDK_PACKAGES)]:
        marker = directory / '.oal-archive-digest'
        require(directory.resolve().is_relative_to(root) and not marker.is_symlink()
                and marker.is_file() and marker.stat().st_size <= 64
                and re.fullmatch('[0-9a-f]{40}|[0-9a-f]{64}', marker.read_text()), FailureCode.TOOL_PROVENANCE)


def qualify(workspace, destination, *, accept_ci_android_licenses=False,
            expected_platform, builder_commit, timeout_seconds=9000):
    require(type(timeout_seconds) is int and 1 <= timeout_seconds <= 9000, FailureCode.BUDGET_INVALID)
    workspace, destination = prepare_ci(workspace, destination,
        accept_ci_android_licenses=accept_ci_android_licenses,
        expected_platform=expected_platform, builder_commit=builder_commit)
    started = time.monotonic()
    report = {'schema': 1, 'status': 'running', 'platform': expected_platform,
              'builderCommit': builder_commit, 'expectedSourceCommit': SOURCE_COMMIT,
              'expectedSourceRef': SOURCE_REF, 'builds': [], 'lastPhase': 'starting',
              'licenseConsent': 'explicit-ci-only', 'toolchain': 'managed',
              'purpose': 'disposable-native-first-update-qualification-not-for-publishing'}
    atomic_json(destination / 'result.json', report)
    cancel = threading.Event()
    timer = threading.Timer(timeout_seconds, cancel.set)
    timer.daemon = True
    timer.start()

    def progress(phase, _message):
        # Never print raw tool text, paths, caller-provided messages or exceptions.
        if phase in {'identity', 'download', 'tools', 'source', 'build', 'validate', 'sign', 'verify', 'complete'}:
            report['lastPhase'] = phase
            print('Native AAB qualification: ' + phase, flush=True)

    try:
        config = BuildConfig(workspace=workspace, app_id=suggest_app_id(), accept_licenses=True)
        results = []
        for _ in range(2):
            require(not cancel.is_set(), FailureCode.BUDGET_EXCEEDED)
            builder = Builder(config, progress=progress, cancel_event=cancel)
            result = builder.build()  # Real engine -> real Toolchain, no injection/fallback CLI.
            report['lastPhase'] = 'managed-toolchain-verification'
            validate_tools(builder.tools, workspace)
            report['lastPhase'] = 'artifact-metadata-verification'
            clean = validate_artifact(result, workspace, config.app_id)
            report['lastPhase'] = 'artifact-reverification'
            builder._verify(Path(result['artifact']), result,
                            {'signingCertificateSha256': clean['signingCertificateSha256']}, False)
            xml = builder._run([builder.tools['java'], '-jar', builder.tools['bundletool'],
                                'dump', 'manifest', '--bundle=' + result['artifact'], '--module=base'], 'verify')
            validate_manifest(xml, config.app_id)
            clean['manifestChecks'] = 'personal-cluster-affinity-and-original-implementation-classes'
            require(not cancel.is_set(), FailureCode.BUDGET_EXCEEDED)
            results.append(result)
            report['builds'].append(clean)
        validate_pair(*report['builds'])
        # Re-read first build too: the update must not mutate the earlier artifact.
        for result in results:
            validate_artifact(result, workspace, config.app_id)
        for result, clean, filename in zip(results, report['builds'], ('first.aab', 'update.aab')):
            target = destination / filename
            shutil.copyfile(result['artifact'], target)
            require(file_digest(target) == clean['sha256'], FailureCode.EXPORT_HASH)
            clean['artifact'] = filename
        require(not cancel.is_set(), FailureCode.BUDGET_EXCEEDED)
        report.update(status='passed', lastPhase='complete')
    except Exception as exc:
        for filename in ('first.aab', 'update.aab'):
            (destination / filename).unlink(missing_ok=True)
        # Fixed diagnostics only; do not turn exception strings into artifacts.
        reason = 'time-budget-exceeded' if cancel.is_set() else 'native-build-or-verification-failed'
        if not cancel.is_set() and type(exc) is QualificationError and isinstance(exc.code, FailureCode):
            reason = exc.code.value
        report.update(status='failed', reason=reason)
        raise BuilderError(reason) from None
    finally:
        timer.cancel()
        report['elapsedSeconds'] = round(time.monotonic() - started, 3)
        atomic_json(destination / 'result.json', report)
    return report


def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--expected-platform', required=True,
                        choices=['linux-x86_64', 'windows-x86_64', 'macos-x86_64', 'macos-arm64'])
    parser.add_argument('--builder-commit', required=True)
    parser.add_argument('--accept-ci-android-licenses', action='store_true',
                        help='Explicit Android SDK license consent for this disposable CI run only')
    parser.add_argument('--timeout-seconds', type=int, default=9000,
                        help='Cooperative cancellation budget (maximum 9000); CI also enforces a hard timeout')
    args = parser.parse_args(argv)
    try:
        qualify(args.workspace, args.output,
                accept_ci_android_licenses=args.accept_ci_android_licenses,
                expected_platform=args.expected_platform, builder_commit=args.builder_commit,
                timeout_seconds=args.timeout_seconds)
    except Exception:
        print('Native AAB qualification failed; inspect the sanitized result if present. No native pass is claimed.', flush=True)
        return 1
    print('Native first/update AAB qualification passed; disposable artifacts are not for publishing.', flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

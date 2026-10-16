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
from pathlib import Path

from oal_builder.engine import BuildConfig, Builder, BuilderError, atomic_json, suggest_app_id, verify_bundle_entries
from oal_builder.native_verification import OVERLAY_ID, SOURCE_COMMIT, SOURCE_REF
from oal_builder.toolchain import file_digest


METADATA = ('applicationId', 'versionCode', 'versionName', 'sourceCommit',
            'sourceRef', 'sourceOverlays', 'signingCertificateSha256',
            'carplayIncluded', 'sha256')


def require(condition, message):
    if not condition:
        raise BuilderError(message)


def native_platform():
    system = {'Linux': 'linux', 'Windows': 'windows', 'Darwin': 'macos'}.get(platform.system())
    arch = {'amd64': 'x86_64', 'x86_64': 'x86_64', 'arm64': 'arm64', 'aarch64': 'arm64'}.get(platform.machine().lower())
    name = f'{system}-{arch}'
    require(name in {'linux-x86_64', 'windows-x86_64', 'macos-x86_64', 'macos-arm64'}, 'Unsupported native host')
    return name


def prepare_ci(workspace, destination, *, accept_ci_android_licenses, expected_platform, builder_commit):
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and accept_ci_android_licenses is True,
            'Explicit CI-only Android license consent is required')
    require(native_platform() == expected_platform, 'Native host architecture does not match matrix')
    require(isinstance(builder_commit, str) and re.fullmatch('[0-9a-f]{40}', builder_commit), 'Exact builder commit required')
    require(bool(os.environ.get('RUNNER_TEMP')), 'RUNNER_TEMP required')
    root = Path(os.environ['RUNNER_TEMP']).resolve()
    paths = []
    for value in (workspace, destination):
        path = Path(value).absolute()
        require(not any(p.is_symlink() for p in (path, *path.parents) if p != root and root in p.parents),
                'Symlink in qualification path')
        path = path.resolve()
        require(path != root and path.is_relative_to(root) and not path.exists(),
                'Use fresh dedicated paths beneath RUNNER_TEMP')
        paths.append(path)
    workspace, destination = paths
    require(not workspace.is_relative_to(destination) and not destination.is_relative_to(workspace),
            'Export must be separate from managed workspace')
    destination.mkdir(parents=True)
    return workspace, destination


def validate_pair(first, update):
    for key in ('applicationId', 'signingCertificateSha256', 'sourceCommit',
                'sourceRef', 'sourceOverlays', 'versionName'):
        require(first[key] == update[key], 'First/update identity or source changed')
    require(update['versionCode'] > first['versionCode'], 'Update version did not increase')
    require(first['artifact'] != update['artifact'] and first['sha256'] != update['sha256'],
            'First/update artifacts were reused')


def validate_artifact(result, workspace, app_id):
    """Check on-disk bytes/sidecars; return only bounded public metadata.

    Cryptographic signer and binary manifest verification is additionally run
    with the real Builder verifier by qualify(), not simulated here.
    """
    require(all(key in result for key in (*METADATA, 'artifact')), 'Incomplete build result')
    require(result['applicationId'] == app_id, 'Application ID changed')
    require(type(result['versionCode']) is int and 1 <= result['versionCode'] <= 2100000000,
            'Invalid version code')
    require(result['sourceCommit'] == SOURCE_COMMIT and result['sourceRef'] == SOURCE_REF
            and result['versionName'] == SOURCE_REF.removeprefix('v'), 'Unexpected source metadata')
    overlays = result['sourceOverlays']
    require(isinstance(overlays, list) and all(isinstance(v, str) for v in overlays)
            and len(overlays) == len(set(overlays))
            and set(overlays) <= {'personal-cluster-task-affinity', OVERLAY_ID}, 'Unexpected source overlays')
    for key in ('sha256', 'signingCertificateSha256'):
        require(isinstance(result[key], str) and re.fullmatch('[0-9a-f]{64}', result[key]), 'Invalid fingerprint or hash')
    require(result['carplayIncluded'] is False, 'CarPlay must be absent')
    path = Path(result['artifact'])
    output = Path(workspace).resolve() / 'output'
    require(not output.is_symlink() and not path.is_symlink()
            and path.resolve().parent == output and path.suffix == '.aab'
            and re.fullmatch('[A-Za-z0-9._-]+\\.aab', path.name)
            and path.is_file() and path.stat().st_size > 0, 'Invalid artifact path')
    require(file_digest(path) == result['sha256'], 'Artifact hash mismatch')
    verify_bundle_entries(path, False)
    sidecar = path.with_suffix('.json')
    checksum = path.with_suffix('.aab.sha256')
    require(not sidecar.is_symlink() and not checksum.is_symlink()
            and sidecar.is_file() and checksum.is_file()
            and sidecar.stat().st_size <= 65536 and checksum.stat().st_size <= 1024, 'Invalid artifact sidecars')
    saved = json.loads(sidecar.read_text('utf-8'))
    require(all(saved.get(key) == result[key] for key in METADATA)
            and saved.get('artifact') == result['artifact'], 'Artifact metadata mismatch')
    require(checksum.read_text('utf-8') == result['sha256'] + '  ' + path.name + '\n', 'Artifact checksum sidecar mismatch')
    return {**{key: result[key] for key in METADATA}, 'artifact': path.name,
            'sizeBytes': path.stat().st_size, 'abis': ['arm64-v8a', 'x86_64']}


def validate_manifest(xml, app_id):
    import xml.etree.ElementTree as ET
    root = ET.fromstring(xml)
    ns = '{http://schemas.android.com/apk/res/android}'
    app = root.find('application')
    require(root.get('package') == app_id and app is not None
            and app.get(ns + 'name') == 'com.openautolink.app.OalApplication',
            'Manifest implementation application class changed')
    names = {node.get(ns + 'name') for node in app}
    require({'com.openautolink.app.MainActivity', 'com.openautolink.app.cluster.OalClusterService'} <= names,
            'Manifest implementation component classes changed')
    cluster = [node for node in app.findall('activity')
               if node.get(ns + 'name') == 'androidx.car.app.activity.CarAppActivity']
    require(len(cluster) == 1 and cluster[0].get(ns + 'taskAffinity') == app_id + '.templates',
            'Manifest cluster affinity is not personalized')


def validate_tools(tools, workspace):
    """Assert the engine actually selected managed tools, not runner SDK/JDK."""
    from oal_builder.toolchain import SDK_PACKAGES
    workspace = Path(workspace).resolve()
    root = workspace / 'tools'
    for key in ('java', 'jarsigner', 'keytool', 'bundletool'):
        path = Path(tools[key])
        require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(root),
                'Non-managed verification tool')
    env = tools['env']
    for key, expected in (('ANDROID_HOME', root / 'android-sdk'), ('ANDROID_SDK_ROOT', root / 'android-sdk'),
                          ('ANDROID_NDK_HOME', root / 'android-sdk/ndk/28.2.13676358'),
                          ('GRADLE_USER_HOME', workspace / 'cache/gradle')):
        require(Path(env[key]).resolve() == expected, 'Non-managed build environment')
    java_home = Path(env['JAVA_HOME']).resolve()
    require(java_home.is_relative_to(root / 'jdk-17')
            and all(Path(tools[k]).resolve().parent == java_home / 'bin' for k in ('java', 'jarsigner', 'keytool')),
            'Non-managed JDK')
    for directory in [root / 'jdk-17', *(root / 'android-sdk' / p.replace(';', '/') for p in SDK_PACKAGES)]:
        marker = directory / '.oal-archive-digest'
        require(directory.resolve().is_relative_to(root) and not marker.is_symlink()
                and marker.is_file() and marker.stat().st_size <= 64
                and re.fullmatch('[0-9a-f]{40}|[0-9a-f]{64}', marker.read_text()), 'Managed tool provenance missing')


def qualify(workspace, destination, *, accept_ci_android_licenses=False,
            expected_platform, builder_commit, timeout_seconds=9000):
    require(type(timeout_seconds) is int and 1 <= timeout_seconds <= 9000, 'Invalid qualification time budget')
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
            require(not cancel.is_set(), 'Qualification time budget exceeded')
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
            require(not cancel.is_set(), 'Qualification time budget exceeded')
            results.append(result)
            report['builds'].append(clean)
        validate_pair(*report['builds'])
        # Re-read first build too: the update must not mutate the earlier artifact.
        for result in results:
            validate_artifact(result, workspace, config.app_id)
        for result, clean, filename in zip(results, report['builds'], ('first.aab', 'update.aab')):
            target = destination / filename
            shutil.copyfile(result['artifact'], target)
            require(file_digest(target) == clean['sha256'], 'Export hash mismatch')
            clean['artifact'] = filename
        require(not cancel.is_set(), 'Qualification time budget exceeded')
        report.update(status='passed', lastPhase='complete')
    except Exception:
        for filename in ('first.aab', 'update.aab'):
            (destination / filename).unlink(missing_ok=True)
        # Fixed diagnostics only; do not turn exception strings into artifacts.
        reason = 'time-budget-exceeded' if cancel.is_set() else 'native-build-or-verification-failed'
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

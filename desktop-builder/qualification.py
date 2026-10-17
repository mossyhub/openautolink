"""CI-only native AAB transformation/signing first-update gate.

All hosts consume one exact template/descriptor/source tuple. Only the separate
Linux producer may compile source; native jobs never install Android tooling.
Local prepublication and actual published-template artifacts remain distinct.
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
            'carplayIncluded', 'sha256', 'mode')


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
    MODE = 'build-mode-mismatch'
    TEMPLATE_HASH = 'template-hash-mismatch'
    PAYLOAD = 'dex-native-payload-mismatch'
    PERSONALIZE_COMPILED = 'personalization-used-android-build-tools'


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


def prepare_ci(workspace, destination, *, expected_platform, builder_commit):
    require(os.environ.get('GITHUB_ACTIONS') == 'true', FailureCode.CI_CONSENT)
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
    require(first['mode'] == update['mode'] == 'personalize', FailureCode.MODE)
    require(first['sourceOverlays'] == update['sourceOverlays'], FailureCode.PAIR_OVERLAYS)
    for key, code in (('applicationId', FailureCode.PAIR_APP_ID),
                      ('signingCertificateSha256', FailureCode.PAIR_SIGNER),
                      ('sourceCommit', FailureCode.PAIR_SOURCE_COMMIT),
                      ('sourceRef', FailureCode.PAIR_SOURCE_REF),

                      ('versionName', FailureCode.PAIR_VERSION_NAME)):
        require(first[key] == update[key], code)
    require(update['versionCode'] > first['versionCode'], FailureCode.PAIR_VERSION)
    require(first['artifact'] != update['artifact'], FailureCode.PAIR_PATH)
    require(first['sha256'] != update['sha256'], FailureCode.PAIR_HASH)


def validate_artifact(result, workspace, app_id, release, mode, template_sha256=None):
    """Check on-disk bytes/sidecars; return only bounded public metadata.

    Cryptographic signer and binary manifest verification is additionally run
    with the real Builder verifier by qualify(), not simulated here.
    """
    require(isinstance(result, dict) and all(key in result for key in (*METADATA, 'artifact')),
            FailureCode.RESULT_INCOMPLETE)
    require(result['applicationId'] == app_id, FailureCode.APP_ID)
    require(type(result['versionCode']) is int and 1 <= result['versionCode'] <= 2100000000,
            FailureCode.VERSION_CODE)
    require(result['sourceCommit'] == release['sourceCommit'], FailureCode.SOURCE_COMMIT)
    require(result['sourceRef'] == release['sourceRef'], FailureCode.SOURCE_REF)
    require(result['versionName'] == release['versionName'], FailureCode.SOURCE_VERSION_NAME)
    require(mode in {'source', 'personalize'} and result['mode'] == mode, FailureCode.MODE)
    if mode == 'personalize':
        require(isinstance(template_sha256, str) and re.fullmatch('[0-9a-f]{64}', template_sha256)
                and result.get('templateSha256') == template_sha256, FailureCode.TEMPLATE_HASH)
    overlays = result['sourceOverlays']
    allowed = ({'personal-cluster-task-affinity', OVERLAY_ID}
               if mode == 'source' and release['sourceCommit'] == SOURCE_COMMIT
               and release['sourceRef'] == SOURCE_REF else set())
    require(isinstance(overlays, list) and all(isinstance(v, str) for v in overlays)
            and len(overlays) == len(set(overlays))
            and set(overlays) <= allowed, FailureCode.OVERLAYS)
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
    fields = METADATA + (('templateSha256',) if mode == 'personalize' else ())
    require(all(saved.get(key) == result[key] for key in fields)
            and saved.get('artifact') == result['artifact'], FailureCode.METADATA_MATCH)
    require(checksum.read_text('utf-8') == result['sha256'] + '  ' + path.name + '\n', FailureCode.CHECKSUM_MATCH)
    return {**{key: result[key] for key in fields}, 'artifact': path.name,
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


def validate_tools(tools, workspace, mode='source'):
    """Assert the engine actually selected managed tools, not runner SDK/JDK."""
    from oal_builder.toolchain import SDK_PACKAGES
    workspace = Path(workspace).resolve()
    root = workspace / 'tools'
    require(mode in {'source', 'personalize'}, FailureCode.MODE)
    for key in ('java', 'jarsigner', 'keytool', 'bundletool') + (('javac',) if mode == 'personalize' else ()):
        path = Path(tools[key])
        require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(root),
                FailureCode.TOOL_PATH)
    env = tools['env']
    android_env = (('ANDROID_HOME', root / 'android-sdk'), ('ANDROID_SDK_ROOT', root / 'android-sdk'),
                          ('ANDROID_NDK_HOME', root / 'android-sdk/ndk/28.2.13676358'),
                          ('GRADLE_USER_HOME', workspace / 'cache/gradle'))
    for key, expected in android_env:
        if mode == 'source':
            require(key in env and Path(env[key]).resolve() == expected, FailureCode.TOOL_ENV)
        else:
            require(key not in env, FailureCode.TOOL_ENV)
    java_home = Path(env['JAVA_HOME']).resolve()
    require(java_home.is_relative_to(root / 'jdk-17')
            and all(Path(tools[k]).resolve().parent == java_home / 'bin' for k in ('java', 'jarsigner', 'keytool')),
            FailureCode.TOOL_JDK)
    for directory in [root / 'jdk-17', *(root / 'android-sdk' / p.replace(';', '/') for p in SDK_PACKAGES if mode == 'source')]:
        marker = directory / '.oal-archive-digest'
        require(directory.resolve().is_relative_to(root) and not marker.is_symlink()
                and marker.is_file() and marker.stat().st_size <= 64
                and re.fullmatch('[0-9a-f]{40}|[0-9a-f]{64}', marker.read_text()), FailureCode.TOOL_PROVENANCE)


EXPORTS = ('personalize-first.aab', 'personalize-update.aab')


def qualify(workspace, destination, *, expected_platform, builder_commit,
            template_inputs, timeout_seconds=3600):
    from scripts import ci_template as local
    from oal_builder.releases import DESCRIPTOR_NAME
    require(type(timeout_seconds) is int and 1 <= timeout_seconds <= 9000, FailureCode.BUDGET_INVALID)
    inputs = local.load_inputs(template_inputs)
    release = inputs['release']
    workspace, destination = prepare_ci(workspace, destination,
        expected_platform=expected_platform, builder_commit=builder_commit)
    started = time.monotonic()
    report = {'schema': 3, 'status': 'running', 'platform': expected_platform,
              'builderCommit': builder_commit, 'expectedSourceCommit': release['sourceCommit'],
              'expectedSourceRef': release['sourceRef'], 'builds': [], 'lastPhase': 'starting',
              'sourceSelection': local.SELECTION, 'liveReleaseTemplateDownloadQualified': False,
              'publishedTemplateArtifactQualified': False,
              'licenseConsent': 'not-required-no-android-tools', 'toolchain': 'managed-java-bundletool-only',
              'purpose': 'disposable-native-aab-only-qualification-not-for-publishing'}
    atomic_json(destination / 'result.json', report)
    cancel = threading.Event()
    timer = threading.Timer(timeout_seconds, cancel.set)
    timer.daemon = True
    timer.start()
    results = []

    def progress(phase, _message):
        # Fixed phase vocabulary only: never tool messages, exceptions or paths.
        if phase in {'identity', 'release', 'download', 'tools', 'source', 'build',
                     'validate', 'sign', 'verify', 'complete', 'personalize', 'template'}:
            report['lastPhase'] = phase
            print('Native AAB qualification: ' + phase, flush=True)

    def build(config, selected, descriptor_bytes=None, template_sha=None):
        require(not cancel.is_set(), FailureCode.BUDGET_EXCEEDED)
        report['scenario'] = EXPORTS[len(results)].removesuffix('.aab')
        with local.bound_inputs(selected, descriptor_bytes):
            builder = Builder(config, progress=progress, cancel_event=cancel)
            result = builder.build()  # Real Builder/Toolchain, never build fixtures.
        report['lastPhase'] = 'managed-toolchain-verification'
        validate_tools(builder.tools, config.workspace, mode=config.mode)
        report['lastPhase'] = 'artifact-metadata-verification'
        clean = validate_artifact(result, config.workspace, config.app_id, release, config.mode, template_sha)
        report['lastPhase'] = 'artifact-reverification'
        builder._verify(Path(result['artifact']), result,
                        {'signingCertificateSha256': clean['signingCertificateSha256']}, False)
        xml = builder._run([builder.tools['java'], '-jar', builder.tools['bundletool'],
                            'dump', 'manifest', '--bundle=' + result['artifact'], '--module=base'], 'verify')
        validate_manifest(xml, config.app_id)
        clean['manifestChecks'] = 'personal-cluster-affinity-and-original-implementation-classes'
        clean['payloadSha256'] = local.payload_digest(result['artifact'])
        require(not cancel.is_set(), FailureCode.BUDGET_EXCEEDED)
        results.append((result, config, template_sha))
        report['builds'].append(clean)
        return builder, result

    try:
        template, descriptor = inputs['template'], inputs['descriptor']
        selected, raw = inputs['selected'], inputs['raw']
        digest = descriptor['templateAsset']['sha256']
        report['template'] = {'provenance': inputs['provenance'],
                              'sourcePreparation': inputs['sourcePreparation'],
                              'sourceCommit': release['sourceCommit'], 'sourceRef': release['sourceRef'],
                              'sha256': digest, 'sizeBytes': descriptor['templateAsset']['size'],
                              'descriptorSha256': file_digest(Path(template_inputs) / DESCRIPTOR_NAME),
                              'payloadSha256': local.payload_digest(template)}
        easy_config = BuildConfig(workspace=workspace, app_id=suggest_app_id(),
                                  accept_licenses=False, mode='personalize')
        local.seed_template(workspace, template, descriptor)
        build(easy_config, selected, raw, digest)
        build(easy_config, selected, raw, digest)
        require(not (workspace / 'tools/android-sdk').exists()
                and not (workspace / 'cache/gradle').exists()
                and not any((workspace / 'source').glob('*')), FailureCode.PERSONALIZE_COMPILED)
        validate_pair(report['builds'][0], report['builds'][1])
        require(all(b['payloadSha256'] == report['template']['payloadSha256'] for b in report['builds']),
                FailureCode.PAYLOAD)
        # Later builds must not modify earlier artifacts or their sidecars.
        for result, config, template_sha in results:
            validate_artifact(result, config.workspace, config.app_id, release, config.mode, template_sha)
        for (result, _, _), clean, filename in zip(results, report['builds'], EXPORTS):
            target = destination / filename
            shutil.copyfile(result['artifact'], target)
            require(file_digest(target) == clean['sha256'], FailureCode.EXPORT_HASH)
            clean['artifact'] = filename
        require(not cancel.is_set(), FailureCode.BUDGET_EXCEEDED)
        report.update(status='passed', lastPhase='complete',
                      relationships=['personalize-first-to-update'],
                      publishedTemplateArtifactQualified=inputs['provenance'] == 'published-release-template')
    except Exception as exc:
        for filename in EXPORTS:
            (destination / filename).unlink(missing_ok=True)
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
    parser.add_argument('--template-inputs', required=True, type=Path,
                        help='Exact shared AAB, descriptor, release and provenance directory')
    parser.add_argument('--timeout-seconds', type=int, default=3600,
                        help='Cooperative cancellation budget (maximum 9000); CI also enforces a hard timeout')
    args = parser.parse_args(argv)
    try:
        qualify(args.workspace, args.output,
                expected_platform=args.expected_platform, builder_commit=args.builder_commit,
                timeout_seconds=args.timeout_seconds, template_inputs=args.template_inputs)
    except Exception:
        print('Native AAB qualification failed; inspect the sanitized result if present. No native pass is claimed.', flush=True)
        return 1
    print('Native AAB-only qualification passed; inspect template provenance in result.json. Native live download is NOT qualified.', flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

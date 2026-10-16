"""Qualification unit fixtures are NOT evidence of native Android builds."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import zipfile

import pytest

ROOT = Path(__file__).resolve().parents[1]


def qualification():
    path = ROOT / 'qualification.py'
    assert path.is_file(), 'native AAB qualification entry point is missing'
    spec = importlib.util.spec_from_file_location('qualification', path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def bundle_result(tmp_path, code=1):
    from oal_builder.native_verification import SOURCE_COMMIT, SOURCE_REF, OVERLAY_ID
    artifact = tmp_path / 'output' / f'fixture-{code}.aab'
    artifact.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(artifact, 'w') as archive:
        for abi in ('arm64-v8a', 'x86_64'):
            archive.writestr(f'base/lib/{abi}/libopenautolink-jni.so', b'unit fixture, NOT native proof')
        archive.writestr('base/manifest/AndroidManifest.xml', str(code))
    result = dict(applicationId='com.example.qualification', versionCode=code,
                  versionName=SOURCE_REF.removeprefix('v'), sourceCommit=SOURCE_COMMIT,
                  sourceRef=SOURCE_REF, sourceOverlays=['personal-cluster-task-affinity', OVERLAY_ID],
                  signingCertificateSha256='a' * 64, carplayIncluded=False,
                  artifact=str(artifact), sha256=hashlib.sha256(artifact.read_bytes()).hexdigest())
    artifact.with_suffix('.json').write_text(json.dumps(result))
    artifact.with_suffix('.aab.sha256').write_text(result['sha256'] + '  ' + artifact.name + '\n')
    return result


def test_artifact_validation_reads_actual_files_and_only_exports_allowlist(tmp_path):
    q = qualification()
    result = bundle_result(tmp_path)
    result['password'] = 'DO-NOT-EXPORT'
    clean = q.validate_artifact(result, tmp_path, result['applicationId'])
    assert clean['sha256'] == hashlib.sha256(Path(result['artifact']).read_bytes()).hexdigest()
    assert clean['sizeBytes'] == Path(result['artifact']).stat().st_size
    assert clean['abis'] == ['arm64-v8a', 'x86_64']
    assert clean['carplayIncluded'] is False
    assert clean['artifact'] == 'fixture-1.aab'
    assert str(tmp_path) not in json.dumps(clean)
    assert 'DO-NOT-EXPORT' not in json.dumps(clean)


@pytest.mark.parametrize('mutation,reason', [
    ('hash', 'artifact-hash-mismatch'), ('missing', 'artifact-path-invalid'),
    ('carplay', 'bundle-entries-invalid'), ('abi', 'bundle-entries-invalid'),
    ('source', 'source-commit-mismatch'), ('sidecar', 'artifact-metadata-mismatch'),
    ('version', 'version-code-invalid'), ('app', 'application-id-mismatch'),
    ('overlay', 'source-overlays-invalid'), ('signer', 'signing-fingerprint-invalid'),
    ('hash-format', 'artifact-hash-invalid'), ('incomplete', 'build-result-incomplete'),
    ('carplay-flag', 'carplay-not-absent'), ('sidecar-missing', 'artifact-sidecars-invalid'),
    ('sidecar-json', 'artifact-metadata-unreadable'), ('sidecar-shape', 'artifact-metadata-invalid'),
    ('checksum', 'artifact-checksum-mismatch'),
])
def test_artifact_validation_rejects_bad_evidence(tmp_path, mutation, reason):
    q = qualification()
    result = bundle_result(tmp_path)
    path = Path(result['artifact'])
    if mutation == 'hash':
        path.write_bytes(path.read_bytes() + b'tampered')
    elif mutation == 'missing':
        path.unlink()
    elif mutation in ('carplay', 'abi'):
        with zipfile.ZipFile(path, 'a') as archive:
            archive.writestr('base/assets/offline-mfi/identity.pk8' if mutation == 'carplay'
                             else 'base/lib/armeabi-v7a/libextra.so', b'not a real credential')
        result['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
    elif mutation == 'source':
        result['sourceCommit'] = '0' * 40
    elif mutation == 'sidecar':
        path.with_suffix('.json').write_text('{}')
    elif mutation == 'version':
        result['versionCode'] = True
    elif mutation == 'app':
        result['applicationId'] = 'com.wrong.identity'
    elif mutation == 'overlay':
        result['sourceOverlays'] = ['PRIVATE-FREEFORM-TEXT']
    elif mutation == 'signer':
        result['signingCertificateSha256'] = 'malformed'
    elif mutation == 'hash-format':
        result['sha256'] = 'SECRET'
    elif mutation == 'incomplete':
        del result['sourceCommit']
    elif mutation == 'carplay-flag':
        result['carplayIncluded'] = True
    elif mutation == 'sidecar-missing':
        path.with_suffix('.json').unlink()
    elif mutation == 'sidecar-json':
        path.with_suffix('.json').write_text('SECRET invalid JSON')
    elif mutation == 'sidecar-shape':
        path.with_suffix('.json').write_text('[]')
    elif mutation == 'checksum':
        path.with_suffix('.aab.sha256').write_text('SECRET')
    with pytest.raises(q.BuilderError) as caught:
        q.validate_artifact(result, tmp_path, 'com.example.qualification')
    assert str(caught.value) == reason


def test_pair_requires_same_identity_source_and_increasing_version(tmp_path):
    q = qualification()
    assert hasattr(q, 'validate_pair'), 'first/update relationship gate is missing'
    first = q.validate_artifact(bundle_result(tmp_path, 1), tmp_path, 'com.example.qualification')
    second = q.validate_artifact(bundle_result(tmp_path, 2), tmp_path, 'com.example.qualification')
    q.validate_pair(first, second)
    for changed, reason in (
        ({'versionCode': 1}, 'update-version-not-increased'),
        ({'applicationId': 'com.changed.app'}, 'pair-application-id-mismatch'),
        ({'signingCertificateSha256': 'b' * 64}, 'pair-signer-mismatch'),
        ({'sourceCommit': '0' * 40}, 'pair-source-commit-mismatch'),
        ({'sourceRef': 'v0.1.527'}, 'pair-source-ref-mismatch'),
        ({'sourceOverlays': []}, 'pair-source-overlays-mismatch'),
        ({'versionName': '0.1.527'}, 'pair-version-name-mismatch'),
        ({'sha256': first['sha256']}, 'pair-artifact-hash-reused'),
        ({'artifact': first['artifact']}, 'pair-artifact-path-reused'),
    ):
        with pytest.raises(q.BuilderError) as caught:
            q.validate_pair(first, {**second, **changed})
        assert str(caught.value) == reason


def test_ci_consent_and_fresh_runner_temp_paths_are_mandatory(tmp_path, monkeypatch):
    q = qualification()
    assert hasattr(q, 'prepare_ci'), 'CI consent and workspace guard is missing'
    monkeypatch.setenv('GITHUB_ACTIONS', 'true')
    monkeypatch.setenv('RUNNER_TEMP', str(tmp_path))
    kwargs = dict(accept_ci_android_licenses=True, expected_platform=q.native_platform(), builder_commit='1' * 40)
    work, out = q.prepare_ci(tmp_path / 'work', tmp_path / 'export', **kwargs)
    assert work == tmp_path / 'work' and out == tmp_path / 'export'
    assert out.is_dir() and not work.exists()
    with pytest.raises(q.BuilderError):
        q.prepare_ci(work, out, **kwargs)  # No stale results on retries.
    with pytest.raises(q.BuilderError):
        q.prepare_ci(tmp_path / 'new', tmp_path / 'new/out', **kwargs)
    with pytest.raises(q.BuilderError):
        q.prepare_ci(tmp_path.parent / 'outside', tmp_path / 'another', **kwargs)
    for override in ({'accept_ci_android_licenses': False}, {'expected_platform': 'wrong'}, {'builder_commit': 'main'}):
        with pytest.raises(q.BuilderError):
            q.prepare_ci(tmp_path / 'unused', tmp_path / 'unused-export', **{**kwargs, **override})
    monkeypatch.delenv('GITHUB_ACTIONS')
    with pytest.raises(q.BuilderError):
        q.prepare_ci(tmp_path / 'unused', tmp_path / 'unused-export', **kwargs)


def tool_fixture(workspace):
    """Path-only tool fixture; never executes Java or Android tooling."""
    tools = workspace / 'tools'
    paths = {}
    for name in ('java', 'jarsigner', 'keytool'):
        path = tools / 'jdk-17/bin' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('unit fixture')
        paths[name] = str(path)
    paths['bundletool'] = str(tools / 'bundletool-1.18.3.jar')
    Path(paths['bundletool']).write_text('unit fixture')
    from oal_builder.toolchain import SDK_PACKAGES
    for directory in [tools / 'jdk-17', *(tools / 'android-sdk' / p.replace(';', '/') for p in SDK_PACKAGES)]:
        directory.mkdir(parents=True, exist_ok=True)
        (directory / '.oal-archive-digest').write_text('a' * 64)
    paths['env'] = {'JAVA_HOME': str(tools / 'jdk-17'),
                    'ANDROID_HOME': str(tools / 'android-sdk'),
                    'ANDROID_SDK_ROOT': str(tools / 'android-sdk'),
                    'ANDROID_NDK_HOME': str(tools / 'android-sdk/ndk/28.2.13676358'),
                    'GRADLE_USER_HOME': str(workspace / 'cache/gradle')}
    return paths


def manifest_fixture(app_id):
    return f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{app_id}">
      <application android:name="com.openautolink.app.OalApplication">
        <activity android:name="com.openautolink.app.MainActivity"/>
        <activity android:name="androidx.car.app.activity.CarAppActivity" android:taskAffinity="{app_id}.templates"/>
        <service android:name="com.openautolink.app.cluster.OalClusterService"/>
      </application>
    </manifest>'''


def test_actual_manifest_keeps_implementation_classes_and_personal_affinity():
    q = qualification()
    assert hasattr(q, 'validate_manifest'), 'manifest affinity and implementation gate is missing'
    app_id = 'com.example.qualification'
    xml = manifest_fixture(app_id)
    q.validate_manifest(xml, app_id)
    for changed, reason in (
        (xml.replace(app_id + '.templates', 'com.openautolink.app.templates'), 'manifest-cluster-affinity-mismatch'),
        (xml.replace('com.openautolink.app.MainActivity', app_id + '.MainActivity'), 'manifest-components-mismatch'),
        (xml.replace('com.openautolink.app.OalApplication', app_id + '.OalApplication'), 'manifest-application-mismatch'),
        (xml.replace('com.openautolink.app.cluster.OalClusterService', app_id + '.cluster.OalClusterService'), 'manifest-components-mismatch'),
        ('SECRET invalid XML', 'manifest-xml-invalid'),
    ):
        with pytest.raises(q.BuilderError) as caught:
            q.validate_manifest(changed, app_id)
        assert str(caught.value) == reason


def test_orchestration_builds_twice_reverifies_and_exports_no_identity(tmp_path, monkeypatch):
    q = qualification()
    assert hasattr(q, 'qualify'), 'real first/update orchestration is missing'
    monkeypatch.setenv('GITHUB_ACTIONS', 'true')
    monkeypatch.setenv('RUNNER_TEMP', str(tmp_path))
    calls = []

    class FixtureBuilder:
        def __init__(self, config, progress, cancel_event):
            assert config.accept_licenses and config.carplay_key is None and config.carplay_certificate is None
            self.config, self.cancel_event = config, cancel_event
            self.tools = tool_fixture(config.workspace)
        def build(self):
            calls.append(('build', self.config.app_id))
            result = bundle_result(self.config.workspace, sum(c[0] == 'build' for c in calls))
            result['applicationId'] = self.config.app_id
            Path(result['artifact']).with_suffix('.json').write_text(json.dumps(result))
            private = self.config.workspace / 'private/identity'
            private.mkdir(parents=True, exist_ok=True)
            (private / 'password').write_text('DO-NOT-EXPORT')
            return result
        def _verify(self, bundle, expected, state, carplay):
            calls.append(('verify', expected['versionCode']))
            assert Path(bundle).is_file() and carplay is False
            assert state['signingCertificateSha256'] == expected['signingCertificateSha256']
        def _run(self, argv, phase):
            assert phase == 'verify' and 'dump' in argv and 'manifest' in argv
            return manifest_fixture(self.config.app_id)

    monkeypatch.setattr(q, 'Builder', FixtureBuilder)
    out = tmp_path / 'export'
    report = q.qualify(tmp_path / 'work', out, accept_ci_android_licenses=True,
                       expected_platform=q.native_platform(), builder_commit='1' * 40, timeout_seconds=60)
    assert [c[0] for c in calls] == ['build', 'verify', 'build', 'verify']
    assert calls[0][1] == calls[2][1]
    assert report['status'] == 'passed'
    assert len(report['builds']) == 2
    assert {p.name for p in out.iterdir()} == {'result.json', 'first.aab', 'update.aab'}
    text = (out / 'result.json').read_text()
    assert 'DO-NOT-EXPORT' not in text and str(tmp_path) not in text
    for build, name in zip(report['builds'], ('first.aab', 'update.aab')):
        assert build['artifact'] == name
        assert build['sha256'] == hashlib.sha256((out / name).read_bytes()).hexdigest()


@pytest.mark.parametrize('field,value,reason', [
    ('sourceCommit', '10dc2286cb3944201edfca18ddb1c7251215ac64', 'source-commit-mismatch'),
    ('sourceRef', 'v0.1.527', 'source-ref-mismatch'),
    ('versionName', '0.1.527', 'source-version-name-mismatch'),
])
def test_post_build_source_failure_exports_exact_safe_reason(tmp_path, monkeypatch, capsys, field, value, reason):
    q = qualification()
    monkeypatch.setenv('GITHUB_ACTIONS', 'true')
    monkeypatch.setenv('RUNNER_TEMP', str(tmp_path))
    calls = []

    class DriftBuilder:
        def __init__(self, config, progress, cancel_event):
            self.config, self.progress = config, progress
            self.tools = tool_fixture(config.workspace)
        def build(self):
            calls.append('build')
            result = bundle_result(self.config.workspace)
            result.update(applicationId=self.config.app_id, password='SECRET /private/identity/password')
            result[field] = value
            self.progress('complete', 'SECRET /private/identity/password')
            return result

    monkeypatch.setattr(q, 'Builder', DriftBuilder)
    out = tmp_path / 'export'
    with pytest.raises(q.BuilderError) as caught:
        q.qualify(tmp_path / 'work', out, accept_ci_android_licenses=True,
                  expected_platform=q.native_platform(), builder_commit='1' * 40, timeout_seconds=60)
    report = json.loads((out / 'result.json').read_text())
    assert report['reason'] == reason
    assert str(caught.value) == reason
    assert report['status'] == 'failed'
    assert report['lastPhase'] == 'artifact-metadata-verification'
    assert report['builds'] == [] and calls == ['build']
    assert {p.name for p in out.iterdir()} == {'result.json'}
    assert 'SECRET' not in json.dumps(report) + capsys.readouterr().out
    assert str(tmp_path) not in json.dumps(report)


@pytest.mark.parametrize('timeout', [False, True])
@pytest.mark.parametrize('error_kind', ['runtime', 'builder', 'spoofed-code'])
def test_failure_is_nonproof_and_no_raw_diagnostics_escape(tmp_path, monkeypatch, capsys, timeout, error_kind):
    q = qualification()
    monkeypatch.setenv('GITHUB_ACTIONS', 'true')
    monkeypatch.setenv('RUNNER_TEMP', str(tmp_path))
    class FailingBuilder:
        def __init__(self, config, progress, cancel_event):
            self.cancel = cancel_event
            progress('build', 'SECRET /private/identity/password')
        def build(self):
            if timeout:
                assert self.cancel.wait(3), 'qualification timer did not fire'
            error_type = RuntimeError if error_kind == 'runtime' else q.BuilderError
            error = error_type('SECRET /private/identity/password')
            if error_kind == 'spoofed-code':
                error.code = q.FailureCode.SOURCE_COMMIT
            raise error
    monkeypatch.setattr(q, 'Builder', FailingBuilder)
    out = tmp_path / 'export'
    with pytest.raises(q.BuilderError, match='time-budget' if timeout else 'native-build'):
        q.qualify(tmp_path / 'work', out, accept_ci_android_licenses=True,
                  expected_platform=q.native_platform(), builder_commit='1' * 40, timeout_seconds=1)
    assert {p.name for p in out.iterdir()} == {'result.json'}
    text = (out / 'result.json').read_text()
    report = json.loads(text)
    assert report['status'] == 'failed' and report['lastPhase'] == 'build'
    assert report['reason'] == ('time-budget-exceeded' if timeout else 'native-build-or-verification-failed')
    assert not report['builds']
    assert 'SECRET' not in text + capsys.readouterr().out


def test_diagnostic_type_rejects_raw_text_and_every_code_is_bounded():
    import re
    q = qualification()
    with pytest.raises(TypeError, match='^Qualification diagnostic must be a fixed code$'):
        q.QualificationError('SECRET /private/identity/password')
    for code in q.FailureCode:
        assert re.fullmatch('[a-z][a-z-]{0,63}', code.value)
        with pytest.raises(q.QualificationError) as caught:
            q.require(False, code)
        assert caught.value.code is code and str(caught.value) == code.value


def test_managed_tool_validation_rejects_host_paths_and_missing_provenance(tmp_path):
    q = qualification()
    tools = tool_fixture(tmp_path)
    q.validate_tools(tools, tmp_path)
    host = tmp_path / 'host-java'
    host.write_text('not managed')
    with pytest.raises(q.BuilderError, match='^verification-tool-not-managed$'):
        q.validate_tools({**tools, 'java': str(host)}, tmp_path)
    with pytest.raises(q.BuilderError, match='^build-environment-not-managed$'):
        q.validate_tools({**tools, 'env': {**tools['env'], 'ANDROID_HOME': '/host-sdk'}}, tmp_path)
    (tmp_path / 'tools/jdk-17/.oal-archive-digest').unlink()
    with pytest.raises(q.BuilderError, match='^managed-tool-provenance-invalid$'):
        q.validate_tools(tools, tmp_path)


def test_every_harness_invariant_uses_an_enumerated_diagnostic():
    import ast
    tree = ast.parse((ROOT / 'qualification.py').read_text())
    checks = [node for node in ast.walk(tree) if isinstance(node, ast.Call)
              and isinstance(node.func, ast.Name) and node.func.id == 'require']
    assert checks
    assert not any(isinstance(node.args[1], ast.Constant) for node in checks)


def test_cli_requires_consent_before_starting_build(tmp_path, monkeypatch, capsys):
    q = qualification()
    assert hasattr(q, 'main'), 'CI CLI entry point is missing'
    monkeypatch.setenv('GITHUB_ACTIONS', 'true')
    monkeypatch.setenv('RUNNER_TEMP', str(tmp_path))
    args = ['--workspace', str(tmp_path / 'work'), '--output', str(tmp_path / 'export'),
            '--expected-platform', q.native_platform(), '--builder-commit', '1' * 40]
    assert q.main(args) == 1
    assert not (tmp_path / 'work').exists()
    assert 'failed' in capsys.readouterr().out


def test_workflow_is_separate_exact_commit_four_host_gate_with_bounded_exports():
    path = ROOT.parent / '.github/workflows/qualify-desktop-aab.yml'
    assert path.is_file(), 'separate native AAB qualification workflow is missing'
    text = path.read_text()
    assert "branches: ['feat/**']" in text
    assert 'pull_request:' in text and 'workflow_dispatch:' in text
    assert text.count("'desktop-builder/**'") == 2
    assert 'ref: ${{ github.event.pull_request.head.sha || github.sha }}' in text
    assert 'persist-credentials: false' in text
    for host in ('linux-x86_64', 'windows-x86_64', 'macos-x86_64', 'macos-arm64'):
        assert f'- name: {host}' in text
    assert 'fail-fast: false' in text and 'max-parallel: 2' in text
    assert 'timeout-minutes: 180' in text
    assert '--accept-ci-android-licenses' in text
    assert '--timeout-seconds 9000' in text
    assert 'continue-on-error' not in text
    assert 'setup-java' not in text and 'setup-android' not in text
    assert 'actions/cache' not in text
    assert 'pull_request_target' not in text
    assert 'secrets.' not in text
    assert text.count('uses: actions/upload-artifact@') == 2
    report_step = text.split('- name: Upload sanitized result only', 1)[1].split('- name:', 1)[0]
    assert "if: always() && (steps.qualify.outcome == 'success' || steps.qualify.outcome == 'failure' || steps.qualify.outcome == 'cancelled')" in report_step
    assert 'if-no-files-found: error' in report_step
    assert "if: success() && steps.qualify.outcome == 'success'" in text
    for filename in ('result.json', 'first.aab', 'update.aab'):
        assert f'${{{{ runner.temp }}}}/oal-aab-export/{filename}' in text
    assert 'include-hidden-files: false' in text

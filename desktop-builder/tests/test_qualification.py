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
        archive.writestr('base/resources.pb', b'fixture')
        archive.writestr('BundleConfig.pb', b'fixture')
        archive.writestr('base/dex/classes.dex', b'fixture NOT compiled code')
    result = dict(applicationId='com.example.qualification', mode='source', versionCode=code,
                  versionName=SOURCE_REF.removeprefix('v'), sourceCommit=SOURCE_COMMIT,
                  sourceRef=SOURCE_REF, sourceOverlays=['personal-cluster-task-affinity', OVERLAY_ID],
                  signingCertificateSha256='a' * 64, carplayIncluded=False,
                  artifact=str(artifact), sha256=hashlib.sha256(artifact.read_bytes()).hexdigest())
    artifact.with_suffix('.json').write_text(json.dumps(result))
    artifact.with_suffix('.aab.sha256').write_text(result['sha256'] + '  ' + artifact.name + '\n')
    return result


def release_fixture():
    from oal_builder.native_verification import SOURCE_COMMIT, SOURCE_REF
    return dict(sourceRef=SOURCE_REF, sourceCommit=SOURCE_COMMIT,
                versionName=SOURCE_REF[1:], assets={}, templateAvailable=False)


def test_artifact_is_bound_to_resolved_release_not_historical_pin(tmp_path):
    q = qualification()
    release = {**release_fixture(), 'sourceRef': 'v0.1.528',
               'sourceCommit': 'b' * 40, 'versionName': '0.1.528'}
    result = bundle_result(tmp_path)
    result.update({k: release[k] for k in ('sourceRef', 'sourceCommit', 'versionName')})
    result.update(sourceOverlays=[], mode='source')
    Path(result['artifact']).with_suffix('.json').write_text(json.dumps(result))
    clean = q.validate_artifact(result, tmp_path, result['applicationId'], release, 'source')
    assert clean['sourceCommit'] == release['sourceCommit']
    with pytest.raises(q.QualificationError, match='source-commit-mismatch'):
        q.validate_artifact(result, tmp_path, result['applicationId'], release_fixture(), 'source')


def test_artifact_validation_reads_actual_files_and_only_exports_allowlist(tmp_path):
    q = qualification()
    result = bundle_result(tmp_path)
    result['password'] = 'DO-NOT-EXPORT'
    clean = q.validate_artifact(result, tmp_path, result['applicationId'], release_fixture(), 'source')
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
        q.validate_artifact(result, tmp_path, 'com.example.qualification', release_fixture(), 'source')
    assert str(caught.value) == reason


def test_pair_requires_same_identity_source_and_increasing_version(tmp_path):
    q = qualification()
    assert hasattr(q, 'validate_pair'), 'first/update relationship gate is missing'
    first = q.validate_artifact(bundle_result(tmp_path, 1), tmp_path, 'com.example.qualification', release_fixture(), 'source')
    second = q.validate_artifact(bundle_result(tmp_path, 2), tmp_path, 'com.example.qualification', release_fixture(), 'source')
    first.update(mode='personalize', sourceOverlays=[])
    second.update(mode='personalize', sourceOverlays=[])
    q.validate_pair(first, second)
    for changed, reason in (
        ({'versionCode': 1}, 'update-version-not-increased'),
        ({'applicationId': 'com.changed.app'}, 'pair-application-id-mismatch'),
        ({'signingCertificateSha256': 'b' * 64}, 'pair-signer-mismatch'),
        ({'sourceCommit': '0' * 40}, 'pair-source-commit-mismatch'),
        ({'sourceRef': 'v0.1.527'}, 'pair-source-ref-mismatch'),
        ({'sourceOverlays': ['unexpected']}, 'pair-source-overlays-mismatch'),
        ({'versionName': '0.1.527'}, 'pair-version-name-mismatch'),
        ({'sha256': first['sha256']}, 'pair-artifact-hash-reused'),
        ({'artifact': first['artifact']}, 'pair-artifact-path-reused'),
    ):
        with pytest.raises(q.BuilderError) as caught:
            q.validate_pair(first, {**second, **changed})
        assert str(caught.value) == reason


def test_native_pair_rejects_source_mode_even_when_identity_matches(tmp_path):
    q = qualification()
    first, update = bundle_result(tmp_path, 1), bundle_result(tmp_path, 2)
    first.update(mode='source')
    update.update(mode='personalize', sourceOverlays=[])
    with pytest.raises(q.BuilderError, match='^build-mode-mismatch$'):
        q.validate_pair(first, update)


def test_ci_fresh_runner_temp_paths_need_no_android_license_consent(tmp_path, monkeypatch):
    q = qualification()
    assert hasattr(q, 'prepare_ci'), 'CI consent and workspace guard is missing'
    monkeypatch.setenv('GITHUB_ACTIONS', 'true')
    monkeypatch.setenv('RUNNER_TEMP', str(tmp_path))
    kwargs = dict(expected_platform=q.native_platform(), builder_commit='1' * 40)
    work, out = q.prepare_ci(tmp_path / 'work', tmp_path / 'export', **kwargs)
    assert work == tmp_path / 'work' and out == tmp_path / 'export'
    assert out.is_dir() and not work.exists()
    with pytest.raises(q.BuilderError):
        q.prepare_ci(work, out, **kwargs)  # No stale results on retries.
    with pytest.raises(q.BuilderError):
        q.prepare_ci(tmp_path / 'new', tmp_path / 'new/out', **kwargs)
    with pytest.raises(q.BuilderError):
        q.prepare_ci(tmp_path.parent / 'outside', tmp_path / 'another', **kwargs)
    for override in ({'expected_platform': 'wrong'}, {'builder_commit': 'main'}):
        with pytest.raises(q.BuilderError):
            q.prepare_ci(tmp_path / 'unused', tmp_path / 'unused-export', **{**kwargs, **override})
    monkeypatch.delenv('GITHUB_ACTIONS')
    with pytest.raises(q.BuilderError):
        q.prepare_ci(tmp_path / 'unused', tmp_path / 'unused-export', **kwargs)


def tool_fixture(workspace, android=True):
    """Path-only tool fixture; never executes Java or Android tooling."""
    tools = workspace / 'tools'
    paths = {}
    for name in ('java', 'javac', 'jarsigner', 'keytool'):
        path = tools / 'jdk-17/bin' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('unit fixture')
        paths[name] = str(path)
    paths['bundletool'] = str(tools / 'bundletool-1.18.3.jar')
    Path(paths['bundletool']).write_text('unit fixture')
    from oal_builder.toolchain import SDK_PACKAGES
    for directory in [tools / 'jdk-17', *(tools / 'android-sdk' / p.replace(';', '/') for p in SDK_PACKAGES if android)]:
        directory.mkdir(parents=True, exist_ok=True)
        (directory / '.oal-archive-digest').write_text('a' * 64)
    paths['env'] = {'JAVA_HOME': str(tools / 'jdk-17'),
                    'ANDROID_HOME': str(tools / 'android-sdk'),
                    'ANDROID_SDK_ROOT': str(tools / 'android-sdk'),
                    'ANDROID_NDK_HOME': str(tools / 'android-sdk/ndk/28.2.13676358'),
                    'GRADLE_USER_HOME': str(workspace / 'cache/gradle')}
    if not android:
        paths['env'] = {'JAVA_HOME': paths['env']['JAVA_HOME']}
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


def shared_fixture(tmp_path, provenance='local-linux-template'):
    from scripts import ci_template as h
    from oal_builder import releases as r
    source = tmp_path / 'fixture-input'
    result = bundle_result(source)
    template = Path(result['artifact'])
    descriptor = dict(schemaVersion=1, formatVersion=1, releaseTag=release_fixture()['sourceRef'],
                      sourceCommit=release_fixture()['sourceCommit'], templateApplicationId=r.TEMPLATE_APPLICATION_ID,
                      transformContract=r.TRANSFORM_CONTRACT,
                      templateAsset=dict(name=r.template_name(release_fixture()['sourceRef']),
                                         size=template.stat().st_size, sha256=q_digest(template)))
    descriptor_path = source / r.DESCRIPTOR_NAME
    descriptor_path.write_text(json.dumps(descriptor))
    release = release_fixture()
    if provenance == 'published-release-template':
        release, _ = h.local_metadata(release, template, descriptor_path)
    directory = tmp_path / 'shared'
    h.export_inputs(release, template, descriptor_path, directory, provenance=provenance)
    return directory


def q_digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.mark.parametrize('provenance', ['local-linux-template', 'published-release-template'])
@pytest.mark.parametrize('fault,reason', [
    (None, None), ('easy-source', 'source-commit-mismatch'),
    ('easy-mode', 'build-mode-mismatch'), ('easy-template', 'template-hash-mismatch'),
    ('easy-update-signer', 'pair-signer-mismatch'),
    ('payload', 'dex-native-payload-mismatch'), ('android', 'personalization-used-android-build-tools'),
])
def test_native_gate_only_personalizes_shared_aab_twice(tmp_path, monkeypatch, provenance, fault, reason):
    """Boundary fakes are orchestration tests, NOT native qualification."""
    from oal_builder import engine
    q = qualification()
    monkeypatch.setenv('GITHUB_ACTIONS', 'true')
    monkeypatch.setenv('RUNNER_TEMP', str(tmp_path))
    inputs = shared_fixture(tmp_path, provenance)
    calls, configs = [], []

    class FixtureBuilder:
        def __init__(self, config, progress, cancel_event):
            assert config.mode == 'personalize' and config.accept_licenses is False
            assert config.carplay_key is None and config.carplay_certificate is None
            self.config = config
            configs.append(config)
            engine.managed_workspace(config.workspace)
            self.tools = tool_fixture(config.workspace, android=False)
        def build(self):
            selected = engine.resolve_latest_release(lambda _: pytest.fail('resolved again'))
            assert selected['sourceCommit'] == release_fixture()['sourceCommit']
            calls.append('build')
            result = bundle_result(self.config.workspace, len(configs))
            descriptor = engine.resolve_template(selected, lambda _: pytest.fail('live template request'))
            result.update(applicationId=self.config.app_id, mode='personalize', sourceOverlays=[],
                          templateSha256=descriptor['templateAsset']['sha256'])
            if fault == 'easy-source':
                result['sourceCommit'] = 'c' * 40
            elif fault == 'easy-mode':
                result['mode'] = 'source'
            elif fault == 'easy-template':
                result['templateSha256'] = 'd' * 64
            elif fault == 'easy-update-signer' and len(configs) == 2:
                result['signingCertificateSha256'] = 'e' * 64
            elif fault == 'payload':
                with zipfile.ZipFile(result['artifact'], 'a') as archive:
                    archive.writestr('base/dex/classes2.dex', b'changed implementation')
                result['sha256'] = q_digest(Path(result['artifact']))
                Path(result['artifact']).with_suffix('.aab.sha256').write_text(
                    result['sha256'] + '  ' + Path(result['artifact']).name + '\n')
            elif fault == 'android':
                (self.config.workspace / 'tools/android-sdk').mkdir(exist_ok=True)
            Path(result['artifact']).with_suffix('.json').write_text(json.dumps(result))
            private = self.config.workspace / 'private/identity'
            private.mkdir(parents=True, exist_ok=True)
            (private / 'password').write_text('DO-NOT-EXPORT')
            return result
        def _verify(self, bundle, expected, state, carplay):
            calls.append('verify')
            assert Path(bundle).is_file() and carplay is False
            assert state['signingCertificateSha256'] == expected['signingCertificateSha256']
        def _run(self, argv, phase, cwd=None):
            assert phase == 'verify' and 'dump' in argv and 'manifest' in argv
            return manifest_fixture(self.config.app_id)

    monkeypatch.setattr(q, 'Builder', FixtureBuilder)
    out = tmp_path / 'export'
    kwargs = dict(expected_platform=q.native_platform(), builder_commit='1' * 40,
                  timeout_seconds=60, template_inputs=inputs)
    if fault:
        with pytest.raises(q.BuilderError, match='^' + reason + '$'):
            q.qualify(tmp_path / 'work', out, **kwargs)
        report = json.loads((out / 'result.json').read_text())
        assert report['status'] == 'failed' and report['reason'] == reason
        assert {p.name for p in out.iterdir()} == {'result.json'}
    else:
        report = q.qualify(tmp_path / 'work', out, **kwargs)
        assert [c.mode for c in configs] == ['personalize', 'personalize']
        assert configs[0].workspace == configs[1].workspace
        assert configs[0].app_id == configs[1].app_id
        assert calls == ['build', 'verify'] * 2
        assert report['status'] == 'passed' and len(report['builds']) == 2
        assert report['template']['provenance'] == provenance
        assert report['publishedTemplateArtifactQualified'] == (provenance == 'published-release-template')
        assert report['relationships'] == ['personalize-first-to-update']
        assert {p.name for p in out.iterdir()} == {'result.json', 'personalize-first.aab', 'personalize-update.aab'}
        for build in report['builds']:
            assert build['sha256'] == q_digest(out / build['artifact'])
    assert report['liveReleaseTemplateDownloadQualified'] is False
    text = (out / 'result.json').read_text()
    assert 'DO-NOT-EXPORT' not in text and str(tmp_path) not in text


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
            self.tools = tool_fixture(config.workspace, android=False)
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
        q.qualify(tmp_path / 'work', out,
                  expected_platform=q.native_platform(), builder_commit='1' * 40, timeout_seconds=60, template_inputs=shared_fixture(tmp_path))
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
        q.qualify(tmp_path / 'work', out,
                  expected_platform=q.native_platform(), builder_commit='1' * 40, timeout_seconds=1, template_inputs=shared_fixture(tmp_path))
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


def test_cli_accepts_only_shared_template_inputs_no_android_consent(tmp_path, monkeypatch, capsys):
    q = qualification()
    seen = []
    monkeypatch.setattr(q, 'qualify', lambda *args, **kwargs: seen.append(kwargs))
    args = ['--workspace', str(tmp_path / 'work'), '--output', str(tmp_path / 'export'),
            '--expected-platform', q.native_platform(), '--builder-commit', '1' * 40,
            '--template-inputs', str(tmp_path / 'shared')]
    assert q.main(args) == 0
    assert seen[0]['template_inputs'] == tmp_path / 'shared'
    assert 'accept_ci_android_licenses' not in seen[0]
    with pytest.raises(SystemExit):
        q.main(args + ['--accept-ci-android-licenses'])


def test_workflow_is_aab_only_four_host_gate_with_one_linux_producer():
    text = (ROOT.parent / '.github/workflows/qualify-desktop-aab.yml').read_text()
    assert "branches: ['feat/**']" in text
    assert 'pull_request:' in text and 'workflow_dispatch:' in text and 'workflow_call:' in text
    assert '\n  release:\n' not in text
    assert 'default: published' in text
    assert 'ref: ${{ inputs.builder_commit || github.event.pull_request.head.sha || github.sha }}' in text
    assert 'persist-credentials: false' in text
    producer, native = text.split('  native-aab:', 1)
    assert 'template-producer:' in producer and 'runs-on: ubuntu-22.04' in producer
    assert '--template-mode local --accept-ci-android-licenses' in producer
    assert '--template-mode published' in producer
    assert 'needs: template-producer' in native
    assert '--template-inputs' in native
    for forbidden in ('--accept-ci-android-licenses', 'source-first.aab', 'source-to-personalize.aab',
                      'test_engine_modes.py', 'scripts.ci_template', 'setup-android', 'setup-java'):
        assert forbidden not in native
    for host in ('linux-x86_64', 'windows-x86_64', 'macos-x86_64', 'macos-arm64'):
        assert f'- name: {host}' in native
    assert 'fail-fast: false' in native and 'max-parallel: 2' in native
    assert '--timeout-seconds 3600' in native
    for forbidden in ('continue-on-error', 'actions/cache', 'pull_request_target', 'secrets.'):
        assert forbidden not in text
    assert text.count('uses: actions/upload-artifact@') == 3
    for filename in ('release.json', 'template.aab', 'openautolink-personalization.json', 'provenance.json'):
        assert f'${{{{ runner.temp }}}}/oal-template-inputs/{filename}' in producer
    assert 'actions/download-artifact@' in native
    report_step = native.split('- name: Upload sanitized result only', 1)[1].split('- name:', 1)[0]
    assert "if: always() && (steps.qualify.outcome == 'success' || steps.qualify.outcome == 'failure' || steps.qualify.outcome == 'cancelled')" in report_step
    assert 'if-no-files-found: error' in report_step
    assert "if: success() && steps.qualify.outcome == 'success'" in native
    for filename in ('result.json', 'personalize-first.aab', 'personalize-update.aab'):
        assert f'${{{{ runner.temp }}}}/oal-aab-export/{filename}' in native
    assert 'include-hidden-files: false' in text

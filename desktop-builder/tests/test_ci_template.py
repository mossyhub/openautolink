"""CI harness boundary tests, never evidence of a real native build."""
import copy
import hashlib
import json
import threading
from pathlib import Path

import pytest
from oal_builder import engine, releases as r
from oal_builder.toolchain import BuilderError, Downloader


def harness():
    from scripts import ci_template
    return ci_template


def release():
    return dict(sourceRef='v0.1.528', sourceCommit='b' * 40, versionName='0.1.528',
                templateAvailable=False, assets={})


def test_release_qualification_waits_for_verified_publication_and_is_read_only():
    root = Path(__file__).resolve().parents[2]
    qualifier = (root / '.github/workflows/qualify-desktop-aab.yml').read_text()
    publisher = (root / '.github/workflows/release-apk.yml').read_text()
    assert '\n  release:\n' not in qualifier
    assert '\n  workflow_call:\n' in qualifier
    assert '\n  workflow_dispatch:\n' in qualifier and '\n  pull_request:\n' in qualifier
    assert 'default: published' in qualifier
    assert 'contents: read' in qualifier and 'contents: write' not in qualifier
    assert 'secrets:' not in qualifier and 'secrets.' not in qualifier
    call = publisher.split('\n  qualify-desktop-aab:\n', 1)[1]
    assert 'needs: build' in call and 'if: always()' not in call
    assert 'uses: ./.github/workflows/qualify-desktop-aab.yml' in call
    assert 'contents: read' in call and 'secrets: inherit' not in call
    assert 'expected_release_tag: ${{ needs.build.outputs.release_tag }}' in call
    assert 'expected_source_commit: ${{ needs.build.outputs.source_commit }}' in call
    assert 'builder_commit: ${{ github.sha }}' in call
    build = publisher.split('\n  qualify-desktop-aab:\n', 1)[0]
    assert 'release_tag: ${{ steps.tag.outputs.value }}' in build
    assert 'source_commit: ${{ steps.readback.outputs.source_commit }}' in build
    assert build.index('uses: softprops/action-gh-release@v2') < build.index('id: readback')
    readback = build.split('id: readback', 1)[1].split('      - name:', 1)[0]
    assert 'gh release download "$RELEASE_TAG"' in readback
    assert 'cmp ' in readback
    assert readback.index('cmp ') < readback.index('source_commit=')
    for asset in ('openautolink-template-${RELEASE_TAG}.aab', 'openautolink-personalization.json',
                  'openautolink-template-${RELEASE_TAG}.aab.sha256'):
        assert asset in readback
    assert qualifier.count('ref: ${{ inputs.builder_commit || github.event.pull_request.head.sha || github.sha }}') == 2
    assert 'BUILDER_COMMIT: ${{ inputs.builder_commit || github.event.pull_request.head.sha || github.sha }}' in qualifier
    assert qualifier.count("assert actual == os.environ['BUILDER_COMMIT']") == 2
    assert '--expected-release-tag "$EXPECTED_RELEASE_TAG"' in qualifier
    assert '--expected-source-commit "$EXPECTED_SOURCE_COMMIT"' in qualifier
    assert 'EXPECTED_RELEASE_TAG: ${{ inputs.expected_release_tag }}' in qualifier
    assert 'EXPECTED_SOURCE_COMMIT: ${{ inputs.expected_source_commit }}' in qualifier
    assert "TEMPLATE_MODE: ${{ inputs.template_mode || 'local' }}" in qualifier
    for text in (publisher, qualifier):
        assert 'repository_dispatch' not in text and 'gh workflow run' not in text


def test_snapshot_pins_real_resolver_once_and_restores_engine(monkeypatch, tmp_path):
    h = harness()
    calls = []
    original = engine.resolve_latest_release
    def fetch(url):
        calls.append(url)
        if url.endswith('/releases/latest'):
            return dict(tag_name='v0.1.528', draft=False, prerelease=False,
                        html_url=r.WEB + '/releases/tag/v0.1.528', assets=[])
        return dict(ref='refs/tags/v0.1.528', object=dict(type='commit', sha='b' * 40))
    snapshot = h.resolve_snapshot(fetch)
    assert len(calls) == 2
    path = tmp_path / 'release.json'
    path.write_text(json.dumps(snapshot))
    selected = h.load_snapshot(path)
    assert selected == release()
    with pytest.raises(RuntimeError):
        with h.bound_inputs(selected):
            one = engine.resolve_latest_release(lambda _: pytest.fail('resolved twice'))
            one['sourceCommit'] = 'c' * 40
            assert engine.resolve_latest_release(None) == release()
            raise RuntimeError('exercise restoration')
    assert engine.resolve_latest_release is original


@pytest.mark.parametrize('change', [
    {'sourceCommit': 'main'}, {'sourceRef': 'v0.1.528-beta'},
    {'versionName': '0.1.526'}, {'templateAvailable': True},
    {'assets': {'private': {'name': 'private'}}},
])
def test_snapshot_rejects_malformed_or_inconsistent_source(tmp_path, change):
    h = harness()
    path = tmp_path / 'release.json'
    path.write_text(json.dumps({'schema': 1, 'selection': 'latest-stable-at-workflow-start',
                                'release': release() | change}))
    with pytest.raises(BuilderError):
        h.load_snapshot(path)


def test_source_template_binding_rejects_same_size_crc_collision_before_tools(tmp_path):
    import zipfile
    h = harness()
    unsigned, signed = tmp_path / 'unsigned.aab', tmp_path / 'signed.aab'
    for path, payload in ((unsigned, b'plumless'), (signed, b'buckeroo')):
        with zipfile.ZipFile(path, 'w') as archive:
            for name in ('BundleConfig.pb', 'base/resources.pb', 'base/manifest/AndroidManifest.xml'):
                archive.writestr(name, b'fixture')
            archive.writestr('base/dex/classes.dex', payload)
    result = {**release(), 'applicationId': 'com.example.fixture', 'versionCode': 1, 'artifact': str(signed)}
    with pytest.raises(BuilderError, match='^ci-template-source-artifact-mismatch$'):
        h.create_template(unsigned, result, release(), tmp_path / 'output',
                          tools={}, run=lambda *_: pytest.fail('Source mismatch must precede tool execution'))


def test_real_retained_unsigned_source_can_make_local_template_without_gradle(tmp_path):
    """Real Java/AAB integration on historical source, NOT latest/native CI proof."""
    import subprocess
    h = harness()
    root = Path('/hermes_work/oal-builder-clean-qualification')
    metadata = root / 'output/latest.json'
    unsigned = root / 'source/app/build/outputs/bundle/release/app-release.aab'
    tools = {k: root / 'tools/jdk-17/bin' / k for k in ('java', 'javac')}
    tools['bundletool'] = root / 'tools/bundletool-1.18.3.jar'
    if not unsigned.is_file() or not metadata.is_file() or not all(p.is_file() for p in tools.values()):
        pytest.skip('Retained real source AAB and managed tools unavailable; CI creates its own')
    result = json.loads(metadata.read_text())
    selected = {**release(), **{k: result[k] for k in ('sourceRef', 'sourceCommit', 'versionName')}}
    calls = []
    def run(argv, phase, cwd=None):
        calls.append([str(a) for a in argv])
        return subprocess.check_output(argv, cwd=cwd, stderr=subprocess.DEVNULL, timeout=180).decode()
    template, descriptor = h.create_template(unsigned, result, selected, tmp_path, tools=tools, run=run)
    assert descriptor['sourceCommit'] == result['sourceCommit']
    assert template.name == r.template_name(result['sourceRef'])
    assert h.payload_digest(template) == h.payload_digest(unsigned)
    assert all(not any('gradlew' in arg for arg in call) for call in calls)
    assert not any('jarsigner' in arg or 'keytool' in arg for call in calls for arg in call)


def test_real_ci_metadata_adapter_drives_fresh_personalization_twice(tmp_path):
    """Opt-in real tools/signatures, historical local payload; NOT latest-source CI."""
    import os
    import subprocess
    if os.environ.get('OAL_CI_TEMPLATE_ENGINE_INTEGRATION') != '1':
        pytest.skip('Set OAL_CI_TEMPLATE_ENGINE_INTEGRATION=1 for real fresh managed-tools integration')
    h = harness()
    root = Path('/hermes_work/oal-builder-clean-qualification')
    result = json.loads((root / 'output/latest.json').read_text())
    selected = {**release(), **{k: result[k] for k in ('sourceRef', 'sourceCommit', 'versionName')}}
    tools = {k: root / 'tools/jdk-17/bin' / k for k in ('java', 'javac')}
    tools['bundletool'] = root / 'tools/bundletool-1.18.3.jar'
    def run(argv, phase, cwd=None):
        return subprocess.check_output(argv, cwd=cwd, stderr=subprocess.DEVNULL, timeout=180).decode()
    template_dir = tmp_path / 'local-template'
    template, descriptor = h.create_template(
        root / 'source/app/build/outputs/bundle/release/app-release.aab', result,
        selected, template_dir, tools=tools, run=run)
    selected, raw = h.local_metadata(selected, template, template_dir / r.DESCRIPTOR_NAME)
    workspace = tmp_path / 'fresh-managed'
    h.seed_template(workspace, template, descriptor)
    config = engine.BuildConfig(workspace, 'com.example.ci.localtemplate', mode='personalize', accept_licenses=False)
    import qualification as q
    results = []
    with h.bound_inputs(selected, raw):
        for _ in range(2):
            builder = engine.Builder(config, lambda *_: None)
            actual = builder.build()
            q.validate_tools(builder.tools, workspace, mode='personalize')
            clean = q.validate_artifact(actual, workspace, config.app_id, selected, 'personalize',
                                        descriptor['templateAsset']['sha256'])
            builder._verify(Path(actual['artifact']), actual,
                            {'signingCertificateSha256': clean['signingCertificateSha256']}, False)
            assert h.payload_digest(actual['artifact']) == h.payload_digest(template)
            results.append(clean)
    q.validate_pair(*results)
    assert [b['versionCode'] for b in results] == [1, 2]
    assert not (workspace / 'tools/android-sdk').exists()
    assert not (workspace / 'cache/gradle').exists()
    assert not list((workspace / 'source').iterdir())
    evidence = {'scope': 'historical-local-template-real-tools-not-latest-native-source-qualification',
                'sourceCommit': selected['sourceCommit'], 'sourceRef': selected['sourceRef'],
                'templateSha256': descriptor['templateAsset']['sha256'], 'builds': results}
    (tmp_path / 'local-integration-result.json').write_text(json.dumps(evidence, indent=2))


def test_linux_producer_compiles_template_once_without_user_identity(tmp_path, monkeypatch):
    import platform
    from oal_builder import toolchain
    h = harness()
    monkeypatch.setenv('GITHUB_ACTIONS', 'true')
    monkeypatch.setattr(platform, 'system', lambda: 'Linux')
    calls = []
    class Manager:
        def __init__(self, workspace, progress, cancel):
            self.workspace = workspace
        def install(self, accept):
            assert accept is True
            calls.append('install')
            return dict(java='/managed/java', bundletool='/managed/bundletool.jar', env={})
        def prepare_source(self, selected):
            assert selected == release()
            calls.append('source')
            source = self.workspace / 'source'
            (source / 'gradle/wrapper').mkdir(parents=True)
            (source / 'gradle/wrapper/gradle-wrapper.properties').write_text('distributionSha256Sum=' + 'a' * 64 + '\n')
            (source / 'gradle/verification-metadata.xml').write_text('<fixture/>')
            (source / 'app').mkdir()
            (source / 'app/build.gradle.kts').write_text('oalBuilderUnsigned oalVersionCode oalVersionName appId')
            (source / 'gradlew').write_text('fixture')
            return selected
    def run(argv, cwd, env, cancel, progress, phase):
        calls.append(argv)
        if ':app:bundleRelease' in argv:
            assert '--dependency-verification=strict' in argv
            assert '-PappId=' + r.TEMPLATE_APPLICATION_ID in argv
            assert '-PoalBuilderUnsigned=true' in argv
            assert '-PoalVersionName=' + release()['versionName'] in argv
            unsigned = Path(cwd) / 'app/build/outputs/bundle/release/app-release.aab'
            unsigned.parent.mkdir(parents=True)
            unsigned.write_bytes(b'unit fixture only')
        else:
            assert argv[1:3] == ['-I', '-S'] and 'publish_template.py' in argv[3]
            assert argv[argv.index('--commit') + 1] == release()['sourceCommit']
        return ''
    monkeypatch.setattr(toolchain, 'Toolchain', Manager)
    monkeypatch.setattr(engine, 'run_process', run)
    monkeypatch.setattr(engine.Builder, 'build', lambda _: pytest.fail('Must not invoke community source mode'))
    template, descriptor = h.compile_template(tmp_path / 'managed', release(), tmp_path / 'template',
                                               accept_ci_android_licenses=True)
    assert template.read_bytes() == b'unit fixture only'
    assert descriptor.name == r.DESCRIPTOR_NAME
    assert sum(isinstance(c, list) and ':app:bundleRelease' in c for c in calls) == 1
    assert not list((tmp_path / 'managed/private').rglob('*'))
    assert not any('jarsigner' in str(c) or 'keytool' in str(c) for c in calls)


@pytest.mark.parametrize('available', [True, False])
@pytest.mark.parametrize('expected', [None, ('v0.1.528', 'b' * 40),
                                      ('v0.1.527', 'b' * 40), ('v0.1.528', 'c' * 40),
                                      ('v0.1.528', 'main'), ('v0.1.528', ''),
                                      ('', 'b' * 40), ('', '')])
def test_published_producer_downloads_exact_latest_or_fails_without_compilation(tmp_path, monkeypatch, capsys, available, expected):
    h = harness()
    template, descriptor_path = input_fixture(tmp_path / 'input')
    selected, raw = h.local_metadata(release(), template, descriptor_path)
    calls = []
    class Downloads:
        def __init__(self, *args):
            pass
        def json(self, url):
            raise AssertionError('resolver boundary is supplied in this unit test')
        def metadata(self, url, max_bytes):
            assert url == r.asset_url(release()['sourceRef'], r.DESCRIPTOR_NAME)
            assert max_bytes == r.MAX_DESCRIPTOR_SIZE
            calls.append('descriptor')
            return raw
        def download(self, url, digest, size):
            assert url == r.asset_url(release()['sourceRef'], template.name)
            assert digest == hashlib.sha256(template.read_bytes()).hexdigest()
            assert size == template.stat().st_size
            calls.append('aab')
            return template
    monkeypatch.setattr(h, 'Downloader', Downloads)
    monkeypatch.setattr(h, 'resolve_snapshot', lambda fetch: dict(schema=1, selection=h.SELECTION,
                       release=selected if available else release()))
    monkeypatch.setattr(h, 'compile_template', lambda *a, **k: pytest.fail('No published-mode source fallback'))
    output = tmp_path / 'shared'
    args = ['--output', str(output), '--cache', str(tmp_path / 'cache'),
            '--workspace', str(tmp_path / 'work'), '--template-mode', 'published']
    if expected is not None:
        args += ['--expected-release-tag', expected[0], '--expected-source-commit', expected[1]]
    status = h.main(args)
    matches = expected in (None, (release()['sourceRef'], release()['sourceCommit']))
    if available and matches:
        assert status == 0 and calls == ['descriptor', 'aab']
        inputs = h.load_inputs(output)
        assert inputs['release'] == selected
        assert inputs['provenance'] == 'published-release-template'
    else:
        assert status == 1 and calls == []
        assert not output.exists()
    assert str(tmp_path) not in capsys.readouterr().out


@pytest.mark.parametrize('mutation', ['source', 'descriptor', 'provenance', 'published-label'])
def test_shared_inputs_reject_tampering_and_false_publication_claim(tmp_path, mutation):
    h = harness()
    template, descriptor = input_fixture(tmp_path / 'input')
    out = tmp_path / 'shared'
    h.export_inputs(release(), template, descriptor, out, provenance='local-linux-template')
    if mutation == 'source':
        value = json.loads((out / 'release.json').read_text())
        value['release']['sourceCommit'] = 'c' * 40
        (out / 'release.json').write_text(json.dumps(value))
    elif mutation == 'descriptor':
        value = json.loads((out / r.DESCRIPTOR_NAME).read_text())
        value['sourceCommit'] = 'c' * 40
        (out / r.DESCRIPTOR_NAME).write_text(json.dumps(value))
    else:
        value = json.loads((out / 'provenance.json').read_text())
        value['kind'] = 'published-release-template' if mutation == 'published-label' else 'SECRET'
        (out / 'provenance.json').write_text(json.dumps(value))
    with pytest.raises(BuilderError, match='^ci-template-input-invalid$'):
        h.load_inputs(out)


@pytest.mark.parametrize('host,ci,consent', [('Windows', 'true', True), ('Darwin', 'true', True),
                                         ('Linux', '', True), ('Linux', 'true', False)])
def test_local_compilation_cannot_escape_linux_ci_consent(tmp_path, monkeypatch, host, ci, consent):
    import platform
    h = harness()
    monkeypatch.setattr(platform, 'system', lambda: host)
    monkeypatch.setenv('GITHUB_ACTIONS', ci)
    with pytest.raises(BuilderError, match='^ci-template-linux-license-consent-required$'):
        h.compile_template(tmp_path / 'work', release(), tmp_path / 'out',
                           accept_ci_android_licenses=consent)
    assert not (tmp_path / 'work').exists()


def test_shared_inputs_bind_exact_source_descriptor_and_bundle(tmp_path):
    h = harness()
    template, descriptor_path = input_fixture(tmp_path / 'input')
    output = tmp_path / 'shared'
    h.export_inputs(release(), template, descriptor_path, output, provenance='local-linux-template')
    inputs = h.load_inputs(output)
    assert inputs['release'] == release()
    assert inputs['provenance'] == 'local-linux-template'
    assert inputs['template'].read_bytes() == template.read_bytes()
    assert inputs['descriptor']['sourceCommit'] == release()['sourceCommit']
    assert {p.name for p in output.iterdir()} == {
        'release.json', 'template.aab', r.DESCRIPTOR_NAME, 'provenance.json'}
    (output / 'template.aab').write_bytes(b'changed')
    with pytest.raises(BuilderError):
        h.load_inputs(output)


def input_fixture(directory):
    directory.mkdir(parents=True)
    template = directory / r.template_name(release()['sourceRef'])
    template.write_bytes(b'unit fixture; NOT an AAB or native proof')
    descriptor = dict(schemaVersion=1, formatVersion=1, releaseTag=release()['sourceRef'],
                      sourceCommit=release()['sourceCommit'], templateApplicationId=r.TEMPLATE_APPLICATION_ID,
                      transformContract=r.TRANSFORM_CONTRACT,
                      templateAsset=dict(name=template.name, size=template.stat().st_size,
                                         sha256=hashlib.sha256(template.read_bytes()).hexdigest()))
    descriptor_path = directory / r.DESCRIPTOR_NAME
    descriptor_path.write_text(json.dumps(descriptor))
    return template, descriptor_path


def test_local_metadata_adapter_uses_real_descriptor_validation_and_digest_cache(tmp_path):
    h = harness()
    data = b'unit fixture; NOT an AAB or native proof'
    template = tmp_path / r.template_name('v0.1.528')
    template.write_bytes(data)
    descriptor = dict(schemaVersion=1, formatVersion=1, releaseTag='v0.1.528',
                      sourceCommit='b' * 40, templateApplicationId=r.TEMPLATE_APPLICATION_ID,
                      transformContract=r.TRANSFORM_CONTRACT,
                      templateAsset=dict(name=template.name, size=len(data), sha256=hashlib.sha256(data).hexdigest()))
    descriptor_path = tmp_path / r.DESCRIPTOR_NAME
    descriptor_path.write_text(json.dumps(descriptor))
    selected, raw = h.local_metadata(release(), template, descriptor_path)
    workspace = tmp_path / 'workspace'
    h.seed_template(workspace, template, descriptor)
    with h.bound_inputs(selected, raw):
        bound = engine.resolve_latest_release(None)
        validated = engine.resolve_template(bound, lambda _: pytest.fail('downloaded local metadata'))
        downloader = Downloader(workspace / 'cache/downloads', lambda *_: None, threading.Event())
        path = downloader.download(validated['url'], descriptor['templateAsset']['sha256'], size=len(data))
        assert path.read_bytes() == data
    assert not release()['templateAvailable']
    bad = copy.deepcopy(descriptor)
    bad['sourceCommit'] = 'c' * 40
    descriptor_path.write_text(json.dumps(bad))
    with pytest.raises(BuilderError):
        h.local_metadata(release(), template, descriptor_path)

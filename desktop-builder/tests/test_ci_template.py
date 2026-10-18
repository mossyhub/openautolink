"""CI harness boundary tests, never evidence of a real native build."""
import copy
import hashlib
import json
import threading
from pathlib import Path

import pytest
from oal_builder import engine, releases as r
from oal_builder.toolchain import BuilderError, Downloader


def test_failure_report_retains_validated_source_without_exception_text(tmp_path, monkeypatch, capsys):
    h = harness()
    monkeypatch.setattr(h, 'resolve_snapshot', lambda _: {'release': release()})
    def fail(*args, **kwargs):
        raise RuntimeError('SECRET /private/identity https://user:password@example.com ' * 10000)
    monkeypatch.setattr(h, 'compile_template', fail)
    report = tmp_path / 'failure.json'
    status = h.main(['--output', str(tmp_path / 'out'), '--cache', str(tmp_path / 'cache'),
                     '--workspace', str(tmp_path / 'work'), '--template-mode', 'local',
                     '--failure-report', str(report)])
    assert status == 1
    value = json.loads(report.read_text())
    assert value == dict(schema=1, status='failed', templateMode='local', phase='compile',
                         codes=['ci-template-unclassified-failure'],
                         source={k: release()[k] for k in ('sourceRef', 'sourceCommit', 'versionName')})
    assert report.stat().st_size < 4096
    assert 'SECRET' not in report.read_text() + capsys.readouterr().out
    assert not (tmp_path / 'out').exists()


@pytest.mark.parametrize('text,codes', [
    ('Dependency verification failed', ['ci-template-dependency-verification-failed']),
    ('No space left on device', ['ci-template-disk-space-exhausted']),
    ('UnknownHostException', ['ci-template-network-download-failed']),
    ('Unresolved reference', ['ci-template-compiler-failed']),
    ('Dependency verification failed; No space left on device',
     ['ci-template-dependency-verification-failed', 'ci-template-disk-space-exhausted']),
    ('opaque SECRET', ['ci-template-tool-exit-unclassified']),
])
def test_real_failed_process_projects_only_fixed_categories(tmp_path, text, codes):
    import os
    import sys
    h = harness()
    with pytest.raises(BuilderError) as caught:
        engine.run_process([sys.executable, '-c', 'import sys; print(sys.argv[1]); sys.exit(7)',
                            text + ' /private/SECRET password=SECRET'], tmp_path, dict(os.environ),
                           threading.Event(), lambda *_: None, 'build')
    assert h.failure_details(caught.value) == dict(codes=codes, toolExitCode=7)


@pytest.mark.parametrize('message,code', [
    ('ci-template-source-mismatch', 'ci-template-source-mismatch'),
    ('ci-template-input-invalid', 'ci-template-input-invalid'),
    ('ci-template-descriptor-invalid', 'ci-template-descriptor-invalid'),
    ('ci-template-producer-time-budget-exceeded', 'ci-template-producer-time-budget-exceeded'),
    ('ci-expected-release-requires-published-mode', 'ci-expected-release-requires-published-mode'),
    ('ci-template-producer-path-not-fresh', 'ci-template-producer-path-not-fresh'),
    ('ci-template-linux-license-consent-required', 'ci-template-linux-license-consent-required'),
    ('Build cancelled', 'ci-template-cancelled'),
    ('Download failed; check connectivity or GitHub API rate limit and retry', 'ci-template-network-download-failed'),
    ('Incomplete download', 'ci-template-download-incomplete'),
    ('build: managed tool could not start (on Apple Silicon, legacy SDK tools may require Rosetta)', 'ci-template-tool-start-failed'),
    ('template: tool output did not close after exit; incomplete output discarded. Check for leftover tools before retrying.', 'ci-template-tool-output-incomplete'),
    ('ci-template-SECRET', 'ci-template-unclassified-failure'),
    ('Compiler failure SECRET', 'ci-template-unclassified-failure'),
    pytest.param('x' * 100000, 'ci-template-unclassified-failure', id='oversized-untrusted-error'),
])
def test_known_failures_are_exact_allowlist_not_arbitrary_text(message, code):
    assert harness().failure_details(BuilderError(message)) == {'codes': [code]}


@pytest.mark.parametrize('failure', ['unavailable', 'mismatch', 'malformed-source', 'report-write'])
def test_report_covers_resolution_failures_and_never_prints_write_errors(tmp_path, monkeypatch, capsys, failure):
    h = harness()
    selected = release()
    if failure == 'malformed-source':
        selected['sourceRef'] = 'SECRET'
    monkeypatch.setattr(h, 'resolve_snapshot', lambda _: {'release': selected})
    report = tmp_path / 'failure.json'
    args = ['--output', str(tmp_path / 'out'), '--cache', str(tmp_path / 'cache'),
            '--workspace', str(tmp_path / 'work'), '--template-mode', 'published',
            '--failure-report', str(report)]
    if failure == 'mismatch':
        args += ['--expected-release-tag', 'v0.1.527', '--expected-source-commit', 'b' * 40]
    if failure == 'report-write':
        def fail(*_):
            raise OSError('SECRET /private/path')
        monkeypatch.setattr(engine, 'atomic_json', fail)
    assert h.main(args) == 1
    output = capsys.readouterr().out
    assert 'SECRET' not in output
    if failure == 'report-write':
        assert 'ci-template-failure-report-write-failed' in output
    else:
        value = json.loads(report.read_text())
        codes = {'unavailable': 'ci-latest-published-template-unavailable',
                 'mismatch': 'ci-expected-published-release-mismatch',
                 'malformed-source': 'ci-source-snapshot-invalid'}
        assert value['codes'] == [codes[failure]]
        assert value['source'] == (None if failure == 'malformed-source' else
                                  {k: release()[k] for k in ('sourceRef', 'sourceCommit', 'versionName')})
        assert 'SECRET' not in report.read_text()
    assert not (tmp_path / 'out').exists()


def test_workflow_uploads_only_producer_failure_json_after_attempt():
    path = Path(__file__).resolve().parents[2] / '.github/workflows/qualify-desktop-aab.yml'
    text = path.read_text().split('\n  native-aab:', 1)[0]
    assert 'id: produce' in text
    assert '--failure-report "$RUNNER_TEMP/oal-template-failure.json"' in text
    step = text.split('- name: Upload bounded template failure report', 1)[1].split('      - name:', 1)[0]
    assert "if: failure() && steps.produce.outcome == 'failure'" in step
    assert 'path: ${{ runner.temp }}/oal-template-failure.json' in step
    assert 'if-no-files-found: error' in step
    assert 'include-hidden-files: false' in step
    assert 'retention-days: 7' in step
    assert '*' not in step and '.log' not in step


@pytest.mark.parametrize('tag,commit', [
    ('v0.1.528', '09c75422b5f0529b7b521c628eefffe9a75273d7'),
    ('v0.1.530', '808e59bf0d51c36d2ebf54ddee2e5f08fc945930'),
])
@pytest.mark.parametrize('mutation', [None, 'changed-bytes', 'changed-size', 'different-commit', 'different-tag'])
def test_latest_template_affinity_repair_is_exact_source_bound(tmp_path, mutation, tag, commit):
    h = harness()
    # The reviewed checkout already contains the upstream placeholder fix.
    # Reconstruct the exact released manifest, then bind its real digest.
    reviewed = (Path(__file__).resolve().parents[2] / 'app/src/main/AndroidManifest.xml').read_bytes()
    released = reviewed.replace(b'android:taskAffinity="${applicationId}.templates"',
                                b'android:taskAffinity="com.openautolink.app.templates"')
    assert hashlib.sha256(released).hexdigest() == '93bdf196c22b08f84447dd6ca6010c215a4fc93060743b1322e2c048243d29aa'
    selected = release() | {'sourceRef': tag, 'versionName': tag[1:], 'sourceCommit': commit}
    if mutation == 'changed-bytes':
        released = bytes([released[0] ^ 1]) + released[1:]
    elif mutation == 'changed-size':
        released += b'\n'
    elif mutation == 'different-commit':
        selected['sourceCommit'] = 'c' * 40
    elif mutation == 'different-tag':
        selected.update(sourceRef='v0.1.529', versionName='0.1.529')
    manifest = tmp_path / 'app/src/main/AndroidManifest.xml'
    manifest.parent.mkdir(parents=True)
    manifest.write_bytes(released)
    if mutation in {'changed-bytes', 'changed-size'}:
        with pytest.raises(BuilderError, match='^ci-template-affinity-source-mismatch$') as caught:
            h.prepare_template_affinity(tmp_path, selected)
        assert h.failure_details(caught.value)['codes'] == ['ci-template-affinity-source-mismatch']
        assert manifest.read_bytes() == released
    elif mutation:
        assert h.prepare_template_affinity(tmp_path, selected) == []
        assert manifest.read_bytes() == released
    else:
        assert h.prepare_template_affinity(tmp_path, selected) == ['ci-template-' + tag + '-cluster-affinity']
        assert manifest.read_bytes() == reviewed


def harness():
    from scripts import ci_template
    return ci_template


def release():
    return dict(sourceRef='v0.1.528', sourceCommit='b' * 40, versionName='0.1.528',
                templateAvailable=False, assets={})


def test_release_qualification_waits_for_verified_publication_and_is_read_only():
    root = Path(__file__).resolve().parents[2]
    qualifier = (root / '.github/workflows/qualify-desktop-aab.yml').read_text()
    publisher = (root / '.github/workflows/publish-personalization-template.yml').read_text()
    apk = (root / '.github/workflows/release-apk.yml').read_text()
    assert 'qualify-desktop-aab' not in apk and 'bundleRelease' not in apk
    assert '\n  release:\n' not in qualifier
    assert '\n  workflow_call:\n' in qualifier
    assert '\n  workflow_dispatch:\n' in qualifier and '\n  pull_request:\n' in qualifier
    assert 'default: published' in qualifier
    assert 'contents: read' in qualifier and 'contents: write' not in qualifier
    assert 'secrets:' not in qualifier and 'secrets.' not in qualifier
    call = publisher.split('\n  qualify-desktop-aab:\n', 1)[1]
    assert 'needs: [produce, upload]' in call and 'if: always()' not in call
    assert 'uses: ./.github/workflows/qualify-desktop-aab.yml' in call
    assert 'contents: read' in call and 'secrets: inherit' not in call
    assert 'expected_release_tag: ${{ needs.produce.outputs.release_tag }}' in call
    assert 'expected_source_commit: ${{ needs.produce.outputs.source_commit }}' in call
    assert 'builder_commit: ${{ needs.produce.outputs.source_commit }}' in call
    build = publisher.split('\n  qualify-desktop-aab:\n', 1)[0]
    assert 'release_tag: ${{ steps.tag.outputs.value }}' in build
    assert 'source_commit: ${{ steps.bind.outputs.source_commit }}' in build
    assert 'release_id: ${{ steps.bind.outputs.release_id }}' in build
    producer, uploader = build.split('\n  upload:\n', 1)
    assert 'contents: write' not in producer and 'secrets.' not in build
    assert 'needs: produce' in uploader and 'contents: write' in uploader
    assert 'softprops/action-gh-release' not in build
    assert 'python -I -S desktop-builder/scripts/upload_template.py' in uploader
    assert '--release-id "$RELEASE_ID" --tag "$RELEASE_TAG" --commit "$SOURCE_COMMIT"' in uploader
    # Transaction tests exercise byte readback, ID binding and retry semantics.
    assert (root / 'desktop-builder/tests/test_upload_template.py').is_file()
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


@pytest.mark.parametrize('preparation_kind', ['none', 'exact528', 'exact530', 'historical'])
@pytest.mark.parametrize('failure', [None, 'tools', 'source', 'source-contract', 'build',
                                   'template-copy', 'template-verify'])
def test_linux_producer_compiles_template_once_without_user_identity(tmp_path, monkeypatch, failure,
                                                                   preparation_kind):
    import platform
    from oal_builder import toolchain
    from oal_builder.native_verification import SOURCE_REF, SOURCE_COMMIT, OVERLAY_ID
    selected = release()
    prior = []
    expected_preparation = []
    if preparation_kind == 'exact528':
        selected['sourceCommit'] = '09c75422b5f0529b7b521c628eefffe9a75273d7'
        expected_preparation = ['ci-template-v0.1.528-cluster-affinity']
    elif preparation_kind == 'exact530':
        selected.update(sourceRef='v0.1.530', versionName='0.1.530',
                        sourceCommit='808e59bf0d51c36d2ebf54ddee2e5f08fc945930')
        expected_preparation = ['ci-template-v0.1.530-cluster-affinity']
    elif preparation_kind == 'historical':
        selected.update(sourceRef=SOURCE_REF, sourceCommit=SOURCE_COMMIT, versionName=SOURCE_REF[1:])
        prior = ['personal-cluster-task-affinity', OVERLAY_ID]
        expected_preparation = prior.copy()
    h = harness()
    monkeypatch.setenv('GITHUB_ACTIONS', 'true')
    monkeypatch.setattr(platform, 'system', lambda: 'Linux')
    calls = []
    original_affinity = h.prepare_template_affinity
    def affinity(source, selected):
        calls.append('affinity-preflight')
        return original_affinity(source, selected)
    monkeypatch.setattr(h, 'prepare_template_affinity', affinity)
    class Manager:
        def __init__(self, workspace, progress, cancel):
            self.workspace = workspace
        def install(self, accept):
            assert accept is True
            calls.append('install')
            if failure == 'tools':
                raise BuilderError('Download checksum mismatch')
            return dict(java='/managed/java', bundletool='/managed/bundletool.jar', env={})
        def prepare_source(self, requested):
            assert requested == selected
            calls.append('source')
            if failure == 'source':
                raise BuilderError('Native-deps release does not match source aasdk gitlink')
            source = self.workspace / 'source'
            (source / 'gradle/wrapper').mkdir(parents=True)
            (source / 'gradle/wrapper/gradle-wrapper.properties').write_text('distributionSha256Sum=' + 'a' * 64 + '\n')
            (source / 'gradle/verification-metadata.xml').write_text('<fixture/>')
            (source / 'app').mkdir()
            (source / 'app/build.gradle.kts').write_text('oalBuilderUnsigned oalVersionCode oalVersionName appId')
            (source / 'gradlew').write_text('fixture')
            if failure == 'source-contract':
                (source / 'app/build.gradle.kts').write_text('unsupported')
            if preparation_kind in {'exact528', 'exact530'}:
                reviewed = (Path(__file__).resolve().parents[2] / 'app/src/main/AndroidManifest.xml').read_bytes()
                manifest = source / 'app/src/main/AndroidManifest.xml'
                manifest.parent.mkdir(parents=True)
                manifest.write_bytes(reviewed.replace(b'${applicationId}.templates',
                                                      b'com.openautolink.app.templates'))
            return selected | {'sourceOverlays': prior}
    def run(argv, cwd, env, cancel, progress, phase):
        calls.append(argv)
        if ':app:bundleRelease' in argv:
            if failure == 'build':
                raise BuilderError(engine.process_failure(b'Dependency verification failed SECRET', phase, 1))
            if failure == 'template-copy':
                return ''  # Successful process with missing required AAB must fail closed.
            assert '--dependency-verification=strict' in argv
            assert '-PappId=' + r.TEMPLATE_APPLICATION_ID in argv
            assert '-PoalBuilderUnsigned=true' in argv
            assert '-PoalVersionName=' + selected['versionName'] in argv
            unsigned = Path(cwd) / 'app/build/outputs/bundle/release/app-release.aab'
            unsigned.parent.mkdir(parents=True)
            unsigned.write_bytes(b'unit fixture only')
        else:
            if failure == 'template-verify':
                raise BuilderError(engine.process_failure(b'SECRET opaque publication failure', phase, 1))
            assert argv[1:3] == ['-I', '-S'] and 'publish_template.py' in argv[3]
            assert argv[argv.index('--commit') + 1] == selected['sourceCommit']
        return ''
    monkeypatch.setattr(toolchain, 'Toolchain', Manager)
    monkeypatch.setattr(engine, 'run_process', run)
    monkeypatch.setattr(engine.Builder, 'build', lambda _: pytest.fail('Must not invoke community source mode'))
    if failure is not None:
        monkeypatch.setattr(h, 'resolve_snapshot', lambda _: {'release': selected})
        report = tmp_path / 'failure.json'
        assert h.main(['--output', str(tmp_path / 'out'), '--cache', str(tmp_path / 'cache'),
                       '--workspace', str(tmp_path / 'managed'), '--template-mode', 'local',
                       '--accept-ci-android-licenses', '--failure-report', str(report)]) == 1
        value = json.loads(report.read_text())
        assert value['phase'] == failure
        expected = {'tools': 'ci-template-download-checksum-mismatch',
                    'source': 'ci-template-native-source-mismatch',
                    'source-contract': 'ci-template-source-contract-unsupported',
                    'build': 'ci-template-dependency-verification-failed',
                    'template-copy': 'ci-template-aab-missing',
                    'template-verify': 'ci-template-tool-exit-unclassified'}
        assert value['codes'] == [expected[failure]]
        assert value['source']['sourceCommit'] == selected['sourceCommit']
        assert 'SECRET' not in report.read_text()
        assert not (tmp_path / 'out').exists()
        return
    diagnostic = {}
    template, descriptor = h.compile_template(tmp_path / 'managed', selected, tmp_path / 'template',
                                               accept_ci_android_licenses=True, diagnostic=diagnostic)
    assert diagnostic.get('sourcePreparation', []) == expected_preparation
    assert template.read_bytes() == b'unit fixture only'
    assert descriptor.name == r.DESCRIPTOR_NAME
    assert calls.index('source') < calls.index('affinity-preflight') < next(
        i for i, c in enumerate(calls) if isinstance(c, list) and ':app:bundleRelease' in c)
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


@pytest.mark.parametrize('tag,commit', [
    ('v0.1.528', '09c75422b5f0529b7b521c628eefffe9a75273d7'),
    ('v0.1.530', '808e59bf0d51c36d2ebf54ddee2e5f08fc945930'),
])
def test_success_provenance_roundtrips_exact_affinity_preparation(tmp_path, tag, commit):
    h = harness()
    selected = release() | {'sourceRef': tag, 'versionName': tag[1:], 'sourceCommit': commit}
    template, descriptor = input_fixture(tmp_path / 'input', selected)
    preparation = ['ci-template-' + tag + '-cluster-affinity']
    output = tmp_path / 'shared'
    h.export_inputs(selected, template, descriptor, output,
                    provenance='local-linux-template', source_preparation=preparation)
    loaded = h.load_inputs(output)
    assert loaded['release'] == selected  # Immutable tag is not the effective patched source.
    assert loaded['sourcePreparation'] == preparation
    assert json.loads((output / 'provenance.json').read_text()) == {
        'schema': 2, 'kind': 'local-linux-template', 'sourcePreparation': preparation,
        'descriptorSha256': hashlib.sha256(descriptor.read_bytes()).hexdigest()}
    assert (output / r.DESCRIPTOR_NAME).read_bytes() == descriptor.read_bytes()
    assert {p.name for p in output.iterdir()} == {
        'release.json', 'template.aab', r.DESCRIPTOR_NAME, 'provenance.json'}


@pytest.mark.parametrize('tag,commit', [
    ('v0.1.528', '09c75422b5f0529b7b521c628eefffe9a75273d7'),
    ('v0.1.530', '808e59bf0d51c36d2ebf54ddee2e5f08fc945930'),
])
@pytest.mark.parametrize('boundary', ['export', 'import'])
@pytest.mark.parametrize('case', ['unknown', 'commit', 'tag', 'historical', 'published',
                                 'duplicate', 'string', 'object', 'null', 'other-release'])
def test_source_preparation_rejects_unbound_or_untrusted_claims(tmp_path, boundary, case, tag, commit):
    h = harness()
    selected = release() | {'sourceRef': tag, 'versionName': tag[1:], 'sourceCommit': commit}
    preparation = ['ci-template-' + tag + '-cluster-affinity']
    provenance = 'local-linux-template'
    if case == 'other-release':
        other = 'v0.1.528' if tag == 'v0.1.530' else 'v0.1.530'
        preparation = ['ci-template-' + other + '-cluster-affinity']
    elif case == 'unknown':
        preparation = ['SECRET /private/key']
    elif case == 'commit':
        selected['sourceCommit'] = 'c' * 40
    elif case == 'tag':
        selected.update(sourceRef='v0.1.529', versionName='0.1.529')
    elif case == 'historical':
        preparation = ['personal-cluster-task-affinity']
    elif case == 'published':
        provenance = 'published-release-template'
    elif case == 'duplicate':
        preparation *= 2
    elif case == 'string':
        preparation = preparation[0]
    elif case == 'object':
        preparation = {'ci-template-v0.1.528-cluster-affinity': 'SECRET'}
    elif case == 'null':
        preparation = None
    template, descriptor = input_fixture(tmp_path / 'input', selected)
    if provenance == 'published-release-template':
        selected, _ = h.local_metadata(selected, template, descriptor)
    output = tmp_path / 'shared'
    if boundary == 'export':
        with pytest.raises(BuilderError, match='^ci-template-input-invalid$'):
            h.export_inputs(selected, template, descriptor, output,
                            provenance=provenance, source_preparation=preparation)
        assert not output.exists()  # Reject before exporting arbitrary supplied strings.
    else:
        h.export_inputs(selected, template, descriptor, output, provenance=provenance)
        path = output / 'provenance.json'
        value = json.loads(path.read_text())
        value['sourcePreparation'] = preparation
        path.write_text(json.dumps(value))
        with pytest.raises(BuilderError, match='^ci-template-input-invalid$'):
            h.load_inputs(output)


@pytest.mark.parametrize('source_kind', ['exact528', 'historical'])
def test_successful_producer_main_exports_actual_preparation(tmp_path, monkeypatch, source_kind):
    from oal_builder.native_verification import SOURCE_REF, SOURCE_COMMIT, OVERLAY_ID
    h = harness()
    selected = release() | {'sourceCommit': '09c75422b5f0529b7b521c628eefffe9a75273d7'}
    preparation = ['ci-template-v0.1.528-cluster-affinity']
    if source_kind == 'historical':
        selected.update(sourceRef=SOURCE_REF, sourceCommit=SOURCE_COMMIT, versionName=SOURCE_REF[1:])
        preparation = ['personal-cluster-task-affinity', OVERLAY_ID]
    template, descriptor = input_fixture(tmp_path / 'input', selected)
    monkeypatch.setattr(h, 'resolve_snapshot', lambda _: {'release': selected})
    def compiled(workspace, actual, destination, *, diagnostic, **kwargs):
        assert actual == selected
        diagnostic['sourcePreparation'] = preparation
        return template, descriptor
    monkeypatch.setattr(h, 'compile_template', compiled)
    output = tmp_path / 'out'
    assert h.main(['--output', str(output), '--cache', str(tmp_path / 'cache'),
                   '--workspace', str(tmp_path / 'work'), '--template-mode', 'local']) == 0
    assert h.load_inputs(output)['sourcePreparation'] == preparation


def input_fixture(directory, selected=None):
    selected = release() if selected is None else selected
    directory.mkdir(parents=True)
    template = directory / r.template_name(selected['sourceRef'])
    template.write_bytes(b'unit fixture; NOT an AAB or native proof')
    descriptor = dict(schemaVersion=1, formatVersion=1, releaseTag=selected['sourceRef'],
                      sourceCommit=selected['sourceCommit'], templateApplicationId=r.TEMPLATE_APPLICATION_ID,
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

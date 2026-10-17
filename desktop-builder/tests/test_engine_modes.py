"""Shared identity orchestration; network and heavy tools are isolated offline."""
import hashlib
import json
import threading
import zipfile
from pathlib import Path
from types import SimpleNamespace

import pytest
from oal_builder import engine as e, releases as r, toolchain as tc


def test_default_mode_and_invalid_mode_fail_before_workspace(tmp_path):
    assert e.BuildConfig(tmp_path, 'com.example.personal').mode == 'personalize'
    assert list(e.BuildConfig.__dataclass_fields__)[-1] == 'mode'
    with pytest.raises(e.BuilderError, match='mode'):
        e.Builder(e.BuildConfig(tmp_path / 'work', 'com.example.personal', mode='other'), lambda *_: None).build()
    assert not (tmp_path / 'work').exists()


@pytest.mark.parametrize('mode', ['personalize', 'source'])
@pytest.mark.parametrize('app_id', [
    'com.openautolink', 'com.openautolink.template.user',
    'com.openautolink.template', 'org.com.openautolink.templatecopy',
])
def test_new_incompatible_identity_fails_before_workspace(tmp_path, monkeypatch, mode, app_id):
    root = tmp_path / 'work'
    def reached_release(*args):
        raise e.BuilderError('Reached release discovery before rejecting incompatible identity')
    monkeypatch.setattr(e, 'resolve_latest_release', reached_release)
    with pytest.raises(e.BuilderError, match='incompatible with AAB personalization.*Choose'):
        e.Builder(e.BuildConfig(root, app_id, mode=mode, accept_licenses=True), lambda *_: None).build()
    assert not root.exists()


@pytest.mark.parametrize('mode', ['personalize', 'source'])
def test_identity_creation_itself_checks_compatibility_before_writes(tmp_path, mode):
    root = tmp_path / 'work'
    builder = e.Builder(e.BuildConfig(root, 'com.openautolink', mode=mode), lambda *_: None)
    with pytest.raises(e.BuilderError, match='incompatible with AAB personalization'):
        builder._prepare_identity()
    assert not root.exists()


@pytest.fixture
def pipeline(tmp_path, monkeypatch):
    """Real resolver/downloader/identity/verification; fake Java/Gradle boundary."""
    import io
    from cryptography.hazmat.primitives import serialization
    root = tmp_path / 'work'
    tag, commit = 'v1.2.3', 'a' * 40
    blob = io.BytesIO()
    with zipfile.ZipFile(blob, 'w') as z:
        for abi in ('arm64-v8a', 'x86_64'):
            z.writestr('base/lib/' + abi + '/libopenautolink-jni.so', b'offline fixture')
    payload = blob.getvalue()
    name = r.template_name(tag)
    descriptor = {'schemaVersion': 1, 'formatVersion': 1, 'releaseTag': tag,
                  'sourceCommit': commit, 'templateApplicationId': r.TEMPLATE_APPLICATION_ID,
                  'transformContract': r.TRANSFORM_CONTRACT,
                  'templateAsset': {'name': name, 'size': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()}}
    raw = json.dumps(descriptor).encode()
    assets = [{'name': n, 'size': len(data), 'digest': 'sha256:' + hashlib.sha256(data).hexdigest(),
               'browser_download_url': r.asset_url(tag, n)} for n, data in ((name, payload), (r.DESCRIPTOR_NAME, raw))]
    release = {'tag_name': tag, 'draft': False, 'prerelease': False,
               'html_url': r.WEB + '/releases/tag/' + tag, 'assets': assets}
    responses = {r.API + '/releases/latest': json.dumps(release).encode(),
                 r.API + '/git/ref/tags/' + tag: json.dumps({'ref': 'refs/tags/' + tag, 'object': {'sha': commit, 'type': 'commit'}}).encode(),
                 r.asset_url(tag, r.DESCRIPTOR_NAME): raw, r.asset_url(tag, name): payload}
    events, commands = [], []
    def open_url(self, url):
        self.check_cancel()
        events.append(url)
        return io.BytesIO(responses[url])
    monkeypatch.setattr(tc.Downloader, '_open', open_url)
    tools = {'java': 'java', 'javac': 'javac', 'bundletool': 'bundletool.jar',
             'jarsigner': 'jarsigner', 'keytool': 'keytool', 'env': {}}
    def install(self):
        events.append('easy-tools')
        assert events[:2] == [r.API + '/releases/latest', r.API + '/git/ref/tags/' + tag]
        return tools
    monkeypatch.setattr(tc.Toolchain, 'install_personalization', install)
    monkeypatch.setattr(tc.Toolchain, 'install', lambda *a: pytest.fail('Easy mode downloaded SDK'))
    def transform(template, output, **kwargs):
        assert kwargs['original_app_id'] == r.TEMPLATE_APPLICATION_ID
        assert kwargs['app_id'] == 'com.example.personal'
        assert kwargs['version_code'] == e.load_state(root)['lastVersionCode']
        assert kwargs['java'] == 'java' and kwargs['javac'] == 'javac'
        assert kwargs['carplay'] is None or isinstance(kwargs['carplay'], Path)
        events.append('transform')
        # Exercise the production transformer's callback convention.
        kwargs['run'](['javac', 'fixture.java'], 'personalize', cwd=kwargs['workdir'])
        Path(output).write_bytes(Path(template).read_bytes())
        if kwargs['carplay']:
            with zipfile.ZipFile(output, 'a') as z:
                for path in (kwargs['carplay'] / 'offline-mfi').iterdir():
                    z.write(path, 'base/assets/offline-mfi/' + path.name)
        return {'templateSha256': descriptor['templateAsset']['sha256']}
    # Patch the existing implementation module so the fixture works before the engine imports it.
    from oal_builder import personalize
    monkeypatch.setattr(personalize, 'personalize_bundle', transform)
    def command(argv, cwd, env, cancel, progress, phase):
        commands.append(list(map(str, argv)))
        assert not any('gradlew' in str(a) for a in argv)
        password = (root / 'private/identity/password').read_text().strip()
        assert not any(password in str(a) for a in [*argv, *env.values()])
        if '-verify' in argv:
            return 'jar verified.'
        if 'manifest' in argv:
            return ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                    'package="com.example.personal" android:versionCode="' + str(e.load_state(root)['lastVersionCode']) +
                    '" android:versionName="1.2.3"/>')
        if 'keytool' in argv:
            _, cert = e.read_identity(root / 'private/identity', e.load_state(root))
            return cert.public_bytes(serialization.Encoding.PEM).decode()
        return ''
    monkeypatch.setattr(e, 'run_process', command)
    return SimpleNamespace(root=root, responses=responses, release=release, descriptor=descriptor,
                           payload=payload, events=events, commands=commands, tools=tools,
                           command=command, transform=transform)


def test_easy_twice_uses_same_identity_without_sdk_or_gradle(pipeline):
    p = pipeline
    builder = e.Builder(e.BuildConfig(p.root, 'com.example.personal'), lambda *_: None)
    first = builder.build()
    key = (p.root / 'private/identity/upload-key.p12').read_bytes()
    second = builder.build()
    assert (first['versionCode'], second['versionCode']) == (1, 2)
    assert first['versionName'] == second['versionName'] == '1.2.3'
    assert first['sourceCommit'] == second['sourceCommit'] == 'a' * 40
    assert first['signingCertificateSha256'] == second['signingCertificateSha256']
    assert (p.root / 'private/identity/upload-key.p12').read_bytes() == key
    assert first['mode'] == second['mode'] == 'personalize'
    assert first['templateSha256'] == p.descriptor['templateAsset']['sha256']
    assert not (p.root / 'tools/android-sdk').exists()
    assert not list((p.root / 'source').iterdir())
    assert json.loads((p.root / 'output/latest.json').read_text()) == second
    assert not list((p.root / 'output').glob('*.pending'))
    assert not (p.root / 'private/personalization').exists()


@pytest.mark.parametrize('missing', ['descriptor', 'template'])
def test_missing_template_fails_before_any_heavy_download(pipeline, missing):
    p = pipeline
    name = r.DESCRIPTOR_NAME if missing == 'descriptor' else r.template_name('v1.2.3')
    p.release['assets'] = [a for a in p.release['assets'] if a['name'] != name]
    p.responses[r.API + '/releases/latest'] = json.dumps(p.release).encode()
    with pytest.raises(r.TemplateUnavailable, match='retry after') as error:
        e.Builder(e.BuildConfig(p.root, 'com.example.personal'), lambda *_: None).build()
    assert 'source compilation' not in str(error.value)
    assert 'Keep your work folder' in str(error.value)
    assert 'easy-tools' not in p.events and 'transform' not in p.events
    assert not p.commands
    assert not any(url.endswith('.aab') for url in p.events)
    assert e.load_state(p.root)['lastVersionCode'] == 0


def test_unsupported_template_stops_with_update_guidance_not_source_fallback(pipeline):
    p = pipeline
    p.descriptor['formatVersion'] = 999
    raw = json.dumps(p.descriptor).encode()
    p.responses[r.asset_url('v1.2.3', r.DESCRIPTOR_NAME)] = raw
    descriptor_asset = next(a for a in p.release['assets'] if a['name'] == r.DESCRIPTOR_NAME)
    descriptor_asset.update(size=len(raw), digest='sha256:' + hashlib.sha256(raw).hexdigest())
    p.responses[r.API + '/releases/latest'] = json.dumps(p.release).encode()
    with pytest.raises(r.ReleaseError, match='update the builder') as error:
        e.Builder(e.BuildConfig(p.root, 'com.example.personal'), lambda *_: None).build()
    assert 'source compilation' not in str(error.value)
    assert 'contact the publisher' in str(error.value)
    assert 'easy-tools' not in p.events and 'transform' not in p.events
    assert not p.commands
    assert e.load_state(p.root)['lastVersionCode'] == 0


def test_downloaded_template_is_independently_verified(pipeline, monkeypatch, tmp_path):
    p = pipeline
    wrong = tmp_path / 'corrupt.aab'
    wrong.write_bytes(b'x' * len(p.payload))
    monkeypatch.setattr(tc.Downloader, 'download', lambda *a, **kw: wrong)
    with pytest.raises(e.BuilderError, match='checksum'):
        e.Builder(e.BuildConfig(p.root, 'com.example.personal'), lambda *_: None).build()
    assert 'easy-tools' not in p.events and 'transform' not in p.events


@pytest.mark.parametrize('legacy_app_id', [None, 'com.openautolink', 'com.openautolink.template.user'])
def test_internal_legacy_source_preserves_key_reservations_and_backup(pipeline, monkeypatch, tmp_path, legacy_app_id):
    p = pipeline
    app_id = legacy_app_id or 'com.example.personal'
    original_key = None
    if legacy_app_id:
        # Model a pre-easy-mode identity without weakening today's creation rules.
        state = e.Builder(e.BuildConfig(p.root, 'com.example.personal', mode='source'), lambda *_: None)._prepare_identity()
        state.update(applicationId=legacy_app_id, lastVersionCode=7)
        e.atomic_json(p.root / 'private/identity/state.json', state)
        original_key = (p.root / 'private/identity/upload-key.p12').read_bytes()
        monkeypatch.setattr(e.rsa, 'generate_private_key', lambda **kw: pytest.fail('Regenerated existing key'))
    def install(self, accepted):
        assert accepted
        assert p.events == [r.API + '/releases/latest', r.API + '/git/ref/tags/v1.2.3']
        # Release publication changes after selection: do not resolve again.
        p.responses[r.API + '/releases/latest'] = b'{}'
        return p.tools
    def source(self, release):
        assert release['sourceRef'] == 'v1.2.3' and release['sourceCommit'] == 'a' * 40
        root = p.root / 'source'
        (root / 'gradle/wrapper').mkdir(parents=True)
        (root / 'gradle/wrapper/gradle-wrapper.properties').write_text('distributionSha256Sum=' + 'b' * 64)
        (root / 'gradle/verification-metadata.xml').write_text('<metadata/>')
        (root / 'app').mkdir()
        (root / 'app/build.gradle.kts').write_text('oalBuilderUnsigned oalVersionCode oalVersionName appId')
        (root / 'gradlew').touch()
        return release | {'sourceOverlays': []}
    def command(argv, *args):
        if ':app:bundleRelease' in argv:
            assert '--dependency-verification=strict' in argv
            assert '-PoalVersionName=1.2.3' in argv
            output = p.root / 'source/app/build/outputs/bundle/release/app-release.aab'
            output.parent.mkdir(parents=True)
            output.write_bytes(p.payload)
            return ''
        result = p.command(argv, *args)
        return result.replace('com.example.personal', app_id) if 'manifest' in argv else result
    monkeypatch.setattr(tc.Toolchain, 'install', install)
    monkeypatch.setattr(tc.Toolchain, 'prepare_source', source)
    monkeypatch.setattr(e, 'run_process', command)
    first = e.Builder(e.BuildConfig(p.root, app_id, accept_licenses=True, mode='source'), lambda *_: None).build()
    p.responses[r.API + '/releases/latest'] = json.dumps(p.release).encode()
    if legacy_app_id:
        assert first['mode'] == 'source' and first['versionCode'] == 8
        assert first['applicationId'] == legacy_app_id
        assert first['signingCertificateSha256'] == state['signingCertificateSha256']
        assert (p.root / 'private/identity/upload-key.p12').read_bytes() == original_key
        before = {path.relative_to(p.root): path.read_bytes() for path in p.root.rglob('*') if path.is_file()}
        with pytest.raises(e.BuilderError, match='incompatible with AAB personalization.*contact the publisher'):
            e.Builder(e.BuildConfig(p.root, app_id), lambda *_: None).build()
        assert {path.relative_to(p.root): path.read_bytes() for path in p.root.rglob('*') if path.is_file()} == before
        archive = tmp_path / 'legacy.oalbackup'
        e.backup_identity(p.root, archive, 'disposable backup password')
        restored = e.restore_identity(tmp_path / 'restored', archive, 'disposable backup password')
        assert restored['applicationId'] == legacy_app_id
        assert restored['lastVersionCode'] == 8
        assert restored['signingCertificateSha256'] == first['signingCertificateSha256']
        return
    second = e.Builder(e.BuildConfig(p.root, 'com.example.personal'), lambda *_: None).build()
    assert first['mode'] == 'source' and second['mode'] == 'personalize'
    assert first['versionCode'] == 1 and second['versionCode'] == 2
    assert first['signingCertificateSha256'] == second['signingCertificateSha256']
    archive = tmp_path / 'identity.oalbackup'
    e.backup_identity(p.root, archive, 'disposable backup password')
    restored = e.restore_identity(tmp_path / 'restored', archive, 'disposable backup password')
    assert restored['lastVersionCode'] == 2
    assert restored['signingCertificateSha256'] == first['signingCertificateSha256']


@pytest.mark.parametrize('phase', ['build', 'sign', 'verify'])
def test_easy_cancellation_removes_carplay_intermediates_and_pending(pipeline, tmp_path, phase):
    from test_engine import carplay_pair
    p = pipeline
    key, certificate = carplay_pair(tmp_path / 'inputs')
    cancel, messages = threading.Event(), []
    def progress(current, message):
        messages.append(message)
        if current == phase:
            cancel.set()
    builder = e.Builder(e.BuildConfig(p.root, 'com.example.personal', key, certificate), progress, cancel)
    with pytest.raises(e.BuilderError, match='cancelled'):
        builder.build()
    assert e.load_state(p.root)['lastVersionCode'] == 1
    assert e.load_state(p.root)['lastAttemptStatus'] == 'cancelled'
    assert not (p.root / 'private/carplay').exists()
    assert not (p.root / 'private/personalization').exists()
    assert not list((p.root / 'output').iterdir())
    assert not (p.root / 'source/app/build').exists()
    assert key.read_bytes() not in repr(messages).encode()
    password = (p.root / 'private/identity/password').read_text().strip()
    assert password not in repr(messages)
    with e.workspace_lock(p.root):
        pass


def test_real_easy_local_template_first_and_update(tmp_path, monkeypatch):
    """LOCAL retained 0.1.526 payload, mocked release METADATA; not GitHub-latest proof.

    Uses a new identity and real managed Java installation/signing/validation.
    Never reads any retained upload key. Only metadata is mocked; the local
    template is seeded into the content-addressed cache and hash-verified.
    """
    import os
    import xml.etree.ElementTree as ET
    from oal_builder.personalize import personalize_bundle
    configured = os.environ.get('OAL_ENGINE_LOCAL_TEMPLATE_INPUT')
    if not configured:
        pytest.skip('Set OAL_ENGINE_LOCAL_TEMPLATE_INPUT for local-template integration')
    original = Path(configured)
    original_hash = tc.file_digest(original)
    assert original_hash == 'f273d25e575197ade9e24b119c98d3a5b2c82312aa674ea38c9ba5c798fba89e'
    retained_tools = Path(os.environ['OAL_PERSONALIZE_TOOLS'])
    java = retained_tools / 'jdk-17/bin/java'
    javac = retained_tools / 'jdk-17/bin/javac'
    jar = retained_tools / 'bundletool-1.18.3.jar'
    assert tc.file_digest(jar) == tc.BUNDLETOOL_SHA256
    cancel = threading.Event()
    prep = tmp_path / 'template-preparation'
    prep.mkdir()
    preparation_manager = tc.Toolchain(prep, lambda *_: None, cancel)
    env = preparation_manager.environment(java.parent.parent)
    def run(argv, phase, cwd=None):
        return e.run_process(argv, cwd or prep, env, cancel, lambda *_: None, phase)
    local_template = tmp_path / 'local-template-0.1.526.aab'
    personalize_bundle(original, local_template,
        original_app_id='com.openautolink.personal.qualification', app_id=r.TEMPLATE_APPLICATION_ID,
        version_code=1, java=java, javac=javac, bundletool=jar, workdir=prep, run=run)
    tag, commit = 'v0.1.526', '580a68d772afc97e444019504178f1678731feb6'
    digest, size = tc.file_digest(local_template), local_template.stat().st_size
    name = r.template_name(tag)
    descriptor = {'schemaVersion': 1, 'formatVersion': 1, 'releaseTag': tag,
                  'sourceCommit': commit, 'templateApplicationId': r.TEMPLATE_APPLICATION_ID,
                  'transformContract': r.TRANSFORM_CONTRACT,
                  'templateAsset': {'name': name, 'size': size, 'sha256': digest}}
    raw = json.dumps(descriptor).encode()
    records = [{'name': n, 'size': s, 'digest': 'sha256:' + h, 'browser_download_url': r.asset_url(tag, n)}
               for n, s, h in [(name, size, digest), (r.DESCRIPTOR_NAME, len(raw), hashlib.sha256(raw).hexdigest())]]
    metadata = {r.API + '/releases/latest': json.dumps({'tag_name': tag, 'draft': False,
        'prerelease': False, 'html_url': r.WEB + '/releases/tag/' + tag, 'assets': records}).encode(),
        r.API + '/git/ref/tags/' + tag: json.dumps({'ref': 'refs/tags/' + tag, 'object': {'type': 'commit', 'sha': commit}}).encode(),
        r.asset_url(tag, r.DESCRIPTOR_NAME): raw}
    real_metadata = tc.Downloader.metadata
    def local_metadata(self, url, **kwargs):
        self.check_cancel()
        if url in metadata:
            return metadata[url]
        return real_metadata(self, url, **kwargs)
    monkeypatch.setattr(tc.Downloader, 'metadata', local_metadata)
    work = e.managed_workspace(tmp_path / 'new-disposable-workspace')
    cache = work / 'cache/downloads'
    cache.mkdir()
    import shutil
    shutil.copyfile(local_template, cache / (digest + '.archive'))
    app_id = 'com.example.oal.localtemplateintegration'
    events = []
    builder = e.Builder(e.BuildConfig(work, app_id), lambda *args: events.append(args))
    first = builder.build()
    key_hash = tc.file_digest(work / 'private/identity/upload-key.p12')
    second = builder.build()
    assert [first['versionCode'], second['versionCode']] == [1, 2]
    assert first['signingCertificateSha256'] == second['signingCertificateSha256']
    assert tc.file_digest(work / 'private/identity/upload-key.p12') == key_hash
    assert tc.file_digest(original) == original_hash
    assert not (work / 'tools/android-sdk').exists()
    assert not (work / 'cache/gradle').exists()
    assert not (work / 'cache/home/.android').exists()
    assert not list((work / 'source').iterdir())
    assert not (work / 'private/personalization').exists()
    ns = '{http://schemas.android.com/apk/res/android}'
    with zipfile.ZipFile(local_template) as source:
        payloads = {n: hashlib.sha256(source.read(n)).hexdigest() for n in source.namelist()
                    if n.startswith(('base/dex/', 'base/lib/'))}
    for result in (first, second):
        assert result['versionName'] == '0.1.526' and result['templateSha256'] == digest
        dump = builder._run([builder.tools['java'], '-jar', builder.tools['bundletool'],
                             'dump', 'manifest', '--bundle=' + result['artifact']], 'verify')
        manifest = ET.fromstring(dump)
        assert manifest.get('package') == app_id
        assert manifest.get(ns + 'versionName') == '0.1.526'
        assert manifest.get(ns + 'versionCode') == str(result['versionCode'])
        assert manifest.find('application').get(ns + 'name') == 'com.openautolink.app.OalApplication'
        assert [node.get(ns + 'taskAffinity') for node in manifest.iter() if node.get(ns + 'taskAffinity')] == [app_id + '.templates']
        with zipfile.ZipFile(result['artifact']) as archive:
            assert {n: hashlib.sha256(archive.read(n)).hexdigest() for n in archive.namelist()
                    if n.startswith(('base/dex/', 'base/lib/'))} == payloads
    report = {'qualification': 'LOCAL TEMPLATE integration; retained 0.1.526 payload, mocked release metadata, NOT GitHub latest proof',
              'inputSha256': original_hash, 'templateSha256': digest, 'results': [first, second],
              'realJavaSigningAndBundleValidation': True, 'sameSignerIncreasingVersion': True,
              'dexNativeBytesUnchanged': True, 'noAndroidSdkOrGradle': True}
    (tmp_path / 'local-template-integration.json').write_text(json.dumps(report, indent=2) + '\n')


def test_helper_cancellation_is_reported_as_cancelled(pipeline, monkeypatch):
    from oal_builder import personalize
    p, cancel = pipeline, threading.Event()
    def cancelled_helper(*args, **kwargs):
        cancel.set()
        # Production transformer deliberately masks all runner exceptions.
        raise e.BuilderError('OAL_PERSONALIZE_HELPER_FAILED')
    monkeypatch.setattr(personalize, 'personalize_bundle', cancelled_helper)
    with pytest.raises(e.BuilderError, match='^Build cancelled$'):
        e.Builder(e.BuildConfig(p.root, 'com.example.personal'), lambda *_: None, cancel).build()
    assert e.load_state(p.root)['lastAttemptStatus'] == 'cancelled'
    assert not (p.root / 'private/personalization').exists()


def test_old_template_version_is_not_relabelled_as_new_release(pipeline, monkeypatch):
    p = pipeline
    def command(argv, *args):
        result = p.command(argv, *args)
        return result.replace('1.2.3', '0.1.526') if 'manifest' in argv else result
    monkeypatch.setattr(e, 'run_process', command)
    with pytest.raises(e.BuilderError, match='manifest'):
        e.Builder(e.BuildConfig(p.root, 'com.example.personal'), lambda *_: None).build()
    assert not list((p.root / 'output').iterdir())
    assert e.load_state(p.root)['lastAttemptStatus'] == 'failed'

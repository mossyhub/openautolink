"""Publication transaction tests: synthetic transport, no GitHub writes."""
import hashlib
import importlib.util
import json
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/upload_template.py'
TAG = 'v0.1.531'
COMMIT = 'a' * 40
RELEASE_ID = 123
AAB = f'openautolink-template-{TAG}.aab'
DESCRIPTOR = 'openautolink-personalization.json'
NAMES = [AAB, AAB + '.sha256', DESCRIPTOR]


def load():
    assert SCRIPT.is_file(), 'Missing safe template uploader'
    spec = importlib.util.spec_from_file_location('upload_template_test', SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class API:
    def __init__(self):
        self.release = {'id': RELEASE_ID, 'tag_name': TAG, 'draft': False, 'prerelease': False}
        self.commit = COMMIT
        self.assets = {}
        self.data = {}
        self.events = []
        self.add('maintainer.apk', b'unchanged signed APK fixture')

    def add(self, name, data):
        asset = {'id': len(self.assets) + 100, 'name': name, 'state': 'uploaded',
                 'size': len(data), 'digest': 'sha256:' + hashlib.sha256(data).hexdigest()}
        self.assets[name] = asset
        self.data[asset['id']] = data
        return asset

    def get(self, path):
        if path.startswith('releases/') and '/assets?' in path:
            return list(self.assets.values())
        if path.startswith('releases/'):
            return dict(self.release)
        if path == 'git/ref/tags/' + TAG:
            return {'ref': 'refs/tags/' + TAG, 'object': {'type': 'commit', 'sha': self.commit}}
        raise AssertionError(path)

    def fingerprint(self, asset):
        self.events.append(('read', asset['name']))
        data = self.data[asset['id']]
        return len(data), hashlib.sha256(data).hexdigest()

    def upload(self, release_id, path):
        assert release_id == RELEASE_ID
        assert path.name in NAMES and path.name not in self.assets
        self.events.append(('upload', path.name))
        return self.add(path.name, path.read_bytes())


@pytest.fixture
def payload(tmp_path):
    data = b'synthetic template bytes; structural gate is producer responsibility'
    sha = hashlib.sha256(data).hexdigest()
    (tmp_path / AAB).write_bytes(data)
    (tmp_path / (AAB + '.sha256')).write_text(sha + '  ' + AAB + '\n')
    (tmp_path / DESCRIPTOR).write_text(json.dumps({
        'schemaVersion': 1, 'formatVersion': 1, 'releaseTag': TAG, 'sourceCommit': COMMIT,
        'templateApplicationId': 'com.openautolink.template', 'transformContract': 'oal-identity-v1',
        'templateAsset': {'name': AAB, 'size': len(data), 'sha256': sha}}))
    return tmp_path


def test_uploads_exact_three_descriptor_last_and_reads_back(payload):
    module = load()
    api = API()
    before = dict(api.assets['maintainer.apk'])
    module.publish(api, payload, release_id=RELEASE_ID, tag=TAG, commit=COMMIT)
    assert [name for kind, name in api.events if kind == 'upload'] == NAMES
    for name in NAMES:
        assert ('read', name) in api.events
    assert api.events.index(('read', AAB)) < api.events.index(('upload', DESCRIPTOR))
    assert api.assets['maintainer.apk'] == before
    assert api.events.count(('read', 'maintainer.apk')) >= 2


@pytest.mark.parametrize('existing', [NAMES[:1], NAMES[:2], NAMES, NAMES[2:]])
def test_identical_partial_and_complete_retries_verify_actual_bytes(payload, existing):
    api = API()
    for name in existing:
        api.add(name, (payload / name).read_bytes())
    load().publish(api, payload, release_id=RELEASE_ID, tag=TAG, commit=COMMIT)
    assert [name for kind, name in api.events if kind == 'upload'] == [n for n in NAMES if n not in existing]
    for name in existing:
        assert ('read', name) in api.events


@pytest.mark.parametrize('conflict', NAMES)
def test_any_existing_conflict_rejects_before_any_write(payload, conflict):
    api = API()
    asset = api.add(conflict, (payload / conflict).read_bytes())
    # Metadata claims the right digest; actual remote bytes are different.
    api.data[asset['id']] = b'wrong bytes'
    with pytest.raises(ValueError, match='conflict'):
        load().publish(api, payload, release_id=RELEASE_ID, tag=TAG, commit=COMMIT)
    assert not any(kind == 'upload' for kind, _ in api.events)


@pytest.mark.parametrize('change', ['id', 'tag_name', 'draft', 'prerelease', 'commit'])
def test_release_tuple_mismatch_rejects_without_writes(payload, change):
    api = API()
    if change == 'commit':
        api.commit = 'b' * 40
    else:
        api.release[change] = {'id': 999, 'tag_name': 'v0.1.532', 'draft': True, 'prerelease': True}[change]
    with pytest.raises(ValueError, match='[Rr]elease|[Tt]ag'):
        load().publish(api, payload, release_id=RELEASE_ID, tag=TAG, commit=COMMIT)
    assert not any(kind == 'upload' for kind, _ in api.events)


def test_tag_move_during_upload_stops_before_descriptor(payload):
    api = API()
    upload = api.upload
    def move(release_id, path):
        result = upload(release_id, path)
        api.commit = 'b' * 40
        return result
    api.upload = move
    with pytest.raises(ValueError, match='[Tt]ag'):
        load().publish(api, payload, release_id=RELEASE_ID, tag=TAG, commit=COMMIT)
    assert DESCRIPTOR not in api.assets


@pytest.mark.parametrize('change', ['descriptor-tag', 'descriptor-commit', 'checksum', 'bytes', 'extra', 'symlink'])
def test_local_payload_binding_rejects_before_writes(payload, change):
    if change.startswith('descriptor'):
        path = payload / DESCRIPTOR
        doc = json.loads(path.read_text())
        doc['releaseTag' if change == 'descriptor-tag' else 'sourceCommit'] = 'wrong'
        path.write_text(json.dumps(doc))
    elif change == 'extra':
        (payload / 'maintainer.apk').write_bytes(b'not allowed')
    elif change == 'symlink':
        data = (payload / AAB).read_bytes()
        (payload / AAB).unlink()
        target = payload.parent / 'linked.aab'
        target.write_bytes(data)
        (payload / AAB).symlink_to(target)
    else:
        (payload / (AAB + '.sha256' if change == 'checksum' else AAB)).write_bytes(b'wrong')
    api = API()
    with pytest.raises(ValueError):
        load().publish(api, payload, release_id=RELEASE_ID, tag=TAG, commit=COMMIT)
    assert not any(kind == 'upload' for kind, _ in api.events)


def test_incomplete_existing_asset_rejected_without_writes(payload):
    api = API()
    asset = api.add(DESCRIPTOR, (payload / DESCRIPTOR).read_bytes())
    asset['state'] = 'starter'
    with pytest.raises(ValueError, match='conflict'):
        load().publish(api, payload, release_id=RELEASE_ID, tag=TAG, commit=COMMIT)
    assert not any(kind == 'upload' for kind, _ in api.events)


@pytest.mark.parametrize('change', ['bytes', 'id', 'addition'])
def test_apk_change_blocks_descriptor_and_failure_still_audits(payload, change):
    api = API()
    upload = api.upload
    def mutate(release_id, path):
        result = upload(release_id, path)
        if change == 'bytes':
            api.data[api.assets['maintainer.apk']['id']] = b'changed APK bytes'
        elif change == 'id':
            asset = api.assets['maintainer.apk']
            data = api.data[asset['id']]
            asset['id'] = 999
            api.data[999] = data
        else:
            api.add('new.apk', b'new independently shipped APK')
        return result
    api.upload = mutate
    with pytest.raises(ValueError, match='APK'):
        load().publish(api, payload, release_id=RELEASE_ID, tag=TAG, commit=COMMIT)
    assert DESCRIPTOR not in api.assets
    assert api.events.count(('read', 'maintainer.apk')) >= 2


def test_interrupted_upload_audits_apks_and_identical_retry_resumes(payload):
    api = API()
    upload = api.upload
    def interrupt(release_id, path):
        upload(release_id, path)
        raise OSError('synthetic lost upload response')
    api.upload = interrupt
    with pytest.raises(OSError):
        load().publish(api, payload, release_id=RELEASE_ID, tag=TAG, commit=COMMIT)
    assert api.events.count(('read', 'maintainer.apk')) >= 2
    api.upload = upload
    load().publish(api, payload, release_id=RELEASE_ID, tag=TAG, commit=COMMIT)
    assert [name for kind, name in api.events if kind == 'upload'] == NAMES


def test_template_asset_id_replacement_rejected_even_for_identical_bytes(payload):
    api = API()
    upload = api.upload
    def replace(release_id, path):
        result = upload(release_id, path)
        if path.name == DESCRIPTOR:
            asset = api.assets[AAB]
            data = api.data[asset['id']]
            asset['id'] = 999
            api.data[999] = data
        return result
    api.upload = replace
    with pytest.raises(ValueError, match='ID'):
        load().publish(api, payload, release_id=RELEASE_ID, tag=TAG, commit=COMMIT)


def test_final_readback_catches_earlier_asset_mutation(payload):
    api = API()
    upload = api.upload
    def mutate(release_id, path):
        result = upload(release_id, path)
        if path.name == DESCRIPTOR:
            api.data[api.assets[AAB]['id']] = b'changed after first read'
        return result
    api.upload = mutate
    with pytest.raises(ValueError, match='conflict|readback'):
        load().publish(api, payload, release_id=RELEASE_ID, tag=TAG, commit=COMMIT)


def test_local_mutation_during_transaction_does_not_upload_changed_bytes(payload):
    api = API()
    original = api.fingerprint
    def mutate(asset):
        if asset['name'] == 'maintainer.apk':
            (payload / AAB).write_bytes(b'changed locally')
        return original(asset)
    api.fingerprint = mutate
    with pytest.raises(ValueError, match='Payload changed'):
        load().publish(api, payload, release_id=RELEASE_ID, tag=TAG, commit=COMMIT)
    assert not any(kind == 'upload' for kind, _ in api.events)


def test_transport_upload_is_allowlisted_and_token_not_in_url(payload, monkeypatch):
    import io
    module = load()
    calls = []
    class Opener:
        def open(self, request, timeout):
            calls.append(request)
            return io.BytesIO(b'{"id": 321}')
    monkeypatch.setattr(module.urllib.request, 'build_opener', lambda *args: Opener())
    api = module.GitHub('mossyhub/openautolink', 'synthetic-secret')
    api.upload(RELEASE_ID, payload / AAB)
    assert calls[0].get_header('Authorization') == 'Bearer synthetic-secret'
    assert 'synthetic-secret' not in calls[0].full_url
    assert calls[0].full_url.startswith('https://uploads.github.com/repos/mossyhub/openautolink/releases/123/assets?')
    assert calls[0].data == (payload / AAB).read_bytes()
    with pytest.raises(ValueError):
        api.upload(RELEASE_ID, payload / 'maintainer.apk')


def test_public_readback_has_no_token_and_bounds_bytes(monkeypatch):
    import io
    module = load()
    data = b'public bytes'
    requests = []
    class Opener:
        def open(self, request, timeout):
            requests.append(request)
            return io.BytesIO(data)
    monkeypatch.setattr(module.urllib.request, 'build_opener', lambda *args: Opener())
    api = module.GitHub('mossyhub/openautolink', 'synthetic-secret')
    asset = {'id': 42, 'name': AAB, 'size': len(data),
             'browser_download_url': f'https://github.com/mossyhub/openautolink/releases/download/{TAG}/{AAB}'}
    assert api.fingerprint(asset) == (len(data), hashlib.sha256(data).hexdigest())
    assert requests[0].get_header('Authorization') is None
    asset['size'] = 1
    with pytest.raises(ValueError, match='size'):
        api.fingerprint(asset)
    asset['browser_download_url'] = 'https://evil.example/file'
    with pytest.raises(ValueError, match='URL'):
        api.fingerprint(asset)


def test_transport_rejects_credential_redirects_and_unsafe_public_redirects():
    import urllib.request
    module = load()
    request = urllib.request.Request('https://api.github.com/', headers={'Authorization': 'Bearer synthetic'})
    with pytest.raises(ValueError):
        module.NoRedirect().redirect_request(request, None, 302, '', {}, 'https://evil.example')
    for url in ('http://github.com/a', 'https://evil.example/a', 'https://github.com:443/a', 'https://user@github.com/a'):
        with pytest.raises(ValueError):
            module.PublicRedirect().redirect_request(request, None, 302, '', {}, url)
    redirected = module.PublicRedirect().redirect_request(request, None, 302, '', {}, 'https://release-assets.githubusercontent.com/a')
    assert redirected.get_header('Authorization') is None


def test_cli_check_only_performs_no_publish(payload, monkeypatch):
    module = load()
    api = API()
    monkeypatch.setenv('GH_TOKEN', 'synthetic-secret')
    monkeypatch.setenv('GITHUB_REPOSITORY', 'mossyhub/openautolink')
    monkeypatch.setattr(module, 'GitHub', lambda *args: api)
    assert module.main(['--check-only', '--tag', TAG, '--commit', COMMIT, '--release-id', str(RELEASE_ID)]) == 0
    assert not api.events


def test_annotated_tag_is_peeled(payload):
    api = API()
    get = api.get
    def annotated(path):
        if path == 'git/ref/tags/' + TAG:
            return {'ref': 'refs/tags/' + TAG, 'object': {'type': 'tag', 'sha': 'c' * 40}}
        if path == 'git/tags/' + 'c' * 40:
            return {'sha': 'c' * 40, 'tag': TAG, 'object': {'type': 'commit', 'sha': COMMIT}}
        return get(path)
    api.get = annotated
    load().publish(api, payload, release_id=RELEASE_ID, tag=TAG, commit=COMMIT)

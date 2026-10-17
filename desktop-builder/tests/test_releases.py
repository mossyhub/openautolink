"""Release provenance and fail-closed personalization contract tests."""
import importlib
import hashlib
import json
import pytest

API = 'https://api.github.com/repos/mossyhub/openautolink'
WEB = 'https://github.com/mossyhub/openautolink'
TAG = 'v0.1.528'
COMMIT = 'a' * 40


def release(assets=None):
    return {'tag_name': TAG, 'draft': False, 'prerelease': False,
            'html_url': WEB + '/releases/tag/' + TAG, 'assets': assets or []}


def fetcher(metadata=None, ref=None, extra=None):
    calls = []
    responses = {API + '/releases/latest': metadata or release(),
                 API + '/git/ref/tags/' + TAG: ref or {
                     'ref': 'refs/tags/' + TAG,
                     'object': {'type': 'commit', 'sha': COMMIT}}}
    responses.update(extra or {})
    def fetch(url):
        calls.append(url)
        return responses[url]
    return fetch, calls


def test_latest_source_available_without_template():
    r = importlib.import_module('oal_builder.releases')
    fetch, calls = fetcher()
    result = r.resolve_latest_release(fetch)
    assert result['sourceRef'] == TAG
    assert result['sourceCommit'] == COMMIT
    assert result['versionName'] == '0.1.528'
    assert result['templateAvailable'] is False
    assert calls == [API + '/releases/latest', API + '/git/ref/tags/' + TAG]


@pytest.mark.parametrize('changes', [
    {'draft': True}, {'prerelease': True}, {'draft': 0}, {'prerelease': None},
    {'tag_name': 'v0.1.528-rc1'}, {'tag_name': 'v01.1.528'},
    {'tag_name': '../main'}, {'tag_name': 'v0.1.528\n'},
    {'html_url': WEB + '/releases/tag/v0.1.527'},
])
def test_rejects_nonstable_or_tampered_release(changes):
    r = importlib.import_module('oal_builder.releases')
    metadata = release() | changes
    fetch, calls = fetcher(metadata)
    with pytest.raises(r.ReleaseError):
        r.resolve_latest_release(fetch)
    assert calls == [API + '/releases/latest']


@pytest.mark.parametrize('ref', [
    {'ref': 'refs/tags/other', 'object': {'type': 'commit', 'sha': COMMIT}},
    {'ref': 'refs/tags/' + TAG, 'object': {'type': 'commit', 'sha': 'main'}},
    {'ref': 'refs/tags/' + TAG, 'object': {'type': 'tree', 'sha': COMMIT}},
])
def test_bad_source_ref(ref):
    r = importlib.import_module('oal_builder.releases')
    with pytest.raises(r.ReleaseError):
        r.resolve_latest_release(fetcher(ref=ref)[0])


def test_annotated_tag_is_peeled_and_bounded():
    r = importlib.import_module('oal_builder.releases')
    ref = {'ref': 'refs/tags/' + TAG, 'object': {'type': 'tag', 'sha': 'b' * 40}}
    obj = {'sha': 'b' * 40, 'tag': TAG, 'object': {'type': 'commit', 'sha': COMMIT}}
    fetch, _ = fetcher(ref=ref, extra={API + '/git/tags/' + 'b' * 40: obj})
    assert r.resolve_latest_release(fetch)['sourceCommit'] == COMMIT
    obj['object'] = ref['object']
    with pytest.raises(r.ReleaseError):
        r.resolve_latest_release(fetch)


def test_network_failure_is_actionable_and_does_not_leak():
    r = importlib.import_module('oal_builder.releases')
    def fail(url):
        raise OSError('secret-token')
    with pytest.raises(r.ReleaseError, match='connectivity|rate limit') as error:
        r.resolve_latest_release(fail)
    assert 'secret-token' not in str(error.value)


NAME = 'openautolink-template-' + TAG + '.aab'
DESCRIPTOR = 'openautolink-personalization.json'


def asset(name, size=123, digest=None):
    return {'name': name, 'size': size,
            'browser_download_url': WEB + '/releases/download/' + TAG + '/' + name,
            'digest': digest}


def descriptor():
    return {'schemaVersion': 1, 'formatVersion': 1,
            'releaseTag': TAG, 'sourceCommit': COMMIT,
            'templateApplicationId': 'com.openautolink.template',
            'transformContract': 'oal-identity-v1',
            'templateAsset': {'name': NAME, 'size': 3,
                              'sha256': hashlib.sha256(b'aab').hexdigest()}}


def ready_release():
    r = importlib.import_module('oal_builder.releases')
    raw = json.dumps(descriptor()).encode()
    assets = [asset(NAME, 3), asset(DESCRIPTOR, len(raw))]
    resolved = r.resolve_latest_release(fetcher(release(assets))[0])
    return resolved, raw


def test_template_contract_and_actual_digest(tmp_path):
    r = importlib.import_module('oal_builder.releases')
    resolved, raw = ready_release()
    assert resolved['templateAvailable'] is True
    urls = []
    def fetch(url):
        urls.append(url)
        return raw
    template = r.resolve_template(resolved, fetch)
    assert template == descriptor() | {'url': asset(NAME)['browser_download_url']}
    assert urls == [asset(DESCRIPTOR)['browser_download_url']]
    aab = tmp_path / 'download.aab'
    aab.write_bytes(b'aab')
    assert r.verify_template_download(aab, template) == aab
    aab.write_bytes(b'bad')
    with pytest.raises(r.ReleaseError, match='checksum'):
        r.verify_template_download(aab, template)


def test_apk_is_not_a_template_and_missing_template_requires_publication():
    r = importlib.import_module('oal_builder.releases')
    resolved = r.resolve_latest_release(fetcher(release([asset('template.apk')]))[0])
    assert not resolved['templateAvailable']
    with pytest.raises(r.TemplateUnavailable, match='retry after the publisher') as error:
        r.resolve_template(resolved, lambda url: pytest.fail('must not fetch'))
    assert 'source compilation' not in str(error.value)


@pytest.mark.parametrize('mutation', ['duplicate', 'url', 'credentials', 'query', 'foreign-tag', 'bad-size', 'bad-digest', 'many'])
def test_invalid_release_asset_metadata(mutation):
    r = importlib.import_module('oal_builder.releases')
    assets = [asset(NAME)]
    if mutation == 'duplicate': assets *= 2
    elif mutation == 'url': assets[0]['browser_download_url'] = 'https://evil.example/x'
    elif mutation == 'credentials': assets[0]['browser_download_url'] = assets[0]['browser_download_url'].replace('https://', 'https://secret@')
    elif mutation == 'query': assets[0]['browser_download_url'] += '?token=secret'
    elif mutation == 'foreign-tag': assets[0]['browser_download_url'] = assets[0]['browser_download_url'].replace(TAG, 'v0.1.527')
    elif mutation == 'bad-size': assets[0]['size'] = True
    elif mutation == 'bad-digest': assets[0]['digest'] = 'sha256:bad'
    else: assets = [asset(str(i)) for i in range(101)]
    with pytest.raises(r.ReleaseError):
        r.resolve_latest_release(fetcher(release(assets))[0])


@pytest.mark.parametrize('mutation', ['tag', 'commit', 'app', 'format', 'schema', 'contract', 'apk', 'size', 'hash', 'missing', 'duplicate-json', 'too-large', 'github-hash'])
def test_rejects_malformed_or_incompatible_descriptor(mutation):
    r = importlib.import_module('oal_builder.releases')
    resolved, _ = ready_release()
    d = descriptor()
    if mutation == 'tag': d['releaseTag'] = 'v0.1.527'
    elif mutation == 'commit': d['sourceCommit'] = 'b' * 40
    elif mutation == 'app': d['templateApplicationId'] = 'com.openautolink.app'
    elif mutation == 'format': d['formatVersion'] = True
    elif mutation == 'schema': d['schemaVersion'] = 2
    elif mutation == 'contract': d['transformContract'] = 'unknown'
    elif mutation == 'apk': d['templateAsset']['name'] = 'template.apk'
    elif mutation == 'size': d['templateAsset']['size'] = 4
    elif mutation == 'hash': d['templateAsset']['sha256'] = 'bad'
    elif mutation == 'missing': resolved['assets'].pop(NAME)
    elif mutation == 'github-hash': resolved['assets'][NAME]['digest'] = 'sha256:' + 'f' * 64
    raw = json.dumps(d).encode()
    if mutation == 'duplicate-json': raw = raw[:-1] + b',"schemaVersion":1}'
    if mutation == 'too-large': raw += b' ' * 65537
    resolved['assets'][DESCRIPTOR]['size'] = len(raw)
    with pytest.raises(r.ReleaseError):
        r.resolve_template(resolved, lambda url: raw)


@pytest.mark.parametrize('mutation', ['size', 'digest', 'network', 'not-bytes', 'json', 'url'])
def test_descriptor_transport_rejects_partial_untrusted_and_invalid_data(mutation):
    r = importlib.import_module('oal_builder.releases')
    resolved, raw = ready_release()
    record = resolved['assets'][DESCRIPTOR]
    if mutation == 'size': record['size'] += 1
    elif mutation == 'digest': record['digest'] = 'sha256:' + '0' * 64
    elif mutation == 'url': record['browser_download_url'] = 'https://secret@github.com/other'
    elif mutation == 'not-bytes': raw = raw.decode()
    elif mutation == 'json': raw = b'{' + b' ' * (len(raw) - 1)
    def fetch(url):
        if mutation == 'network': raise OSError('secret')
        return raw
    with pytest.raises(r.ReleaseError) as error:
        r.resolve_template(resolved, fetch)
    assert 'secret' not in str(error.value)


@pytest.mark.parametrize('mutation', ['missing', 'size', 'symlink'])
def test_download_requires_complete_regular_file(tmp_path, mutation):
    r = importlib.import_module('oal_builder.releases')
    resolved, raw = ready_release()
    template = r.resolve_template(resolved, lambda url: raw)
    path = tmp_path / 'download.aab'
    if mutation == 'size': path.write_bytes(b'bad-size')
    elif mutation == 'symlink':
        target = tmp_path / 'target'
        target.write_bytes(b'aab')
        try: path.symlink_to(target)
        except OSError: pytest.skip('symlinks unavailable')
    with pytest.raises(r.ReleaseError, match='size'):
        r.verify_template_download(path, template)

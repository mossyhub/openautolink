"""Publication contracts use fixture payloads, never pretend native qualification."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / 'packaging/publish_desktop.py'
SHA = 'a' * 40
SOURCE = 'b' * 64


def module():
    assert SPEC.is_file(), 'Desktop publication script missing'
    spec = importlib.util.spec_from_file_location('publish_desktop', SPEC)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def fixture(root):
    for folder, suffix, artifact, system, smoke in [
        ('windows', 'windows-x86_64', 'OpenAutoLink-Builder.exe', 'Windows', 'qwindows-passed'),
        ('linux', 'linux-x86_64', 'OpenAutoLink-Builder', 'Linux', 'xcb-display-passed'),
        ('macos', 'macos', 'OpenAutoLink-Builder.dmg', 'Darwin', None)]:
        path = root / folder
        path.mkdir()
        data = ('TEST FIXTURE ONLY ' + artifact).encode()
        (path / artifact).write_bytes(data)
        sha = hashlib.sha256(data).hexdigest()
        manifest = dict(artifact=artifact, sha256=sha, files={artifact: sha}, system=system,
                        kind='direct-executable', architecture='x86_64', builderSourceSha256=SOURCE,
                        frozenSmokeTest='passed', nativeCoverage='exact-payload-evidence-verified',
                        nativePlatformSmoke=smoke)
        if folder == 'windows':
            manifest.update(bootstrap='official-vc-runtime-bootstrap-v1',
                bootstrapExtraction='exact-payload-verified', innerSha256='c' * 64,
                runtimeInstaller='recipient-download-only-interactive',
                embeddedPayload={name: 'c' * 64 for name in (
                    'builder.exe', 'bootstrap.ps1', 'setup-manifest.json',
                    'NSIS-COPYING.txt', 'BOOTSTRAP-NOTICE.txt')},
                bootstrapLoader={'status': 'exact-nonresource-sections',
                    'supplierStubSha256': '577beebf93003432c0104641d8a7e49ca8366253eece062dd5bec8ad2ad87a09',
                    'supplierArchiveSha256': 'ba63dffc4410ee89193e1cb5a41989991bd77c61068da17e3156d136b7b0b3d8',
                    'sections': [{'name': '.text', 'sha256': 'd' * 64}],
                    'imports': sorted(['advapi32.dll', 'shell32.dll', 'ole32.dll', 'comctl32.dll',
                                       'user32.dll', 'gdi32.dll', 'kernel32.dll'])})
        if folder == 'macos':
            manifest.update(kind='dual-runtime-dmg', architecture='dual-runtime-arm64-x86_64',
                            frozenSmokeTest='passed-on-arm64-and-x86_64', nativeSmokeTests={}, runtimeStages={})
            for arch in ('arm64', 'x86_64'):
                png = f'smoke-macos-{arch}.png'
                (path / png).write_bytes(b'TEST PNG')
                png_hash = hashlib.sha256(b'TEST PNG').hexdigest()
                manifest['files'][png] = png_hash
                manifest['nativeSmokeTests'][arch] = dict(architecture=arch, artifact=png, sha256=png_hash,
                    files={png: png_hash}, packageSha256=sha, builderSourceSha256=SOURCE,
                    frozenSmokeTest='passed', nativePlatformSmoke='cocoa-passed',
                    scope='combined-dmg-launcher-native-runtime-and-ui')
                manifest['runtimeStages'][arch] = dict(architecture=arch, system='Darwin',
                    kind='internal-runtime-stage', builderSourceSha256=SOURCE, frozenSmokeTest='passed',
                    nativeCoverage='exact-payload-evidence-verified')
        (path / f'OpenAutoLink-Builder-{suffix}.json').write_text(json.dumps(manifest))
    return root


def test_exact_three_downloads_and_source_binding(tmp_path):
    m = module()
    assets = m.verify_downloads(fixture(tmp_path), SOURCE)
    assert [a.path.name for a in assets] == ['OpenAutoLink-Builder.exe', 'OpenAutoLink-Builder', 'OpenAutoLink-Builder.dmg']
    with pytest.raises(ValueError):
        m.verify_downloads(tmp_path, 'c' * 64)
    assets[0].path.write_bytes(b'transport tamper')
    with pytest.raises(ValueError):
        m.verify_downloads(tmp_path, SOURCE)


@pytest.mark.parametrize('folder,field,value', [
    ('windows', 'nativePlatformSmoke', None), ('windows', 'nativeCoverage', 'blocked'),
    ('linux', 'nativePlatformSmoke', 'offscreen'), ('macos', 'nativeSmokeTests', {}),
    ('macos', 'frozenSmokeTest', 'pending-combined-native-gates'),
    ('macos', 'runtimeStages', {}), ('macos', 'builderSourceSha256', 'f' * 64)])
def test_fail_closed_gates(tmp_path, folder, field, value):
    m = module()
    fixture(tmp_path)
    path = next((tmp_path / folder).glob('*.json'))
    data = json.loads(path.read_text())
    data[field] = value
    path.write_text(json.dumps(data))
    with pytest.raises(ValueError):
        m.verify_downloads(tmp_path, SOURCE)


@pytest.mark.parametrize('field', ['bootstrap', 'bootstrapExtraction', 'innerSha256',
                                   'embeddedPayload', 'bootstrapLoader', 'runtimeInstaller'])
def test_windows_requires_outer_bootstrap_evidence(tmp_path, field):
    fixture(tmp_path)
    path = tmp_path / 'windows/OpenAutoLink-Builder-windows-x86_64.json'
    manifest = json.loads(path.read_text())
    del manifest[field]
    path.write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match='bootstrap'):
        module().verify_downloads(tmp_path, SOURCE)


@pytest.mark.parametrize('mutation', ['inner', 'extra', 'digest', 'stub', 'imports'])
def test_windows_rejects_inconsistent_outer_evidence(tmp_path, mutation):
    fixture(tmp_path)
    path = tmp_path / 'windows/OpenAutoLink-Builder-windows-x86_64.json'
    m = json.loads(path.read_text())
    if mutation == 'inner': m['innerSha256'] = 'e' * 64
    if mutation == 'extra': m['embeddedPayload']['vc_redist.exe'] = 'f' * 64
    if mutation == 'digest': m['embeddedPayload']['bootstrap.ps1'] = 'bad'
    if mutation == 'stub': m['bootstrapLoader']['supplierStubSha256'] = 'f' * 64
    if mutation == 'imports': m['bootstrapLoader']['imports'].append('vcruntime140.dll')
    path.write_text(json.dumps(m))
    with pytest.raises(ValueError, match='bootstrap'):
        module().verify_downloads(tmp_path, SOURCE)


def event():
    return {'action': 'published', 'repository': {'full_name': 'owner/repo', 'private': False},
            'release': {'id': 12, 'tag_name': 'v0.1.600', 'draft': False, 'prerelease': False}}


class FakeAPI:
    def __init__(self):
        self.release = event()['release']
        self.commit = SHA
        self.uploads = []
        self.assets = []
        self.data = {}

    def get(self, path):
        if path.startswith('git/ref/tags/'):
            return {'object': {'type': 'commit', 'sha': self.commit}}
        if path.startswith('releases/12/assets'):
            return copy.deepcopy(self.assets)
        if path.startswith('releases/'):
            return copy.deepcopy(self.release)
        raise AssertionError(path)

    def upload(self, release_id, path):
        assert release_id == 12
        self.uploads.append(path.name)
        data = path.read_bytes()
        asset = {'id': len(self.assets) + 1, 'name': path.name, 'size': len(data),
                 'state': 'uploaded', 'digest': 'sha256:' + hashlib.sha256(data).hexdigest()}
        self.assets.append(asset)
        self.data[asset['id']] = data

    def download(self, asset):
        return self.data[asset['id']]


def test_payload_mutated_after_verification_is_never_uploaded(tmp_path):
    m = module()
    api = FakeAPI()
    assets = m.verify_downloads(fixture(tmp_path), SOURCE)
    (tmp_path / 'windows/OpenAutoLink-Builder.exe').write_bytes(b'changed-after-verification')
    with pytest.raises(ValueError):
        m.publish(api, event(), 'owner/repo', SHA, assets)
    assert api.uploads == []


def test_publication_uploads_only_three_and_reads_back(tmp_path):
    m = module()
    api = FakeAPI()
    assets = m.verify_downloads(fixture(tmp_path), SOURCE)
    m.publish(api, event(), 'owner/repo', SHA, assets)
    assert api.uploads == [a.path.name for a in assets]


@pytest.mark.parametrize('bad', ['pr', 'draft', 'tag-moved', 'wrong-id', 'existing', 'repo'])
def test_no_write_on_wrong_release_or_moved_tag(tmp_path, bad):
    m = module()
    api, payload = FakeAPI(), event()
    if bad == 'pr': payload['action'] = 'opened'
    if bad == 'draft': api.release['draft'] = True
    if bad == 'tag-moved': api.commit = 'c' * 40
    if bad == 'wrong-id': api.release['id'] = 13
    if bad == 'existing': api.assets = [{'name': 'OpenAutoLink-Builder.exe'}]
    if bad == 'repo': payload['repository']['full_name'] = 'other/repo'
    with pytest.raises(ValueError):
        m.publish(api, payload, 'owner/repo', SHA, m.verify_downloads(fixture(tmp_path), SOURCE))
    assert api.uploads == []


def test_clean_interpreter_runs_full_offline_verification(tmp_path):
    import subprocess
    import sys
    m = module()
    fixture(tmp_path)
    archive = tmp_path / 'source.zip'
    m.build.source_archive(archive)
    source = m.build.sha256(archive)
    for manifest in tmp_path.glob('*/*.json'):
        manifest.write_text(manifest.read_text().replace(SOURCE, source))
    result = subprocess.run([sys.executable, '-I', '-S', str(SPEC), '--downloads', str(tmp_path), '--verify-only'],
                            capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    assert 'Verified three exact downloads' in result.stdout


def test_readback_corruption_is_not_success(tmp_path):
    m = module()
    api = FakeAPI()
    api.download = lambda asset: b'corrupt'
    with pytest.raises(ValueError):
        m.publish(api, event(), 'owner/repo', SHA, m.verify_downloads(fixture(tmp_path), SOURCE))

"""Ubuntu CI coverage: exact payloads, not version/glob approval."""
import hashlib
import importlib.util
import json
import platform
import re
import subprocess
import sys
from pathlib import Path

import pytest

BASE = Path(__file__).resolve().parents[1] / 'packaging'
NATIVE_LINUX = pytest.mark.skipif(
    platform.system() != 'Linux' or platform.machine().lower() not in {'x86_64', 'amd64'},
    reason='The generic native gate correctly rejects a different platform')


def load_module():
    path = BASE / 'coverage_linux_ci.py'
    assert path.is_file(), 'Ubuntu coverage generator is not implemented'
    spec = importlib.util.spec_from_file_location('coverage_linux_ci', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_exact_payload_required():
    module = load_module()
    payload = [{'path': 'libfoo.so', 'sha256': 'a' * 64, 'kind': 'binary', 'size': 12}]
    review = {'schema': 2, 'system': 'Linux', 'architecture': 'x86_64',
              'components': [{'id': 'foo', 'payload': payload}]}
    assert module.check_payload({'payload': payload}, review) == payload
    for changed in ([], payload + payload, [{**payload[0], 'sha256': 'b' * 64}],
                    [{**payload[0], 'path': 'other.so'}]):
        with pytest.raises(ValueError, match='payload'):
            module.check_payload({'payload': changed}, review)


@NATIVE_LINUX
def test_materialize_uses_real_generic_gate(tmp_path):
    module = load_module()
    notice = tmp_path / 'notice.txt'
    notice.write_text('Test-only MIT notice fixture, not supplier evidence.\n')
    payload = [{'path': 'libfoo.so', 'sha256': 'a' * 64, 'kind': 'binary', 'size': 12}]
    review = {'schema': 2, 'system': 'Linux', 'architecture': 'x86_64',
              'components': [{'id': 'foo', 'version': 'test', 'license': 'MIT',
                              'provenance': 'Unit test fixture, not approval',
                              'statically_linked': [], 'payload': payload,
                              'notices': [{'path': 'notice.txt',
                                           'sha256': hashlib.sha256(notice.read_bytes()).hexdigest()}]}]}
    result = module.materialize({'payload': payload}, review, tmp_path,
                                tmp_path / 'cache', tmp_path / 'output',
                                {'schema': 1, 'inputs': []}, offline=True)
    assert json.loads(result.read_text()) == review
    assert (result.parent / 'notice.txt').read_bytes() == notice.read_bytes()


@NATIVE_LINUX
def test_cli_accepts_reviewed_payload_without_prior_failed_freeze(tmp_path):
    # Current reviewed payload is the default; final build gate still compares
    # its independently audited freeze. Use a notice-only fixture for speed.
    (tmp_path / 'notice.txt').write_text('Test-only notice fixture\n')
    payload = [{'path': 'libfoo.so', 'sha256': 'a' * 64, 'kind': 'binary', 'size': 12}]
    review = {'schema': 2, 'system': 'Linux', 'architecture': 'x86_64',
              'components': [{'id': 'foo', 'version': 'test', 'license': 'MIT',
                              'provenance': 'Unit test fixture', 'payload': payload,
                              'statically_linked': [], 'notices': [{'path': 'notice.txt',
                              'sha256': hashlib.sha256((tmp_path / 'notice.txt').read_bytes()).hexdigest()}]}]}
    (tmp_path / 'review.json').write_text(json.dumps(review))
    (tmp_path / 'lock.json').write_text(json.dumps({'schema': 1, 'inputs': []}))
    result = subprocess.run([sys.executable, str(BASE / 'coverage_linux_ci.py'),
                             '--review', str(tmp_path / 'review.json'), '--base', str(tmp_path),
                             '--lock', str(tmp_path / 'lock.json'), '--cache', str(tmp_path / 'cache'),
                             '--output', str(tmp_path / 'result'), '--offline'],
                            capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    assert (tmp_path / 'result/coverage.json').is_file(), result.stdout


@pytest.mark.parametrize('field,value', [('kind', 'symlink'), ('size', 99), ('target', 'outside.so')])
def test_payload_metadata_drift_is_rejected(field, value):
    module = load_module()
    payload = [{'path': 'libfoo.so', 'sha256': 'a' * 64, 'kind': 'binary', 'size': 12}]
    review = {'schema': 2, 'system': 'Linux', 'architecture': 'x86_64',
              'components': [{'id': 'foo', 'payload': payload}]}
    with pytest.raises(ValueError, match='payload'):
        module.check_payload({'payload': [{**payload[0], field: value}]}, review)


def test_drift_does_not_start_acquisition(tmp_path):
    module = load_module()
    review = json.loads((BASE / 'provenance/linux-ci-reviewed.json').read_text())
    with pytest.raises(ValueError, match='payload'):
        module.materialize({'payload': []}, review, BASE, tmp_path / 'cache',
                           tmp_path / 'out', {'schema': 1, 'inputs': []}, offline=True)
    assert not (tmp_path / 'cache').exists()
    assert not (tmp_path / 'out').exists()


@NATIVE_LINUX
def test_unknown_license_is_not_approved_by_generator(tmp_path):
    module = load_module()
    (tmp_path / 'notice').write_text('Fixture notice\n')
    payload = [{'path': 'fixture', 'sha256': 'a' * 64}]
    review = {'schema': 2, 'system': 'Linux', 'architecture': 'x86_64',
              'components': [{'id': 'fixture', 'version': '1', 'license': 'LicenseRef-NotReviewed',
                              'provenance': 'Test fixture', 'payload': payload, 'statically_linked': [],
                              'notices': [{'path': 'notice', 'sha256': hashlib.sha256(
                                  (tmp_path / 'notice').read_bytes()).hexdigest()}]}]}
    with pytest.raises(ValueError, match='Unsupported native license'):
        module.materialize({'payload': payload}, review, tmp_path, tmp_path / 'cache',
                           tmp_path / 'out', {'schema': 1, 'inputs': []}, offline=True)
    assert not (tmp_path / 'out').exists()


def test_reviewed_assets_static_edges_and_source_locks_are_complete():
    review = json.loads((BASE / 'provenance/linux-ci-reviewed.json').read_text())
    lock = json.loads((BASE / 'provenance/linux-ci/ubuntu-sources.lock.json').read_text())
    inputs = {e['path']: e for e in lock['inputs']}
    assert len(inputs) == len(lock['inputs'])
    components = {c['id']: c for c in review['components']}
    assert len(components) == len(review['components'])
    payload = [e for c in components.values() for e in c['payload']]
    assert len(payload) == len({e['path'] for e in payload}) == 141
    assert not any(c.startswith('debian-') for c in components)
    for c in components.values():
        assert re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', c['id'])
        assert all(i in components and i != c['id'] for i in c['statically_linked'])
        for a in c['notices'] + ([c['build_instructions']] if c.get('build_instructions') else []):
            assert hashlib.sha256((BASE / a['path']).read_bytes()).hexdigest() == a['sha256']
        for a in c.get('sources', []):
            e = inputs[a['path'].removeprefix('sources/')]
            assert (e['sha256'], e['url']) == (a['sha256'], a['url'])
    # Ubuntu source archives, including all patch members, must match the exact
    # retained source descriptor; never quietly select a newer upstream source.
    for g in lock['groups'].values():
        dsc = (BASE / 'licenses/linux-ci' / g['descriptor']['path']).read_bytes()
        assert hashlib.sha256(dsc).hexdigest() == g['descriptor']['sha256']
        for a in g['archives']:
            assert re.search(r'^ ' + a['sha256'] + r' +\d+ +' + re.escape(a['path']) + r'$',
                             dsc.decode(), re.M)


def test_discovery_supplier_matches_cover_actual_binary_entries():
    review = json.loads((BASE / 'provenance/linux-ci-reviewed.json').read_text())
    suppliers = json.loads((BASE / 'provenance/linux-ci/supplier-matches.json').read_text())
    assert review['discovery_sha256'] == suppliers['discovery_sha256']
    matches = {(m['payload'], m['sha256']) for w in suppliers['wheels'] for m in w['nativeMatches']}
    for p in suppliers['linuxPackages'] + [suppliers['python']]:
        matches.update((path, n['sha256']) for n in p['nativeCandidates'] for path in n['matches'])
    entries = {e['path']: e for c in review['components'] for e in c['payload']}
    for e in entries.values():
        if e['kind'] == 'binary':
            assert (e['path'], e['sha256']) in matches
        elif e['kind'] == 'symlink':
            assert e['target'] in entries
            raw = e['target'].encode() + b'\0'
            assert len(raw) == e['size']
            assert hashlib.sha256(raw).hexdigest() == e['sha256']
        else:
            assert e['path'] == '@bootloader'
            old = json.loads((BASE / 'provenance/linux-reviewed.json').read_text())
            approved = next(x for c in old['components'] for x in c['payload'] if x['path'] == '@bootloader')
            assert e['sha256'] == approved['sha256']


THIRD_CI_UPDATES = {
    'libX11-xcb.so.1': ('ubuntu-libx11-xcb1', '2:1.7.5-1ubuntu0.4',
                      'd7f9e86161421311a1023c44295a88b4a00b6ecbd20c416fc71895f43e358a0a'),
    'libX11.so.6': ('ubuntu-libx11-6', '2:1.7.5-1ubuntu0.4',
                  'e23ed094eaf0a74a822a6d9d14eee628ab9f7ec08aecbf20ffd803f019ff6e98'),
    'libfreetype.so.6': ('ubuntu-libfreetype6', '2.11.1+dfsg-1ubuntu0.4',
                       'c338538f8d90eb85217e8525ab363e68e79b46d3c8acc0b6824190e032687e5f'),
    'libpng16.so.16': ('ubuntu-libpng16-16', '1.6.37-3ubuntu0.7',
                     '0c55d5776d3382cb7859ff48fcf813e0c3b27f33c46f9364633748830246c723'),
}


def canonical_digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def test_third_ci_exact_security_update_mappings():
    review = json.loads((BASE / 'provenance/linux-ci-reviewed.json').read_text())
    entries = {e['path']: (c, e) for c in review['components'] for e in c['payload']}
    for path, (component, version, sha256) in THIRD_CI_UPDATES.items():
        c, e = entries[path]
        assert (c['id'], c['version'], e['sha256']) == (component, version, sha256)


def test_third_ci_supplier_proof_preserves_other_mappings_and_source_obligations():
    module = load_module()
    review = json.loads((BASE / 'provenance/linux-ci-reviewed.json').read_text())
    evidence = json.loads((BASE / 'provenance/linux-ci/supplier-updates-run38953744570.json').read_text())
    assert evidence['identity']['runId'] == '38953744570'
    assert evidence['discovery_sha256'] == review['discovery_sha256']
    assert evidence['discovery_sha256'] == '654031e73e9e799604ac9c5cbe11a3c40499e1bc1a0d127b9ceef6b4dbe37b9e'
    payload = [e for c in review['components'] for e in c['payload']]
    assert canonical_digest(sorted(payload, key=lambda e: e['path'])) == evidence['payload_records_sha256']
    changed_ids = {v[0] for v in THIRD_CI_UPDATES.values()}
    unchanged = [c for c in review['components'] if c['id'] not in changed_ids]
    assert canonical_digest(unchanged) == evidence['unchanged_components_sha256']
    assert hashlib.sha256((BASE / 'provenance/linux-ci/ubuntu-sources.lock.json').read_bytes()).hexdigest() == evidence['retained_source_lock_sha256']
    approved = {e['path']: e for e in payload}
    updates = evidence['packages']
    assert len(updates) == len(THIRD_CI_UPDATES)
    assert {m['payload'] for p in updates for m in p['nativeMatches']} == set(THIRD_CI_UPDATES)
    assert module.check_payload({'payload': payload}, review) == payload
    for package in updates:
        assert package['version'] != package['previousVersion']
        assert package['sourceVersion'] == package['version']
        assert package['architecture'] == 'amd64'
        archive = package['archive']
        assert archive['url'].startswith('https://archive.ubuntu.com/ubuntu/pool/main/')
        assert re.fullmatch('[0-9a-f]{64}', archive['sha256'])
        assert archive['size'] > 0
        notice = package['notice']
        assert notice['unchanged'] is True
        assert hashlib.sha256((BASE / notice['path']).read_bytes()).hexdigest() == notice['sha256']
        for match in package['nativeMatches']:
            entry = approved[match['payload']]
            assert (match['sha256'], match['size']) == (entry['sha256'], entry['size'])
            assert match['member'] == match['symlinkChain'][-1]
            # Approval replaces only this reviewed version, not a wildcard or
            # a growing list that quietly accepts stale supplier bytes.
            old = [{**e, 'sha256': match['previousSha256']} if e['path'] == match['payload'] else e
                   for e in payload]
            with pytest.raises(ValueError, match='payload'):
                module.check_payload({'payload': old}, review)


def test_third_ci_official_deb_bytes_when_supplied():
    """Opt-in replay against downloaded artifacts, never substitute fixture bytes."""
    import io
    import os
    import tarfile

    cache = os.environ.get('OAL_LINUX_SUPPLIER_CACHE')
    if not cache:
        pytest.skip('Set OAL_LINUX_SUPPLIER_CACHE to replay the four official Ubuntu .deb files')
    evidence = json.loads((BASE / 'provenance/linux-ci/supplier-updates-run38953744570.json').read_text())
    for package in evidence['packages']:
        archive = package['archive']
        deb = Path(cache) / archive['url'].rsplit('/', 1)[-1]
        data = deb.read_bytes()
        assert (hashlib.sha256(data).hexdigest(), len(data)) == (archive['sha256'], archive['size'])
        control = subprocess.check_output(
            ['dpkg-deb', '-f', str(deb), 'Package', 'Version', 'Architecture', 'Source'], text=True)
        fields = dict(line.split(': ', 1) for line in control.splitlines())
        assert fields == {'Package': package['package'].split(':')[0], 'Version': package['version'],
                          'Architecture': package['architecture'], 'Source': package['sourcePackage']}
        data_tar = subprocess.check_output(['dpkg-deb', '--fsys-tarfile', str(deb)])
        with tarfile.open(fileobj=io.BytesIO(data_tar)) as tar:
            for match in package['nativeMatches']:
                chain = match['symlinkChain']
                for source, target in zip(chain, chain[1:]):
                    link = tar.getmember(source)
                    assert link.issym()
                    assert './' + str(Path(source).parent / link.linkname) == target
                member = tar.getmember(match['member'])
                assert member.isfile()
                blob = tar.extractfile(member).read()
                assert (hashlib.sha256(blob).hexdigest(), len(blob)) == (match['sha256'], match['size'])
            notice = package['notice']
            member = tar.getmember(notice['member'])
            assert member.isfile()
            blob = tar.extractfile(member).read()
            assert hashlib.sha256(blob).hexdigest() == notice['sha256']
            assert blob == (BASE / notice['path']).read_bytes()


def test_composite_routes_are_notice_bound_not_generic_aliases():
    policy = json.loads((BASE / 'provenance/linux-ci/license-policy.json').read_text())['scoped_notices']
    review = json.loads((BASE / 'provenance/linux-ci-reviewed.json').read_text())
    used = {c['license'] for c in review['components'] if c['license'].startswith('LicenseRef-Ubuntu-')}
    assert used == set(policy)
    for c in review['components']:
        if c['license'] not in policy:
            continue
        p = policy[c['license']]
        assert c['license_scope'] == p['license_scope']
        assert p['notice_sha256'] in {a['sha256'] for a in c['notices']}
        assert hashlib.sha256((BASE / p['notice_path']).read_bytes()).hexdigest() == p['notice_sha256']

"""Parser diagnostics, not native codesign or distribution qualification."""
import hashlib
import importlib.util
from pathlib import Path
import struct

import pytest

PATH = Path(__file__).resolve().parents[1] / 'packaging/macos_loader.py'


def module():
    assert PATH.exists(), 'Mac supplier diagnostic helper is missing'
    spec = importlib.util.spec_from_file_location('macos_loader', PATH)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def thin(uuid=b'u' * 16, code=b'actual-code', cpu=0x100000c):
    commands = struct.pack('<II16s', 0x1b, 24, uuid)
    return struct.pack('<8I', 0xfeedfacf, cpu, 0, 2, 1, len(commands), 0, 0) + commands + code


def test_uuid_comparison_requires_exact_pkg_derived_value_and_code():
    m = module()
    pkg = b'changed archive'
    original = thin()
    uuid = hashlib.sha1(pkg + b'u' * 16).digest()[:16]
    report = m.compare_loader(thin(uuid), original, pkg)
    assert report['uuidMatchesPkg'] is True
    assert report['nonMetadataBytesEqual'] is True
    assert report['status'] == 'UNQUALIFIED-DISCOVERY-ONLY'
    assert m.compare_loader(thin(b'x' * 16), original, pkg)['uuidMatchesPkg'] is False
    assert m.compare_loader(thin(uuid, b'alteredcode'), original, pkg)['nonMetadataBytesEqual'] is False


def test_sdk_and_linkedit_changes_remain_visible_not_approved():
    m = module()
    sdk = struct.pack('<4I', 0x24, 16, 0x0a0d00, 0x0e0500)
    raw = struct.pack('<8I', 0xfeedfacf, 0x1000007, 3, 2, 1, len(sdk), 0, 0) + sdk
    fields = m.parse_thin(raw)['commands'][0]['fields']
    assert fields == {'version': 0x0a0d00, 'sdk': 0x0e0500}
    changed = raw[:-4] + struct.pack('<I', 0x0c0100)
    assert m.compare_loader(changed, raw, b'pkg')['nonMetadataBytesEqual'] is False


def test_section_code_hashes_detect_mutation_without_normalization():
    m = module()
    offset = 32 + 72 + 80
    code = b'CODE'
    section = struct.pack('<16s16sQQ8I', b'__text', b'__TEXT', 0x1000, len(code), offset, 2, 0, 0, 0x80000400, 0, 0, 0)
    segment = struct.pack('<II16sQQQQiiII', 0x19, 152, b'__TEXT', 0, 4096, 0, offset + len(code), 5, 5, 1, 0)
    raw = struct.pack('<8I', 0xfeedfacf, 0x1000007, 3, 2, 1, 152, 0, 0) + segment + section + code
    report = m.compare_loader(raw, raw, b'pkg')
    assert report['sectionPayloadsEqual'] is True
    assert report['candidate']['commands'][0]['sections'][0]['sha256'] == hashlib.sha256(code).hexdigest()
    assert m.compare_loader(raw[:-1] + b'X', raw, b'pkg')['sectionPayloadsEqual'] is False
    with pytest.raises(ValueError):
        m.parse_thin(raw[:-1])


def test_diagnostics_do_not_dump_code_or_paths():
    m = module()
    import json
    report = m.compare_loader(thin(code=b'/secret/path'), thin(), b'pkg')
    encoded = json.dumps(report)
    assert '/secret' not in encoded
    assert 'actual-code' not in encoded
    assert report['nonMetadataBytesEqual'] is False


@pytest.mark.parametrize('data', [b'', thin()[:35], thin()[:8] + b'\xff' * 24, b'MZ' * 40])
def test_malformed_macho_rejected(data):
    with pytest.raises(ValueError):
        module().parse_thin(data)


def test_diagnose_binds_candidate_hash_and_sanitizes_failures(tmp_path, monkeypatch):
    import json
    import zipfile
    from PyInstaller.archive.writers import CArchiveWriter
    m = module()
    monkeypatch.setattr(m, 'BASE', tmp_path)
    monkeypatch.setenv('RUN_ID', '/private/do-not-export')
    lock = tmp_path / 'provenance/macos-ci/supplier-inputs.lock.json'
    lock.parent.mkdir(parents=True)
    wheel = tmp_path / m.WHEEL
    with zipfile.ZipFile(wheel, 'w') as archive:
        archive.writestr(m.MEMBER, thin())
    lock.write_text(json.dumps({'inputs': [{'path': m.WHEEL, 'sha256': m.digest(wheel.read_bytes())}]}))
    pkg = tmp_path / 'fixture.pkg'
    CArchiveWriter(str(pkg), [], 'Python')
    pkg_bytes = pkg.read_bytes()
    uuid = hashlib.sha1(pkg_bytes + b'u' * 16).digest()[:16]
    candidate = tmp_path / 'private-candidate'
    candidate.write_bytes(thin(uuid) + pkg_bytes)
    discovery = {'artifactSha256': m.digest(candidate.read_bytes()), 'builderSourceSha256': 'b' * 64}
    output = tmp_path / 'report.json'
    result = m.diagnose(candidate, tmp_path, output, discovery)
    assert result['comparison']['uuidMatchesPkg'] is True
    assert 'runId' not in result
    assert 'private-candidate' not in output.read_text()
    candidate.write_bytes(thin(uuid, b'alteredcode') + pkg_bytes)
    assert m.diagnose(candidate, tmp_path, output, discovery)['diagnosticError'] == 'MAC_LOADER_DIAGNOSTIC_UNAVAILABLE'
    candidate.write_bytes(b'not an archive')
    discovery['artifactSha256'] = m.digest(candidate.read_bytes())
    assert m.diagnose(candidate, tmp_path, output, discovery)['diagnosticError'] == 'MAC_LOADER_DIAGNOSTIC_UNAVAILABLE'


def official_supplier(cpu):
    """Offline real-wheel replay, never a claim of native codesign execution."""
    import os
    import zipfile
    wheel = os.environ.get('OAL_MACOS_SUPPLIER_WHEEL')
    if not wheel:
        pytest.skip('Set OAL_MACOS_SUPPLIER_WHEEL for pinned official-wheel replay')
    m = module()
    assert m.digest(Path(wheel).read_bytes()) == 'aa404f0b02cd57948098055e76ee190b8e65ccf7a2a3f048e5000f668317069f'
    with zipfile.ZipFile(wheel) as archive:
        return m.supplier_slice(archive.read(m.MEMBER), cpu)


def replay(supplier, pkg, sign_size=4096):
    """Replay observed native metadata on real supplier; signature is a placeholder.

    Signature verification belongs to the native wrapper, not this byte comparator.
    """
    m = module()
    parsed = m.parse_thin(supplier)
    sig = next((c for c in parsed['commands'] if c['command'] == 0x1d), None)
    prefix_size = sig['fields']['dataoff'] if sig else len(supplier)
    data = bytearray(supplier[:prefix_size])
    pkg_end = prefix_size + len(pkg)
    sign_offset = (pkg_end + 15) // 16 * 16
    total = sign_offset + sign_size
    if not sig:
        offset = parsed['commandEnd']
        assert data[offset:offset + 16] == bytes(16)
        struct.pack_into('<II', data, 16, parsed['header'][4] + 1, parsed['header'][5] + 16)
        sig = {'offset': offset}
    struct.pack_into('<4I', data, sig['offset'], 0x1d, 16, sign_offset, sign_size)
    for command in parsed['commands']:
        offset, kind = command['offset'], command['command']
        if kind == 0x1b:
            data[offset + 8:offset + 24] = hashlib.sha1(pkg + supplier[offset + 8:offset + 24]).digest()[:16]
        elif kind == 0x19 and supplier[offset + 8:offset + 24].rstrip(b'\0') == b'__LINKEDIT':
            size = total - command['fields']['fileoff']
            struct.pack_into('<Q', data, offset + 32, (size + 16383) // 16384 * 16384)
            struct.pack_into('<Q', data, offset + 48, size)
        elif kind == 2:
            struct.pack_into('<I', data, offset + 20, pkg_end - command['fields']['stroff'])
        elif kind in (0x24, 0x32):
            struct.pack_into('<I', data, offset + (12 if kind == 0x24 else 16), 0x0c0100)
    return bytes(data) + pkg + bytes(sign_offset - pkg_end) + bytes(sign_size), prefix_size, pkg_end


@pytest.mark.parametrize('cpu', [0x1000007, 0x100000c])
def test_official_supplier_permits_only_causal_metadata(cpu):
    m = module()
    supplier = official_supplier(cpu)
    candidate, start, end = replay(supplier, b'app-specific-pkg!')
    assert hasattr(m, 'validate_transformation'), 'Missing strict supplier comparison'
    result = m.validate_transformation(candidate, supplier, start, end, python_sdk=0x0c0100)
    assert result['supplierSliceSha256'] == m.digest(supplier)
    assert result['candidatePrefixSha256'] == m.digest(candidate[:start])
    assert result['comparisonScope'] == 'all-loader-bytes-with-exact-packaging-rewrites'
    # Code, inter-section padding, protected headers, UUID, all size fields and
    # signature alignment padding remain rejection points, not blanket masks.
    commands = m.parse_thin(candidate)['commands']
    offsets = [4, 24, 3000, start - 1, end]
    for command in commands:
        offset, kind = command['offset'], command['command']
        offsets.append(offset)  # unknown/changed command types are never ignored
        offsets.extend(section['offset'] + section['size'] // 2
                       for section in command['sections'] if section['sha256'] and section['size'])
        if kind == 0x1b:
            offsets.append(offset + 8)
        elif kind == 0x19:
            offsets.extend([offset + 24, offset + 32, offset + 48, offset + 56])
        elif kind == 2:
            offsets.extend([offset + 8, offset + 20])
        elif kind == 0x1d:
            offsets.extend([offset + 8, offset + 12])
        elif kind in (0x24, 0x32):
            offsets.append(offset + (12 if kind == 0x24 else 16))
    for offset in offsets:
        damaged = bytearray(candidate)
        damaged[offset] ^= 1
        with pytest.raises(ValueError):
            m.validate_transformation(bytes(damaged), supplier, start, end, python_sdk=0x0c0100)
    with pytest.raises(ValueError):
        m.validate_transformation(candidate + b'unsigned-tail', supplier, start, end, python_sdk=0x0c0100)


@pytest.mark.parametrize(('arch', 'cpu', 'sha'), [
    ('arm', 0x100000c, '0ecb8bbebe2a046335d473e5303bda3979a6503109e111e8df59b8e73f5ca3d8'),
    ('intel', 0x1000007, '253c146be11279300d9933b08f9f0b7ca8c233d4545053a69be0b8d05cda5c52'),
])
def test_native_diagnostic_metadata_matches_official_wheel_replay(arch, cpu, sha):
    import os
    import json
    root = os.environ.get('OAL_MACOS_LOADER_DIAGNOSTICS_DIR')
    if not root:
        pytest.skip('Set OAL_MACOS_LOADER_DIAGNOSTICS_DIR for retained native evidence replay')
    m = module()
    raw = (Path(root) / f'mac-{arch}-macos-loader-review.json').read_bytes()
    assert m.digest(raw) == sha
    report = json.loads(raw)
    assert report['commit'] == '7dc7a434a90a59849bc62c7aa84839ab9348af44'
    native = report['comparison']
    supplier = official_supplier(cpu)
    assert m.digest(supplier) == native['supplierSliceSha256']
    assert m.parse_thin(supplier) == native['supplier']
    symtab = next(c for c in native['candidate']['commands'] if c['command'] == 2)['fields']
    pkg_size = symtab['stroff'] + symtab['strsize'] - native['candidatePrefixSize']
    signature = native['candidate']['commands'][-1]['fields']
    # Actual PKG and signature bytes were NOT retained. Use explicitly synthetic
    # placeholders and never call this a reconstruction or codesign verification.
    data, start, end = replay(supplier, bytes(pkg_size), signature['datasize'])
    m.validate_transformation(data, supplier, start, end, python_sdk=0x0c0100)
    parsed = m.parse_thin(data[:start])
    assert parsed['header'] == native['candidate']['header']
    assert len(parsed['commands']) == len(native['candidate']['commands'])
    for actual, expected in zip(parsed['commands'], native['candidate']['commands']):
        if actual['command'] != 0x1b:  # UUID alone depends on unavailable PKG bytes.
            assert actual == expected
    differences = m.compare_loader(data[:start], supplier, data[start:end])
    assert differences['differenceCount'] == native['differenceCount']
    assert differences['firstDifferenceOffsets'] == native['firstDifferenceOffsets']
    assert native['uuidMatchesPkg'] and native['sectionPayloadsEqual']


def test_live_verifier_requires_native_codesign_and_external_compliance(tmp_path, monkeypatch):
    import os
    import subprocess
    from PyInstaller.archive.writers import CArchiveWriter
    m = module()
    supplier = official_supplier(0x100000c)
    pkg_path = tmp_path / 'fixture.pkg'
    CArchiveWriter(str(pkg_path), [], 'Python')
    data, start, end = replay(supplier, pkg_path.read_bytes())
    candidate = tmp_path / 'candidate'
    candidate.write_bytes(data)
    internal = tmp_path / '_internal'
    internal.mkdir()
    (internal / 'builder-compliance.zip').write_bytes(b'initial compliance')
    python = internal / 'Python.framework/Versions/3.12/Python'
    python.parent.mkdir(parents=True)
    python_bytes = bytearray(supplier)
    version = next(c for c in m.parse_thin(supplier)['commands'] if c['command'] == 0x32)
    struct.pack_into('<I', python_bytes, version['offset'] + 16, 0x0c0100)
    python.write_bytes(python_bytes)
    assert hasattr(m, 'verify_candidate'), 'Missing native verification gate'
    cache = Path(os.environ['OAL_MACOS_SUPPLIER_WHEEL']).parent
    monkeypatch.setattr(m.platform, 'system', lambda: 'Linux')
    with pytest.raises(ValueError, match='native macOS'):
        m.verify_candidate(candidate, cache)
    monkeypatch.setattr(m.platform, 'system', lambda: 'Darwin')
    calls = []
    def codesign(argv, **kwargs):
        calls.append((argv, kwargs))
        return subprocess.CompletedProcess(argv, 0)
    monkeypatch.setattr(m.subprocess, 'run', codesign)
    result = m.verify_candidate(candidate, cache)
    assert result['codesignVerified'] is True
    assert calls[0][0] == ['/usr/bin/codesign', '--verify', '--strict', '--all-architectures', str(candidate)]
    assert calls[0][1]['check'] is True
    (internal / 'builder-compliance.zip').write_bytes(b'larger inventory and coverage evidence' * 100)
    assert m.verify_candidate(candidate, cache) == result  # no evidence/PKG hash self-reference
    def failed(*args, **kwargs):
        raise subprocess.CalledProcessError(1, args[0])
    monkeypatch.setattr(m.subprocess, 'run', failed)
    with pytest.raises(ValueError, match='signature verification'):
        m.verify_candidate(candidate, cache)
    monkeypatch.setattr(m.subprocess, 'run', codesign)
    # No pass may hide a recursive evidence ZIP inside PKG.
    CArchiveWriter(str(pkg_path), [('builder-compliance.zip', str(internal / 'builder-compliance.zip'), False, 'x')], 'Python')
    recursive, _, _ = replay(supplier, pkg_path.read_bytes())
    candidate.write_bytes(recursive)
    with pytest.raises(ValueError, match='external to PKG'):
        m.verify_candidate(candidate, cache)
    candidate.write_bytes(data)
    def changed_during_verification(argv, **kwargs):
        candidate.write_bytes(data[:-1] + b'X')
        return subprocess.CompletedProcess(argv, 0)
    monkeypatch.setattr(m.subprocess, 'run', changed_during_verification)
    with pytest.raises(ValueError, match='changed during'):
        m.verify_candidate(candidate, cache)
    bad_cache = tmp_path / 'bad-cache'
    bad_cache.mkdir()
    (bad_cache / m.WHEEL).write_bytes(b'untrusted supplier')
    with pytest.raises(ValueError, match='Supplier wheel mismatch'):
        m.verify_candidate(candidate, bad_cache)


def test_dynamic_coverage_changes_only_verified_loader_and_keeps_raw_hashes(monkeypatch):
    import copy
    m = module()
    runtime = {'path': 'Python.framework/Versions/3.12/Python', 'sha256': 'b' * 64, 'size': 100, 'kind': 'binary'}
    old_loader = {'path': '@bootloader', 'sha256': 'a' * 64, 'size': 89152, 'kind': 'bootloader', 'hash_scope': 'prefix-bytes'}
    new_loader = {**old_loader, 'sha256': 'c' * 64}
    coverage = {'schema': 2, 'system': 'Darwin', 'architecture': 'arm64', 'components': [
        {'id': 'python', 'payload': [runtime]},
        {'id': 'pyinstaller-embedded-loader', 'version': '6.13.0',
         'license': 'GPL-2.0-or-later WITH Bootloader-exception', 'payload': [old_loader],
         'notices': [{'path': 'unchanged-notice', 'sha256': 'e' * 64}]}]}
    discovery = {'artifactSha256': 'd' * 64, 'payload': [runtime, new_loader]}
    proof = {'artifactSha256': 'd' * 64, 'candidatePrefixSha256': 'c' * 64,
             'candidatePrefixSize': 89152, 'pythonLibrarySha256': 'b' * 64, 'codesignVerified': True}
    assert hasattr(m, 'candidate_coverage'), 'Missing dynamic evidence assignment'
    monkeypatch.setattr(m, 'verify_candidate', lambda *args: proof)
    original = copy.deepcopy(coverage)
    result = m.candidate_coverage(Path('live-candidate'), Path('cache'), discovery, coverage)
    assert coverage == original
    assert result['components'][0] == original['components'][0]
    assert result['components'][1]['notices'] == original['components'][1]['notices']
    assert result['components'][1]['payload'] == [new_loader]
    assert result['macos_loader_evidence'] == proof
    for key, value in [('sha256', 'f' * 64), ('size', 101), ('kind', 'bootloader')]:
        changed = copy.deepcopy(discovery)
        changed['payload'][0][key] = value
        with pytest.raises(ValueError):
            m.candidate_coverage(Path('live-candidate'), Path('cache'), changed, coverage)
    changed = copy.deepcopy(discovery)
    changed['artifactSha256'] = 'e' * 64
    with pytest.raises(ValueError):
        m.candidate_coverage(Path('live-candidate'), Path('cache'), changed, coverage)
    changed = copy.deepcopy(discovery)
    changed['payload'][1]['hash_scope'] = 'masked-code'
    with pytest.raises(ValueError):
        m.candidate_coverage(Path('live-candidate'), Path('cache'), changed, coverage)


def test_select_exact_supplier_architecture():
    m = module()
    arm, intel = thin(), thin(cpu=0x1000007)
    fat = struct.pack('>II', 0xcafebabe, 2)
    fat += struct.pack('>5I', 0x100000c, 0, 48, len(arm), 0)
    fat += struct.pack('>5I', 0x1000007, 0, 48 + len(arm), len(intel), 0)
    assert m.supplier_slice(fat + arm + intel, 0x100000c) == arm
    with pytest.raises(ValueError):
        m.supplier_slice(fat + arm + intel, 42)

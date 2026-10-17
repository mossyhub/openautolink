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


def test_select_exact_supplier_architecture():
    m = module()
    arm, intel = thin(), thin(cpu=0x1000007)
    fat = struct.pack('>II', 0xcafebabe, 2)
    fat += struct.pack('>5I', 0x100000c, 0, 48, len(arm), 0)
    fat += struct.pack('>5I', 0x1000007, 0, 48 + len(arm), len(intel), 0)
    assert m.supplier_slice(fat + arm + intel, 0x100000c) == arm
    with pytest.raises(ValueError):
        m.supplier_slice(fat + arm + intel, 42)

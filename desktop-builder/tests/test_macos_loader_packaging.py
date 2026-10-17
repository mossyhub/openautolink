"""Mac-only orchestration tests; not native freezing or codesign evidence."""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import zipfile

import pytest

ROOT = Path(__file__).resolve().parents[1]


def build_module():
    spec = importlib.util.spec_from_file_location('mac_loader_build', ROOT / 'packaging/build.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_mac_main_rechecks_supplier_after_second_freeze(tmp_path, monkeypatch):
    b = build_module()
    calls = []
    monkeypatch.setattr(b, 'ROOT', tmp_path / 'desktop-builder')
    b.ROOT.mkdir()
    monkeypatch.setattr(b, 'platform', SimpleNamespace(system=lambda: 'Darwin', machine=lambda: 'arm64', python_version=lambda: '3.12.10'))
    def compliance(work):
        with zipfile.ZipFile(work / 'builder-source.zip', 'w') as z:
            z.writestr('desktop-builder/oal_builder/__main__.py', '# snapshot')
        path = work / 'compliance.zip'
        path.write_bytes(b'initial evidence')
        return path
    monkeypatch.setattr(b, 'compliance_archive', compliance)
    monkeypatch.setattr(b, 'runtime_data', lambda *args: [])
    def run(*argv):
        if 'PyInstaller' in argv:
            calls.append('freeze')
            stage = Path(argv[argv.index('--distpath') + 1]) / b.NAME
            stage.mkdir(parents=True, exist_ok=True)
            (stage / b.NAME).write_bytes(b'unchanged onedir EXE')
    monkeypatch.setattr(b, 'run', run)
    monkeypatch.setattr(b, 'verify_embedded_compliance', lambda *args: calls.append('embedded'))
    monkeypatch.setattr(b, 'native_inventory', lambda *args: [])
    monkeypatch.setattr(b, 'macos_loader_diagnostics', lambda *args: calls.append('diagnostic'))
    proof = {'artifactSha256': 'same-live-exe'}
    coverage = tmp_path / 'coverage.json'
    coverage.write_text(json.dumps({'macos_loader_evidence': proof}))
    assert hasattr(b, 'macos_candidate_coverage'), 'Missing Mac dynamic coverage integration'
    def first(*args):
        calls.append('first-live-verification')
        return coverage
    monkeypatch.setattr(b, 'macos_candidate_coverage', first)
    def second(*args):
        calls.append('second-live-verification')
        return proof
    monkeypatch.setattr(b, 'macos_support', lambda: SimpleNamespace(verify_candidate=second))
    monkeypatch.setattr(b, 'add_native_compliance', lambda *args: calls.append('attach-evidence'))
    monkeypatch.setattr(b, 'verify_native_compliance', lambda *args: calls.append('final-inventory'))
    monkeypatch.setattr(b, 'smoke_artifact', lambda exe, shot, source: shot.write_bytes(b'unit smoke'))
    monkeypatch.setattr(b, 'write_manifest', lambda *args, **kwargs: None)
    assert b.main(['--output', str(tmp_path / 'out'), '--macos-ci-cache', str(tmp_path / 'cache'),
                   '--native-coverage', str(coverage)]) == 0
    assert calls.count('freeze') == 2
    assert calls.index('first-live-verification') < calls.index('attach-evidence')
    assert calls.index('attach-evidence') < calls.index('second-live-verification') < calls.index('final-inventory')
    assert calls.index('second-live-verification') > max(i for i, c in enumerate(calls) if c == 'freeze')
    # The live second report cannot be substituted with the first report.
    monkeypatch.setattr(b, 'macos_support', lambda: SimpleNamespace(verify_candidate=lambda *args: {'artifactSha256': 'drift'}))
    with pytest.raises(ValueError, match='second freeze'):
        b.main(['--output', str(tmp_path / 'out-drift'), '--macos-ci-cache', str(tmp_path / 'cache'),
                '--native-coverage', str(coverage)])
    assert not (tmp_path / 'out-drift' / f'{b.NAME}-macos-arm64.runtime.tar.gz').exists()


def test_mac_dynamic_coverage_copies_only_hash_verified_assets(tmp_path, monkeypatch):
    b = build_module()
    monkeypatch.setattr(b, 'platform', SimpleNamespace(system=lambda: 'Darwin', machine=lambda: 'arm64'))
    base = tmp_path / 'input'
    base.mkdir()
    asset = base / 'notice.txt'
    asset.write_bytes(b'Exact supplier notice\r\n')
    review = {'components': [{'notices': [{'path': asset.name, 'sha256': b.sha256(asset)}]}]}
    coverage = base / 'coverage.json'
    coverage.write_text(json.dumps(review))
    verified = {**review, 'macos_loader_evidence': {'artifactSha256': 'proof'}}
    assert hasattr(b, 'macos_candidate_coverage'), 'Missing Mac dynamic coverage integration'
    monkeypatch.setattr(b, 'macos_support', lambda: SimpleNamespace(candidate_coverage=lambda *args: verified))
    validations = []
    monkeypatch.setattr(b, 'validate_native_coverage', lambda *args: validations.append(args))
    work = tmp_path / 'work'
    work.mkdir()
    result = b.macos_candidate_coverage(Path('candidate'), {'payload': []}, Path('cache'), work, coverage)
    assert json.loads(result.read_text()) == verified
    assert (result.parent / asset.name).read_bytes() == asset.read_bytes()
    assert json.loads(coverage.read_text()) == review
    assert validations
    asset.write_bytes(b'changed')
    with pytest.raises(ValueError, match='changed native evidence'):
        b.macos_candidate_coverage(Path('candidate'), {'payload': []}, Path('cache'), work, coverage)

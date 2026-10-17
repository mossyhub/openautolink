"""The actual frozen runtime must contain its Java transformer, not just source ZIP."""
import importlib.util
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('packaging_personalization', ROOT / 'packaging/build.py')
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


def test_source_snapshot_includes_exact_personalization_helper(tmp_path):
    archive = tmp_path / 'source.zip'
    build.source_archive(archive)
    with zipfile.ZipFile(archive) as z:
        member = 'desktop-builder/oal_builder/resources/ProtoPersonalizer.java'
        assert member in z.namelist()
        assert z.read(member) == (ROOT / 'oal_builder/resources/ProtoPersonalizer.java').read_bytes()


def test_windows_spec_bundles_helper_as_runtime_data(tmp_path, monkeypatch):
    import PyInstaller.building.makespec
    captured = {}
    def fake_makespec(scripts, **kwargs):
        captured.update(kwargs)
        path = tmp_path / 'test.spec'
        path.write_text('a = None\npyz = PYZ(a.pure)\n')
        return str(path)
    monkeypatch.setattr(PyInstaller.building.makespec, 'main', fake_makespec)
    build.windows_spec(ROOT, tmp_path, tmp_path / 'compliance.zip')
    assert (str(ROOT / 'oal_builder/resources/ProtoPersonalizer.java'), 'oal_builder/resources') in captured['datas']


def test_nonwindows_command_bundles_same_helper():
    import ast
    tree = ast.parse((ROOT / 'packaging/build.py').read_text())
    # Both CLI PyInstaller and Windows-spec paths must use the same source file.
    calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call)
             and isinstance(node.func, ast.Name) and node.func.id == 'runtime_data']
    assert len(calls) >= 2
    data = build.runtime_data(ROOT, ROOT / 'fixture-compliance.zip')
    assert data == [(str(ROOT / 'fixture-compliance.zip'), '.'),
                    (str(ROOT / 'oal_builder/resources/ProtoPersonalizer.java'), 'oal_builder/resources')]

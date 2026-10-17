"""Parser/spec tests are not native Windows replacement qualification."""
import hashlib
import importlib.util
from pathlib import Path

import pytest

PACKAGING = Path(__file__).resolve().parents[1] / "packaging"


def load(name):
    spec = importlib.util.spec_from_file_location(name, PACKAGING / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def digest(data):
    return hashlib.sha256(data).hexdigest()


def test_marker_is_only_appended_outside_pe_image(tmp_path):
    assert (PACKAGING / "windows_recombine.py").exists(), "replacement probe not implemented"
    probe = load("windows_recombine")
    from test_windows_ci_coverage import WindowsCoverageTests
    original = WindowsCoverageTests.minimal_pe()
    target = tmp_path / "Qt6Core.dll"
    result = probe.write_marked_clone(original, target)
    assert target.read_bytes() == original + probe.MARKER
    assert result == {"originalSha256": digest(original), "modifiedSha256": digest(target.read_bytes()),
                      "originalSize": len(original), "modifiedSize": len(target.read_bytes()),
                      "mutation": "non-loaded-file-overlay-only"}
    with pytest.raises(FileExistsError):
        probe.write_marked_clone(original, target)
    with pytest.raises(ValueError):
        probe.write_marked_clone(b"not a PE", tmp_path / "bad.dll")


def test_private_spec_replaces_exactly_one_core_without_touching_original(tmp_path, monkeypatch):
    from unittest import mock
    build = load("build")
    original = tmp_path / "installed.dll"
    modified = tmp_path / "private.dll"
    original.write_bytes(b"supplier")
    modified.write_bytes(b"supplier+marker")
    replacement = {"source": str(modified), "originalSha256": digest(original.read_bytes()),
                   "modifiedSha256": digest(modified.read_bytes())}
    rows = [("PySide6\\Qt6Core.dll", str(original), "BINARY"), ("other.dll", "other", "BINARY")]
    analysis = mock.Mock(binaries=rows, pure=[], scripts=[], datas=[])
    exe = mock.Mock()
    with mock.patch("PyInstaller.building.makespec.is_darwin", False):
        spec = build.windows_spec(PACKAGING.parent, tmp_path, tmp_path / "builder-compliance.zip",
                                  replacement=replacement, extra_runtime_hooks=[str(tmp_path / "probe-hook.py")])
    text = spec.read_text()
    assert "probe-hook.py" in text
    exec(compile(text, str(spec), "exec"), {"Analysis": lambda *a, **kw: analysis,
                                          "PYZ": mock.Mock(), "EXE": exe})
    assert rows[0][1] == str(original)
    assert original.read_bytes() == b"supplier"
    assert analysis.binaries == [(rows[0][0], str(modified), "BINARY"), rows[1]]
    assert analysis.binaries in exe.call_args.args
    probe = load("windows_recombine")
    for invalid in ([], rows + rows, [(rows[0][0], str(original), "DATA")]):
        with pytest.raises(ValueError):
            probe.replace_qtcore_toc(invalid, replacement)
    modified.write_bytes(b"wrong")
    with pytest.raises(ValueError):
        probe.replace_qtcore_toc(rows, replacement)


@pytest.mark.parametrize('archive_separator', ['/', '\\'])
@pytest.mark.parametrize("fault", [None, "build-timeout", "original-in-archive", "bad-runtime-hash",
                                   "offscreen", "wrong-plugin", "external-core", "workspace-write",
                                   "missing-runtime", "bad-png", "coverage-accepts", "original-changed"])
def test_private_probe_archives_changed_bytes_and_keeps_original_inventory(tmp_path, monkeypatch, fault, archive_separator):
    """Real archive/gates; subprocess replay is NOT native runtime proof."""
    import copy
    import json
    import zipfile
    from types import SimpleNamespace
    from PyInstaller.archive.writers import CArchiveWriter
    from test_windows_ci_coverage import WindowsCoverageTests
    probe = load("windows_recombine")
    assert hasattr(probe, "probe_recombination"), "bounded native probe not implemented"
    build = load("build")
    windows = load("coverage_windows_ci")
    core = WindowsCoverageTests.minimal_pe()
    plugin = core + b"plugin"
    cache, work, output = (tmp_path / n for n in ("cache", "work", "output"))
    for directory in (cache, work, output):
        directory.mkdir()
    wheel = cache / "official.whl"
    with zipfile.ZipFile(wheel, "w") as archive:
        archive.writestr(probe.CORE, core)
    entry = {"path": probe.CORE, "sha256": digest(core), "size": len(core), "kind": "binary"}
    plugin_entry = {"path": "PySide6/plugins/platforms/qwindows.dll", "sha256": digest(plugin),
                    "size": len(plugin), "kind": "binary"}
    candidate = work / "original.exe"
    candidate.write_bytes(b"untouched candidate")
    discovery = {"artifactSha256": digest(candidate.read_bytes()), "builderSourceSha256": "a" * 64,
                 "payload": [entry, plugin_entry]}
    minimal = {"schema": 2, "system": "Windows", "architecture": "x86_64", "status": "blocked",
               "blockers": ["WINDOWS_RECOMBINATION_UNTESTED"], "observed_payload": discovery["payload"],
               "components": [{"id": "qt", "payload": discovery["payload"], "statically_linked": []}],
               "supplier_inputs": [{"path": wheel.name, "sha256": digest(wheel.read_bytes())}],
               "supplier_matches": [{"payload": probe.CORE, "input": wheel.name, "member": probe.CORE,
                                     "sha256": digest(core)}]}
    before = copy.deepcopy((discovery, minimal))
    monkeypatch.setattr(probe.platform, "system", lambda: "Windows")
    monkeypatch.setattr(probe.platform, "machine", lambda: "AMD64")
    monkeypatch.setattr(windows, "ci_identity", lambda: None)
    monkeypatch.setattr(build, "windows_spec", lambda *a, **kw: a[1] / "replacement.spec")
    # Keep archive parsing real; the fake launcher creates genuine CArchive bytes.
    def inventory(executable):
        from PyInstaller.archive.readers import CArchiveReader
        reader = CArchiveReader(str(executable))
        members = [name for name in reader.toc if name.replace('\\', '/') == probe.CORE]
        assert len(members) == 1
        blob = reader.extract(members[0])
        return [{**entry, "sha256": digest(blob), "size": len(blob)}, plugin_entry]
    monkeypatch.setattr(build, "native_inventory", inventory)
    if fault == "coverage-accepts":
        monkeypatch.setattr(build, "validate_native_coverage", lambda *a: None)
    calls = []
    def launch(argv, **kwargs):
        calls.append((argv, kwargs))
        assert kwargs["timeout"] in (900, 120)
        if "PyInstaller" in argv:
            if fault == "build-timeout":
                raise probe.subprocess.TimeoutExpired("private-command-secret", 900)
            dist = Path(argv[argv.index("--distpath") + 1])
            dist.mkdir(exist_ok=True)
            replacement = next(work.rglob("replacement/Qt6Core.dll"))
            if fault == "original-in-archive":
                replacement.write_bytes(core)
            plugin_file = replacement.with_name("qwindows.dll")
            plugin_file.write_bytes(plugin)
            CArchiveWriter(str(dist / (build.NAME + ".exe")),
                           [(probe.CORE.replace("/", archive_separator), str(replacement), True, "b"),
                            (plugin_entry["path"].replace("/", archive_separator), str(plugin_file), True, "b")], "python")
        else:
            assert kwargs["env"]["QT_QPA_PLATFORM"] == "windows"
            png = Path(argv[argv.index("--screenshot") + 1])
            png.write_bytes(b"\x89PNG\r\n\x1a\nfixture")
            modified = next(work.rglob("replacement/Qt6Core.dll")).read_bytes()
            runtime = {
                "system": "Windows", "architecture": "x86_64", "frozen": True,
                "qtPlatform": "windows", "coreSha256": digest(modified), "pluginSha256": digest(plugin),
                "bundledCore": True, "bundledPlugin": True, "builderWindow": True}
            if fault == "bad-runtime-hash":
                runtime["coreSha256"] = digest(core)
            if fault == "offscreen":
                runtime["qtPlatform"] = "offscreen"
            if fault == "wrong-plugin":
                runtime["pluginSha256"] = "0" * 64
            if fault == "external-core":
                runtime["bundledCore"] = False
            if fault == "workspace-write":
                Path(argv[argv.index("--workspace") + 1]).mkdir()
            if fault == "bad-png":
                png.write_bytes(b"not PNG")
            if fault == "original-changed":
                candidate.write_bytes(b"unexpected mutation")
            if fault != "missing-runtime":
                Path(kwargs["env"]["OAL_RECOMBINATION_RUNTIME_REPORT"]).write_text(json.dumps(runtime))
        return SimpleNamespace(returncode=0)
    monkeypatch.setattr(probe.subprocess, "run", launch)
    if fault:
        with pytest.raises(ValueError, match="WINDOWS_RECOMBINATION_"):
            probe.probe_recombination(candidate, discovery, minimal, cache, work, output,
                                      build=build, windows=windows)
        report = json.loads((output / "windows-recombination.json").read_text())
        assert report["status"] == "failed"
        assert report["failureCode"].startswith("WINDOWS_RECOMBINATION_")
        assert "private-command-secret" not in json.dumps(report)
        assert str(tmp_path) not in json.dumps(report)
        assert (discovery, minimal) == before
        assert not list(work.glob("windows-recombine-*"))
        assert {p.name for p in output.iterdir()} == {"windows-recombination.json"}
        return
    result = probe.probe_recombination(candidate, discovery, minimal, cache, work, output,
                                       build=build, windows=windows)
    assert result["status"] == "passed"
    assert result["notQualification"] is True
    assert result["originalCoverageRejected"] is True
    assert result["modifiedSha256"] == result["archivedCoreSha256"] == result["runtime"]["coreSha256"]
    assert (discovery, minimal) == before
    assert candidate.read_bytes() == b"untouched candidate"
    assert len(calls) == 2  # one rebuild, one native UI invocation, no retries
    assert not list(work.glob("windows-recombine-*"))
    assert {p.name for p in output.iterdir()} == {"windows-recombination.json", "windows-recombination.png"}
    report = json.loads((output / "windows-recombination.json").read_text())
    assert report == result
    assert str(tmp_path) not in json.dumps(report)


def test_runtime_hook_observes_loaded_handles_and_actual_qpa(tmp_path, monkeypatch):
    """WinAPI/Qt replay only; native qualification executes this in frozen EXE."""
    import ctypes
    import json
    import sys
    from types import ModuleType, SimpleNamespace
    probe = load("windows_recombine")
    assert hasattr(probe, "runtime_hook_source"), "runtime observation not implemented"
    core = tmp_path / "PySide6/Qt6Core.dll"
    plugin = tmp_path / "PySide6/plugins/platforms/qwindows.dll"
    for path in (core, plugin):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(path.name.encode())
    class Function:
        def __init__(self, func):
            self.func = func
        def __call__(self, *args):
            return self.func(*args)
    paths = {1: core, 2: plugin}
    def filename(handle, buffer, count):
        buffer.value = str(paths[handle])
        return len(buffer.value)
    kernel = SimpleNamespace(GetModuleHandleW=Function(lambda name: {"Qt6Core.dll": 1, "qwindows.dll": 2}[name]),
                             GetModuleFileNameW=Function(filename))
    monkeypatch.setattr(ctypes, "WinDLL", lambda *a, **kw: kernel, raising=False)
    callbacks = []
    class BuilderWindow:
        pass
    BuilderWindow.__module__ = "oal_builder.ui"
    app = SimpleNamespace(aboutToQuit=SimpleNamespace(connect=callbacks.append),
                          platformName=lambda: "windows", topLevelWidgets=lambda: [BuilderWindow()])
    qt = ModuleType("PySide6.QtWidgets")
    qt.QApplication = SimpleNamespace(instance=lambda: app)
    monkeypatch.setitem(sys.modules, "PySide6.QtWidgets", qt)
    monkeypatch.setattr(sys, "_MEIPASS", str(tmp_path), raising=False)
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(probe.platform, "system", lambda: "Windows")
    monkeypatch.setattr(probe.platform, "machine", lambda: "AMD64")
    report = tmp_path / "runtime.json"
    monkeypatch.setenv("OAL_RECOMBINATION_RUNTIME_REPORT", str(report))
    source = probe.runtime_hook_source()
    assert "PyInstaller" not in source and "pefile" not in source
    profile_callbacks = []
    monkeypatch.setattr(sys, "setprofile", profile_callbacks.append)
    exec(compile(source, "hook.py", "exec"), {})
    assert callbacks == [], "Qt must not initialize before standard frozen runtime hooks"
    assert len(profile_callbacks) == 1
    frame = SimpleNamespace(f_code=SimpleNamespace(co_name="main"), f_globals={"__name__": "__main__"})
    profile_callbacks[0](frame, "call", None)
    assert profile_callbacks[-1] is None
    assert len(callbacks) == 1
    callbacks[0]()
    observed = json.loads(report.read_text())
    assert observed == {"system": "Windows", "architecture": "x86_64", "frozen": True,
                        "qtPlatform": "windows", "coreSha256": digest(core.read_bytes()),
                        "pluginSha256": digest(plugin.read_bytes()), "bundledCore": True,
                        "bundledPlugin": True, "builderWindow": True}
    report.unlink()
    paths[1] = tmp_path / "external.dll"
    paths[1].write_bytes(core.read_bytes())
    app.platformName = lambda: "offscreen"
    callbacks[0]()
    observed = json.loads(report.read_text())
    assert observed["bundledCore"] is False
    assert observed["qtPlatform"] == "offscreen"


def test_candidate_runs_recombination_before_legal_materialization(tmp_path, monkeypatch):
    import json
    from types import SimpleNamespace
    build = load("build")
    calls = []
    review = tmp_path / "review.json"
    review.write_text("{}")
    (tmp_path / "provenance").mkdir()
    (tmp_path / "provenance/windows-ci-bootstrap-policy.json").write_text("{}")
    minimal = {"status": "blocked", "blockers": ["MICROSOFT_STILL_BLOCKED"], "components": []}
    def recombine(*args, **kwargs):
        calls.append("replacement")
        assert args[2] is minimal
        return {"status": "passed", "notQualification": True}
    def materialize(*args, **kwargs):
        calls.append("legal")
        raise ValueError("still legally blocked")
    def bootstrap(*args, **kwargs):
        calls.append("bootstrap")
        return {"status": "passed", "notQualification": True}
    windows = SimpleNamespace(REVIEW=review, BASE=tmp_path,
                              run_candidate_probe=lambda candidate, discovery, collect: collect(),
                              accept_candidate_evidence=lambda *args, **kwargs: minimal,
                              ci_identity=lambda: None,
                              probe_native_loader=lambda *a: {}, minimal_review=lambda *a: minimal,
                              probe_candidate_ui=lambda *a: {}, materialize=materialize,
                              _load=lambda name: (SimpleNamespace(probe_recombination=recombine) if name == "windows_recombine"
                                                  else SimpleNamespace(probe_candidate=bootstrap) if name == "windows_setup" else build))
    monkeypatch.setattr(build, "windows_support", lambda: windows)
    with pytest.raises(ValueError, match="still legally blocked"):
        build.windows_candidate_coverage(tmp_path / "candidate.exe",
            {"artifactSha256": "a" * 64, "builderSourceSha256": "b" * 64, "payload": []},
            tmp_path / "cache", tmp_path / "work", tmp_path)
    assert calls == ["replacement", "bootstrap", "legal"]
    report = json.loads((tmp_path / "windows-candidate-review.json").read_text())
    assert report["recombinationProbe"]["status"] == "passed"
    assert report["coverage"]["blockers"] == ["MICROSOFT_STILL_BLOCKED"]


def test_non_windows_cannot_claim_runtime_evidence(tmp_path, monkeypatch):
    import json
    probe = load("windows_recombine")
    monkeypatch.setattr(probe.platform, "system", lambda: "Linux")
    monkeypatch.setattr(probe.subprocess, "run", lambda *a, **kw: pytest.fail("must not build or launch"))
    with pytest.raises(ValueError, match="WINDOWS_RECOMBINATION_PLATFORM"):
        probe.probe_recombination(tmp_path / "candidate", {"artifactSha256": "a" * 64,
            "builderSourceSha256": "b" * 64}, {}, tmp_path, tmp_path, tmp_path,
            build=load("build"), windows=load("coverage_windows_ci"))
    report = json.loads((tmp_path / "windows-recombination.json").read_text())
    assert report["status"] == "failed"
    assert "runtime" not in report

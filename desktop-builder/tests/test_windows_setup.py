"""Cross-platform packaging checks; not native bootstrap qualification."""
import hashlib
import importlib.util
from pathlib import Path

import pytest

PACKAGING = Path(__file__).resolve().parents[1] / "packaging"


def load():
    path = PACKAGING / "windows_setup.py"
    assert path.is_file(), "Windows bootstrap is not implemented"
    spec = importlib.util.spec_from_file_location("windows_setup_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_pre_freeze_policy_removes_runtime_from_every_nested_destination():
    setup = load()
    removed = ["vcruntime140.dll", "PySide6/MSVCP140_1.dll", "x\\VCRUNTIME140_1.dll",
               "concrt140.dll", "ucrtbase.dll", "api-ms-win-crt-heap-l1-1-0.dll", "msvcp140_atomic_wait.dll"]
    rows = [(name, "supplier/" + name, "BINARY") for name in removed]
    retained = [("python312.dll", "python312.dll", "BINARY"), ("Qt6Core.dll", "Qt6Core.dll", "BINARY")]
    assert setup.external_runtime_binaries(rows + retained) == retained
    assert setup.external_runtime_binaries(retained) == retained
    for name in removed:
        with pytest.raises(ValueError, match="runtime"):
            setup.assert_external_runtime([{"path": name, "sha256": "a" * 64}])
    setup.assert_external_runtime([{"path": row[0]} for row in retained])


def test_render_is_interactive_and_pins_official_recipient_download(tmp_path):
    setup = load()
    assert hasattr(setup, "render_support"), "bootstrap renderer is missing"
    setup.render_support(tmp_path, "a" * 64)
    script = (tmp_path / "bootstrap.ps1").read_text()
    nsis = (tmp_path / "setup.nsi").read_text()
    assert "cc0ff0eb1dc3f5188ae6300faef32bf5beeba4bdd6e8e445a9184072096b713b" in script
    assert "download.visualstudio.microsoft.com" in script
    assert "Get-AuthenticodeSignature" in script
    assert "Microsoft Corporation" in script
    assert "'/install', '/norestart'" in script
    assert "/quiet" not in script and "/passive" not in script
    assert "RequestExecutionLevel user" in nsis
    assert "SetCompressor /FINAL zlib" in nsis
    assert "-NoProfile" in nsis and "-File" in nsis
    assert "OAL_SETUP_EXTRACT_ONLY" in nsis
    assert "@" not in script.replace("@{", "").replace("@(", "")


def test_acquisition_rejects_tampering_before_extract(tmp_path):
    setup = load()
    assert hasattr(setup, "extract_compiler"), "pinned compiler extraction is missing"
    import zipfile
    source = tmp_path / "compiler.zip"
    with zipfile.ZipFile(source, "w") as z:
        z.writestr("../outside", "bad")
    with pytest.raises(ValueError, match="digest"):
        setup.extract_compiler(source, tmp_path / "out", {"sha256": "0" * 64, "root": "nsis"})
    with pytest.raises(ValueError, match="Unsafe"):
        setup.extract_compiler(source, tmp_path / "out", {"sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "root": "nsis"})
    assert not (tmp_path / "outside").exists()


def test_windows_spec_excludes_reviewed_runtime_from_binaries_and_datas(tmp_path):
    from unittest import mock
    setup = load()
    build = setup.load_module("build")
    rows = [("PySide6/MSVCP140.dll", "runtime.dll", "BINARY"), ("python312.dll", "python.dll", "BINARY")]
    analysis = mock.Mock(binaries=rows[:], datas=rows[:], pure=[], scripts=[])
    with mock.patch("PyInstaller.building.makespec.is_darwin", False):
        spec = build.windows_spec(PACKAGING.parent, tmp_path, tmp_path / "compliance.zip")
    exec(compile(spec.read_text(), str(spec), "exec"),
         {"Analysis": lambda *a, **kw: analysis, "PYZ": mock.Mock(), "EXE": mock.Mock()})
    assert analysis.binaries == rows[1:]
    assert analysis.datas == rows[1:]


def test_snapshot_retains_bootstrap_templates(tmp_path):
    import zipfile
    build = load().load_module("build")
    source = tmp_path / "source.zip"
    build.source_archive(source)
    with zipfile.ZipFile(source) as archive:
        for name in ("bootstrap.ps1", "setup.nsi"):
            assert archive.read("desktop-builder/packaging/windows-setup/" + name) == (PACKAGING / "windows-setup" / name).read_bytes()


def test_supplier_fetch_includes_exact_qt_recombination_wheel(tmp_path, monkeypatch):
    import json
    from types import SimpleNamespace
    coverage = load().load_module("coverage_windows_ci")
    review = json.loads(coverage.REVIEW.read_text())
    calls = []
    monkeypatch.setattr(coverage, "_load", lambda name: SimpleNamespace(prepare=lambda *a, **kw: calls.append((a, kw))))
    coverage.fetch_sources(review, tmp_path, offline=True)
    inputs = calls[0][0][0]["inputs"]
    core = next(e for e in review["supplier_matches"] if e["payload"] == "PySide6/Qt6Core.dll")
    assert next(e for e in review["supplier_inputs"] if e["path"] == core["input"]) in inputs
    assert len({e["path"] for e in inputs}) == len(inputs)
    assert calls[0][1]["offline"] is True


@pytest.mark.parametrize("case", ["ready", "decline", "smoke", "download-failure", "hash", "signature", "cancel", "restart", "install-failure", "success", "postcheck-failure"])
def test_native_powershell_control_flow_never_accepts_terms(tmp_path, case):
    """Native PowerShell orchestration with fake external operations, no assent."""
    import os
    import subprocess
    import shutil
    if os.name != "nt":
        pytest.skip("Native inbox Windows PowerShell required")
    setup = load()
    setup.render_support(tmp_path, "a" * 64)
    script = setup.SUPPORT / "test-control-flow.ps1"
    assert script.exists(), "native control-flow harness missing"
    command = Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"
    subprocess.run([str(command), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(script),
                    "-Bootstrap", str(tmp_path / "bootstrap.ps1"), "-Case", case],
                   check=True, timeout=30, env=setup.child_environment())
    assert not (tmp_path / "VC_redist.x64.exe").exists()


def test_bootstrap_checks_both_registry_views_and_readonly_workspace(tmp_path):
    setup = load()
    setup.render_support(tmp_path, "a" * 64)
    text = (tmp_path / "bootstrap.ps1").read_text()
    assert "RegistryView]::Registry32" in text
    assert "RegistryView]::Registry64" in text
    assert "Read-only smoke unexpectedly created a workspace" in text
    assert "OAL_SETUP_SMOKE_SCREENSHOT" in text and "automated Microsoft assent is prohibited" in text


@pytest.mark.parametrize("fault", [None, "compiler", "loader", "extract", "ui", "candidate-changed"])
def test_private_bootstrap_probe_exports_evidence_not_an_executable(tmp_path, monkeypatch, fault):
    import json
    from types import SimpleNamespace
    setup = load()
    assert hasattr(setup, "probe_candidate"), "private bootstrap gate missing"
    candidate = tmp_path / "candidate.exe"
    candidate.write_bytes(b"candidate fixture")
    discovery = {"artifactSha256": setup.sha256(candidate), "builderSourceSha256": "b" * 64, "payload": []}
    output = tmp_path / "evidence"
    output.mkdir()
    monkeypatch.setattr(setup, "require_native", lambda: None)
    monkeypatch.setattr(setup, "acquire_compiler", lambda *a, **kw: tmp_path)
    monkeypatch.setattr(setup, "verify_snapshot", lambda *a: None)
    def payload(candidate, staged, compiler, metadata):
        staged.mkdir()
        for name in setup.PAYLOAD:
            (staged / name).write_text(name)
        return metadata
    monkeypatch.setattr(setup, "write_payload", payload)
    monkeypatch.setattr(setup, "compile_setup", lambda p, c: candidate)
    monkeypatch.setattr(setup, "verify_stub", lambda *a: {"status": "exact-nonresource-sections"})
    monkeypatch.setattr(setup, "extract_outer", lambda *a: None)
    monkeypatch.setattr(setup, "smoke_outer", lambda p, s: s.write_bytes(b"\x89PNG\r\n\x1a\nreplay"))
    phases = {"compiler": ("compile_setup", "WINDOWS_BOOTSTRAP_COMPILER"),
              "loader": ("verify_stub", "WINDOWS_BOOTSTRAP_LOADER"),
              "extract": ("extract_outer", "WINDOWS_BOOTSTRAP_EXTRACT"),
              "ui": ("smoke_outer", "WINDOWS_BOOTSTRAP_UI")}
    if fault in phases:
        def fail(*a, **kw):
            raise ValueError("private exception text must never be exported")
        monkeypatch.setattr(setup, phases[fault][0], fail)
    if fault == "candidate-changed":
        monkeypatch.setattr(setup, "smoke_outer", lambda *a: candidate.write_bytes(b"changed"))
    if fault:
        with pytest.raises(ValueError, match="WINDOWS_BOOTSTRAP"):
            setup.probe_candidate(candidate, discovery, tmp_path / "cache", tmp_path, output)
        report = json.loads((output / "windows-bootstrap.json").read_text())
        assert report["status"] == "failed" and report["notQualification"] is True
        assert "private exception" not in json.dumps(report) and str(tmp_path) not in json.dumps(report)
        assert {p.name for p in output.iterdir()} == {"windows-bootstrap.json"}
        assert not list(tmp_path.glob("bootstrap-probe-*"))
        return
    report = setup.probe_candidate(candidate, discovery, tmp_path / "cache", tmp_path, output)
    assert report["status"] == "passed" and report["notQualification"] is True
    assert report["artifactSha256"] == discovery["artifactSha256"]
    assert report["humanInstallation"] == "not-tested-no-assent-automated"
    assert {p.name for p in output.iterdir()} == {"windows-bootstrap.json", "windows-bootstrap.png"}
    assert str(tmp_path) not in json.dumps(report)


def test_build_exports_internal_windows_stage_and_routes_outer_smoke(tmp_path, monkeypatch):
    from types import SimpleNamespace
    setup = load()
    build = setup.load_module("build")
    assert hasattr(build, "windows_stage_metadata"), "inner manifest contract missing"
    assert build.windows_stage_metadata() == {"kind": "internal-runtime-stage", "runtimeCollection": setup.MODE}
    calls = []
    manifest = {"bootstrap": setup.MODE}
    monkeypatch.setattr(build.platform, "system", lambda: "Windows")
    monkeypatch.setattr(build.platform, "machine", lambda: "AMD64")
    monkeypatch.setenv("QT_QPA_PLATFORM", "windows")
    monkeypatch.setattr(build, "read_manifest", lambda *a: manifest)
    monkeypatch.setattr(build, "windows_support", lambda: SimpleNamespace(_load=lambda name: SimpleNamespace(smoke_package=lambda p: calls.append(p))))
    build.smoke_windows_qwindows(tmp_path)
    assert calls == [tmp_path]


@pytest.mark.parametrize("fault", [None, "source-hash", "template-bytes"])
def test_snapshot_gate_checks_real_carchive_source_bytes(tmp_path, fault):
    import io
    import zipfile
    from PyInstaller.archive.writers import CArchiveWriter
    setup = load()
    build = setup.load_module("build")
    source = tmp_path / "builder-source.zip"
    build.source_archive(source)
    if fault == "template-bytes":
        rewritten = io.BytesIO()
        with zipfile.ZipFile(source) as old, zipfile.ZipFile(rewritten, "w") as new:
            for name in old.namelist():
                data = old.read(name)
                if name.endswith("windows-setup/bootstrap.ps1"):
                    data += b"\n# unexpected source change\n"
                new.writestr(name, data)
        source.write_bytes(rewritten.getvalue())
    compliance = tmp_path / "builder-compliance.zip"
    with zipfile.ZipFile(compliance, "w") as archive:
        archive.write(source, source.name)
    candidate = tmp_path / "candidate.exe"
    CArchiveWriter(str(candidate), [(compliance.name, str(compliance), True, "x")], "python")
    digest = "0" * 64 if fault == "source-hash" else setup.sha256(source)
    if fault:
        with pytest.raises(ValueError, match="source|snapshot"):
            setup.verify_snapshot(candidate, digest)
    else:
        setup.verify_snapshot(candidate, digest)


def test_stage_rejects_unqualified_inner_and_binds_every_embedded_file(tmp_path, monkeypatch):
    import json
    import zipfile
    setup = load()
    assert hasattr(setup, "stage_payload"), "inner-to-outer interface missing"
    build = setup.load_module("build")
    incoming, staged, compiler = (tmp_path / n for n in ("incoming", "staged", "compiler"))
    for p in (incoming, staged, compiler):
        p.mkdir()
    (compiler / "COPYING").write_text("supplier notice")
    candidate = incoming / "OpenAutoLink-Builder.exe"
    candidate.write_bytes(b"frozen runtime fixture")
    build.write_manifest(incoming, "OpenAutoLink-Builder-windows-x86_64", candidate, [candidate],
                         system="Windows", architecture="x86_64", kind="internal-runtime-stage",
                         builderSourceSha256="b" * 64, frozenSmokeTest="passed",
                         nativeCoverage="exact-payload-evidence-verified", runtimeCollection="official-vc-runtime-bootstrap-v1")
    monkeypatch.setattr(build, "native_inventory", lambda p: [{"path": "python312.dll"}])
    checks = []
    monkeypatch.setattr(setup, "verify_inner", lambda *a, **kw: checks.append(a))
    result = setup.stage_payload(incoming, staged, compiler, build=build)
    assert checks, "must verify actual inner compliance, source and native bytes"
    assert (staged / "builder.exe").read_bytes() == candidate.read_bytes()
    assert result["innerSha256"] == setup.sha256(candidate)
    manifest = json.loads((staged / "setup-manifest.json").read_text())
    assert manifest["builderSourceSha256"] == "b" * 64
    assert set(manifest["payload"]) == {"builder.exe", "bootstrap.ps1", "NSIS-COPYING.txt", "BOOTSTRAP-NOTICE.txt"}
    for name, digest in manifest["payload"].items():
        assert setup.sha256(staged / name) == digest
    path = incoming / "OpenAutoLink-Builder-windows-x86_64.json"
    metadata = json.loads(path.read_text())
    metadata["nativeCoverage"] = "unqualified"
    path.write_text(json.dumps(metadata))
    with pytest.raises(ValueError, match="qualified"):
        setup.stage_payload(incoming, staged, compiler, build=build)


@pytest.mark.parametrize("fault", [None, "changed", "extra", "missing", "symlink"])
def test_outer_extraction_verifies_exact_payload_without_starting_builder(tmp_path, monkeypatch, fault):
    setup = load()
    assert hasattr(setup, "verify_extracted"), "outer byte verification missing"
    expected = {}
    for name in ("builder.exe", "bootstrap.ps1", "setup-manifest.json", "NSIS-COPYING.txt", "BOOTSTRAP-NOTICE.txt"):
        (tmp_path / name).write_text(name)
        expected[name] = setup.sha256(tmp_path / name)
    if fault == "changed":
        (tmp_path / "builder.exe").write_bytes(b"modified")
    elif fault == "extra":
        (tmp_path / "VC_redist.x64.exe").write_bytes(b"must never redistribute")
    elif fault == "missing":
        (tmp_path / "bootstrap.ps1").unlink()
    elif fault == "symlink":
        (tmp_path / "builder.exe").unlink()
        (tmp_path / "builder.exe").symlink_to(tmp_path / "bootstrap.ps1")
    if fault:
        with pytest.raises(ValueError):
            setup.verify_extracted(tmp_path, expected)
    else:
        setup.verify_extracted(tmp_path, expected)


def test_acquire_pinned_compiler_uses_verified_cache_only_in_offline_mode(tmp_path, monkeypatch):
    import json
    import zipfile
    setup = load()
    assert hasattr(setup, "acquire_compiler"), "compiler acquisition missing"
    archive = tmp_path / "nsis-test.zip"
    with zipfile.ZipFile(archive, "w") as z:
        z.writestr("nsis-test/Stubs/zlib-x86-unicode", b"stub")
        z.writestr("nsis-test/COPYING", b"notice")
        z.writestr("nsis-test/makensis.exe", b"compiler")
    record = {"version": "test", "root": "nsis-test", "url": "https://example.invalid/compiler.zip",
              "sha256": setup.sha256(archive), "stub": "Stubs/zlib-x86-unicode",
              "stubSha256": hashlib.sha256(b"stub").hexdigest(), "notice": "COPYING",
              "noticeSha256": hashlib.sha256(b"notice").hexdigest()}
    monkeypatch.setattr(setup, "LOCK", tmp_path / "lock.json")
    setup.LOCK.write_text(json.dumps({"schema": 1, "nsis": record}))
    root = setup.acquire_compiler(tmp_path, tmp_path / "unpacked", offline=True)
    assert (root / "makensis.exe").read_bytes() == b"compiler"
    archive.write_bytes(b"tampered")
    with pytest.raises(ValueError, match="hash mismatch"):
        setup.acquire_compiler(tmp_path, tmp_path / "unpacked2", offline=True)


@pytest.mark.parametrize("fault", [None, "compile", "extract", "smoke"])
def test_package_pipeline_publishes_only_after_outer_byte_and_smoke_gates(tmp_path, monkeypatch, fault):
    """Native process API replay, NOT native NSIS/Windows execution."""
    import json
    import shutil
    setup = load()
    assert hasattr(setup, "package"), "production outer pipeline missing"
    compiler = tmp_path / "compiler"
    compiler.mkdir()
    output = tmp_path / "output"
    monkeypatch.setattr(setup.platform, "system", lambda: "Windows")
    monkeypatch.setattr(setup.platform, "machine", lambda: "AMD64")
    monkeypatch.setattr(setup, "acquire_compiler", lambda *a, **kw: compiler)
    calls = []
    def stage(incoming, directory, compiler, **kwargs):
        directory.mkdir()
        for name in setup.PAYLOAD:
            (directory / name).write_text(name)
        return {"innerSha256": setup.sha256(directory / "builder.exe"), "builderSourceSha256": "b" * 64}
    monkeypatch.setattr(setup, "stage_payload", stage)
    def compile_(directory, compiler):
        calls.append("compile")
        if fault == "compile":
            raise ValueError("compiler failure")
        exe = directory / "OpenAutoLink-Bootstrap.exe"
        exe.write_bytes(b"outer fixture")
        return exe
    monkeypatch.setattr(setup, "compile_setup", compile_)
    monkeypatch.setattr(setup, "verify_stub", lambda *a: {"status": "exact-nonresource-sections"})
    def extract(executable, directory, expected):
        calls.append("extract")
        directory.mkdir()
        for name in setup.PAYLOAD:
            shutil.copyfile(executable.parent / name, directory / name)
        if fault == "extract":
            (directory / "builder.exe").write_bytes(b"bad")
        setup.verify_extracted(directory, expected)
    monkeypatch.setattr(setup, "extract_outer", extract)
    def smoke(executable, screenshot):
        calls.append("smoke")
        if fault == "smoke":
            raise ValueError("smoke failure")
        screenshot.write_bytes(b"\x89PNG\r\n\x1a\nreplay")
    monkeypatch.setattr(setup, "smoke_outer", smoke)
    if fault:
        with pytest.raises(ValueError):
            setup.package(tmp_path / "inner", tmp_path / "cache", output, offline=True)
        assert not list(output.glob("*.exe"))
        assert not list(output.glob("*.json"))
        return
    result = setup.package(tmp_path / "inner", tmp_path / "cache", output, offline=True)
    assert calls == ["compile", "extract", "smoke"]
    assert result["kind"] == "direct-executable"
    assert result["bootstrap"] == setup.MODE
    assert result["nativePlatformSmoke"] == "qwindows-passed"
    assert result["innerSha256"] == hashlib.sha256(b"builder.exe").hexdigest()
    assert set(result["embeddedPayload"]) == setup.PAYLOAD
    assert setup.load_module("build").read_manifest(output / setup.MANIFEST) == result


def test_package_cannot_claim_native_execution_on_linux(tmp_path, monkeypatch):
    setup = load()
    assert hasattr(setup, "package"), "production outer pipeline missing"
    monkeypatch.setattr(setup.platform, "system", lambda: "Linux")
    with pytest.raises(ValueError, match="native Windows"):
        setup.package(tmp_path / "inner", tmp_path / "cache", tmp_path / "out")
    assert not (tmp_path / "out").exists()


@pytest.mark.parametrize("mutation", [None, "code", "ndata", "resource-executable", "resource-overlap", "entrypoint", "directory"])
def test_real_official_nsis_stub_comparison(tmp_path, mutation):
    """Opt-in actual compiler/stub parser replay; never Windows execution."""
    import os
    import pefile
    if not os.environ.get("OAL_TEST_NSIS_ROOT") or not os.environ.get("OAL_TEST_NSIS_ARTIFACT"):
        pytest.skip("Set paths to a verified extracted official NSIS and cross-compiled probe")
    setup = load()
    root = Path(os.environ["OAL_TEST_NSIS_ROOT"])
    artifact = Path(os.environ["OAL_TEST_NSIS_ARTIFACT"])
    candidate = tmp_path / "probe.exe"
    candidate.write_bytes(artifact.read_bytes())
    if mutation:
        with pefile.PE(str(candidate)) as pe:
            if mutation == "code":
                pe.set_bytes_at_offset(pe.sections[0].PointerToRawData, b"CORRUPT")
            elif mutation == "ndata":
                next(s for s in pe.sections if s.Name.startswith(b".ndata")).Misc_VirtualSize += 4096
            elif mutation == "resource-executable":
                next(s for s in pe.sections if s.Name.startswith(b".rsrc")).Characteristics |= 0x20000000
            elif mutation == "resource-overlap":
                resource = next(s for s in pe.sections if s.Name.startswith(b".rsrc"))
                ndata = next(s for s in pe.sections if s.Name.startswith(b".ndata"))
                resource.VirtualAddress = ndata.VirtualAddress + 4096
                pe.OPTIONAL_HEADER.DATA_DIRECTORY[2].VirtualAddress = resource.VirtualAddress
                pe.OPTIONAL_HEADER.SizeOfImage = (resource.VirtualAddress + resource.SizeOfRawData + 4095) // 4096 * 4096
            elif mutation == "entrypoint":
                pe.OPTIONAL_HEADER.AddressOfEntryPoint += 1
            else:
                pe.OPTIONAL_HEADER.DATA_DIRECTORY[1].VirtualAddress += 1
            pe.write(str(candidate))
        with pytest.raises(ValueError):
            setup.verify_stub(candidate, root)
    else:
        result = setup.verify_stub(candidate, root)
        assert result["status"] == "exact-nonresource-sections"
        assert "kernel32.dll" in result["imports"]

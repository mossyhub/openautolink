"""Packaging contracts; native binary execution is separately required in CI."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import zipfile
import tarfile
import io
import os
import platform
import plistlib

import pytest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("builder_packaging", ROOT / "packaging/build.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


def test_reviewed_generator_materializes_exact_schema2_evidence(tmp_path):
    module_path = ROOT / "packaging/generate_native.py"
    assert module_path.is_file(), "Reviewed coverage materializer is missing"
    spec = importlib.util.spec_from_file_location("generate_native", module_path)
    generator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(generator)
    payload, review = coverage_fixture(tmp_path)
    review["schema"] = 2
    output = tmp_path / "output"
    manifest = generator.generate(payload, review, tmp_path, output)
    actual = json.loads(manifest.read_text())
    assert actual == review
    assert build.validate_native_coverage(payload, actual, output) == actual
    (tmp_path / "notice.txt").write_bytes(b"changed")
    with pytest.raises(ValueError, match="changed"):
        generator.generate(payload, review, tmp_path, tmp_path / "bad")
    assert not (tmp_path / "bad/coverage.json").exists()


@pytest.mark.parametrize("mutation", ["new", "changed", "missing", "duplicate"])
def test_generator_does_not_expand_review_to_new_payload(tmp_path, mutation):
    spec = importlib.util.spec_from_file_location("generate_native", ROOT / "packaging/generate_native.py")
    generator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(generator)
    payload, review = coverage_fixture(tmp_path)
    review["schema"] = 2
    if mutation == "new":
        payload.append({"path": "unknown.so", "sha256": "b" * 64})
    elif mutation == "changed":
        payload[0]["sha256"] = "b" * 64
    elif mutation == "missing":
        payload.clear()
    else:
        payload.append(dict(payload[0]))
    with pytest.raises(ValueError, match="native payload"):
        generator.generate(payload, review, tmp_path, tmp_path / "result")
    assert not (tmp_path / "result").exists()


@pytest.mark.parametrize("license_id", ["MIT-0", "0BSD", "CC0-1.0", "MIT AND Unicode-3.0",
    "BSD-2-Clause AND Imlib2", "X11 AND HPND", "Apache-2.0 WITH LLVM-exception",
    "LicenseRef-ICC-License", "LicenseRef-SHA1-Public-Domain", "LicenseRef-libselinux-Public-Domain",
    "LicenseRef-BSD-3-Clause-with-PCRE2-Binary-Like-Packages-Exception", "MIT-open-group"])
def test_reviewed_notice_terms_have_specific_schema2_routes(tmp_path, license_id):
    payload, coverage = coverage_fixture(tmp_path)
    coverage["schema"] = 2
    component = coverage["components"][0]
    component["license"] = license_id
    for key in ("sources", "source_basis", "build_instructions"):
        component.pop(key)
    assert build.validate_native_coverage(payload, coverage, tmp_path) == coverage


@pytest.mark.parametrize("license_id", ["AFL-2.1", "GPL-3.0-or-later WITH GCC-exception-3.1"])
def test_added_source_routes_do_not_erase_source_obligation(tmp_path, license_id):
    payload, coverage = coverage_fixture(tmp_path)
    coverage["schema"] = 2
    c = coverage["components"][0]
    c["license"] = license_id
    c["exception_basis"] = "UNIT TEST runtime supplier and eligible compilation"
    assert build.validate_native_coverage(payload, coverage, tmp_path) == coverage
    c.pop("sources")
    with pytest.raises(ValueError, match="sources"):
        build.validate_native_coverage(payload, coverage, tmp_path)


@pytest.mark.parametrize("license_id,notice", [
    ("LicenseRef-Kerberos-1.21.3", "libkrb5-3-1.21.3-5+deb13u1.copyright"),
    ("LicenseRef-libX11-1.8.12", "libx11-6-2_1.8.12-1.copyright"),
    ("LicenseRef-com-err-1.47.2", "libcom-err2-1.47.2-3+b12.copyright"),
])
def test_scoped_composite_terms_require_exact_reviewed_notice(tmp_path, license_id, notice):
    payload, coverage = coverage_fixture(tmp_path)
    coverage["schema"] = 2
    c = coverage["components"][0]
    c["license"] = license_id
    c["license_scope"] = "UNIT TEST unmodified library, not utilities or documentation"
    for field in ("sources", "source_basis", "build_instructions"):
        c.pop(field)
    path = tmp_path / "reviewed.copyright"
    path.write_bytes((ROOT / "packaging/licenses/native-linux-observed" / notice).read_bytes())
    c["notices"].append({"path": path.name, "sha256": build.sha256(path)})
    assert build.validate_native_coverage(payload, coverage, tmp_path) == coverage
    c["notices"].pop()
    with pytest.raises(ValueError, match="reviewed notice"):
        build.validate_native_coverage(payload, coverage, tmp_path)


def test_linux_review_binds_payload_sources_and_nontrivial_static_terms():
    review = json.loads((ROOT / "packaging/provenance/linux-reviewed.json").read_text())
    locked = {e["path"]: e for e in json.loads((ROOT / "packaging/provenance/source-inputs.lock.json").read_text())["inputs"]}
    components = {c["id"]: c for c in review["components"]}
    payload = [e for c in components.values() for e in c["payload"]]
    assert len(payload) == 121 and len({e["path"] for e in payload}) == len(payload)
    assert components["cffi"]["license"] == "MIT-0"
    assert components["cffi-static-libffi"]["version"] == "3.4.6"
    assert components["debian-gcc-runtime"]["sources"]
    assert components["debian-gcc-runtime"]["exception_basis"]
    assert components["qt-static-psl-data"]["license"] == "MPL-2.0"
    assert components["qt-static-psl-data"]["sources"]
    assert components["crypto-crate-unicode-ident"]["license"] == "MIT AND Unicode-3.0"
    assert components["crypto-crate-target-lexicon"]["license"] == "Apache-2.0 WITH LLVM-exception"
    assert len([c for c in components if c.startswith("crypto-crate-")]) == 35
    assert "rust-std-addr2line" in components["rust-standard-library"]["statically_linked"]
    assert components["cpython-static-siphash"]["license"] == "MIT"
    for c in components.values():
        assert all(s in components for s in c["statically_linked"])
        for a in c["notices"]:
            build.coverage_asset(ROOT / "packaging", a)
        for a in c.get("sources", []):
            assert a["sha256"] == locked[Path(a["path"]).name]["sha256"]
        if c.get("build_instructions"):
            build.coverage_asset(ROOT / "packaging", c["build_instructions"])


def test_native_gate_rejects_an_uncovered_payload(tmp_path):
    payload = [{"path": "libfreetype.so.6", "sha256": "a" * 64, "kind": "binary"}]
    with pytest.raises(ValueError, match="Uncovered native payload.*libfreetype"):
        build.validate_native_coverage(payload, {"components": []}, tmp_path)


def coverage_fixture(tmp_path):
    import hashlib
    notice = tmp_path / "notice.txt"
    notice.write_bytes(b"UNIT TEST component-specific notice")
    source = tmp_path / "source.zip"
    with zipfile.ZipFile(source, "w") as archive:
        archive.writestr("source.c", "/* UNIT TEST corresponding source */")
    recipe = tmp_path / "build.txt"
    recipe.write_bytes(b"UNIT TEST build and relink instructions")
    def asset(path):
        return {"path": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    payload = [{"path": "libexample.so", "sha256": "a" * 64, "kind": "binary"}]
    component = {"id": "example", "version": "1.2.3", "license": "MIT",
                 "provenance": "UNIT TEST binary supplier and exact build identity",
                 "source_basis": "UNIT TEST supplier corresponding-source attestation",
                 "payload": [dict(entry) for entry in payload], "notices": [asset(notice)],
                 "sources": [{**asset(source), "url": "https://example.invalid/source-1.2.3.zip"}],
                 "build_instructions": asset(recipe), "statically_linked": []}
    return payload, {"schema": 1, "system": platform.system(),
                     "architecture": platform.machine().lower().replace("amd64", "x86_64"),
                     "components": [component]}


def test_permissive_runtime_needs_notices_not_corresponding_source(tmp_path):
    payload, coverage = coverage_fixture(tmp_path)
    component = coverage["components"][0]
    for key in ("sources", "source_basis", "build_instructions"):
        component.pop(key)
    coverage["schema"] = 2
    assert build.validate_native_coverage(payload, coverage, tmp_path) == coverage
    manifest = tmp_path / "coverage.json"
    manifest.write_text(json.dumps(coverage))
    compliance = build.compliance_archive(tmp_path)
    build.add_native_compliance(compliance, payload, manifest)
    build.verify_native_compliance(None, compliance, expected_payload=payload)


@pytest.mark.parametrize("license_id", ["UNKNOWN", "Proprietary", "MIT OR Unknown", "LGPL-3.0-only WITH invented-exception"])
def test_unknown_license_cannot_be_approved_by_adding_source(tmp_path, license_id):
    payload, coverage = coverage_fixture(tmp_path)
    coverage["schema"] = 2
    coverage["components"][0]["license"] = license_id
    with pytest.raises(ValueError, match="Unsupported native license"):
        build.validate_native_coverage(payload, coverage, tmp_path)


@pytest.mark.parametrize("license_id", ["LGPL-2.1-only", "LGPL-3.0-only"])
@pytest.mark.parametrize("missing", ["sources", "source_basis", "build_instructions"])
def test_schema2_preserves_lgpl_source_and_relink_obligations(tmp_path, license_id, missing):
    payload, coverage = coverage_fixture(tmp_path)
    coverage["schema"] = 2
    component = coverage["components"][0]
    component["license"] = license_id
    assert build.validate_native_coverage(payload, coverage, tmp_path) == coverage
    component.pop(missing)
    with pytest.raises(ValueError):
        build.validate_native_coverage(payload, coverage, tmp_path)


def test_notice_only_does_not_exempt_static_copyleft_dependency(tmp_path):
    payload, coverage = coverage_fixture(tmp_path)
    coverage["schema"] = 2
    parent = coverage["components"][0]
    child = {**parent, "id": "static-lgpl", "payload": [], "license": "LGPL-3.0-only", "sources": []}
    parent["statically_linked"] = [child["id"]]
    coverage["components"].append(child)
    with pytest.raises(ValueError, match="sources"):
        build.validate_native_coverage(payload, coverage, tmp_path)


@pytest.mark.parametrize("license_id", ["MIT", "BSD-2-Clause", "BSD-3-Clause", "Apache-2.0", "PSF-2.0", "ICU", "Zlib"])
def test_notice_only_still_requires_notices_and_provenance(tmp_path, license_id):
    payload, coverage = coverage_fixture(tmp_path)
    coverage["schema"] = 2
    component = coverage["components"][0]
    component["license"] = license_id
    component.pop("sources")
    assert build.validate_native_coverage(payload, coverage, tmp_path) == coverage
    component["notices"] = []
    with pytest.raises(ValueError, match="notices"):
        build.validate_native_coverage(payload, coverage, tmp_path)


def test_supplier_nested_source_member_is_pinned_and_replayed_offline(tmp_path):
    spec = importlib.util.spec_from_file_location("prepare_native", ROOT / "packaging/prepare_native.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    inner = b"UNIT TEST nested source archive bytes"
    outer = tmp_path / "outer.tar.gz"
    with tarfile.open(outer, "w:gz") as tar:
        member = tarfile.TarInfo("supplier/source.tar.xz")
        member.size = len(inner)
        tar.addfile(member, io.BytesIO(inner))
    import hashlib
    entries = [dict(path=outer.name, sha256=build.sha256(outer), url="https://example.invalid/outer.tar.gz"),
               dict(path="inner.tar.xz", sha256=hashlib.sha256(inner).hexdigest(),
                    url="https://example.invalid/outer.tar.gz", container=outer.name,
                    archive_member="supplier/source.tar.xz")]
    module.prepare({"schema": 1, "inputs": entries}, tmp_path, [], offline=True)
    assert (tmp_path / "inner.tar.xz").read_bytes() == inner
    (tmp_path / "inner.tar.xz").unlink()
    entries[1]["archive_member"] = "not-present"
    with pytest.raises((ValueError, KeyError)):
        module.prepare({"schema": 1, "inputs": entries}, tmp_path, [], offline=True)


def test_locked_evidence_preserves_real_notices_and_matches_payload(tmp_path):
    import importlib.util
    import hashlib
    spec = importlib.util.spec_from_file_location("prepare_native", ROOT / "packaging/prepare_native.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    wheel = tmp_path / "supplier.whl"
    with zipfile.ZipFile(wheel, "w") as archive:
        archive.writestr("pkg/library.so", b"unit-test binary")
        archive.writestr("pkg/LICENSE", b"unit-test supplier notice")
    entry = {"path": "supplier.whl", "url": "https://example.invalid/supplier.whl", "sha256": build.sha256(wheel),
             "notice_members": ["pkg/LICENSE"]}
    payload = [{"path": "frozen/library.so", "sha256": hashlib.sha256(b"unit-test binary").hexdigest(), "kind": "binary"}]
    report = module.prepare({"schema": 1, "inputs": [entry]}, tmp_path, payload, offline=True)
    assert report["unmatched"] == []
    assert report["matches"][0]["member"] == "pkg/library.so"
    assert report["status"] == "evidence-only"
    assert b"unit-test supplier notice" in (tmp_path / report["notices"][0]["path"]).read_bytes()
    wheel.write_bytes(b"tampered")
    with pytest.raises(ValueError, match="hash"):
        module.prepare({"schema": 1, "inputs": [entry]}, tmp_path, payload, offline=True)


def test_unmodified_embedded_pyinstaller_loader_uses_its_exception(tmp_path):
    payload, coverage = coverage_fixture(tmp_path)
    coverage["schema"] = 2
    component = coverage["components"][0]
    payload[0]["path"] = "@bootloader"
    component["payload"] = [dict(payload[0])]
    component["license"] = "GPL-2.0-or-later WITH Bootloader-exception"
    component["exception_basis"] = "UNIT TEST supplier loader, unmodified, embedded in combined executable"
    for key in ("sources", "source_basis", "build_instructions"):
        component.pop(key)
    assert build.validate_native_coverage(payload, coverage, tmp_path) == coverage
    component.pop("exception_basis")
    with pytest.raises(ValueError, match="exception"):
        build.validate_native_coverage(payload, coverage, tmp_path)


def test_checked_in_supplier_notices_are_hash_bound_and_nonempty():
    evidence = json.loads((ROOT / "packaging/provenance/supplier-evidence.json").read_text())
    lock = json.loads((ROOT / "packaging/provenance/source-inputs.lock.json").read_text())
    locked = {entry["path"]: entry for entry in lock["inputs"]}
    assert len(locked) == len(lock["inputs"])
    assert evidence["status"] == "evidence-only"
    for notice in evidence["notices"]:
        assert notice["input"] in locked
        assert build.coverage_asset(ROOT / "packaging", notice).stat().st_size
    assert all(match["input"] in locked for match in evidence["matches"])
    assert "@bootloader" in evidence["unmatched"]  # raw-wheel matcher does not claim prefix equivalence


@pytest.mark.parametrize("mutation", ["path", "duplicate", "missing-member", "missing-cache"])
def test_supplier_evidence_refuses_unsafe_or_incomplete_inputs(tmp_path, mutation):
    import importlib.util
    spec = importlib.util.spec_from_file_location("prepare_native", ROOT / "packaging/prepare_native.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    source = tmp_path / "notice.txt"
    source.write_bytes(b"unit test notice")
    entry = {"path": source.name, "sha256": build.sha256(source), "url": "https://example.invalid/notice", "notice_members": ["@file"]}
    entries = [entry]
    if mutation == "path":
        entry["path"] = "../notice.txt"
    elif mutation == "duplicate":
        entries.append(dict(entry))
    elif mutation == "missing-member":
        entry["notice_members"] = ["missing"]
    elif mutation == "missing-cache":
        source.unlink()
    with pytest.raises(ValueError):
        module.prepare({"schema": 1, "inputs": entries}, tmp_path, [], offline=True)


def test_supplier_download_is_pinned_and_does_not_cache_bad_bytes(tmp_path, monkeypatch):
    import importlib.util
    import hashlib
    spec = importlib.util.spec_from_file_location("prepare_native", ROOT / "packaging/prepare_native.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    def response(url, **kwargs):
        stream = io.BytesIO(b"verified unit-test supplier notice")
        stream.url = url
        return stream
    monkeypatch.setattr(module, "urlopen", response)
    entry = {"path": "notice.txt", "sha256": hashlib.sha256(b"verified unit-test supplier notice").hexdigest(),
             "url": "https://example.invalid/notice", "notice_members": ["@file"]}
    report = module.prepare({"schema": 1, "inputs": [entry]}, tmp_path, [])
    assert report["notices"]
    assert (tmp_path / "notice.txt").read_bytes() == b"verified unit-test supplier notice"
    entry = {**entry, "path": "bad.txt", "sha256": "0" * 64}
    with pytest.raises(ValueError, match="hash"):
        module.prepare({"schema": 1, "inputs": [entry]}, tmp_path, [])
    assert not (tmp_path / "bad.txt").exists()
    assert {p.name for p in tmp_path.iterdir()} == {"notice.txt", "notice.txt.notices.txt"}


def test_mpl_file_level_source_does_not_require_relink_recipe(tmp_path):
    payload, coverage = coverage_fixture(tmp_path)
    coverage["schema"] = 2
    component = coverage["components"][0]
    component["license"] = "MPL-2.0"
    component.pop("build_instructions")
    assert build.validate_native_coverage(payload, coverage, tmp_path) == coverage
    component["sources"] = []
    with pytest.raises(ValueError, match="sources"):
        build.validate_native_coverage(payload, coverage, tmp_path)


def test_native_coverage_requires_component_notices_and_exact_source(tmp_path):
    payload, coverage = coverage_fixture(tmp_path)
    assert build.validate_native_coverage(payload, coverage, tmp_path) == coverage


@pytest.mark.parametrize("mutation", ["notice", "source", "hash", "stale", "duplicate", "static", "generic", "traversal", "platform"])
def test_native_coverage_rejects_incomplete_or_mismatched_evidence(tmp_path, mutation):
    payload, coverage = coverage_fixture(tmp_path)
    component = coverage["components"][0]
    if mutation == "notice":
        component["notices"] = []
    elif mutation == "source":
        component["sources"] = []
    elif mutation == "hash":
        (tmp_path / "source.zip").write_bytes(b"changed")
    elif mutation == "stale":
        component["payload"][0] = {**payload[0], "sha256": "b" * 64}
    elif mutation == "duplicate":
        coverage["components"].append({**component, "id": "duplicate"})
    elif mutation == "static":
        component["statically_linked"] = ["unknown-vendored-library"]
    elif mutation == "generic":
        component["sources"] = [{**component["notices"][0], "url": "https://example.invalid/LICENSE"}]
    elif mutation == "traversal":
        component["notices"][0]["path"] = "../notice.txt"
    elif mutation == "platform":
        coverage["system"] = "NotThisOS"
    with pytest.raises(ValueError):
        build.validate_native_coverage(payload, coverage, tmp_path)


@pytest.mark.parametrize("magic", [b"\x7fELF", b"MZ\x90\x00", b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbe"])
def test_inventory_reads_actual_archive_bytes_including_mislabelled_native_data(tmp_path, magic):
    from PyInstaller.archive.writers import CArchiveWriter
    binary = tmp_path / "binary"
    binary.write_bytes(magic + b"UNIT TEST native payload")
    data = tmp_path / "data"
    data.write_bytes(b"not native")
    pkg = tmp_path / "payload.pkg"
    CArchiveWriter(str(pkg), [("library", str(binary), True, "b"),
                             ("disguised-data", str(binary), True, "x"),
                             ("readme", str(data), True, "x"),
                             ("alias", "library", False, "n")], "python")
    executable = tmp_path / "app"
    executable.write_bytes(magic + b"UNIT TEST bootloader" + pkg.read_bytes())
    inventory = build.native_inventory(executable)
    assert {entry["path"] for entry in inventory} == {"@bootloader", "library", "disguised-data", "alias"}
    assert next(e for e in inventory if e["path"] == "library")["sha256"] == build.sha256(binary)
    assert next(e for e in inventory if e["path"] == "alias")["target"] == "library"


def test_inventory_includes_onedir_transitive_libraries(tmp_path):
    from PyInstaller.archive.writers import CArchiveWriter
    pkg = tmp_path / "payload.pkg"
    CArchiveWriter(str(pkg), [], "python")
    executable = tmp_path / "app"
    executable.write_bytes(b"\xcf\xfa\xed\xfeUNIT TEST bootloader" + pkg.read_bytes())
    internal = tmp_path / "_internal"
    internal.mkdir()
    (internal / "libunanticipated.dylib").write_bytes(b"\xcf\xfa\xed\xfeUNIT TEST transitive")
    assert {entry["path"] for entry in build.native_inventory(executable)} == {
        "@bootloader", "libunanticipated.dylib"}


def test_widget_hook_does_not_collect_qml_svg_pdf_or_virtual_keyboard(monkeypatch):
    import runpy
    import PyInstaller.utils.hooks.qt as qt
    plugins = ["platforms/libqxcb.so", "platforms/libqoffscreen.so", "platforms/qwindows.dll",
               "platforms/libqcocoa.dylib", "imageformats/libqpdf.so", "imageformats/libqsvg.so",
               "platforminputcontexts/libqtvirtualkeyboardplugin.so", "platforms/libqwayland-egl.so"]
    monkeypatch.setattr(qt, "add_qt6_dependencies", lambda path: (
        ["PySide6.QtCore"], [("/plugins/" + p, "PySide6/Qt/plugins/" + p.rsplit("/", 1)[0]) for p in plugins], []))
    result = runpy.run_path(str(ROOT / "packaging/hook-PySide6.QtGui.py"))
    assert {Path(src).name for src, _ in result["binaries"]} == {
        "libqxcb.so", "libqoffscreen.so", "qwindows.dll", "libqcocoa.dylib"}
    assert result["hiddenimports"] == ["PySide6.QtCore"]


def test_native_evidence_is_embedded_and_checked_against_final_payload(tmp_path, monkeypatch):
    payload, coverage = coverage_fixture(tmp_path)
    manifest = tmp_path / "coverage.json"
    manifest.write_text(json.dumps(coverage))
    compliance = build.compliance_archive(tmp_path)
    build.add_native_compliance(compliance, payload, manifest)
    monkeypatch.setattr(build, "native_inventory", lambda executable: payload)
    build.verify_native_compliance(tmp_path / "app", compliance)
    with zipfile.ZipFile(compliance) as archive:
        assert archive.read("native/files/notice.txt") == (tmp_path / "notice.txt").read_bytes()
        assert archive.read("native/files/source.zip") == (tmp_path / "source.zip").read_bytes()
        assert json.loads(archive.read("native/payload.json")) == payload
    monkeypatch.setattr(build, "native_inventory", lambda executable: [{**payload[0], "sha256": "b" * 64}])
    with pytest.raises(ValueError, match="payload"):
        build.verify_native_compliance(tmp_path / "app", compliance)


def test_packaging_refuses_to_offer_uncovered_native_candidate(tmp_path, monkeypatch):
    def fake_run(*args):
        if "PyInstaller" not in args:
            return
        from PyInstaller.archive.writers import CArchiveWriter
        stage = Path(args[args.index("--distpath") + 1])
        pkg = tmp_path / "unit.pkg"
        CArchiveWriter(str(pkg), [], "python")
        (stage / build.NAME).write_bytes(b"\x7fELFUNIT TEST bootloader" + pkg.read_bytes())
    monkeypatch.setattr(build.platform, "system", lambda: "Linux")
    monkeypatch.setattr(build.platform, "machine", lambda: "x86_64")
    monkeypatch.setattr(build, "run", fake_run)
    monkeypatch.setattr(build, "smoke_artifact", lambda executable, screenshot, source: screenshot.write_bytes(b"UNIT TEST PNG"))
    output = tmp_path / "output"
    with pytest.raises(ValueError, match="Uncovered native payload"):
        build.main(["--output", str(output)])
    assert not (output / build.NAME).exists()
    report = json.loads((output / "native-payload-review.json").read_text())
    assert report["status"] == "unqualified"
    assert report["payload"][0]["path"] == "@bootloader"


def test_elf_loader_digest_ignores_only_appended_archive_section_offset(tmp_path):
    from PyInstaller.archive.writers import CArchiveWriter
    import struct
    pkg = tmp_path / "unit.pkg"
    CArchiveWriter(str(pkg), [], "python")
    prefix = bytearray(b"\x7fELF\x02\x01" + b"\0" * 90)
    executable = tmp_path / "app"
    struct.pack_into("<Q", prefix, 40, 12345)
    executable.write_bytes(prefix + pkg.read_bytes())
    first = build.native_inventory(executable)
    struct.pack_into("<Q", prefix, 40, 54321)
    executable.write_bytes(prefix + pkg.read_bytes())
    assert build.native_inventory(executable) == first
    prefix[80] = 1
    executable.write_bytes(prefix + pkg.read_bytes())
    assert build.native_inventory(executable) != first


def test_native_packaging_rejects_reusing_output_with_old_artifact(tmp_path, monkeypatch):
    def should_not_build(*args):
        pytest.fail("started freezing into an output with stale artifacts")
    monkeypatch.setattr(build, "run", should_not_build)
    output = tmp_path / "output"
    output.mkdir()
    (output / build.NAME).write_bytes(b"old unqualified artifact")
    with pytest.raises(ValueError, match="empty output"):
        build.main(["--output", str(output)])


def test_mac_launcher_inventory_uses_selected_native_tree(tmp_path, monkeypatch):
    launcher = tmp_path / f"{build.NAME}.app/Contents/MacOS/{build.NAME}"
    expected = launcher.parents[1] / f"Resources/runtimes/arm64/{build.NAME}/{build.NAME}"
    monkeypatch.setattr(build.platform, "system", lambda: "Darwin")
    monkeypatch.setattr(build.platform, "machine", lambda: "arm64")
    assert build.inventory_target(launcher) == expected
    assert build.inventory_target(tmp_path / build.NAME) == tmp_path / build.NAME


def test_source_snapshot_preserves_nested_component_notices(tmp_path, monkeypatch):
    root = tmp_path / "repo/desktop-builder"
    notice = root / "packaging/licenses/native/component.copyright"
    notice.parent.mkdir(parents=True)
    notice.write_bytes(b"specific native component notice")
    lock = root / "packaging/provenance/source-inputs.lock.json"
    lock.parent.mkdir()
    lock.write_text('{"schema": 1, "inputs": []}')
    monkeypatch.setattr(build, "ROOT", root)
    monkeypatch.setattr(build, "REPOSITORY", root.parent)
    target = tmp_path / "source.zip"
    build.source_archive(target)
    with zipfile.ZipFile(target) as archive:
        assert archive.read("desktop-builder/packaging/licenses/native/component.copyright") == notice.read_bytes()
        assert archive.read("desktop-builder/packaging/provenance/source-inputs.lock.json") == lock.read_bytes()


def test_unicode_data_archive_is_preferred_source_not_license_only(tmp_path):
    payload, coverage = coverage_fixture(tmp_path)
    source = tmp_path / "source.zip"
    with zipfile.ZipFile(source, "w") as archive:
        archive.writestr("UnicodeData.txt", "0041;LATIN CAPITAL LETTER A;Lu;0;L;;;;;N;;;;0061;\n")
    coverage["components"][0]["sources"][0]["sha256"] = build.sha256(source)
    assert build.validate_native_coverage(payload, coverage, tmp_path) == coverage


def test_license_only_zip_is_not_corresponding_source(tmp_path):
    payload, coverage = coverage_fixture(tmp_path)
    source = tmp_path / "source.zip"
    with zipfile.ZipFile(source, "w") as archive:
        archive.writestr("LICENSE", "generic LGPL text is not source")
    coverage["components"][0]["sources"][0]["sha256"] = build.sha256(source)
    with pytest.raises(ValueError, match="source material"):
        build.validate_native_coverage(payload, coverage, tmp_path)


def runtime_tar(path, name="OpenAutoLink-Builder/runtime", link=None):
    with tarfile.open(path, "w:gz") as archive:
        member = tarfile.TarInfo(name)
        if link is not None:
            member.type = tarfile.SYMTYPE
            member.linkname = link
            archive.addfile(member)
        else:
            data = b"runtime"
            member.size = len(data)
            member.mode = 0o755
            archive.addfile(member, io.BytesIO(data))


def test_runtime_extraction_preserves_executables_and_internal_links(tmp_path):
    if sys.platform == "win32":
        pytest.skip("Mac framework symlinks and POSIX modes are tested on Linux and both Macs")
    archive = tmp_path / "runtime.tar.gz"
    with tarfile.open(archive, "w:gz") as tar:
        member = tarfile.TarInfo(build.NAME + "/runtime")
        member.size, member.mode = 3, 0o755
        tar.addfile(member, io.BytesIO(b"bin"))
        link = tarfile.TarInfo(build.NAME + "/link")
        link.type, link.linkname = tarfile.SYMTYPE, "runtime"
        tar.addfile(link)
    target = tmp_path / "target"
    build.extract_runtime(archive, target)
    assert (target / build.NAME / "runtime").stat().st_mode & 0o111
    assert (target / build.NAME / "link").read_bytes() == b"bin"


@pytest.mark.parametrize("name,link", [
    ("../escape", None), ("/absolute", None), ("other-root/file", None),
    ("OpenAutoLink-Builder/link", "../../escape"),
    ("OpenAutoLink-Builder/link", "/etc/passwd"),
    ("OpenAutoLink-Builder/../escape", None),
])
def test_runtime_extraction_rejects_unsafe_members(tmp_path, name, link):
    archive = tmp_path / "unsafe.tar.gz"
    runtime_tar(archive, name, link)
    with pytest.raises(ValueError):
        build.extract_runtime(archive, tmp_path / "target")


def test_dual_runtime_app_uses_one_launcher_and_keeps_quoted_arguments(tmp_path):
    if sys.platform == "win32":
        pytest.skip("POSIX launcher is exercised natively on both Mac runners")
    runtimes = {}
    for arch in ("arm64", "x86_64"):
        runtime = tmp_path / arch / build.NAME
        runtime.mkdir(parents=True)
        executable = runtime / build.NAME
        executable.write_text('#!/bin/sh\nprintf "%s\\n" "' + arch + '" "$@"\n')
        executable.chmod(0o755)
        runtimes[arch] = runtime
    app = build.assemble_mac_app(runtimes, tmp_path / "stage with spaces")
    assert list(app.parent.glob("*.app")) == [app]
    assert not list((app / "Contents/Resources").rglob("*.app"))
    with (app / "Contents/Info.plist").open("rb") as reader:
        assert plistlib.load(reader)["CFBundleExecutable"] == build.NAME
    argument = "literal spaces; $(touch SHOULD_NOT_EXIST)"
    result = subprocess.run([app / "Contents/MacOS" / build.NAME, argument], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    assert result.stdout.splitlines() == [platform.machine(), argument]
    assert not (tmp_path / "SHOULD_NOT_EXIST").exists()


@pytest.mark.parametrize("system,machine,expected", [
    ("Linux", "x86_64", "OpenAutoLink-Builder"),
    ("Windows", "AMD64", "OpenAutoLink-Builder.exe"),
    ("Darwin", "arm64", "OpenAutoLink-Builder-macos-arm64.runtime.tar.gz"),
])
def test_native_build_offers_direct_payload_and_hashes_every_output(tmp_path, monkeypatch, system, machine, expected):
    commands, smoked = [], []
    monkeypatch.setattr(build.platform, "system", lambda: system)
    monkeypatch.setattr(build.platform, "machine", lambda: machine)
    import hashlib
    from PyInstaller.archive.writers import CArchiveWriter
    prefix = b"\x7fELFUNIT TEST stand-in native bootloader"
    payload, coverage = coverage_fixture(tmp_path)
    coverage["components"][0]["payload"] = [{"path": "@bootloader", "sha256": hashlib.sha256(prefix).hexdigest()}]
    coverage_path = tmp_path / "coverage.json"
    coverage_path.write_text(json.dumps(coverage))
    def fake_run(*args):
        commands.append([str(a) for a in args])
        if "PyInstaller" in args:
            frozen_root = Path(args[args.index("--paths") + 1])
            assert frozen_root != ROOT, "Freeze the embedded source snapshot, not a concurrently editable checkout"
            assert (frozen_root / "oal_builder/__main__.py").read_bytes() == (ROOT / "oal_builder/__main__.py").read_bytes()
            assert Path(args[-1]) == frozen_root / "oal_builder/__main__.py"
            stage = Path(args[args.index("--distpath") + 1])
            native = stage / build.NAME / build.NAME if system == "Darwin" else stage / expected
            native.parent.mkdir(parents=True, exist_ok=True)
            pkg = tmp_path / "unit.pkg"
            CArchiveWriter(str(pkg), [], "python")
            native.write_bytes(prefix + pkg.read_bytes())
            native.chmod(0o755)
    def fake_smoke(executable, screenshot, source_sha256):
        smoked.append(executable)
        screenshot.write_bytes(b"UNIT TEST screenshot")
    monkeypatch.setattr(build, "run", fake_run)
    monkeypatch.setattr(build, "smoke_artifact", fake_smoke, raising=False)
    output = tmp_path / "output"
    assert build.main(["--output", str(output), "--native-coverage", str(coverage_path)]) == 0
    assert (output / expected).is_file()
    command = next(c for c in commands if "PyInstaller" in c)
    assert len([c for c in commands if "PyInstaller" in c]) == 2
    assert "--additional-hooks-dir" in command
    assert "--runtime-hook" in command
    assert any("builder-compliance.zip:." in c for c in command)
    assert ("--onedir" if system == "Darwin" else "--onefile") in command
    if system != "Darwin":
        assert smoked == [output / expected]
        assert not list(output.glob("*.zip")) and not list(output.glob("*.tar.gz"))
    manifest = json.loads(next(output.glob("*.json")).read_text())
    offered = {p.name for p in output.iterdir() if p.suffix != ".json"}
    assert set(manifest["files"]) == offered
    for name, digest in manifest["files"].items():
        assert build.sha256(output / name) == digest
    assert manifest["artifact"] == expected
    assert manifest["builderSourceSha256"]


def test_source_archive_is_deterministic_across_checkout_times(tmp_path, monkeypatch):
    root = tmp_path / "repo/desktop-builder"
    (root / "oal_builder").mkdir(parents=True)
    source = root / "oal_builder/main.py"
    source.write_bytes(b"print('same bytes')\n")
    monkeypatch.setattr(build, "ROOT", root)
    monkeypatch.setattr(build, "REPOSITORY", root.parent)
    first, second = tmp_path / "first.zip", tmp_path / "second.zip"
    build.source_archive(first)
    os.utime(source, (1000000000, 1000000000))
    build.source_archive(second)
    assert first.read_bytes() == second.read_bytes()


def stage_fixtures(tmp_path):
    incoming = tmp_path / "incoming"
    source = tmp_path / "source.zip"
    build.source_archive(source)
    for arch in ("arm64", "x86_64"):
        directory = incoming / arch
        directory.mkdir(parents=True)
        stem = f"{build.NAME}-macos-{arch}"
        archive = directory / f"{stem}.runtime.tar.gz"
        runtime_tar(archive, build.NAME + "/" + build.NAME)
        build.write_manifest(directory, stem, archive, [archive], system="Darwin", architecture=arch,
                             kind="internal-runtime-stage", frozenSmokeTest="passed",
                             builderSourceSha256=build.sha256(source), python="3.12.test")
    return incoming


def test_combine_mac_verifies_stages_and_creates_only_one_dmg(tmp_path, monkeypatch):
    if sys.platform == "win32":
        pytest.skip("Mac Applications symlink assembly is tested on Linux and both Macs")
    incoming = stage_fixtures(tmp_path)
    monkeypatch.setattr(build.platform, "system", lambda: "Darwin")
    commands = []
    def fake_native(*args):
        commands.append(args)
        if args[:2] == ("hdiutil", "create"):
            stage = Path(args[args.index("-srcfolder") + 1])
            assert len(list(stage.glob("*.app"))) == 1
            assert (stage / "Applications").is_symlink()
            Path(args[-1]).write_bytes(b"UNIT TEST ONLY: native DMG tool stand-in")
    monkeypatch.setattr(build, "run", fake_native)
    output = tmp_path / "output"
    build.combine_mac(incoming, output)
    manifest = json.loads((output / f"{build.NAME}-macos.json").read_text())
    assert manifest["architecture"] == "dual-runtime-arm64-x86_64"
    assert manifest["frozenSmokeTest"] == "pending-combined-native-gates"
    assert manifest["artifact"] == build.NAME + ".dmg"
    assert set(manifest["files"]) == {build.NAME + ".dmg"}
    assert len(list(output.glob("*.dmg"))) == 1
    assert len([c for c in commands if c[:2] == ("lipo", "-verify_arch")]) == 2


@pytest.mark.parametrize("mutation", ["bytes", "source", "architecture", "traversal"])
def test_combine_mac_rejects_mismatched_stages(tmp_path, monkeypatch, mutation):
    incoming = stage_fixtures(tmp_path)
    monkeypatch.setattr(build.platform, "system", lambda: "Darwin")
    path = incoming / "arm64" / f"{build.NAME}-macos-arm64.json"
    manifest = json.loads(path.read_text())
    if mutation == "bytes":
        (path.parent / manifest["artifact"]).write_bytes(b"tampered")
    elif mutation == "source":
        manifest["builderSourceSha256"] = "0" * 64
    elif mutation == "architecture":
        manifest["architecture"] = "x86_64"
    else:
        manifest["files"]["../escape"] = "0" * 64
    path.write_text(json.dumps(manifest))
    monkeypatch.setattr(build, "run", lambda *args: None)
    with pytest.raises(ValueError):
        build.combine_mac(incoming, tmp_path / "output")


def test_compliance_archive_embeds_exact_source_and_licenses(tmp_path):
    archive = build.compliance_archive(tmp_path)
    with zipfile.ZipFile(archive) as bundle:
        assert bundle.read("licenses/LICENSE") == (ROOT.parent / "LICENSE").read_bytes()
        assert "licenses/runtime-versions.json" in bundle.namelist()
        source = tmp_path / "source.zip"
        source.write_bytes(bundle.read("builder-source.zip"))
    with zipfile.ZipFile(source) as snapshot:
        name = "desktop-builder/packaging/build.py"
        assert snapshot.read(name) == (ROOT / "packaging/build.py").read_bytes()
        assert not any(".venv" in n or "dist/" in n for n in snapshot.namelist())


def test_runtime_info_reports_actual_child_architecture_without_gui(tmp_path):
    target = tmp_path / "info.json"
    hook = ROOT / "packaging/runtime_hook.py"
    script = "import sys,runpy; sys.frozen=True; sys.argv=['app','--runtime-info',sys.argv[1]]; runpy.run_path(" + repr(str(hook)) + ")"
    result = subprocess.run([sys.executable, "-c", script, str(target)], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    info = json.loads(target.read_text())
    assert info["architecture"] == platform.machine().lower().replace("amd64", "x86_64")
    assert info["frozen"] is True


@pytest.mark.parametrize("fail_smoke", [False, True])
def test_final_mac_smoke_uses_mounted_launcher_and_detaches_on_failure(tmp_path, monkeypatch, fail_smoke):
    package_dir = tmp_path / "package"
    package_dir.mkdir()
    dmg = package_dir / (build.NAME + ".dmg")
    dmg.write_bytes(b"UNIT TEST ONLY: DMG bytes")
    build.write_manifest(package_dir, build.NAME + "-macos", dmg, [dmg],
                         kind="dual-runtime-dmg", builderSourceSha256="a" * 64)
    monkeypatch.setattr(build.platform, "system", lambda: "Darwin")
    monkeypatch.setattr(build.platform, "machine", lambda: "arm64")
    commands, launched = [], []
    def native(*args):
        commands.append(args)
        if args[:2] == ("hdiutil", "attach"):
            mount = Path(args[args.index("-mountpoint") + 1])
            (mount / f"{build.NAME}.app/Contents/MacOS").mkdir(parents=True, exist_ok=True)
            (mount / f"{build.NAME}.app/Contents/MacOS/{build.NAME}").write_bytes(b"launcher")
    def smoke(executable, screenshot, source_sha):
        launched.append(executable)
        assert f"{build.NAME}.app/Contents/MacOS/{build.NAME}" in executable.as_posix()
        if fail_smoke:
            raise ValueError("injected native smoke failure")
        screenshot.write_bytes(b"UNIT TEST ONLY: screenshot")
    monkeypatch.setattr(build, "run", native)
    monkeypatch.setattr(build, "smoke_artifact", smoke)
    evidence = tmp_path / "evidence"
    if fail_smoke:
        with pytest.raises(ValueError, match="injected"):
            build.smoke_mac(package_dir, evidence, "arm64")
        assert not (evidence / "smoke-macos-arm64.json").exists()
    else:
        build.smoke_mac(package_dir, evidence, "arm64")
        report = json.loads((evidence / "smoke-macos-arm64.json").read_text())
        assert report["packageSha256"] == build.sha256(dmg)
        assert report["architecture"] == "arm64"
    assert launched
    assert commands[-1][:2] == ("hdiutil", "detach")


@pytest.mark.parametrize("wrong_arch", [False, True])
def test_smoke_checks_actual_frozen_child_architecture(tmp_path, monkeypatch, wrong_arch):
    compliance = build.compliance_archive(tmp_path)
    payload, coverage = coverage_fixture(tmp_path)
    coverage_path = tmp_path / "coverage.json"
    coverage_path.write_text(json.dumps(coverage))
    build.add_native_compliance(compliance, payload, coverage_path)
    monkeypatch.setattr(build, "native_inventory", lambda executable: payload)
    called = []
    def fake_child(args, **kwargs):
        called.append(args[1])
        target = Path(args[-1])
        if args[1] == "--runtime-info":
            target.write_text(json.dumps({"architecture": "wrong" if wrong_arch else platform.machine().lower().replace("amd64", "x86_64"),
                                          "system": platform.system(), "frozen": True}))
        elif args[1] == "--export-compliance":
            target.write_bytes(compliance.read_bytes())
        elif args[1] == "--smoke-test":
            target.write_bytes(b"\x89PNG\r\n\x1a\n")
    monkeypatch.setattr(build.subprocess, "run", fake_child)
    if wrong_arch:
        with pytest.raises(ValueError, match="architecture"):
            build.smoke_artifact(tmp_path / "fake-child", tmp_path / "shot.png", build.sha256(tmp_path / "builder-source.zip"))
    else:
        build.smoke_artifact(tmp_path / "fake-child", tmp_path / "shot.png", build.sha256(tmp_path / "builder-source.zip"))
        assert called == ["--runtime-info", "--export-compliance", "--smoke-test"]


@pytest.mark.parametrize("mutation", [None, "hash", "missing", "architecture"])
def test_mac_qualification_requires_both_matching_native_reports(tmp_path, mutation):
    package_dir = tmp_path / "package"
    package_dir.mkdir()
    dmg = package_dir / (build.NAME + ".dmg")
    dmg.write_bytes(b"UNIT TEST ONLY: DMG bytes")
    build.write_manifest(package_dir, build.NAME + "-macos", dmg, [dmg],
                         kind="dual-runtime-dmg", builderSourceSha256="a" * 64)
    evidence = tmp_path / "evidence"
    for arch in ("arm64", "x86_64"):
        if mutation == "missing" and arch == "x86_64":
            continue
        folder = evidence / arch
        folder.mkdir(parents=True)
        screenshot = folder / f"smoke-macos-{arch}.png"
        screenshot.write_bytes(b"UNIT TEST ONLY: screenshot")
        build.write_manifest(folder, f"smoke-macos-{arch}", screenshot, [screenshot],
                             architecture="wrong" if mutation == "architecture" else arch,
                             packageSha256="0" * 64 if mutation == "hash" else build.sha256(dmg),
                             builderSourceSha256="a" * 64, frozenSmokeTest="passed",
                             scope="combined-dmg-launcher-native-runtime-and-ui")
    if mutation:
        with pytest.raises((ValueError, FileNotFoundError)):
            build.qualify_mac(package_dir, evidence)
    else:
        build.qualify_mac(package_dir, evidence)
        manifest = build.read_manifest(package_dir / f"{build.NAME}-macos.json")
        assert manifest["frozenSmokeTest"] == "passed-on-arm64-and-x86_64"
        assert set(manifest["nativeSmokeTests"]) == {"arm64", "x86_64"}
        assert set(manifest["files"]) == {build.NAME + ".dmg", "smoke-macos-arm64.png", "smoke-macos-x86_64.png"}


@pytest.mark.parametrize("xcb", [False, True])
def test_linux_native_gate_requires_xcb_display_and_hashes_evidence(tmp_path, monkeypatch, xcb):
    package = tmp_path / build.NAME
    package.write_bytes(b"UNIT TEST ONLY: executable")
    path = tmp_path / f"{build.NAME}-linux-x86_64.json"
    build.write_manifest(tmp_path, path.stem, package, [package], kind="direct-executable",
                         system="Linux", architecture="x86_64", builderSourceSha256="a" * 64)
    monkeypatch.setattr(build.platform, "system", lambda: "Linux")
    monkeypatch.setenv("QT_QPA_PLATFORM", "xcb" if xcb else "offscreen")
    monkeypatch.setenv("DISPLAY", ":99")
    monkeypatch.setattr(build, "smoke_artifact", lambda executable, screenshot, source: screenshot.write_bytes(b"PNG unit fixture"))
    if not xcb:
        with pytest.raises(ValueError, match="xcb"):
            build.smoke_linux_xcb(tmp_path)
    else:
        build.smoke_linux_xcb(tmp_path)
        manifest = build.read_manifest(path)
        assert manifest["nativePlatformSmoke"] == "xcb-display-passed"
        assert "smoke-linux-xcb.png" in manifest["files"]


def test_frozen_hook_exports_compliance_without_starting_ui(tmp_path):
    archive = tmp_path / "builder-compliance.zip"
    archive.write_bytes(b"test embedded bytes")
    target = tmp_path / "export with spaces.zip"
    script = ("import sys,runpy; sys._MEIPASS=sys.argv[1]; "
              "sys.argv=['app','--export-compliance',sys.argv[2]]; "
              "runpy.run_path(sys.argv_hook)")
    hook = ROOT / "packaging/runtime_hook.py"
    result = subprocess.run([sys.executable, "-c", "import sys; sys.argv_hook=" + repr(str(hook)) + ";" + script,
                             str(tmp_path), str(target)], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    assert target.read_bytes() == archive.read_bytes()

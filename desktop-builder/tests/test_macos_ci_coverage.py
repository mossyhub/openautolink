"""Target-manifest tests; these do not claim execution on macOS."""
import hashlib
import json
import importlib.util
from pathlib import Path

import pytest

PACKAGING = Path(__file__).resolve().parents[1] / "packaging"


def load_module():
    path = PACKAGING / "coverage_macos_ci.py"
    assert path.is_file(), "Mac coverage materializer is missing"
    spec = importlib.util.spec_from_file_location("macos_ci_under_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_exact_symlink_target_and_metadata_are_reviewed():
    module = load_module()
    target = "Versions/Current/QtCore"
    entry = {"path": "PySide6/Qt/lib/QtCore.framework/QtCore", "kind": "symlink",
             "sha256": hashlib.sha256(target.encode() + b"\0").hexdigest(),
             "size": len(target.encode()) + 1, "target": target}
    review = {"schema": 2, "system": "Darwin", "architecture": "arm64",
              "components": [{"payload": [entry]}]}
    assert module.validate_payload([entry], review) == [entry]
    with pytest.raises(ValueError, match="payload"):
        module.validate_payload([{**entry, "target": "../../outside"}], review)


def test_materializes_target_manifest_using_generic_asset_gate(tmp_path):
    module = load_module()
    assert hasattr(module, "materialize"), "Mac target materialization is missing"
    source = tmp_path / "assets"
    source.mkdir()
    (source / "notice.txt").write_bytes(b"Exact supplier notice\r\n")
    notice = {"path": "notice.txt", "sha256": hashlib.sha256((source / "notice.txt").read_bytes()).hexdigest()}
    entry = {"path": "lib.dylib", "kind": "binary", "size": 4, "sha256": "a" * 64}
    review = {"schema": 2, "system": "Darwin", "architecture": "arm64", "components": [
        {"id": "example", "version": "1", "license": "MIT", "provenance": "test fixture",
         "payload": [entry], "notices": [notice], "statically_linked": []}]}
    output = tmp_path / "result"
    result = module.materialize({"payload": [entry]}, review, source, output, cache=tmp_path)
    assert result == output / "coverage.json"
    assert json.loads(result.read_text()) == review
    assert (output / "notice.txt").read_bytes() == b"Exact supplier notice\r\n"
    with pytest.raises(ValueError):
        module.materialize({"payload": [entry]}, review, source, output, cache=tmp_path)


def test_xz_library_only_scope_is_verified_without_relabelling(tmp_path):
    module = load_module()
    review = json.loads((PACKAGING / "provenance/macos-arm64-ci-reviewed.json").read_text())
    xz = next(c for c in review["components"] if c["id"] == "cpython-static-xz")
    cffi = next(c for c in review["components"] if c["id"] == "cffi")
    cffi["statically_linked"] = [xz["id"]]
    review["components"] = [cffi, xz]
    result = module.materialize({"payload": cffi["payload"]}, review, PACKAGING,
                                tmp_path / "result", cache=tmp_path)
    actual = json.loads(result.read_text())
    assert actual == review
    assert actual["components"][1]["license"] == "LicenseRef-XZ-5.2.3-Public-Domain"
    xz["license_scope"] = "all XZ tools are public domain"
    with pytest.raises(ValueError, match="XZ"):
        module.materialize({"payload": cffi["payload"]}, review, PACKAGING,
                           tmp_path / "bad", cache=tmp_path)


@pytest.mark.parametrize("field,value", [("architecture", "x86_64"), ("identity", {}),
                                        ("system", "Linux"), ("collectionErrors", ["error"])])
def test_rejects_misattributed_discovery_before_copying(tmp_path, field, value):
    module = load_module()
    review = json.loads((PACKAGING / "provenance/macos-arm64-ci-reviewed.json").read_text())
    payload = [p for c in review["components"] for p in c["payload"]]
    document = {"schema": 1, "status": "UNQUALIFIED-DISCOVERY-ONLY", "system": "Darwin",
                "architecture": "arm64", "identity": review["discovery_identity"], "collectionErrors": [],
                "inventory": {"payload": payload, "artifactSha256": review["discovery_artifact_sha256"],
                              "builderSourceSha256": review["discovery_builder_source_sha256"]}}
    document[field] = value
    with pytest.raises(ValueError, match="discovery"):
        module.materialize(document, review, PACKAGING, tmp_path / "result", cache=tmp_path)
    assert not (tmp_path / "result").exists()


def test_cli_materialization_requires_explicit_target(tmp_path, capsys):
    module = load_module()
    assert hasattr(module, "main"), "Materialization CLI is missing"
    review = json.loads((PACKAGING / "provenance/macos-x86_64-ci-reviewed.json").read_text())
    review["components"] = [c for c in review["components"] if c["id"] == "cffi"]
    inventory = tmp_path / "inventory.json"
    inventory.write_text(json.dumps({"payload": review["components"][0]["payload"]}))
    review_path = tmp_path / "review.json"
    review_path.write_text(json.dumps(review))
    args = ["--inventory", str(inventory), "--review", str(review_path), "--architecture", "x86_64",
            "--cache", str(tmp_path), "--output", str(tmp_path / "result")]
    assert module.main(args) == 0
    assert json.loads((tmp_path / "result/coverage.json").read_text()) == review
    assert "not-native-qualified" in capsys.readouterr().out
    with pytest.raises(ValueError, match="architecture"):
        module.main(["arm64" if a == "x86_64" else a for a in args])


def test_xz_extension_does_not_weaken_generic_static_dependency_gate(tmp_path):
    module = load_module()
    review = json.loads((PACKAGING / "provenance/macos-arm64-ci-reviewed.json").read_text())
    review["components"] = [c for c in review["components"] if c["id"] in {"cffi", "cpython-static-xz"}]
    cffi = next(c for c in review["components"] if c["id"] == "cffi")
    del cffi["statically_linked"]
    with pytest.raises(ValueError, match="static"):
        module.materialize({"payload": cffi["payload"]}, review, PACKAGING,
                           tmp_path / "result", cache=tmp_path)


@pytest.mark.parametrize("arch", ["arm64", "x86_64"])
def test_full_checked_in_maps_and_assets(arch):
    module = load_module()
    review = json.loads((PACKAGING / f"provenance/macos-{arch}-ci-reviewed.json").read_text())
    payload = [p for c in review["components"] for p in c["payload"]]
    assert len(payload) == 97
    assert len({p["path"] for p in payload}) == 97
    assert module.validate_payload(payload, review) == payload
    assert review["architecture"] == arch
    assert review["discovery_identity"]["checkoutCommit"] == "40c10a5f68f6e7ecb17e95053da075df944314ba"
    components = {c["id"]: c for c in review["components"]}
    assert "qt-static-qeventdispatcher_cf" in components["qt-Core"]["statically_linked"]
    assert "qt-static-cocoa-platform-plugin" in components["qt-plugins"]["statically_linked"]
    assert components["cffi"]["statically_linked"] == []
    for component in components.values():
        assert set(component["statically_linked"]) <= components.keys()
        for asset in component["notices"] + ([component["build_instructions"]] if component.get("build_instructions") else []):
            assert hashlib.sha256((PACKAGING / asset["path"]).read_bytes()).hexdigest() == asset["sha256"]


@pytest.mark.parametrize("mutation", ["changed-hash", "missing", "extra", "duplicate", "kind", "size", "target"])
def test_changed_native_payload_is_rejected(mutation):
    import copy
    module = load_module()
    review = json.loads((PACKAGING / "provenance/macos-arm64-ci-reviewed.json").read_text())
    payload = copy.deepcopy([p for c in review["components"] for p in c["payload"]])
    binary = next(p for p in payload if p["kind"] == "binary")
    if mutation == "changed-hash":
        binary["sha256"] = "f" * 64
    elif mutation == "missing":
        payload.pop()
    elif mutation == "extra":
        payload.append({**binary, "path": "unknown.dylib"})
    elif mutation == "duplicate":
        payload.append(binary)
    elif mutation == "kind":
        binary["kind"] = "bootloader"
    elif mutation == "size":
        binary["size"] += 1
    else:
        link = next(p for p in payload if p["kind"] == "symlink")
        link["target"] = "Versions/Other/QtCore"
    with pytest.raises(ValueError, match="payload"):
        module.validate_payload(payload, review)

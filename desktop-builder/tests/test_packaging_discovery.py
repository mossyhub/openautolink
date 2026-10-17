"""Discovery artifacts are diagnostic-only, never substitute coverage approval."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "packaging/discovery_evidence.py"


def load_helper():
    assert HELPER.is_file(), "Safe native discovery collector is missing"
    spec = importlib.util.spec_from_file_location("discovery_evidence", HELPER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def inventory(tmp_path):
    data = {"status": "unqualified", "artifactSha256": "a" * 64,
            "builderSourceSha256": "b" * 64,
            "payload": [{"path": "@bootloader", "sha256": "c" * 64, "size": 10,
                         "kind": "bootloader", "hash_scope": "prefix-bytes"}]}
    path = tmp_path / "native-payload-review.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    return path, data


def test_inventory_is_retained_without_unrelated_dist_files(tmp_path):
    module = load_helper()
    source, expected = inventory(tmp_path)
    (tmp_path / "OpenAutoLink-Builder.exe").write_bytes(b"MZprivate candidate")
    (tmp_path / "upload-key.pem").write_text("PRIVATE KEY SECRET")
    (tmp_path / "builder-source.zip").write_bytes(b"private source workspace")
    result = module.read_inventory(source)
    assert result == expected
    assert "PRIVATE" not in json.dumps(result)
    assert "workspace" not in json.dumps(result)


def test_windows_normalized_loader_scope_is_retained(tmp_path):
    module = load_helper()
    source, data = inventory(tmp_path)
    data["payload"][0]["hash_scope"] = "PE-prefix-with-build-timestamps-and-checksum-zeroed"
    source.write_text(json.dumps(data), encoding="utf-8")
    assert module.read_inventory(source) == data
    data["payload"][0]["hash_scope"] = "PE-arbitrary-code-zeroed"
    source.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(ValueError, match="hash scope"):
        module.read_inventory(source)


def test_supplier_projection_preserves_notices_and_matches_real_bytes(tmp_path):
    from importlib.metadata import Distribution
    module = load_helper()
    info = tmp_path / "cffi-2.1.1.dist-info"
    info.mkdir()
    (info / "METADATA").write_text("Name: cffi\nVersion: 2.1.1\nLicense-Expression: MIT-0\n\nUNRELATED README SECRET")
    (info / "WHEEL").write_text("Wheel-Version: 1.0\nTag: cp312-cp312-win_amd64\n")
    notice = b"Copyright supplier\r\nMIT-0 notice\r\n"
    (info / "LICENSE").write_bytes(notice)
    (info / "direct_url.json").write_text('{"url":"https://token:SECRET@host/private"}')
    (info / "LICENSE.exe").write_bytes(b"MZforbidden")
    (tmp_path / "_cffi_backend.pyd").write_bytes(b"MZfixture binary")
    digest = hashlib.sha256(b"MZfixture binary").hexdigest()
    files = ["cffi-2.1.1.dist-info/" + name for name in ("METADATA", "WHEEL", "LICENSE", "direct_url.json", "LICENSE.exe")]
    (info / "RECORD").write_text("\n".join(name + ",," for name in files + ["_cffi_backend.pyd"]))
    result = module.wheel_evidence([{"path": "_cffi_backend.pyd", "sha256": digest}],
                                   distributions=[Distribution.at(info)])
    package = result[0]
    assert package["name"] == "cffi" and package["version"] == "2.1.1"
    assert package["wheelTags"] == ["cp312-cp312-win_amd64"]
    assert package["nativeMatches"] == [{"payload": "_cffi_backend.pyd", "member": "_cffi_backend.pyd", "sha256": digest}]
    assert package["notices"][0]["text"].encode() == notice
    assert package["notices"][0]["sha256"] == hashlib.sha256(notice).hexdigest()
    assert "SECRET" not in json.dumps(result)
    assert "forbidden" not in json.dumps(result)
    assert package["nativeCandidates"] == [{"member": "_cffi_backend.pyd", "sha256": digest,
                                               "size": len(b"MZfixture binary"), "matches": ["_cffi_backend.pyd"]}]
    changed = module.wheel_evidence([{"path": "_cffi_backend.pyd", "sha256": "e" * 64}],
                                    distributions=[Distribution.at(info)])
    assert changed[0]["nativeMatches"] == []
    assert changed[0]["nativeCandidates"][0]["sha256"] == digest
    assert changed[0]["nativeCandidates"][0]["matches"] == []


def test_cli_emits_one_diagnostic_json_with_exact_checkout_identity(tmp_path):
    source, expected = inventory(tmp_path)
    pip_report = tmp_path / "pip-report.json"
    pip_report.write_text(json.dumps({"install": [
        {"metadata": {"name": "cffi", "version": "2.1.1", "description": "SECRET"},
         "download_info": {"url": "https://files.pythonhosted.org/packages/example/cffi.whl",
                           "archive_info": {"hashes": {"sha256": "d" * 64}}}},
        {"metadata": {"name": "private", "version": "1"},
         "download_info": {"url": "https://user:SECRET@example.org/private.whl"}}]}))
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    output = tmp_path / "evidence.json"
    command = [sys.executable, str(HELPER), "--inventory", str(source), "--pip-report", str(pip_report),
               "--output", str(output), "--expected-commit", head, "--runner", "ubuntu-22.04",
               "--run-id", "1234", "--run-attempt", "2"]
    result = subprocess.run(command, text=True, capture_output=True, cwd=ROOT)
    assert result.returncode == 0, result.stderr
    assert output.is_file(), "Collector did not produce diagnostic evidence"
    data = json.loads(output.read_text())
    assert data["status"] == "UNQUALIFIED-DISCOVERY-ONLY"
    assert data["inventory"] == expected
    assert data["identity"]["checkoutCommit"] == head
    assert data["identity"]["runId"] == "1234"
    assert data["identity"]["runAttempt"] == "2"
    assert data["identity"]["runner"] == "ubuntu-22.04"
    assert data["wheelDownloads"] == [{"name": "cffi", "version": "2.1.1",
        "url": "https://files.pythonhosted.org/packages/example/cffi.whl", "sha256": "d" * 64}]
    assert data["python"]["version"]
    assert data["wheels"]
    assert "SECRET" not in output.read_text()
    assert str(ROOT) not in output.read_text()
    assert data["unmatchedPayload"] == ["@bootloader"]
    source.unlink()
    missing_output = tmp_path / "missing.json"
    command[command.index(str(output))] = str(missing_output)
    missing = subprocess.run(command, text=True, capture_output=True, cwd=ROOT)
    assert missing.returncode != 0
    assert not missing_output.exists()


def test_missing_supplier_report_retains_inventory_with_fixed_error(tmp_path):
    from argparse import Namespace
    module = load_helper()
    source, expected = inventory(tmp_path)
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    result = module.collect(Namespace(inventory=source, pip_report=tmp_path / "SECRET-missing.json",
                                     expected_commit=head, runner="local-diagnostic", run_id="0", run_attempt="0"))
    assert result["inventory"] == expected
    assert result["collectionErrors"] == ["wheelDownloads-unavailable"]
    assert "SECRET" not in json.dumps(result)


def test_symlink_inputs_and_output_are_rejected(tmp_path):
    module = load_helper()
    source, _ = inventory(tmp_path)
    link = tmp_path / "linked.json"
    try:
        link.symlink_to(source)
    except OSError:
        pytest.skip("Symlink creation is not enabled")
    with pytest.raises(ValueError):
        module.read_inventory(link)
    with pytest.raises(ValueError):
        module.notice(link, "LICENSE")
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    before = source.read_bytes()
    with pytest.raises(FileExistsError):
        module.main(["--inventory", str(source), "--pip-report", str(tmp_path / "absent.json"),
                     "--output", str(link), "--expected-commit", head, "--runner", "local-diagnostic",
                     "--run-id", "0", "--run-attempt", "0"])
    assert source.read_bytes() == before


def test_workflow_retains_only_diagnostics_after_failed_discovery():
    workflow = (ROOT.parent / ".github/workflows/build-desktop-builder.yml").read_text()
    assert "--report pip-install-report.json" in workflow
    assert "id: freeze" in workflow
    assert "failure() && steps.freeze.outcome == 'failure'" in workflow
    assert "hashFiles('desktop-builder/dist/native-payload-review.json') != ''" in workflow
    assert "!cancelled() && steps.discovery.outcome == 'success'" in workflow
    assert "path: desktop-builder/discovery/native-discovery-evidence.json" in workflow
    assert "UNQUALIFIED-native-discovery-${{ matrix.name }}-${{ github.sha }}-${{ github.run_attempt }}" in workflow
    assert "continue-on-error" not in workflow
    assert "needs: [native-package, smoke-final-mac]" in workflow


@pytest.mark.parametrize("mutation", ["extra", "traversal", "digest", "duplicate", "absolute-target"])
def test_inventory_rejects_unsafe_or_unexpected_content(tmp_path, mutation):
    module = load_helper()
    path, data = inventory(tmp_path)
    if mutation == "extra":
        data["privateKey"] = "SECRET"
    elif mutation == "traversal":
        data["payload"][0]["path"] = "../../identity.pem"
    elif mutation == "digest":
        data["payload"][0]["sha256"] = "SECRET"
    elif mutation == "duplicate":
        data["payload"].append(data["payload"][0])
    else:
        data["payload"][0]["target"] = "C:\\private\\identity.pem"
    path.write_text(json.dumps(data))
    with pytest.raises(ValueError):
        module.read_inventory(path)


@pytest.mark.parametrize("blob", [b"MZ\x00binary", b"-----BEGIN PRIVATE KEY-----\nSECRET\n-----END PRIVATE KEY-----"])
def test_notice_rejects_binary_or_private_key_content(tmp_path, blob):
    module = load_helper()
    path = tmp_path / "LICENSE.txt"
    path.write_bytes(blob)
    with pytest.raises(ValueError):
        module.notice(path, "LICENSE.txt")


def test_identity_rejects_wrong_or_malformed_commit():
    module = load_helper()
    for commit in ("f" * 40, "bad commit"):
        with pytest.raises(ValueError):
            module.identity(commit, "ubuntu-22.04", "1", "1")


def test_linux_supplier_reports_actual_owner_version_notice_and_hash():
    import platform
    if platform.system() != "Linux":
        pytest.skip("Native dpkg evidence requires Linux")
    library = Path("/usr/lib/x86_64-linux-gnu/libz.so.1")
    if not library.is_file():
        pytest.skip("Native dpkg zlib fixture unavailable")
    module = load_helper()
    digest = module.file_digest(library)
    result = module.linux_evidence([{"path": library.name, "sha256": digest}])
    package = next(item for item in result if item["package"].startswith("zlib1g"))
    assert package["version"] and package["sourceVersion"] and package["sourcePackage"]
    assert package["notices"][0]["text"]
    assert any(item["matches"] == [library.name] for item in package["nativeCandidates"])


def test_windows_version_projects_only_numeric_resource_fields(monkeypatch, tmp_path):
    from types import SimpleNamespace
    module = load_helper()
    class PE:
        def __init__(self, path, fast_load):
            self.VS_FIXEDFILEINFO = [SimpleNamespace(FileVersionMS=0x00010002, FileVersionLS=0x00030004,
                                                    ProductVersionMS=0x00050006, ProductVersionLS=0x00070008)]
        def parse_data_directories(self, directories):
            assert directories == [2]
        def close(self):
            pass
    monkeypatch.setitem(sys.modules, "pefile", SimpleNamespace(PE=PE, PEFormatError=ValueError))
    assert module.windows_version(tmp_path / "runtime.dll") == {"fileVersion": "1.2.3.4", "productVersion": "5.6.7.8"}


@pytest.mark.skipif(sys.platform != "win32", reason="Native PE resource validation")
def test_native_windows_python_version_resource():
    module = load_helper()
    version = module.windows_version(Path(sys.executable))
    assert version["fileVersion"].startswith(f"{sys.version_info.major}.{sys.version_info.minor}.")

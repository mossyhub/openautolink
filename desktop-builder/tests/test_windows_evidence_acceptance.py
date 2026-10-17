"""Synthetic report contract tests, never native execution/consent evidence."""
import copy
import hashlib
import json

import pytest

import test_windows_ci_coverage as contracts

PACKAGING = contracts.PACKAGING


def fixture():
    module, inventory, review, policy, loader = contracts.WindowsCoverageTests.bootstrap_fixture()
    identity = {"checkoutCommit": "1" * 40, "expectedCommit": "1" * 40,
                "trackedCheckoutDirty": False, "runner": "windows-2022",
                "runId": "123", "runAttempt": "1"}
    common = {"schema": 1, "notQualification": True, "identity": identity,
              "artifactSha256": inventory["artifactSha256"],
              "builderSourceSha256": inventory["builderSourceSha256"],
              "originalUnchanged": True, "sourceEvidenceVerified": True}
    payload = {e["path"]: e for e in inventory["payload"]}
    core = payload["PySide6/Qt6Core.dll"]
    match = next(m for m in review["supplier_matches"] if m["payload"] == core["path"])
    wheel = next(m for m in review["supplier_inputs"] if m["path"] == match["input"])
    nsis = json.loads((PACKAGING / "provenance/windows-setup.lock.json").read_text())["nsis"]
    reports = {"loaderProbe": {**loader, "identity": identity,
            "scope": "nonresource-PE-sections-with-debug-timestamps-zeroed-and-critical-headers",
            "supplierSha256": "7ed67be1a51d6d7c6d0d912b77e61c0950f8cdb7b9403f1de4da46c5506b337f"},
        "candidateSmoke": {**common, "status": "evidence-only",
            "screenshots": [{"platform": qpa, "sha256": "d" * 64} for qpa in ("windows", "offscreen")],
            "termsPreview": {"platform": "windows", "sha256": "e" * 64,
                             "termsToken": policy["pythonDownstream"]["termsToken"], "assent": "not-given"}},
        "recombinationProbe": {**common, "status": "passed",
            "scope": "native-windows-private-QtCore-overlay-replacement",
            "supplierWheelSha256": wheel["sha256"], "supplierMember": core["path"],
            "originalSha256": core["sha256"], "modifiedSha256": "f" * 64,
            "originalSize": core["size"], "modifiedSize": core["size"] + len(module._load("windows_recombine").MARKER),
            "mutation": "non-loaded-file-overlay-only", "archivedCoreSha256": "f" * 64,
            "replacementArtifactSha256": "9" * 64, "instrumentationSha256": "8" * 64,
            "screenshotSha256": "7" * 64, "originalCoverageRejected": True,
            "runtime": {"system": "Windows", "architecture": "x86_64", "frozen": True,
                "qtPlatform": "windows", "coreSha256": "f" * 64,
                "pluginSha256": payload["PySide6/plugins/platforms/qwindows.dll"]["sha256"],
                "bundledCore": True, "bundledPlugin": True, "builderWindow": True}},
        "bootstrapProbe": {**common, "status": "passed",
            "scope": "native-windows-private-bootstrap-installed-runtime",
            "humanInstallation": "not-tested-no-assent-automated", "outerSha256": "6" * 64,
            "screenshotSha256": "5" * 64, "extraction": "exact-payload-verified",
            "loader": {"status": "exact-nonresource-sections", "supplierStubSha256": nsis["stubSha256"],
                "supplierArchiveSha256": nsis["sha256"], "sections": [{"name": ".text", "sha256": "4" * 64}],
                "imports": sorted(["advapi32.dll", "shell32.dll", "ole32.dll", "comctl32.dll", "user32.dll", "gdi32.dll", "kernel32.dll"])}}}
    return module, inventory, review, policy, reports, identity


CORRECTED_COMPONENTS = frozenset({"qt-Core", "qt-Gui", "qt-Network", "qt-Widgets",
                                  "qt-plugins", "pyside-shiboken", "cffi", "cryptography"})


def test_bootstrap_mapping_removes_only_unsupported_edges_preserving_history():
    module, inventory, review, policy, evidence, identity = fixture()
    original = copy.deepcopy(review)
    result = module.minimal_review(inventory, review, policy, evidence["loaderProbe"])
    parents = [c for c in result["components"] if c["id"] in CORRECTED_COMPONENTS]
    assert len(parents) == 8
    assert sum(len(c["payload"]) for c in parents) == 19
    for parent in parents:
        historical = next(c for c in original["components"] if c["id"] == parent["id"])
        assert parent["statically_linked"] == [e for e in historical["statically_linked"] if e != "microsoft-msvc"]
        for field in ("license", "notices", "sources", "build_instructions", "provenance", "source_basis"):
            assert parent.get(field) == historical.get(field)
    assert review == original
    rationale = result["bootstrapMappingCorrection"]
    assert {i["component"] for i in rationale["items"]} == CORRECTED_COMPONENTS
    assert sum(len(i["payload"]) for i in rationale["items"]) == 19
    assert all(i["suppliers"] and i["notices"] for i in rationale["items"])


def test_correction_changes_no_other_component_or_supplier_record(monkeypatch):
    module, inventory, review, policy, evidence, identity = fixture()
    corrected = module.minimal_review(inventory, review, policy, evidence["loaderProbe"])
    monkeypatch.setattr(module, "_correct_bootstrap_static_mapping", lambda *args: None)
    before = module.minimal_review(inventory, review, policy, evidence["loaderProbe"])
    for component in before["components"]:
        if component["id"] in CORRECTED_COMPONENTS:
            component["statically_linked"].remove("microsoft-msvc")
    assert corrected["components"] == before["components"]
    for key in ("supplier_inputs", "supplier_matches", "source_inputs", "observed_payload"):
        assert corrected[key] == before[key]


def test_changed_notice_bytes_cannot_support_mapping_correction(tmp_path, monkeypatch):
    module, inventory, review, policy, evidence, identity = fixture()
    result = module.minimal_review(inventory, review, policy, evidence["loaderProbe"])
    for component in result["components"]:
        if component["id"] in CORRECTED_COMPONENTS:
            for asset in component["notices"]:
                target = tmp_path / asset["path"]
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((PACKAGING / asset["path"]).read_bytes())
    notice = next(c for c in result["components"] if c["id"] == "cffi")["notices"][0]
    (tmp_path / notice["path"]).write_bytes(b"changed notice")
    gate = module._load("build")
    monkeypatch.setattr(module, "_load", lambda name: gate)
    monkeypatch.setattr(module, "BASE", tmp_path)
    with pytest.raises(ValueError, match="changed native evidence.*cffi.*notices"):
        module._correct_bootstrap_static_mapping(result, review)


@pytest.mark.parametrize("mutation", ["wheel", "new-supplier", "unknown-supplier", "member", "notice", "license", "new-component"])
def test_mapping_correction_rejects_unreviewed_supplier_or_notice(mutation):
    module, inventory, review, policy, evidence, identity = fixture()
    parent = next(c for c in review["components"] if c["id"] == "cffi")
    supplier = next(s for s in review["supplier_inputs"] if s["path"].startswith("cffi-"))
    match = next(m for m in review["supplier_matches"] if m["payload"] == parent["payload"][0]["path"])
    if mutation == "wheel": supplier["sha256"] = "0" * 64
    if mutation == "new-supplier": review["supplier_inputs"].append({**supplier, "path": "new.whl"})
    if mutation == "unknown-supplier": match["input"] = "unknown.whl"
    if mutation == "member": match["member"] = "different.pyd"
    if mutation == "notice": parent["notices"][0]["sha256"] = "0" * 64
    if mutation == "license": parent["license"] = "MIT"
    if mutation == "new-component": parent["id"] = "new-cffi"
    with pytest.raises(ValueError, match="mapping correction"):
        module.minimal_review(inventory, review, policy, evidence["loaderProbe"])


def test_acceptance_uses_exact_compiled_supplier_grant_preserving_other_gates():
    module, inventory, review, policy, evidence, identity = fixture()
    review["blockers"].append({"code": "FUTURE_UNKNOWN_GATE", "detail": "retain me"})
    original = copy.deepcopy(review)
    result = module.accept_candidate_evidence(inventory, review, policy, evidence, identity=identity)
    codes = {b["code"] for b in result["blockers"]}
    assert codes == {"FUTURE_UNKNOWN_GATE", "WINDOWS_BOOTSTRAP_UNVALIDATED"}
    assert result["status"] == "blocked"
    assert review == original
    components = {c["id"]: c for c in result["components"]}
    static = components["microsoft-msvc"]
    original_static = next(c for c in original["components"] if c["id"] == static["id"])
    assert static["license"] == original_static["license"]  # not GPL relicensing
    assert static["notices"] == original_static["notices"]
    assert static["payload"] == []
    assert components["pyinstaller-embedded-loader"]["statically_linked"] == ["pyinstaller-static-zlib", "microsoft-msvc"]
    basis = static["distribution_basis"]
    assert basis["route"] == "pyinstaller-6.13.0-compiled-loader-combination"
    assert basis["supplierSha256"] == "8d4296236b85aae570379488c2da833b28828b17c57c2cc21fccd7e3811fe372"
    assert basis["member"] == "PyInstaller/bootloader/Windows-64bit-intel/runw.exe"
    assert basis["supplierMemberSha256"] == "7ed67be1a51d6d7c6d0d912b77e61c0950f8cdb7b9403f1de4da46c5506b337f"
    assert basis["copyingSha256"] == "dcf75fdb959db1e3b41c0f8505069d2ece781b5ec6b3d0a4d30975cfc6580245"
    assert basis["notice"]["sha256"] == "563755bbf4aaf6ffcb409adcaf1046cf69df26a669d6221de02bdb6cbf9ea70b"
    assert basis["candidateLoaderSha256"] == inventory["payload"][0]["sha256"]
    assert basis["candidateLoaderHashScope"] == evidence["loaderProbe"]["loaderHashScope"]
    assert "microsoft-cpython-embedded" in components["cpython"]["statically_linked"]
    assert "microsoft-msvc" not in components["cpython"]["statically_linked"]
    assert "microsoft-msvc" not in components["qt-Core"]["statically_linked"]
    assert components["microsoft-cpython-embedded"]["license"].startswith("LicenseRef-Microsoft-")
    assert components["microsoft-cpython-embedded"]["payload"] == []
    assert result["windowsEvidence"]["candidateSmoke"]["termsPreview"]["assent"] == "not-given"
    with pytest.raises(ValueError, match="WINDOWS_BOOTSTRAP_UNVALIDATED"):
        module.materialize(inventory, result, PACKAGING, PACKAGING / "must-not-create", cache=PACKAGING)


@pytest.mark.parametrize("mutation", ["version", "license", "scope", "notice", "static-notice", "zlib-edge", "unknown-parent"])
def test_compiled_grant_rejects_changed_review_scope(mutation):
    module, inventory, review, policy, evidence, identity = fixture()
    parent = next(c for c in review["components"] if c["id"] == "pyinstaller-embedded-loader")
    static = next(c for c in review["components"] if c["id"] == "microsoft-msvc")
    if mutation == "version": parent["version"] = "6.14.0"
    if mutation == "license": parent["license"] = "MIT"
    if mutation == "scope": parent["license_scope"] = "standalone or modified loaders too"
    if mutation == "notice": parent["notices"][-1]["sha256"] = "0" * 64
    if mutation == "static-notice": static["notices"] = []
    if mutation == "zlib-edge": parent["statically_linked"].remove("pyinstaller-static-zlib")
    if mutation == "unknown-parent":
        next(c for c in review["components"] if c["id"] == "pyinstaller-static-zlib")["statically_linked"].append("microsoft-msvc")
    with pytest.raises(ValueError, match="compiled-loader"):
        module.accept_candidate_evidence(inventory, review, policy, evidence, identity=identity)


@pytest.mark.parametrize("name,gate", [
    ("candidateSmoke", "WINDOWS_FROZEN_UI_UNTESTED"),
    ("recombinationProbe", "WINDOWS_RECOMBINATION_UNTESTED"),
    ("bootstrapProbe", "WINDOWS_INSTALLED_RUNTIME_UNTESTED")])
def test_missing_proof_retains_corresponding_blocker(name, gate):
    module, inventory, review, policy, evidence, identity = fixture()
    evidence.pop(name)
    result = module.accept_candidate_evidence(inventory, review, policy, evidence, identity=identity)
    assert gate in {b["code"] for b in result["blockers"]}
    assert result["status"] == "blocked"


@pytest.mark.parametrize("name", ["candidateSmoke", "recombinationProbe", "bootstrapProbe"])
@pytest.mark.parametrize("field,value", [
    ("artifactSha256", "0" * 64), ("builderSourceSha256", "0" * 64),
    ("identity", None), ("originalUnchanged", False), ("sourceEvidenceVerified", False),
    ("status", "failed"), ("notQualification", False), ("failureCode", "FAILED")])
def test_stale_or_failed_probes_never_clear_gate(name, field, value):
    module, inventory, review, policy, evidence, identity = fixture()
    evidence[name][field] = value
    with pytest.raises(ValueError):
        module.accept_candidate_evidence(inventory, review, policy, evidence, identity=identity)


@pytest.mark.parametrize("path,value", [
    (("loaderProbe", "scope"), "sections-only"),
    (("loaderProbe", "supplierSha256"), None),
    (("loaderProbe", "supplierSha256"), "0" * 64),
    (("loaderProbe", "failureCode"), "FAILED"),
    (("candidateSmoke", "termsPreview", "assent"), "accepted"),
    (("candidateSmoke", "termsPreview", "termsToken"), "old-token"),
    (("candidateSmoke", "termsPreview", "platform"), "offscreen"),
    (("recombinationProbe", "runtime", "coreSha256"), "0" * 64),
    (("recombinationProbe", "runtime", "pluginSha256"), "0" * 64),
    (("recombinationProbe", "runtime", "bundledCore"), False),
    (("recombinationProbe", "supplierWheelSha256"), "0" * 64),
    (("recombinationProbe", "originalCoverageRejected"), False),
    (("recombinationProbe", "originalSha256"), "0" * 64),
    (("bootstrapProbe", "loader", "supplierStubSha256"), "0" * 64),
    (("bootstrapProbe", "loader", "supplierArchiveSha256"), "0" * 64),
    (("bootstrapProbe", "extraction"), "not-tested"),
    (("bootstrapProbe", "humanInstallation"), "accepted"),
])
def test_exact_supplier_runtime_and_nonconsent_contracts(path, value):
    module, inventory, review, policy, evidence, identity = fixture()
    target = evidence
    for key in path[:-1]:
        target = target[key]
    target[path[-1]] = value
    with pytest.raises(ValueError):
        module.accept_candidate_evidence(inventory, review, policy, evidence, identity=identity)


def test_installed_runtime_probe_never_qualifies_clean_machine_installation():
    module, inventory, review, policy, evidence, identity = fixture()
    result, resolved = module.validate_candidate_evidence(inventory, review, policy, evidence, identity=identity)
    assert "WINDOWS_INSTALLED_RUNTIME_UNTESTED" in resolved
    assert "WINDOWS_BOOTSTRAP_UNVALIDATED" not in resolved
    accepted = module.accept_candidate_evidence(inventory, review, policy, evidence, identity=identity)
    gate = next(b for b in accepted["blockers"] if b["code"] == "WINDOWS_BOOTSTRAP_UNVALIDATED")
    assert gate["installationPaths"] == {"acceptance": "not-tested", "cancellation": "not-tested", "failure": "not-tested"}
    assert gate["unitReplay"] == "separate-unit-tests-not-human-integration"
    assert "clean-machine" in gate["detail"]
    assert "WINDOWS_INSTALLED_RUNTIME_UNTESTED" not in {b["code"] for b in accepted["blockers"]}
    with pytest.raises(ValueError, match="WINDOWS_BOOTSTRAP_UNVALIDATED"):
        module.materialize(inventory, accepted, PACKAGING, PACKAGING / "must-not-create", cache=PACKAGING)


def test_live_probe_binds_unchanged_candidate_and_embedded_source(tmp_path, monkeypatch):
    module, inventory, review, policy, evidence, identity = fixture()
    candidate = tmp_path / "candidate.exe"
    candidate.write_bytes(b"synthetic API boundary test, not a Windows binary")
    inventory["artifactSha256"] = hashlib.sha256(candidate.read_bytes()).hexdigest()
    monkeypatch.setattr(module, "ci_identity", lambda: identity)
    monkeypatch.setattr(module, "verify_candidate_source", lambda *args: None)
    monkeypatch.setattr("platform.system", lambda: "Windows")
    monkeypatch.setattr("platform.machine", lambda: "AMD64")
    report = module.run_candidate_probe(candidate, inventory, lambda: {"status": "passed"})
    assert report["originalUnchanged"] is True
    assert report["sourceEvidenceVerified"] is True
    assert report["identity"] == identity
    assert report["artifactSha256"] == inventory["artifactSha256"]
    def changed():
        candidate.write_bytes(b"changed")
        return {"status": "passed"}
    with pytest.raises(ValueError, match="changed"):
        module.run_candidate_probe(candidate, inventory, changed)


def test_shared_gate_accepts_only_exact_cpython_embedded_route(monkeypatch):
    module, inventory, review, policy, evidence, identity = fixture()
    result = module.accept_candidate_evidence(inventory, review, policy, evidence, identity=identity)
    narrow = next(c for c in result["components"] if c["id"] == "microsoft-cpython-embedded")
    assert module.validate_python_embedded_route(narrow, result, PACKAGING) is True
    # Exercise both routes through the real shared gate. A sentinel after them
    # proves each passed without pretending missing source archives are present.
    result["components"].remove(narrow)
    result["components"].insert(0, narrow)
    compiled = next(c for c in result["components"] if c["id"] == "microsoft-msvc")
    result["components"].remove(compiled)
    result["components"].insert(1, compiled)
    result["components"].insert(2, {"id": "test-sentinel", "license": "Unsupported-test-sentinel",
                                    "payload": [], "statically_linked": []})
    build = module._load("build")
    monkeypatch.setattr(build.platform, "system", lambda: "Windows")
    monkeypatch.setattr(build.platform, "machine", lambda: "AMD64")
    with pytest.raises(ValueError, match="Unsupported native license: Unsupported-test-sentinel"):
        build.validate_native_coverage(inventory["payload"], result, PACKAGING)


@pytest.mark.parametrize("mutation", [None, "scope", "license", "version", "notice", "copying", "wheel", "member", "code", "candidate-hash", "standalone", "other-parent", "zlib-edge", "exception", "missing-probe", "supplier"])
def test_compiled_loader_shared_route_is_exact(mutation):
    module, inventory, review, policy, evidence, identity = fixture()
    result = module.accept_candidate_evidence(inventory, review, policy, evidence, identity=identity)
    static = next(c for c in result["components"] if c["id"] == "microsoft-msvc")
    parent = next(c for c in result["components"] if c["id"] == "pyinstaller-embedded-loader")
    basis = static["distribution_basis"]
    if mutation == "scope": static["license_scope"] = "all supplier runtime code"
    if mutation == "license": static["license"] = "GPL-2.0-or-later"
    if mutation == "version": parent["version"] = "6.14.0"
    if mutation == "notice": static["notices"][0]["sha256"] = "0" * 64
    if mutation == "copying": basis["copyingSha256"] = "0" * 64
    if mutation == "wheel": basis["supplierSha256"] = "0" * 64
    if mutation == "member": basis["member"] = "PyInstaller/bootloader/Windows-64bit-intel/run.exe"
    if mutation == "code": result["windowsEvidence"]["loaderProbe"]["supplierSha256"] = "0" * 64
    if mutation == "candidate-hash": parent["payload"][0]["sha256"] = "0" * 64
    if mutation == "standalone": parent["payload"][0]["path"] = "runw.exe"
    if mutation == "other-parent": next(c for c in result["components"] if c["id"] == "qt-Core")["statically_linked"].append(static["id"])
    if mutation == "zlib-edge": parent["statically_linked"].remove("pyinstaller-static-zlib")
    if mutation == "exception": parent["exception_basis"] = "standalone loader waiver"
    if mutation == "missing-probe": result["windowsEvidence"].pop("bootstrapProbe")
    if mutation == "supplier": result["supplier_inputs"][-1]["sha256"] = "0" * 64
    if mutation:
        with pytest.raises(ValueError):
            module.validate_compiled_loader_route(static, result, PACKAGING)
    else:
        assert module.validate_compiled_loader_route(static, result, PACKAGING) is True


def test_compiled_grant_checks_actual_notice_bytes(tmp_path):
    module, inventory, review, policy, evidence, identity = fixture()
    result = module.accept_candidate_evidence(inventory, review, policy, evidence, identity=identity)
    static = next(c for c in result["components"] if c["id"] == "microsoft-msvc")
    parent = next(c for c in result["components"] if c["id"] == "pyinstaller-embedded-loader")
    for asset in parent["notices"] + static["notices"]:
        path = tmp_path / asset["path"]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes((PACKAGING / asset["path"]).read_bytes())
    assert module.validate_compiled_loader_route(static, result, tmp_path) is True
    notice = tmp_path / static["distribution_basis"]["notice"]["path"]
    notice.write_bytes(notice.read_bytes().replace(b"unlimited permission", b"no permission", 1))
    with pytest.raises(ValueError, match="changed native evidence"):
        module.validate_compiled_loader_route(static, result, tmp_path)


@pytest.mark.parametrize("mutation", ["scope", "notice", "version", "standalone", "other-parent", "parent-hash", "source", "probe", "supplier"])
def test_cpython_route_never_grants_other_suppliers_or_standalone_dlls(mutation):
    module, inventory, review, policy, evidence, identity = fixture()
    result = module.accept_candidate_evidence(inventory, review, policy, evidence, identity=identity)
    narrow = next(c for c in result["components"] if c["id"] == "microsoft-cpython-embedded")
    if mutation == "scope": narrow["license_scope"] = "All Microsoft code"
    if mutation == "notice": narrow["notices"][0]["sha256"] = "0" * 64
    if mutation == "version": narrow["version"] = "any"
    if mutation == "standalone": narrow["payload"] = [{"path": "vcruntime140.dll", "sha256": "1" * 64}]
    if mutation == "other-parent": next(c for c in result["components"] if c["id"] == "qt-Core")["statically_linked"].append(narrow["id"])
    if mutation == "parent-hash": next(c for c in result["components"] if c["id"] == "cpython")["payload"][0]["sha256"] = "0" * 64
    if mutation == "source": result["pythonDownstream"]["sourceEvidence"][0]["sha256"] = "0" * 64
    if mutation == "probe": result["windowsEvidence"].pop("bootstrapProbe")
    if mutation == "supplier": result["supplier_matches"][0]["sha256"] = "0" * 64
    with pytest.raises(ValueError):
        module.validate_python_embedded_route(narrow, result, PACKAGING)


@pytest.mark.parametrize("mutation", [None, "source-hash", "assent-source"])
def test_actual_embedded_source_bytes_must_match_hash_pinned_assent(tmp_path, monkeypatch, mutation):
    import io
    import zipfile
    from types import SimpleNamespace
    module, inventory, review, policy, evidence, identity = fixture()
    source = io.BytesIO()
    paths = [PACKAGING.parent / a["path"] for a in policy["pythonDownstream"]["sourceEvidence"]]
    paths += [PACKAGING / "windows_setup.py", PACKAGING / "provenance/windows-setup.lock.json"]
    paths += [p for p in (PACKAGING / "windows-setup").iterdir() if p.is_file()]
    with zipfile.ZipFile(source, "w") as archive:
        for path in paths:
            data = path.read_bytes()
            if mutation == "assent-source" and path.name == "windows_terms.py":
                data += b"\n# changed source test fixture\n"
            archive.writestr("desktop-builder/" + path.relative_to(PACKAGING.parent).as_posix(), data)
    blob = io.BytesIO()
    with zipfile.ZipFile(blob, "w") as archive:
        archive.writestr("builder-source.zip", source.getvalue())
    inventory["builderSourceSha256"] = hashlib.sha256(source.getvalue()).hexdigest()
    if mutation == "source-hash": inventory["builderSourceSha256"] = "0" * 64
    monkeypatch.setattr("PyInstaller.archive.readers.CArchiveReader", lambda *args: SimpleNamespace(extract=lambda name: blob.getvalue()))
    if mutation:
        with pytest.raises(ValueError, match="source"):
            module.verify_candidate_source(tmp_path / "synthetic.exe", inventory)
    else:
        module.verify_candidate_source(tmp_path / "synthetic.exe", inventory)

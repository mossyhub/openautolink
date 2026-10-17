"""Exact Windows CI review audit; blocked evidence is never coverage approval.

Run ``audit --inventory discovery.json`` to inspect the review (exit 2 while
blocked). ``materialize --inventory discovery.json --cache DIR --output NEW``
uses the shared schema-2 generator only after all review blockers are resolved.
No auto-approval, filename classification, hash refresh, or platform override.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import re

BASE = Path(__file__).resolve().parent
REVIEW = BASE / "provenance/windows-ci-reviewed.json"


def _payload_map(payload):
    if not isinstance(payload, list) or not payload:
        raise ValueError("Empty or invalid native payload")
    result = {}
    for entry in payload:
        name = entry.get("path", "")
        if (not isinstance(name, str) or not name or name.startswith("/")
                or "\\" in name or ":" in name or ".." in name.split("/")
                or not re.fullmatch(r"[0-9a-f]{64}", entry.get("sha256", ""))):
            raise ValueError("Unsafe payload path/hash")
        if name.casefold() in {p.casefold() for p in result}:
            raise ValueError("Unreviewed duplicate payload path")
        result[name] = entry
    return result


def audit(inventory, review):
    """Validate exact mapping without pretending this host is Windows."""
    if (review.get("schema") != 2 or review.get("system") != "Windows"
            or review.get("architecture") != "x86_64"):
        raise ValueError("Windows x86_64 schema-2 review required")
    system = inventory.get("system", "Windows")
    architecture = inventory.get("architecture", "x86_64").lower().replace("amd64", "x86_64")
    if system != "Windows" or architecture != "x86_64":
        raise ValueError("Discovery platform mismatch")
    actual = _payload_map(inventory.get("inventory", inventory).get("payload"))
    observed = _payload_map(review.get("observed_payload"))
    if actual != observed:
        raise ValueError("Unreviewed, missing or changed Windows payload")
    components = review.get("components", [])
    approved = _payload_map([p for c in components for p in c.get("payload", [])])
    if {p: e["sha256"] for p, e in approved.items()} != {p: e["sha256"] for p, e in actual.items()}:
        raise ValueError("Unreviewed, missing or changed Windows component assignment")
    ids = [c.get("id") for c in components]
    if len(ids) != len(set(ids)) or any(not isinstance(i, str) or not i for i in ids):
        raise ValueError("Duplicate or invalid component IDs")
    for component in components:
        edges = component.get("statically_linked")
        if not isinstance(edges, list) or any(i not in ids or i == component["id"] for i in edges):
            raise ValueError("Unresolved static dependency record")
    blockers = review.get("blockers")
    if not isinstance(blockers, list) or review.get("status") not in {"blocked", "reviewed"}:
        raise ValueError("Explicit review status and blockers required")
    return {"status": "blocked" if blockers or review["status"] == "blocked" else "reviewed",
            "payload_count": len(actual), "component_count": len(components), "blockers": blockers}


def _load(name):
    spec = importlib.util.spec_from_file_location("windows_ci_" + name, BASE / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def minimal_review(inventory, review, policy, probe):
    """Derive exact candidate assignments, never legal or native-test approval.

    Historical DATA assignments retain their separate notices/source obligations.
    Only paths expressly removed by the reviewed collection policy are dropped.
    """
    import copy
    if (policy.get("schema") != 1 or policy.get("status") != "candidate-policy-not-coverage"
            or policy.get("system") != "Windows" or policy.get("architecture") != "x86_64"):
        raise ValueError("Expected reviewed Windows minimal candidate policy")
    data = inventory.get("inventory", inventory)
    actual = _payload_map(data.get("payload"))
    expected = _payload_map(policy.get("expectedNonLoaderPayload"))
    bootstrap = policy.get("collectionMode") == BOOTSTRAP_MODE
    if policy.get("collectionMode") not in {None, BOOTSTRAP_MODE}:
        raise ValueError("Unknown Windows collection policy")
    if bootstrap:
        canonical = json.loads(BOOTSTRAP_POLICY.read_text(encoding="utf-8"))
        legacy = json.loads((BASE / "provenance/windows-ci-minimal-policy.json").read_text(encoding="utf-8"))
        original = _payload_map(legacy["expectedNonLoaderPayload"])
        excluded = _payload_map(policy.get("excludedAppLocalMsvcPayload"))
        if (policy != canonical or set(excluded) != BOOTSTRAP_MSVC_PATHS
                or excluded != {p: e for p, e in original.items() if p in BOOTSTRAP_MSVC_PATHS}
                or expected != {p: e for p, e in original.items() if p not in BOOTSTRAP_MSVC_PATHS}):
            raise ValueError("Changed bootstrap policy or excluded runtime mapping")
    loader = actual.get("@bootloader", {})
    if ({p: e for p, e in actual.items() if p != "@bootloader"} != expected
            or len(actual) != policy.get("expectedTotalNativeCount")
            or loader.get("kind") != "bootloader" or loader.get("hash_scope") != PE_PREFIX_SCOPE
            or not isinstance(loader.get("size"), int) or loader["size"] <= 0):
        raise ValueError("Unreviewed, missing or changed minimal Windows payload")
    wheel = [e for e in review["supplier_inputs"]
             if e["path"] == "pyinstaller-6.13.0-py3-none-win_amd64.whl"]
    if (len(wheel) != 1 or probe.get("schema") != 1 or probe.get("status") != "evidence-only"
            or probe.get("notQualification") is not True or probe.get("system") != "Windows"
            or probe.get("architecture") != "x86_64" or not probe.get("sections")
            or probe.get("supplierWheelSha256") != wheel[0]["sha256"]
            or probe.get("supplierMember") != "PyInstaller/bootloader/Windows-64bit-intel/runw.exe"
            or probe.get("loaderSha256") != loader["sha256"]
            or probe.get("loaderHashScope") != PE_PREFIX_SCOPE
            or any(not re.fullmatch(r"[0-9a-f]{64}", data.get(field, ""))
                   or probe.get(field) != data[field] for field in ("artifactSha256", "builderSourceSha256"))):
        raise ValueError("Missing or mismatched actual candidate loader probe")
    # Validate the historical complete assignment before deriving its exact subset.
    audit({"payload": review["observed_payload"]}, review)
    result = copy.deepcopy(review)
    removed = {"mesa-llvmpipe", "mesa-llvm", "microsoft-ucrt", "unresolved-openssl-x64",
               "builder-compliance-container"}
    excluded_paths = {e["path"] for c in review["components"] if c["id"] in removed for e in c["payload"]}
    excluded_paths.add("PySide6/plugins/tls/qopensslbackend.dll")
    if bootstrap:
        excluded_paths.update(BOOTSTRAP_MSVC_PATHS)
    components = []
    for component in result["components"]:
        if component["id"] in removed:
            continue
        native, non_native = [], []
        for entry in component["payload"]:
            name = entry["path"]
            if name == "@bootloader":
                native.append(loader)
            elif name in actual:
                if actual[name]["sha256"] != entry["sha256"]:
                    raise ValueError("Changed supplier assignment")
                native.append(actual[name])
            elif name not in excluded_paths:
                non_native.append(entry)
        component["payload"] = native
        if non_native:
            component["historical_non_native_payload"] = non_native
        if component["id"] == "pyinstaller-embedded-loader":
            component["provenance"] = "Actual candidate non-resource PE sections and critical headers compared with the exact official 6.13.0 wheel; see loaderProbe."
            component["exception_basis"] = "Embedded loader only. Exact supplier section comparison permits only packaging resources and COFF/DEBUG timestamp/checksum metadata edits."
        if component["id"] == "cpython-base-library":
            component["provenance"] = "Generated Python standard library ZIP; bounded actual-container scan found no unassigned native code. PSF notices retained separately."
        if component["id"] == "qt-static-wintab":
            component["license_scope"] = "Only the Wintab headers embedded in the Qt 6.8.3 Windows platform plugin."
        if component["id"] == "cpython-static-liblzma":
            component["license_scope"] = "Only embedded liblzma 5.2.5 in CPython _lzma; not xz tools, scripts or build tools."
        components.append(component)
    result.update(components=components, observed_payload=data["payload"], loaderProbe=probe,
                  status="blocked", minimumWindows=policy["minimumWindows"])
    resolved = {"SCHEMA_LICENSE_ROUTES_REQUIRED", "OPENSSL_X64_SUPPLIER_UNRESOLVED",
                "MESA_LLVM_CLOSURE_INCOMPLETE", "PE_LOADER_CODE_IDENTITY_UNVERIFIED",
                "GENERATED_CONTAINER_CONTENTS_UNVERIFIED"}
    # Resolve only enumerated technical findings, retaining future/unknown gates.
    result["blockers"] = [b for b in result["blockers"] if b["code"] not in resolved]
    microsoft = next((b for b in result["blockers"] if b["code"] == "MICROSOFT_REDISTRIBUTION_UNRESOLVED"), None)
    if microsoft is None or not any(b["code"] == "WINDOWS_RECOMBINATION_UNTESTED" for b in result["blockers"]):
        raise ValueError("Missing explicit Windows release gates")
    microsoft["detail"] = ("Python's exact Windows LICENSE permits compliant downstream redistribution; no paid VS license or entitlement certificate is assumed necessary. "
        "Implement and verify Microsoft-only downstream protective assent for distributors/external end users. "
        "The separate PySide/Shiboken-supplied MSVC downstream grant still requires review; exact supplier bytes are known. "
        "No assent was accepted on the publisher's or user's behalf.")
    if bootstrap:
        result.update(collectionMode=BOOTSTRAP_MODE,
                      pythonDownstream=copy.deepcopy(policy["pythonDownstream"]))
        # Keep the conservative embedded-code/static notice closure. Removing
        # separately shipped DLLs does not turn all compiler output into PSF code.
        msvc = next(c for c in components if c["id"] == "microsoft-msvc")
        msvc["provenance"] = ("No separately redistributed MSVC DLLs remain. Retained conservative embedded-code notice closure; "
            "the exact Python Windows downstream grant applies to embedded Microsoft code in CPython only. "
            "This is not a blanket grant for other supplier code or evidence of human assent.")
        msvc["license_scope"] = "Microsoft code embedded in retained supplier binaries only; not independently licensed Python, Qt, PySide, Shiboken or builder code."
        microsoft.update(code="WINDOWS_EMBEDDED_MICROSOFT_TERMS_UNVERIFIED",
            detail="Exact CPython downstream notice and scoped assent source are bound; native frozen assent and pre-Python wrapper gates remain required. No publisher VS license purchase is assumed.")
        result["blockers"].extend([
            {"code": "WINDOWS_BOOTSTRAP_UNVALIDATED", "detail": "Validate official Microsoft installed runtime and interactive bootstrap before Python/Qt, including cancellation/failure paths."},
            {"code": "WINDOWS_FROZEN_UI_UNTESTED", "detail": "Require native frozen inner BuilderWindow on windows and offscreen and separate terms preview, bound to this candidate."},
        ])
        verify_python_downstream(result, BASE)
        _correct_bootstrap_static_mapping(result, review)
    audit(inventory, result)
    return result


BOOTSTRAP_CORRECTED_COMPONENTS = frozenset({
    "qt-Core", "qt-Gui", "qt-Network", "qt-Widgets", "qt-plugins",
    "pyside-shiboken", "cffi", "cryptography",
})


def _correct_bootstrap_static_mapping(result, historical):
    """Remove an unsupported edge, not supplier licenses or other static edges."""
    import copy
    import hashlib
    historical_bytes = REVIEW.read_bytes()
    historical_sha256 = "e5b6ceba87c8d96afcb13d52a0032446b0f6212acd73ff750ab74762fbff8998"
    if hashlib.sha256(historical_bytes).hexdigest() != historical_sha256:
        raise ValueError("Changed historical evidence for bootstrap mapping correction")
    canonical = json.loads(historical_bytes)
    if (any(historical.get(k) != canonical[k] for k in ("supplier_inputs", "supplier_matches"))
            or [c for c in historical["components"] if c["id"] in BOOTSTRAP_CORRECTED_COMPONENTS]
            != [c for c in canonical["components"] if c["id"] in BOOTSTRAP_CORRECTED_COMPONENTS]):
        raise ValueError("Unreviewed supplier/notice/component for bootstrap mapping correction")
    items = []
    gate = _load("build")
    for component in result["components"]:
        if component["id"] not in BOOTSTRAP_CORRECTED_COMPONENTS:
            continue
        for asset in component["notices"]:
            gate.coverage_asset(BASE, asset)
        names = {m["input"] for m in historical["supplier_matches"]
                 if m["payload"] in {p["path"] for p in component["payload"]}}
        items.append({"component": component["id"], "payload": copy.deepcopy(component["payload"]),
                      "notices": copy.deepcopy(component["notices"]),
                      "suppliers": [copy.deepcopy(s) for s in historical["supplier_inputs"] if s["path"] in names]})
        component["statically_linked"] = [e for e in component["statically_linked"] if e != "microsoft-msvc"]
    result["bootstrapMappingCorrection"] = {
        "historicalReviewSha256": historical_sha256,
        "scope": "exact-runtime-free-bootstrap-suppliers-only",
        "rationale": "Official Qt/PySide/Shiboken LGPL/GPL, CFFI MIT-0 and cryptography Apache/BSD supplier grants are retained. MSVC compilation or dynamic runtime imports alone do not establish a static Microsoft edge. This removes an unsupported mapping, not a claim of universal absence or an exemption from evidenced obligations.",
        "items": items,
    }


def _digest(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def _native_identity(identity):
    if (not isinstance(identity, dict)
            or set(identity) != {"checkoutCommit", "expectedCommit", "trackedCheckoutDirty", "runner", "runId", "runAttempt"}
            or not re.fullmatch(r"[0-9a-f]{40}", str(identity.get("checkoutCommit", "")))
            or identity["checkoutCommit"] != identity["expectedCommit"]
            or identity["trackedCheckoutDirty"] is not False or identity["runner"] != "windows-2022"
            or any(not isinstance(identity[k], str) or not re.fullmatch(r"[1-9][0-9]*", identity[k])
                   for k in ("runId", "runAttempt"))):
        raise ValueError("Native clean Windows CI identity required")


def _bound_probe(probe, inventory, identity, status):
    data = inventory.get("inventory", inventory)
    if (not isinstance(probe, dict) or probe.get("schema") != 1 or probe.get("status") != status
            or probe.get("notQualification") is not True or "failureCode" in probe
            or probe.get("identity") != identity or probe.get("originalUnchanged") is not True
            or probe.get("sourceEvidenceVerified") is not True
            or any(not _digest(data.get(k)) or probe.get(k) != data[k]
                   for k in ("artifactSha256", "builderSourceSha256"))):
        raise ValueError("Missing or mismatched candidate-bound native probe")


def validate_candidate_evidence(inventory, review, policy, evidence, *, identity):
    """Replay report contracts, not signatures or claims of human consent.

    Reports are collected in-process by the native build; JSON alone does not
    establish their authenticity. Missing probes leave their named gate closed;
    supplied but malformed/stale/failed probes reject the entire submission.
    """
    _native_identity(identity)
    if not isinstance(evidence, dict) or evidence.keys() - {
            "loaderProbe", "candidateSmoke", "recombinationProbe", "bootstrapProbe"}:
        raise ValueError("Unknown Windows evidence kind")
    loader = evidence.get("loaderProbe", {})
    result = minimal_review(inventory, review, policy, loader)
    if (result.get("collectionMode") != BOOTSTRAP_MODE or loader.get("identity") != identity
            or loader.get("scope") != "nonresource-PE-sections-with-debug-timestamps-zeroed-and-critical-headers"
            or loader.get("supplierSha256") != "7ed67be1a51d6d7c6d0d912b77e61c0950f8cdb7b9403f1de4da46c5506b337f"
            or "failureCode" in loader
            or not any(s.get("name") == ".text" for s in loader["sections"])
            or not all(_digest(s.get("sha256")) for s in loader["sections"])):
        raise ValueError("Runtime-free native candidate/loader identity and exact comparison required")
    data = inventory.get("inventory", inventory)
    actual = _payload_map(data["payload"])
    _load("windows_setup").assert_external_runtime(data["payload"])
    # Split the historical combined gate without discarding installation paths.
    installation = next(b for b in result["blockers"] if b["code"] == "WINDOWS_BOOTSTRAP_UNVALIDATED")
    installation.update(
        detail="Native clean-machine interactive Microsoft installer acceptance, cancellation and failure paths remain untested. Installed-runtime smoke and native unit replay are not human installer integration or assent.",
        installationPaths={"acceptance": "not-tested", "cancellation": "not-tested", "failure": "not-tested"},
        unitReplay="separate-unit-tests-not-human-integration")
    result["blockers"].append({"code": "WINDOWS_INSTALLED_RUNTIME_UNTESTED",
        "detail": "Require exact outer extraction/stub and native BuilderWindow smoke with an already installed runtime; this does not exercise installation."})
    resolved = set()
    ui = evidence.get("candidateSmoke")
    if ui is not None:
        _bound_probe(ui, inventory, identity, "evidence-only")
        shots = ui.get("screenshots", [])
        preview = ui.get("termsPreview", {})
        if (not isinstance(shots, list) or len(shots) != 2
                or [s.get("platform") for s in shots] != ["windows", "offscreen"]
                or not all(_digest(s.get("sha256")) for s in shots)
                or preview.get("platform") != "windows" or not _digest(preview.get("sha256"))
                or preview.get("termsToken") != policy["pythonDownstream"]["termsToken"]
                or preview.get("assent") != "not-given"):
            raise ValueError("Separate frozen builder and non-assenting terms preview required")
        verify_python_downstream(result, BASE)
        resolved.add("WINDOWS_FROZEN_UI_UNTESTED")
    replacement = evidence.get("recombinationProbe")
    if replacement is not None:
        _bound_probe(replacement, inventory, identity, "passed")
        core = "PySide6/Qt6Core.dll"
        matches = [m for m in review["supplier_matches"] if m["payload"] == core]
        suppliers = [s for s in review["supplier_inputs"] if len(matches) == 1 and s["path"] == matches[0]["input"]]
        runtime = {"system": "Windows", "architecture": "x86_64", "frozen": True,
                   "qtPlatform": "windows", "coreSha256": replacement.get("modifiedSha256"),
                   "pluginSha256": actual["PySide6/plugins/platforms/qwindows.dll"]["sha256"],
                   "bundledCore": True, "bundledPlugin": True, "builderWindow": True}
        if (len(suppliers) != 1 or matches[0]["member"] != core
                or matches[0]["sha256"] != actual[core]["sha256"]
                or replacement.get("scope") != "native-windows-private-QtCore-overlay-replacement"
                or replacement.get("supplierWheelSha256") != suppliers[0]["sha256"]
                or replacement.get("supplierMember") != core
                or replacement.get("originalSha256") != actual[core]["sha256"]
                or replacement.get("modifiedSha256") == actual[core]["sha256"]
                or replacement.get("archivedCoreSha256") != replacement.get("modifiedSha256")
                or replacement.get("originalSize") != actual[core]["size"]
                or replacement.get("modifiedSize") != actual[core]["size"] + len(_load("windows_recombine").MARKER)
                or replacement.get("mutation") != "non-loaded-file-overlay-only"
                or replacement.get("originalCoverageRejected") is not True
                or replacement.get("runtime") != runtime
                or any(not _digest(replacement.get(k)) for k in ("modifiedSha256", "archivedCoreSha256",
                       "replacementArtifactSha256", "instrumentationSha256", "screenshotSha256"))):
            raise ValueError("Exact supplier Qt replacement must actually load with bundled qwindows")
        resolved.add("WINDOWS_RECOMBINATION_UNTESTED")
    bootstrap = evidence.get("bootstrapProbe")
    if bootstrap is not None:
        _bound_probe(bootstrap, inventory, identity, "passed")
        nsis = json.loads((BASE / "provenance/windows-setup.lock.json").read_text(encoding="utf-8"))["nsis"]
        stub = bootstrap.get("loader", {})
        if (bootstrap.get("scope") != "native-windows-private-bootstrap-installed-runtime"
                or bootstrap.get("humanInstallation") != "not-tested-no-assent-automated"
                or bootstrap.get("extraction") != "exact-payload-verified"
                or not _digest(bootstrap.get("outerSha256")) or not _digest(bootstrap.get("screenshotSha256"))
                or stub.get("status") != "exact-nonresource-sections"
                or stub.get("supplierStubSha256") != nsis["stubSha256"]
                or stub.get("supplierArchiveSha256") != nsis["sha256"]
                or not stub.get("sections") or not all(_digest(s.get("sha256")) for s in stub["sections"])
                or stub.get("imports") != sorted(["advapi32.dll", "shell32.dll", "ole32.dll", "comctl32.dll",
                                                   "user32.dll", "gdi32.dll", "kernel32.dll"])):
            raise ValueError("Exact supplier bootstrap extraction and native qwindows evidence required")
        resolved.add("WINDOWS_INSTALLED_RUNTIME_UNTESTED")
    return result, resolved


PYTHON_EMBEDDED_ID = "microsoft-cpython-embedded"
PYTHON_EMBEDDED_LICENSE = "LicenseRef-Microsoft-CPython-3.12.10-Embedded-Code"
PYTHON_EMBEDDED_SCOPE = ("Microsoft Distributable Code embedded only in unmodified official CPython 3.12.10 "
                         "Windows embeddable archive binaries; no standalone Microsoft DLLs, other suppliers, "
                         "or restrictions on independently licensed code.")
PYTHON_PARENTS = frozenset({"cpython", "cpython-libffi", "cpython-openssl"})


def _split_python_embedded_terms(result):
    """Use Python's actual downstream grant, never extend it to another supplier."""
    import copy
    parents = [c for c in result["components"] if c["id"] in PYTHON_PARENTS]
    for parent in parents:
        parent["statically_linked"] = [PYTHON_EMBEDDED_ID if e == "microsoft-msvc" else e
                                      for e in parent["statically_linked"]]
    old = next(c for c in result["components"] if c["id"] == "microsoft-msvc")
    component = {"id": PYTHON_EMBEDDED_ID, "version": "CPython 3.12.10 Windows binary build",
                 "license": PYTHON_EMBEDDED_LICENSE, "license_scope": PYTHON_EMBEDDED_SCOPE,
                 "provenance": "Official Python Windows LICENSE lines 283-320 permits compliant downstream redistribution; supplier member hashes retained. Source-bound protective assent mechanism, not a claim of assent.",
                 "payload": [], "statically_linked": [], "notices": copy.deepcopy(old["notices"])}
    result["components"].append(component)
    # Only evidenced static scopes survive the derived mapping correction.
    items = []
    for parent in result["components"]:
        if "microsoft-msvc" not in parent["statically_linked"]:
            continue
        for entry in parent["payload"]:
            matches = [m for m in result["supplier_matches"] if m["payload"] == entry["path"]]
            if entry["path"] == "@bootloader":
                matches = [{"input": "pyinstaller-6.13.0-py3-none-win_amd64.whl",
                            "member": result["loaderProbe"]["supplierMember"]}]
            if len(matches) != 1:
                raise ValueError("Unresolved embedded-code supplier identity")
            supplier = next(s for s in result["supplier_inputs"] if s["path"] == matches[0]["input"])
            items.append({"component": parent["id"], "payload": entry["path"], "sha256": entry["sha256"],
                          "supplier": supplier["path"], "supplierSha256": supplier["sha256"],
                          "member": matches[0]["member"],
                          "supplierMemberSha256": result["loaderProbe"]["supplierSha256"] if entry["path"] == "@bootloader" else matches[0]["sha256"],
                          "missingEvidence": "Applicable downstream redistribution terms for the evidenced static Microsoft runtime in this exact supplier loader; CPython's grant does not apply."})
    old["provenance"] = "Evidenced static Microsoft runtime in the exact PyInstaller runw.exe supplier loader (supplier bootloader-building documentation describes the static runtime); downstream grant not established by the retained notices. No standalone runtime DLLs."
    old["license_scope"] = "Microsoft runtime statically embedded in the exact PyInstaller supplier runw.exe only; not Qt, PySide, Shiboken, CFFI, cryptography or CPython."
    return items


def accept_candidate_evidence(inventory, review, policy, evidence, *, identity):
    """Resolve fixed gates only from this candidate's corresponding native proof."""
    import copy
    result, resolved = validate_candidate_evidence(inventory, review, policy, evidence, identity=identity)
    result["windowsEvidence"] = copy.deepcopy(evidence)
    if "WINDOWS_FROZEN_UI_UNTESTED" in resolved:
        items = _split_python_embedded_terms(result)
        resolved.add("WINDOWS_EMBEDDED_MICROSOFT_TERMS_UNVERIFIED")
        if items:
            result["blockers"].append({"code": "WINDOWS_EMBEDDED_MICROSOFT_SUPPLIER_GRANTS_UNPROVEN",
                "detail": "CPython's exact downstream grant and scoped protective terms are bound. The exact PyInstaller loader retains an evidenced static Microsoft runtime scope: which applicable supplier downstream redistribution terms cover that embedded runtime? No paid VS entitlement or human assent is inferred.",
                "items": items})
    result["blockers"] = [b for b in result["blockers"] if b["code"] not in resolved]
    result["status"] = "blocked" if result["blockers"] else "reviewed"
    audit(inventory, result)
    return result


def validate_python_embedded_route(component, coverage, base):
    """Shared-gate route for the exact Python downstream grant, no DLL route.

    Source evidence is hash-pinned, not signed. This validates a retained
    in-process native report contract; it does not certify a human's assent.
    """
    if (coverage.get("schema") != 2 or coverage.get("system") != "Windows"
            or coverage.get("architecture") != "x86_64" or coverage.get("collectionMode") != BOOTSTRAP_MODE
            or component.get("id") != PYTHON_EMBEDDED_ID or component.get("license") != PYTHON_EMBEDDED_LICENSE
            or component.get("payload") != [] or component.get("sources")
            or component.get("statically_linked") != []):
        raise ValueError("Microsoft CPython route requires embedded-only Windows supplier scope")
    canonical = json.loads(REVIEW.read_text(encoding="utf-8"))
    policy = json.loads(BOOTSTRAP_POLICY.read_text(encoding="utf-8"))
    if (coverage.get("supplier_matches") != canonical["supplier_matches"]
            or coverage.get("supplier_inputs") != canonical["supplier_inputs"]):
        raise ValueError("Changed Microsoft embedded-code supplier bindings")
    evidence = coverage.get("windowsEvidence", {})
    loader = evidence.get("loaderProbe", {})
    inventory = {"payload": coverage.get("observed_payload"),
                 **{key: loader.get(key) for key in ("artifactSha256", "builderSourceSha256")}}
    audit(inventory, coverage)
    expected, resolved = validate_candidate_evidence(inventory, canonical, policy, evidence,
                                                     identity=loader.get("identity"))
    if resolved != {"WINDOWS_FROZEN_UI_UNTESTED", "WINDOWS_RECOMBINATION_UNTESTED", "WINDOWS_INSTALLED_RUNTIME_UNTESTED"}:
        raise ValueError("Microsoft embedded-code route needs all candidate-bound native probes")
    _split_python_embedded_terms(expected)
    exact = next(c for c in expected["components"] if c["id"] == PYTHON_EMBEDDED_ID)
    if component != exact or coverage.get("pythonDownstream") != policy["pythonDownstream"]:
        raise ValueError("Changed Microsoft embedded-code scope, notices or sourceEvidence")
    parents = [c for c in coverage["components"] if PYTHON_EMBEDDED_ID in c["statically_linked"]]
    if {c["id"] for c in parents} != PYTHON_PARENTS:
        raise ValueError("Python downstream grant cannot cover another supplier")
    for parent in parents:
        original = next(c for c in expected["components"] if c["id"] == parent["id"])
        if parent["payload"] != original["payload"] or parent["statically_linked"] != original["statically_linked"]:
            raise ValueError("Changed Python supplier payload or static closure")
    for asset in component["notices"]:
        _load("build").coverage_asset(base, asset)
    return True


def verify_python_downstream(review, base):
    """Bind the retained supplier notice and reviewed assent implementation bytes."""
    expected = json.loads(BOOTSTRAP_POLICY.read_text(encoding="utf-8"))["pythonDownstream"]
    if review.get("pythonDownstream") != expected:
        raise ValueError("Changed Python downstream notice/assent binding")
    gate = _load("build")
    gate.coverage_asset(base, expected["notice"])
    cpython = next((c for c in review["components"] if c["id"] == "cpython"), {})
    if expected["notice"] not in cpython.get("notices", []):
        raise ValueError("Missing embedded Python downstream notice")
    for asset in expected["sourceEvidence"]:
        gate.coverage_asset(Path(base).parent, asset)


def materialize(inventory, review, base, output, *, cache):
    report = audit(inventory, review)
    if report["status"] != "reviewed":
        raise ValueError("Windows coverage blocked: " + ", ".join(
            b["code"] for b in report["blockers"]))
    # Shared generator performs asset checks, native platform/license validation
    # and transactional staging. No simulated Windows host or license override.
    return _load("generate_native").generate(
        inventory.get("inventory", inventory)["payload"], review, base, output, cache=cache)


def verify_assets(review, base):
    """Check local notices/recipes; source archives are checked at materialization."""
    gate = _load("build")
    assets = {}
    for component in review["components"]:
        entries = component.get("notices", []) + ([component["build_instructions"]]
                  if component.get("build_instructions") else [])
        if not component.get("notices"):
            raise ValueError("Missing component-specific notices")
        for entry in entries:
            if entry["path"] in assets and assets[entry["path"]] != entry["sha256"]:
                raise ValueError("Conflicting evidence asset hashes")
            assets[entry["path"]] = entry["sha256"]
            gate.coverage_asset(base, entry)
    return len(assets)


def fetch_sources(review, cache, *, offline=False):
    """Acquire required sources and exact loader/Qt replacement probe wheels."""
    inputs = list(review["source_inputs"])
    if "supplier_inputs" in review:
        core = [e for e in review.get("supplier_matches", []) if e["payload"] == "PySide6/Qt6Core.dll"]
        if len(core) != 1:
            raise ValueError("Missing unique pinned Windows QtCore supplier")
        for name in ("pyinstaller-6.13.0-py3-none-win_amd64.whl", core[0]["input"]):
            matches = [e for e in review["supplier_inputs"] if e["path"] == name]
            if len(matches) != 1:
                raise ValueError("Missing unique pinned Windows probe wheel")
            if matches[0] not in inputs:
                inputs += matches
    return _load("prepare_native").prepare(
        {"schema": 1, "inputs": inputs}, cache, [], offline=offline)


BOOTSTRAP_MODE = "official-vc-runtime-bootstrap-v1"
BOOTSTRAP_POLICY = BASE / "provenance/windows-ci-bootstrap-policy.json"
BOOTSTRAP_MSVC_PATHS = frozenset({
    "PySide6/MSVCP140.dll", "PySide6/MSVCP140_1.dll", "PySide6/MSVCP140_2.dll",
    "PySide6/VCRUNTIME140.dll", "PySide6/VCRUNTIME140_1.dll",
    "VCRUNTIME140.dll", "VCRUNTIME140_1.dll", "shiboken6/MSVCP140.dll",
    "shiboken6/VCRUNTIME140.dll", "shiboken6/VCRUNTIME140_1.dll",
})


def windows10_runtime_binaries(toc, *, bootstrap=False):
    """Filter Analysis.binaries BEFORE freeze, never a frozen inventory.

    The default retains app-local MSVC for historical CI. Bootstrap mode drops
    only the ten reviewed MSVC destination paths and requires the official-runtime
    wrapper to validate the installed x64 runtime before Python/Qt can start.
    Unknown MSVC destinations fail rather than being silently hidden. All other
    entries survive for the exact frozen-payload gate (not an unknown-path waiver).
    """
    known = {p.casefold() for p in BOOTSTRAP_MSVC_PATHS}
    result = []
    for entry in toc:
        path = entry[0].replace("\\", "/").casefold()
        name = path.rsplit("/", 1)[-1]
        if (name == "ucrtbase.dll" or
                re.fullmatch(r"api-ms-win-(?:core|crt)-[a-z0-9-]+\.dll", name)):
            continue
        if bootstrap:
            if path in known:
                continue
            if re.fullmatch(r"(?:msvcp|vcruntime|concrt|vcomp|msvcr)[a-z0-9_]*\.dll", name):
                raise ValueError("Unreviewed Microsoft runtime destination")
        result.append(entry)
    return result


PE_PREFIX_SCOPE = "PE-prefix-with-build-timestamps-and-checksum-zeroed"


def normalize_pe_build_metadata(blob):
    """Zero only the fields PyInstaller 6.13 rewrites after appending CArchive.

    winutils.set_exe_build_timestamp changes COFF and DEBUG timestamps;
    update_exe_pe_checksum hashes the entire EXE including its appended archive.
    Keeping these in an embedded prefix hash causes a two-pass self-reference.
    All remaining bytes, including resources, remain covered by this hash.
    """
    import pefile
    try:
        pe = pefile.PE(data=blob, fast_load=True)
    except pefile.PEFormatError as exc:
        raise ValueError("Invalid PE loader") from exc
    if pe.FILE_HEADER.Machine != 0x8664 or pe.OPTIONAL_HEADER.Magic != 0x20b:
        raise ValueError("Expected Windows x64 PE32+ loader")
    if len(pe.OPTIONAL_HEADER.DATA_DIRECTORY) != 16:
        raise ValueError("Incomplete PE directories")
    offsets = [pe.FILE_HEADER.get_field_absolute_offset("TimeDateStamp"),
               pe.OPTIONAL_HEADER.get_field_absolute_offset("CheckSum")]
    pe.parse_data_directories(directories=[6])
    entries = getattr(pe, "DIRECTORY_ENTRY_DEBUG", [])
    if pe.OPTIONAL_HEADER.DATA_DIRECTORY[6].Size and not entries:
        raise ValueError("Invalid PE debug directory")
    for entry in entries:
        offset = entry.struct.get_field_absolute_offset("TimeDateStamp")
        if not any(not s.Characteristics & 0x20000000
                   and s.PointerToRawData <= offset and offset + 4 <= s.PointerToRawData + s.SizeOfRawData
                   for s in pe.sections):
            raise ValueError("PE debug timestamp outside non-executable section")
        offsets.append(offset)
    result = bytearray(blob)
    for offset in offsets:
        if offset < 0 or offset + 4 > len(result):
            raise ValueError("Truncated PE metadata")
        result[offset:offset + 4] = bytes(4)
    pe.close()
    return bytes(result)


def compare_pe_loader(frozen, supplier):
    """Compare real PE loader sections, not the self-referential prefix hash.

    Resource addition, COFF/DEBUG timestamp and checksum updates are packaging edits.
    Only an identical trailing .reloc may move immediately after an inserted
    non-executable .rsrc, with directory 5 retaining its section-relative span.
    All other code/data layout, relocation bytes and critical headers stay fixed.
    This is scoped evidence for the embedded-loader exception, not a substitute
    for notices or native frozen execution.
    """
    import hashlib
    import pefile

    def image(blob):
        blob = normalize_pe_build_metadata(blob)
        try:
            pe = pefile.PE(data=blob, fast_load=True)
        except pefile.PEFormatError as exc:
            raise ValueError("Invalid PE loader") from exc
        if pe.FILE_HEADER.Machine != 0x8664 or pe.OPTIONAL_HEADER.Magic != 0x20b:
            raise ValueError("Expected Windows x64 PE32+ loader")
        sections = {}
        resources = []
        for section in pe.sections:
            name = section.Name.rstrip(b"\0").decode("ascii")
            if name in sections or name in [r["name"] for r in resources]:
                raise ValueError("Duplicate PE section")
            start, size = section.PointerToRawData, section.SizeOfRawData
            if start + size > len(blob) or (size and start < pe.OPTIONAL_HEADER.SizeOfHeaders):
                raise ValueError("Truncated or overlapping PE section")
            row = {"name": name, "rva": section.VirtualAddress,
                   "virtual_size": section.Misc_VirtualSize, "raw_size": size, "raw_offset": start,
                   "characteristics": section.Characteristics,
                   "sha256": hashlib.sha256(blob[start:start + size]).hexdigest()}
            if name == ".rsrc":
                if section.Characteristics & 0x20000000:
                    raise ValueError("Executable resource section")
                resources.append(row)
            else:
                sections[name] = row
        ranges = {"raw": [], "virtual": []}
        for section in pe.sections:
            for label, start, size in [("raw", section.PointerToRawData, section.SizeOfRawData),
                                       ("virtual", section.VirtualAddress, max(section.Misc_VirtualSize, section.SizeOfRawData))]:
                if size:
                    if any(start < end and previous < start + size for previous, end in ranges[label]):
                        raise ValueError("PE sections overlap")
                    ranges[label].append((start, start + size))
        if not sections or not any(s["characteristics"] & 0x20000000 for s in sections.values()):
            raise ValueError("Missing executable PE section")
        fields = ["Magic", "AddressOfEntryPoint", "ImageBase", "SectionAlignment", "FileAlignment",
                  "MajorOperatingSystemVersion", "MinorOperatingSystemVersion", "MajorSubsystemVersion",
                  "MinorSubsystemVersion", "Subsystem", "DllCharacteristics", "SizeOfStackReserve",
                  "SizeOfStackCommit", "SizeOfHeapReserve", "SizeOfHeapCommit", "LoaderFlags",
                  "NumberOfRvaAndSizes", "SizeOfHeaders", "SizeOfCode", "SizeOfUninitializedData",
                  "BaseOfCode", "MajorImageVersion", "MinorImageVersion", "MajorLinkerVersion",
                  "MinorLinkerVersion", "Reserved1"]
        headers = {key: getattr(pe.OPTIONAL_HEADER, key) for key in fields}
        headers.update({key: getattr(pe.FILE_HEADER, key) for key in (
            "Characteristics", "PointerToSymbolTable", "NumberOfSymbols", "SizeOfOptionalHeader")})
        for section in pe.sections:
            name = section.Name.rstrip(b"\0").decode("ascii")
            if name != ".rsrc":
                for key in ("PointerToRelocations", "PointerToLinenumbers", "NumberOfRelocations", "NumberOfLinenumbers"):
                    headers[name + "." + key] = getattr(section, key)
        directories = [(i, d.VirtualAddress, d.Size) for i, d in enumerate(pe.OPTIONAL_HEADER.DATA_DIRECTORY)
                       if i != 2]  # IMAGE_DIRECTORY_ENTRY_RESOURCE only.
        resource_dir = pe.OPTIONAL_HEADER.DATA_DIRECTORY[2]
        if resource_dir.Size and not any(r["rva"] <= resource_dir.VirtualAddress
                and resource_dir.VirtualAddress + resource_dir.Size <= r["rva"] + max(r["virtual_size"], r["raw_size"])
                for r in resources):
            raise ValueError("Resource directory outside resource section")
        # Diagnostic-only fields: never broaden the comparison's allowlist.
        diagnostic_headers = {**headers, **{key: getattr(pe.OPTIONAL_HEADER, key) for key in (
            "SizeOfHeaders", "SizeOfImage", "SizeOfCode", "SizeOfInitializedData",
            "SizeOfUninitializedData", "BaseOfCode")},
            "Machine": pe.FILE_HEADER.Machine, "NumberOfSections": pe.FILE_HEADER.NumberOfSections}
        summary = {"headers": diagnostic_headers,
                   "directories": [(i, d.VirtualAddress, d.Size)
                                   for i, d in enumerate(pe.OPTIONAL_HEADER.DATA_DIRECTORY)],
                   "sectionCount": len(pe.sections),
                   "sections": (list(sections.values()) + resources)[:16],
                   "sectionsTruncated": len(pe.sections) > 16}
        pe.close()
        return sections, headers, directories, resources, summary, blob

    actual, headers, directories, resources, actual_summary, actual_blob = image(frozen)
    expected, expected_headers, expected_directories, expected_resources, expected_summary, expected_blob = image(supplier)

    def resource_insertion_moves_relocations():
        # Native Windows run 38955553640 (53508c16) retained identical .reloc
        # bytes/sizes/attributes, with a new .rsrc occupying its old position.
        # Permit that one transformation, not arbitrary section movement.
        a, e = actual.get(".reloc"), expected.get(".reloc")
        if (a is None or e is None or expected_resources or len(resources) != 1
                or set(actual) != set(expected)
                or a["characteristics"] & 0x20000000
                or any(a[k] != e[k] for k in e if k not in {"rva", "raw_offset"})):
            return False
        resource = resources[0]
        file_alignment = headers["FileAlignment"]
        section_alignment = headers["SectionAlignment"]
        if (not 512 <= file_alignment <= 65536 or file_alignment & (file_alignment - 1)
                or section_alignment < file_alignment or section_alignment & (section_alignment - 1)
                or not resource["virtual_size"] or not resource["raw_size"]):
            return False
        for start, size, alignment in (("raw_offset", "raw_size", file_alignment),
                                       ("rva", "virtual_size", section_alignment)):
            extent = max(resource["virtual_size"], resource["raw_size"]) if start == "rva" else resource[size]
            aligned_end = ((resource[start] + extent + alignment - 1) // alignment) * alignment
            if (resource[start] != e[start] or resource[start] % alignment
                    or a[start] != aligned_end or a[start] <= e[start]
                    or any(s[start] + (max(s["virtual_size"], s["raw_size"]) if start == "rva" else s[size]) > e[start]
                           for name, s in expected.items() if name != ".reloc")):
                return False
        if resource["raw_size"] % file_alignment or a["raw_size"] % file_alignment:
            return False
        # EndUpdateResource recomputed these aggregates in the retained native
        # evidence; do not turn the movement exception into a header wildcard.
        end = a["rva"] + max(a["virtual_size"], a["raw_size"])
        image_size = ((end + section_alignment - 1) // section_alignment) * section_alignment
        initialized_size = sum(s["raw_size"] for s in [*actual.values(), resource]
                               if s["characteristics"] & 0x40)
        if (actual_summary["headers"]["SizeOfImage"] != image_size
                or actual_summary["headers"]["SizeOfInitializedData"] != initialized_size):
            return False
        old_directory = next(d for d in expected_directories if d[0] == 5)
        new_directory = next(d for d in directories if d[0] == 5)
        relative = old_directory[1] - e["rva"]
        if (old_directory[2] <= 0 or relative < 0
                or relative + old_directory[2] > min(e["virtual_size"], e["raw_size"])
                or new_directory != (5, a["rva"] + relative, old_directory[2])):
            return False
        # Resource directory must describe file-backed bytes in the insertion.
        _, address, size = actual_summary["directories"][2]
        return (size > 0 and resource["rva"] <= address
                and address + size <= resource["rva"] + min(resource["virtual_size"], resource["raw_size"]))

    comparable = actual
    comparable_directories = directories
    if resource_insertion_moves_relocations():
        comparable = {**actual, ".reloc": {**actual[".reloc"],
                       "rva": expected[".reloc"]["rva"], "raw_offset": expected[".reloc"]["raw_offset"]}}
        comparable_directories = [e if a[0] == 5 else a for a, e in zip(directories, expected_directories)]
    if comparable != expected or headers != expected_headers or comparable_directories != expected_directories:
        # Diagnostics retain actual coordinates, including rejected movement;
        # the narrowly permitted transformation above never mutates evidence.
        names = sorted(set(actual) | set(expected))
        changed_names = [name for name in names if actual.get(name) != expected.get(name)]
        differences = []
        for name in changed_names[:16]:
            a, e = actual.get(name), expected.get(name)
            difference = {"name": name, "changedFields": sorted(
                key for key in set(a or {}) | set(e or {}) if (a or {}).get(key) != (e or {}).get(key))}
            if a is not None and e is not None and a["sha256"] != e["sha256"]:
                # Relative offsets, not payload bytes. Bound both scanning work
                # and output; size/hash differences remain visible beyond the cap.
                size = min(a["raw_size"], e["raw_size"], 1024 * 1024)
                offsets = []
                scanned = 0
                for offset in range(size):
                    scanned = offset + 1
                    if actual_blob[a["raw_offset"] + offset] != expected_blob[e["raw_offset"] + offset]:
                        offsets.append(offset)
                        if len(offsets) == 8:
                            break
                difference.update(firstDifferingOffsets=offsets, comparedPrefixBytes=scanned,
                                  byteScanLimited=scanned < min(a["raw_size"], e["raw_size"]))
            differences.append(difference)
        diagnostic = {"candidatePrefixSha256": hashlib.sha256(frozen).hexdigest(),
                      "supplierPrefixSha256": hashlib.sha256(supplier).hexdigest(),
                      "candidate": actual_summary, "supplier": expected_summary,
                      "sectionDifferenceCount": len(changed_names),
                      "sectionDifferences": differences,
                      "sectionDifferencesTruncated": len(changed_names) > 16,
                      "headerDifferences": sorted(key for key in set(headers) | set(expected_headers)
                                                  if headers.get(key) != expected_headers.get(key)),
                      "directoryDifferences": [a[0] for a, e in zip(directories, expected_directories) if a != e]}
        error = ValueError("PE loader section bytes/layout changed" if actual != expected else
                           "PE loader entry point/header/directory changed")
        error.diagnostics = diagnostic
        raise error
    return {"scope": "nonresource-PE-sections-with-debug-timestamps-zeroed-and-critical-headers",
            "supplierSha256": hashlib.sha256(supplier).hexdigest(),
            "sections": list(actual.values()), "resourceSections": resources}


def verify_candidate_source(executable, inventory):
    """Check actual embedded snapshot and hash-pinned assent/source evidence."""
    import hashlib
    import io
    import zipfile
    from PyInstaller.archive.readers import CArchiveReader
    data = inventory.get("inventory", inventory)
    policy = json.loads(BOOTSTRAP_POLICY.read_text(encoding="utf-8"))
    build = _load("build")
    blob = CArchiveReader(str(executable)).extract("builder-compliance.zip")
    with zipfile.ZipFile(io.BytesIO(blob)) as compliance:
        if compliance.namelist().count("builder-source.zip") != 1:
            raise ValueError("Unique embedded source required")
        source = compliance.read("builder-source.zip")
    if hashlib.sha256(source).hexdigest() != data.get("builderSourceSha256"):
        raise ValueError("Candidate source digest changed")
    with zipfile.ZipFile(io.BytesIO(source)) as snapshot:
        for asset in policy["pythonDownstream"]["sourceEvidence"]:
            build.coverage_asset(BASE.parent, asset)
            name = "desktop-builder/" + asset["path"]
            if (snapshot.namelist().count(name) != 1
                    or hashlib.sha256(snapshot.read(name)).hexdigest() != asset["sha256"]):
                raise ValueError("Candidate assent source differs from reviewed sourceEvidence")
    _load("windows_setup").verify_snapshot(executable, data["builderSourceSha256"])


def run_candidate_probe(executable, inventory, collect):
    """Bind an in-process native probe; never turn imported JSON into evidence."""
    import platform
    if platform.system() != "Windows" or platform.machine().lower() not in {"amd64", "x86_64"}:
        raise ValueError("Native Windows x64 probe required")
    identity = ci_identity()
    _native_identity(identity)
    data = inventory.get("inventory", inventory)
    build = _load("build")
    if (build.sha256(executable) != data.get("artifactSha256")
            or inventory.get("identity", identity) != identity):
        raise ValueError("Candidate or identity changed before probe")
    verify_candidate_source(executable, inventory)
    report = collect()
    if build.sha256(executable) != data["artifactSha256"] or ci_identity() != identity:
        raise ValueError("Original candidate or CI identity changed during probe")
    binding = {"schema": 1, "notQualification": True, "identity": identity,
               "artifactSha256": data["artifactSha256"], "builderSourceSha256": data["builderSourceSha256"],
               "originalUnchanged": True, "sourceEvidenceVerified": True}
    if not isinstance(report, dict) or any(k in report and report[k] != value for k, value in binding.items()):
        raise ValueError("Probe returned conflicting candidate binding")
    return {**report, **binding}


def probe_candidate_ui(executable, work):
    """Exercise private Windows candidate before legal gating; export hashes only.

    This is startup evidence, NOT license coverage or screenshot readability
    approval. The unqualified executable and screenshots stay in private staging.
    """
    import hashlib
    import os
    import platform
    import subprocess
    if platform.system() != "Windows" or platform.machine().lower() not in {"amd64", "x86_64"}:
        raise ValueError("Candidate UI probe requires native Windows x64")
    info_path = work / "candidate-runtime.json"
    info_path.unlink(missing_ok=True)
    env = {**os.environ, "QT_QPA_PLATFORM": "windows"}
    subprocess.run([str(executable), "--runtime-info", str(info_path)], env=env, check=True, timeout=120)
    info = json.loads(info_path.read_text(encoding="utf-8"))
    if info.get("system") != "Windows" or info.get("architecture") != "x86_64" or info.get("frozen") is not True:
        raise ValueError("Wrong candidate runtime platform or architecture")
    screenshots = []
    workspace = work / "must-not-be-created"
    if workspace.exists():
        raise ValueError("Candidate smoke requires absent workspace")
    for qpa in ("windows", "offscreen"):
        target = work / ("candidate-" + qpa + ".png")
        target.unlink(missing_ok=True)
        subprocess.run([str(executable), "--smoke-test", "--workspace", str(workspace),
                        "--screenshot", str(target)], env={**env, "QT_QPA_PLATFORM": qpa}, check=True, timeout=120)
        if workspace.exists() or not target.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError("Candidate smoke created workspace or failed screenshot")
        screenshots.append({"platform": qpa, "sha256": hashlib.sha256(target.read_bytes()).hexdigest()})
    preview = work / "candidate-terms-preview.png"
    preview.unlink(missing_ok=True)
    subprocess.run([str(executable), "--terms-preview", "--workspace", str(workspace),
                    "--screenshot", str(preview)], env=env, check=True, timeout=120)
    if workspace.exists() or not preview.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("Candidate terms preview created workspace or failed screenshot")
    policy = json.loads(BOOTSTRAP_POLICY.read_text(encoding="utf-8"))
    return {"schema": 1, "status": "evidence-only", "notQualification": True, "screenshots": screenshots,
            "termsPreview": {"platform": "windows", "sha256": hashlib.sha256(preview.read_bytes()).hexdigest(),
                             "termsToken": policy["pythonDownstream"]["termsToken"], "assent": "not-given"},
            "scope": "private-candidate-startup-only; readability, final coverage and LGPL replacement remain separate gates"}


def ci_identity():
    """Bind Actions diagnostics to a verified checkout, never infer consent."""
    import os
    if os.environ.get("GITHUB_ACTIONS") != "true":
        return None  # Local evidence remains explicitly unqualified.
    identity = _load("discovery_evidence").identity(os.environ.get("EXPECTED_COMMIT", ""),
        "windows-2022", os.environ.get("GITHUB_RUN_ID", ""), os.environ.get("GITHUB_RUN_ATTEMPT", ""))
    if identity["trackedCheckoutDirty"]:
        raise ValueError("Windows candidate CI checkout is dirty")
    return identity


def probe_native_loader(executable, inventory, review, cache):
    """Read an actual native CI artifact and pinned wheel; never approve coverage."""
    import hashlib
    import platform
    import zipfile
    if platform.system() != "Windows" or platform.machine().lower() not in {"amd64", "x86_64"}:
        raise ValueError("Loader probe requires native Windows x64")
    from PyInstaller.archive.readers import CArchiveReader

    identity = ci_identity()
    if inventory.get("identity") is not None and inventory["identity"] != identity:
        raise ValueError("Loader probe CI identity differs from discovery")
    actual = executable.read_bytes()
    payload = inventory.get("inventory", inventory)
    if hashlib.sha256(actual).hexdigest() != payload.get("artifactSha256"):
        raise ValueError("Probe artifact differs from discovery")
    loader = _payload_map(payload.get("payload")).get("@bootloader", {})
    reader = CArchiveReader(str(executable))
    prefix = actual[:reader._start_offset]
    scope = loader.get("hash_scope")
    normalized = normalize_pe_build_metadata(prefix) if scope == PE_PREFIX_SCOPE else prefix
    if (scope not in {"prefix-bytes", PE_PREFIX_SCOPE}
            or loader.get("size") != len(prefix)
            or loader.get("sha256") != hashlib.sha256(normalized).hexdigest()):
        raise ValueError("Probe loader differs from discovery")
    name = "pyinstaller-6.13.0-py3-none-win_amd64.whl"
    inputs = [entry for entry in review["supplier_inputs"] if entry["path"] == name]
    if len(inputs) != 1:
        raise ValueError("Missing unique pinned Windows loader wheel")
    wheel = _load("build").coverage_asset(cache, inputs[0])
    member = "PyInstaller/bootloader/Windows-64bit-intel/runw.exe"
    with zipfile.ZipFile(wheel) as archive:
        supplier = archive.read(member)
    try:
        result = compare_pe_loader(prefix, supplier)
    except ValueError as error:
        # build.py deliberately deletes unqualified executables and does not
        # retain a failed return value. Emit bounded, hash-bound diagnostics to
        # the native job log BEFORE re-raising; never turn rejection into evidence
        # accepted by minimal_review, and never export executable bytes/paths.
        import sys
        diagnostic = {"schema": 1, "event": "PE_LOADER_COMPARISON_REJECTED",
                      "status": "rejected", "notQualification": True,
                      "artifactSha256": payload["artifactSha256"],
                      "loaderSha256": loader["sha256"], "loaderHashScope": scope,
                      "builderSourceSha256": payload.get("builderSourceSha256"),
                      "supplierWheelSha256": inputs[0]["sha256"], "supplierMember": member,
                      "identity": identity, "error": str(error)[:512],
                      "comparison": getattr(error, "diagnostics", None)}
        print(json.dumps(diagnostic, indent=2), file=sys.stderr, flush=True)
        raise
    result.update({"schema": 1, "status": "evidence-only", "system": "Windows", "architecture": "x86_64",
                   "artifactSha256": payload["artifactSha256"], "loaderSha256": loader["sha256"],
                   "loaderHashScope": scope, "builderSourceSha256": payload.get("builderSourceSha256"),
                   "supplierWheelSha256": inputs[0]["sha256"], "supplierMember": member,
                   "identity": identity, "notQualification": True})
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["audit", "fetch", "materialize", "probe-loader"])
    parser.add_argument("--executable", type=Path, help="Actual native CI frozen EXE, never published by this command")
    parser.add_argument("--inventory", type=Path)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--review", type=Path, default=REVIEW)
    parser.add_argument("--base", type=Path, default=BASE)
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        review = json.loads(args.review.read_text(encoding="utf-8"))
        if args.command == "fetch":
            if args.cache is None:
                parser.error("fetch requires --cache")
            report = fetch_sources(review, args.cache, offline=args.offline)
            print(json.dumps(report, indent=2))
            return 0  # Evidence acquisition only; never a coverage approval.
        if args.inventory is None:
            parser.error("audit/materialize require --inventory")
        inventory = json.loads(args.inventory.read_text(encoding="utf-8"))
        if args.command == "probe-loader":
            if args.executable is None or args.cache is None:
                parser.error("probe-loader requires --executable and --cache")
            report = probe_native_loader(args.executable, inventory, review, args.cache)
            serialized = json.dumps(report, indent=2) + "\n"
            if args.output is not None:
                with args.output.open("x", encoding="utf-8") as stream:
                    stream.write(serialized)
            print(serialized, end="")
            return 0  # Evidence-only, not coverage approval.
        report = audit(inventory, review)
        report["verified_assets"] = verify_assets(review, args.base)
        if args.command == "materialize":
            if args.cache is None or args.output is None:
                parser.error("materialize requires --cache and --output")
            report["coverage"] = str(materialize(inventory, review, args.base, args.output, cache=args.cache))
        print(json.dumps(report, indent=2))
        return 2 if report["status"] == "blocked" else 0
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(json.dumps({"status": "rejected", "error": str(error)}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

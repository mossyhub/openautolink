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
    audit(inventory, result)
    return result


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
    """Acquire required sources and the exact probe wheel, not unrelated wheels."""
    inputs = list(review["source_inputs"])
    if "supplier_inputs" in review:
        loader = [e for e in review["supplier_inputs"]
                  if e["path"] == "pyinstaller-6.13.0-py3-none-win_amd64.whl"]
        if len(loader) != 1:
            raise ValueError("Missing unique pinned Windows loader wheel")
        inputs += loader
    return _load("prepare_native").prepare(
        {"schema": 1, "inputs": inputs}, cache, [], offline=offline)


def windows10_runtime_binaries(toc):
    """Windows-10+ deployment policy applied to Analysis.binaries BEFORE freeze.

    OS UCRT/API-set forwarders must resolve from the supported Windows system,
    not the build runner's private SDK/OS copies. This does not suppress any
    entries from a frozen inventory. MSVC runtime DLLs remain distributable
    payload and require their own exact supplier/terms review.
    """
    def system_runtime(name):
        name = name.replace("\\", "/").rsplit("/", 1)[-1].lower()
        return (name == "ucrtbase.dll" or
                re.fullmatch(r"api-ms-win-(?:core|crt)-[a-z0-9-]+\.dll", name) is not None)
    return [entry for entry in toc if not system_runtime(entry[0])]


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
    No code, data, import, relocation, entry-point or executable-section changes
    are permitted. This is scoped evidence for the embedded-loader exception,
    not a substitute for notices or native frozen execution.
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
                  "NumberOfRvaAndSizes"]
        headers = {key: getattr(pe.OPTIONAL_HEADER, key) for key in fields}
        headers["Characteristics"] = pe.FILE_HEADER.Characteristics
        directories = [(i, d.VirtualAddress, d.Size) for i, d in enumerate(pe.OPTIONAL_HEADER.DATA_DIRECTORY)
                       if i != 2]  # IMAGE_DIRECTORY_ENTRY_RESOURCE only.
        resource_dir = pe.OPTIONAL_HEADER.DATA_DIRECTORY[2]
        if resource_dir.Size and not any(r["rva"] <= resource_dir.VirtualAddress
                and resource_dir.VirtualAddress + resource_dir.Size <= r["rva"] + max(r["virtual_size"], r["raw_size"])
                for r in resources):
            raise ValueError("Resource directory outside resource section")
        pe.close()
        return sections, headers, directories, resources

    actual, headers, directories, resources = image(frozen)
    expected, expected_headers, expected_directories, _ = image(supplier)
    if actual != expected:
        raise ValueError("PE loader section bytes/layout changed")
    if headers != expected_headers or directories != expected_directories:
        raise ValueError("PE loader entry point/header/directory changed")
    return {"scope": "nonresource-PE-sections-with-debug-timestamps-zeroed-and-critical-headers",
            "supplierSha256": hashlib.sha256(supplier).hexdigest(),
            "sections": list(actual.values()), "resourceSections": resources}


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
    return {"status": "evidence-only", "notQualification": True, "screenshots": screenshots,
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
    result = compare_pe_loader(prefix, supplier)
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

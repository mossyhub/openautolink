"""Recipient-download Windows bootstrap; no Microsoft runtime redistribution.

The NSIS zlib launcher uses only inbox Win32 DLLs. The frozen inner builder
retains its existing exact source/native-license verification gate.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile
import zipfile

BASE = Path(__file__).resolve().parent
SUPPORT = BASE / "windows-setup"
LOCK = BASE / "provenance/windows-setup.lock.json"


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def load_module(name):
    spec = importlib.util.spec_from_file_location("setup_" + name, BASE / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def render_support(destination, builder_hash):
    if not re.fullmatch(r"[0-9a-f]{64}", builder_hash):
        raise ValueError("Invalid builder digest")
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    replacements = {"BUILDER_SHA256": builder_hash,
                    **{"RUNTIME_" + field.upper(): lock["runtime"][field]
                       for field in ("version", "url", "sha256")}}
    destination.mkdir(parents=True, exist_ok=True)
    for name in ("bootstrap.ps1", "setup.nsi"):
        text = (SUPPORT / name).read_text(encoding="utf-8")
        for key, value in replacements.items():
            text = text.replace("@" + key + "@", value)
        if re.search(r"@[A-Z_]+@", text):
            raise ValueError("Unresolved bootstrap placeholder")
        (destination / name).write_text(text, encoding="utf-8", newline="\n")


def extract_compiler(archive, destination, record):
    """Validate the whole pinned ZIP before writing any member."""
    import stat
    if sha256(archive) != record["sha256"]:
        raise ValueError("NSIS archive digest mismatch")
    if destination.exists():
        raise ValueError("Compiler destination must be new")
    with zipfile.ZipFile(archive) as source:
        seen, total = set(), 0
        for item in source.infolist():
            raw = item.orig_filename
            parts = raw.rstrip("/").split("/")
            total += item.file_size
            if (not raw or "\\" in raw or ":" in raw or "\0" in raw
                    or any(p in {"", ".", ".."} or p.endswith((" ", ".")) for p in parts)
                    or parts[0] != record["root"] or raw.casefold() in seen
                    or stat.S_ISLNK(item.external_attr >> 16) or item.flag_bits & 1
                    or len(seen) > 20000 or total > 256 * 1024 * 1024):
                raise ValueError("Unsafe compiler ZIP member")
            seen.add(raw.casefold())
        try:
            source.extractall(destination)
            root = destination / record["root"]
            for path_key, hash_key in (("stub", "stubSha256"), ("notice", "noticeSha256")):
                if path_key in record and sha256(root / record[path_key]) != record[hash_key]:
                    raise ValueError("NSIS supplier member digest mismatch")
            return root
        except Exception:
            shutil.rmtree(destination, ignore_errors=True)
            raise


def acquire_compiler(cache, destination, *, offline=False):
    lock = json.loads(LOCK.read_text(encoding="utf-8"))["nsis"]
    entry = {"path": lock["root"] + ".zip", "url": lock["url"], "sha256": lock["sha256"]}
    load_module("prepare_native").prepare({"schema": 1, "inputs": [entry]}, cache, [], offline=offline)
    return extract_compiler(cache / entry["path"], destination, lock)


MODE = "official-vc-runtime-bootstrap-v1"
PAYLOAD = frozenset({"builder.exe", "bootstrap.ps1", "setup-manifest.json", "NSIS-COPYING.txt", "BOOTSTRAP-NOTICE.txt"})
MANIFEST = "OpenAutoLink-Builder-windows-x86_64.json"
NOTICE = """OpenAutoLink Builder Windows bootstrap
The NSIS zlib launcher is covered by the unmodified accompanying NSIS-COPYING.txt.
Builder, Python and Qt notices and corresponding builder/library source are
embedded in builder.exe (use --export-compliance DESTINATION.zip).
The matching bootstrap source is in that embedded builder-source.zip.
Microsoft's runtime installer is NOT included. If needed, this wrapper asks
permission to download it directly from Microsoft, checks its pinned SHA-256
and valid Microsoft Authenticode signature, then opens Microsoft's interactive
installer. Declining the installer or elevation never starts the builder.
Microsoft-only downstream terms for embedded code remain in the inner app;
they do not restrict LGPL library replacement or debugging such replacements.
This wrapper and builder are not developer-signed. No security policy is changed.
"""


def verify_inner(candidate, manifest, *, build):
    """Inspect embedded compliance without running any executable."""
    from PyInstaller.archive.readers import CArchiveReader
    assert_external_runtime(build.native_inventory(candidate))
    reader = CArchiveReader(str(candidate))
    blob = reader.extract("builder-compliance.zip")
    with tempfile.TemporaryDirectory(prefix="inner-check-", dir=candidate.parent) as temporary:
        archive = Path(temporary) / "compliance.zip"
        archive.write_bytes(blob)
        build.verify_embedded_compliance(candidate, archive)
        build.verify_native_compliance(candidate, archive)
    verify_snapshot(candidate, manifest["builderSourceSha256"])


def verify_snapshot(candidate, source_hash):
    from PyInstaller.archive.readers import CArchiveReader
    import io
    blob = CArchiveReader(str(candidate)).extract("builder-compliance.zip")
    with zipfile.ZipFile(io.BytesIO(blob)) as compliance:
        source = compliance.read("builder-source.zip")
    if hashlib.sha256(source).hexdigest() != source_hash:
        raise ValueError("Inner source digest mismatch")
    # Wrapper must be generated from precisely the embedded reviewed source.
    with zipfile.ZipFile(io.BytesIO(source)) as snapshot:
        for path in [Path(__file__), LOCK, *sorted(SUPPORT.glob("*"))]:
            if path.is_file() and snapshot.read("desktop-builder/" + path.relative_to(BASE.parent).as_posix()) != path.read_bytes():
                raise ValueError("Bootstrap source differs from inner snapshot")


def stage_payload(incoming, staged, compiler, *, build):
    manifest = build.read_manifest(incoming / MANIFEST)
    if (manifest.get("kind") != "internal-runtime-stage" or manifest.get("system") != "Windows"
            or manifest.get("architecture") != "x86_64" or manifest.get("runtimeCollection") != MODE
            or manifest.get("nativeCoverage") != "exact-payload-evidence-verified"
            or manifest.get("frozenSmokeTest") != "passed"
            or manifest.get("artifact") != "OpenAutoLink-Builder.exe"
            or not re.fullmatch(r"[0-9a-f]{64}", manifest.get("builderSourceSha256", ""))):
        raise ValueError("Expected qualified Windows bootstrap inner runtime stage")
    candidate = incoming / manifest["artifact"]
    verify_inner(candidate, manifest, build=build)
    return write_payload(candidate, staged, compiler, manifest)


def write_payload(candidate, staged, compiler, manifest):
    """Private staging only; caller determines diagnostic versus qualified use."""
    staged.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(candidate, staged / "builder.exe")
    render_support(staged, manifest["sha256"])
    shutil.copyfile(compiler / "COPYING", staged / "NSIS-COPYING.txt")
    (staged / "BOOTSTRAP-NOTICE.txt").write_text(NOTICE, encoding="utf-8", newline="\n")
    result = {"schema": 1, "kind": MODE, "innerSha256": manifest["sha256"],
              "builderSourceSha256": manifest["builderSourceSha256"],
              "payload": {name: sha256(staged / name) for name in sorted(PAYLOAD - {"setup-manifest.json"})}}
    (staged / "setup-manifest.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def verify_extracted(directory, expected):
    if set(expected) != PAYLOAD or {p.relative_to(directory).as_posix() for p in directory.rglob("*")} != PAYLOAD:
        raise ValueError("Unexpected bootstrap payload file set")
    for name, digest in expected.items():
        target = directory / name
        if (not re.fullmatch(r"[0-9a-f]{64}", digest) or target.is_symlink()
                or not target.is_file() or sha256(target) != digest):
            raise ValueError("Changed extracted bootstrap payload")


def require_native():
    if platform.system() != "Windows" or platform.machine().lower() not in {"amd64", "x86_64"}:
        raise ValueError("Requires native Windows x64; parser/cross-compile evidence is not qualification")


def child_environment(**updates):
    blocked = {"QT_PLUGIN_PATH", "QT_QPA_PLATFORM_PLUGIN_PATH", "PYTHONPATH", "PYTHONHOME",
               "OAL_SETUP_EXTRACT_ONLY", "OAL_SETUP_SMOKE_SCREENSHOT", "NSISDIR", "NSISCONFDIR"}
    return {**{k: v for k, v in os.environ.items() if k.upper() not in blocked}, **updates}


def compile_setup(staged, compiler):
    require_native()
    # /NOCONFIG excludes per-user/system nsisconf.nsh; every input is private/pinned.
    subprocess.run([str(compiler / "makensis.exe"), "/NOCONFIG", "/V2", str(staged / "setup.nsi")],
                   cwd=staged, env=child_environment(NSISDIR=str(compiler)), check=True, timeout=900)
    return staged / "OpenAutoLink-Bootstrap.exe"


def verify_stub(executable, compiler):
    """Require exact official zlib code/imports; permit only NSIS PE resources."""
    import pefile
    lock = json.loads(LOCK.read_text(encoding="utf-8"))["nsis"]
    stub = compiler / lock["stub"]
    if sha256(stub) != lock["stubSha256"]:
        raise ValueError("Changed official NSIS stub")
    with pefile.PE(str(stub)) as supplier, pefile.PE(str(executable)) as actual:
        if actual.FILE_HEADER.Machine != 0x14c or actual.OPTIONAL_HEADER.Magic != 0x10b:
            raise ValueError("Expected official x86 NSIS launcher")
        if actual.FILE_HEADER.__pack__() != supplier.FILE_HEADER.__pack__():
            raise ValueError("Changed NSIS COFF header")
        if len(actual.sections) != len(supplier.sections):
            raise ValueError("Changed NSIS section count")
        sections = []
        ndata = next(s for s in supplier.sections if s.Name.rstrip(b"\0") == b".ndata")
        alignment = supplier.OPTIONAL_HEADER.SectionAlignment
        resource_rva = (ndata.VirtualAddress + 65536 + alignment - 1) // alignment * alignment
        for a, s in zip(actual.sections, supplier.sections):
            if a.Name != s.Name:
                raise ValueError("Changed NSIS section order")
            if a.Name.rstrip(b"\0") != b".rsrc":
                if a.Name.rstrip(b"\0") == b".ndata":
                    # NSIS reserves the default 32 Unicode user-variable slots
                    # (1024 wchar_t each) in uninitialized, non-executable memory.
                    if (a.SizeOfRawData or a.Misc_VirtualSize != 65536
                            or a.Characteristics != 0xc0000080):
                        raise ValueError("Unexpected NSIS user-variable allocation")
                    a.Misc_VirtualSize = s.Misc_VirtualSize
                    a.Characteristics = s.Characteristics
                if a.__pack__() != s.__pack__() or a.get_data() != s.get_data():
                    raise ValueError("Changed NSIS nonresource section")
                sections.append({"name": a.Name.rstrip(b"\0").decode("ascii"),
                                 "sha256": hashlib.sha256(a.get_data()).hexdigest()})
            else:
                if (a.Characteristics != s.Characteristics or a.Characteristics & 0x20000000
                        or a.VirtualAddress != resource_rva
                        or not 0 < a.Misc_VirtualSize <= a.SizeOfRawData
                        or a.PointerToRelocations != s.PointerToRelocations
                        or a.PointerToLinenumbers != s.PointerToLinenumbers
                        or a.NumberOfRelocations != s.NumberOfRelocations
                        or a.NumberOfLinenumbers != s.NumberOfLinenumbers
                        or a.PointerToRawData != s.PointerToRawData
                        or a.SizeOfRawData <= 0 or a.SizeOfRawData > 1024 * 1024
                        or a.PointerToRawData + a.SizeOfRawData > executable.stat().st_size
                        or a.VirtualAddress % actual.OPTIONAL_HEADER.SectionAlignment
                        or a.SizeOfRawData % actual.OPTIONAL_HEADER.FileAlignment):
                    raise ValueError("Unsafe NSIS resource section")
        # Only the resource directory can move. The unchanged .rdata binds imports.
        for index, (a, s) in enumerate(zip(actual.OPTIONAL_HEADER.DATA_DIRECTORY, supplier.OPTIONAL_HEADER.DATA_DIRECTORY)):
            if index == 2:
                resource = next(s for s in actual.sections if s.Name.rstrip(b"\0") == b".rsrc")
                if a.VirtualAddress != resource.VirtualAddress or a.Size > resource.SizeOfRawData:
                    raise ValueError("Invalid NSIS resource directory")
            elif a.__pack__() != s.__pack__():
                raise ValueError("Changed NSIS nonresource directory")
        optional = actual.OPTIONAL_HEADER
        alignment = optional.SectionAlignment
        extent = max(s.VirtualAddress + max(s.Misc_VirtualSize, s.SizeOfRawData) for s in actual.sections)
        if optional.SizeOfImage != (extent + alignment - 1) // alignment * alignment:
            raise ValueError("Invalid NSIS image size")
        if optional.MajorImageVersion != 6 or optional.DllCharacteristics != 0x8540:
            raise ValueError("Unexpected NSIS PE security flags")
        for field in ("MajorImageVersion", "DllCharacteristics", "SizeOfImage"):
            setattr(optional, field, getattr(supplier.OPTIONAL_HEADER, field))
        if optional.__pack__() != supplier.OPTIONAL_HEADER.__pack__():
            raise ValueError("Changed NSIS optional header")
        imports = sorted(e.dll.decode("ascii").lower() for e in actual.DIRECTORY_ENTRY_IMPORT)
        if imports != sorted(["advapi32.dll", "shell32.dll", "ole32.dll", "comctl32.dll", "user32.dll", "gdi32.dll", "kernel32.dll"]):
            raise ValueError("Non-inbox NSIS dependency")
    return {"status": "exact-nonresource-sections", "supplierStubSha256": lock["stubSha256"],
            "supplierArchiveSha256": lock["sha256"], "sections": sections, "imports": imports}


def extract_outer(executable, directory, expected):
    require_native()
    if directory.exists():
        raise ValueError("Extraction target must be new")
    subprocess.run([str(executable)], check=True, timeout=120,
                   env=child_environment(OAL_SETUP_EXTRACT_ONLY=str(directory)))
    verify_extracted(directory, expected)


def smoke_outer(executable, screenshot):
    require_native()
    screenshot.unlink(missing_ok=True)
    subprocess.run([str(executable)], check=True, timeout=180,
                   env=child_environment(QT_QPA_PLATFORM="windows", OAL_SETUP_SMOKE_SCREENSHOT=str(screenshot)))
    if not screenshot.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("Outer bootstrap did not render the read-only BuilderWindow")


def probe_candidate(candidate, discovery, cache, work, output):
    """Private pre-coverage evidence, never permission to publish or accept terms."""
    report = {"schema": 1, "status": "failed", "notQualification": True,
              "artifactSha256": discovery["artifactSha256"],
              "builderSourceSha256": discovery["builderSourceSha256"],
              "scope": "native-windows-private-bootstrap-installed-runtime",
              "humanInstallation": "not-tested-no-assent-automated"}
    screenshot = output / "windows-bootstrap.png"
    phase = "WINDOWS_BOOTSTRAP_PLATFORM"
    try:
        screenshot.unlink(missing_ok=True)
        require_native()
        phase = "WINDOWS_BOOTSTRAP_INPUT"
        if sha256(candidate) != discovery["artifactSha256"]:
            raise ValueError("Candidate changed")
        assert_external_runtime(discovery["payload"])
        verify_snapshot(candidate, discovery["builderSourceSha256"])
        with tempfile.TemporaryDirectory(prefix="bootstrap-probe-", dir=work) as temporary:
            private = Path(temporary)
            phase = "WINDOWS_BOOTSTRAP_COMPILER"
            compiler = acquire_compiler(cache, private / "compiler")
            staged = private / "payload"
            write_payload(candidate, staged, compiler,
                          {"sha256": discovery["artifactSha256"], "builderSourceSha256": discovery["builderSourceSha256"]})
            expected = {name: sha256(staged / name) for name in sorted(PAYLOAD)}
            executable = compile_setup(staged, compiler)
            report["outerSha256"] = sha256(executable)
            phase = "WINDOWS_BOOTSTRAP_LOADER"
            report["loader"] = verify_stub(executable, compiler)
            phase = "WINDOWS_BOOTSTRAP_EXTRACT"
            extract_outer(executable, private / "extracted", expected)
            report["extraction"] = "exact-payload-verified"
            phase = "WINDOWS_BOOTSTRAP_UI"
            smoke_outer(executable, screenshot)
            if sha256(executable) != report["outerSha256"] or sha256(candidate) != discovery["artifactSha256"]:
                raise ValueError("Probe artifact changed")
            report["screenshotSha256"] = sha256(screenshot)
        report["status"] = "passed"
        return report
    except Exception as error:
        report["failureCode"] = phase
        screenshot.unlink(missing_ok=True)
        raise ValueError(phase) from error
    finally:
        (output / "windows-bootstrap.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


def package(incoming, cache, output, *, offline=False):
    """Two-stage contract: only fully covered inner stages become outer downloads."""
    require_native()
    incoming, cache, output = incoming.resolve(), cache.resolve(), output.resolve()
    if output.exists() and any(output.iterdir()):
        raise ValueError("Use an empty bootstrap output directory")
    output.mkdir(parents=True, exist_ok=True)
    build = load_module("build")
    with tempfile.TemporaryDirectory(prefix="bootstrap-", dir=output.parent) as temporary:
        work = Path(temporary)
        compiler = acquire_compiler(cache, work / "compiler", offline=offline)
        staged = work / "payload"
        metadata = stage_payload(incoming, staged, compiler, build=build)
        expected = {name: sha256(staged / name) for name in sorted(PAYLOAD)}
        executable = compile_setup(staged, compiler)
        stub = verify_stub(executable, compiler)
        outer_hash = sha256(executable)
        extract_outer(executable, work / "extracted", expected)
        screenshot = work / "smoke-windows-qwindows.png"
        smoke_outer(executable, screenshot)
        if sha256(executable) != outer_hash:
            raise ValueError("Outer artifact changed during qualification")
        target = output / "OpenAutoLink-Builder.exe"
        image = output / screenshot.name
        try:
            shutil.copyfile(executable, target)
            shutil.copyfile(screenshot, image)
            return build.write_manifest(output, Path(MANIFEST).stem, target, [target, image],
                system="Windows", architecture="x86_64", kind="direct-executable", bootstrap=MODE,
                builderSourceSha256=metadata["builderSourceSha256"], innerSha256=metadata["innerSha256"],
                embeddedPayload=expected, bootstrapLoader=stub, frozenSmokeTest="passed",
                nativeCoverage="exact-payload-evidence-verified", nativePlatformSmoke="qwindows-passed",
                bootstrapExtraction="exact-payload-verified", runtimeInstaller="recipient-download-only-interactive")
        except Exception:
            for path in (target, image, output / MANIFEST):
                path.unlink(missing_ok=True)
            raise


def smoke_package(package_dir):
    require_native()
    build = load_module("build")
    manifest = build.read_manifest(package_dir / MANIFEST)
    if (manifest.get("bootstrap") != MODE or manifest.get("kind") != "direct-executable"
            or manifest.get("nativeCoverage") != "exact-payload-evidence-verified"
            or manifest.get("system") != "Windows" or manifest.get("architecture") != "x86_64"):
        raise ValueError("Expected qualified Windows bootstrap manifest")
    executable = package_dir / manifest["artifact"]
    with tempfile.TemporaryDirectory(prefix="outer-verify-", dir=package_dir.parent) as temporary:
        extracted = Path(temporary) / "payload"
        extract_outer(executable, extracted, manifest["embeddedPayload"])
        verify_inner(extracted / "builder.exe", manifest, build=build)
        screenshot = package_dir / "smoke-windows-qwindows.png"
        smoke_outer(executable, screenshot)
    manifest["nativePlatformSmoke"] = "qwindows-passed"
    manifest["files"][screenshot.name] = sha256(screenshot)
    (package_dir / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--inner", type=Path, help="Fully qualified internal Windows runtime directory")
    mode.add_argument("--smoke", type=Path, help="Reverify final bootstrap directory on native Windows")
    parser.add_argument("--cache", type=Path, help="Pinned NSIS supplier cache")
    parser.add_argument("--output", type=Path, help="New/empty public artifact directory")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args(argv)
    if args.smoke:
        smoke_package(args.smoke.resolve())
    else:
        if args.cache is None or args.output is None:
            parser.error("--inner requires --cache and --output")
        package(args.inner, args.cache, args.output, offline=args.offline)
    return 0


def is_external_runtime(name):
    name = name.replace("\\", "/").rsplit("/", 1)[-1].casefold()
    return (name in {"ucrtbase.dll", "ucrtbased.dll"}
            or re.fullmatch(r"(?:vcruntime|msvcp|msvcr|concrt|vcomp)\d+[a-z0-9_]*\.dll", name) is not None
            or re.fullmatch(r"api-ms-win-(?:crt|core)-[a-z0-9-]+\.dll", name) is not None)


def external_runtime_binaries(toc):
    """Apply to Analysis.binaries AND datas before EXE, never to inventory."""
    return [row for row in toc if not is_external_runtime(row[0])]


def assert_external_runtime(payload):
    if any(is_external_runtime(row["path"]) for row in payload):
        raise ValueError("Standalone Microsoft runtime remains in actual payload")


if __name__ == "__main__":
    raise SystemExit(main())

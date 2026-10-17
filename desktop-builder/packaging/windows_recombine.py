"""Private Windows Qt replacement evidence; never release/license approval.

One bounded extra PyInstaller build substitutes a private official-wheel QtCore
clone at Analysis's binary TOC, the same collection point used by a recipient's
modified private Qt installation. No installed DLL or original spec is changed.
The non-loaded overlay is a replacement-route test, NOT a Qt source rebuild or
proof that arbitrary ABI-incompatible changes work. Diagnostic exports contain
only hashes, fixed labels, CI identity and a read-only builder screenshot.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import zipfile

CORE = "PySide6/Qt6Core.dll"
MARKER = b"\nOAL-PRIVATE-LGPL-RECOMBINATION-PROBE-v1\n"


def replace_qtcore_toc(binaries, replacement):
    """Substitute one private file, before EXE; never mutate installed Qt."""
    matches = [row for row in binaries if row[0].replace("\\", "/").casefold() == CORE.casefold()]
    source = Path(replacement["source"])
    if (len(matches) != 1 or matches[0][2] != "BINARY"
            or digest(Path(matches[0][1]).read_bytes()) != replacement["originalSha256"]
            or digest(source.read_bytes()) != replacement["modifiedSha256"]
            or replacement["originalSha256"] == replacement["modifiedSha256"]):
        raise ValueError("Replacement requires one exact original and different private QtCore")
    return [(name, str(source), kind) if (name, path, kind) == matches[0] else (name, path, kind)
            for name, path, kind in binaries]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def write_marked_clone(original: bytes, target: Path) -> dict:
    """Preserve EVERY supplier byte, including code, headers and certificates.

    Appending outside all mapped sections does not change executed code. This
    deliberately does not assert Authenticode validity for the modified file.
    """
    import pefile
    try:
        with pefile.PE(data=original, fast_load=True) as pe:
            if (not pe.sections or pe.FILE_HEADER.Machine != 0x8664
                    or pe.OPTIONAL_HEADER.Magic != 0x20B
                    or pe.OPTIONAL_HEADER.SizeOfHeaders > len(original)
                    or any(s.PointerToRawData + s.SizeOfRawData > len(original) for s in pe.sections)):
                raise ValueError("Invalid x64 PE section extent")
    except pefile.PEFormatError as exc:
        raise ValueError("Expected x64 PE supplier") from exc
    modified = original + MARKER
    with target.open("xb") as stream:
        stream.write(modified)
    return {"originalSha256": digest(original), "modifiedSha256": digest(modified),
            "originalSize": len(original), "modifiedSize": len(modified),
            "mutation": "non-loaded-file-overlay-only"}


def runtime_hook_source():
    # Include only the observer, not this build module's archive/PE dependencies.
    import inspect
    return inspect.getsource(install_runtime_probe) + '''
# Custom frozen hooks run before Qt's standard hook. Observe only once main
# starts, after plugin/library paths have been established; then stop profiling.
import sys
def _oal_arm_observer(frame, event, arg):
    if event == "call" and frame.f_code.co_name == "main" and frame.f_globals.get("__name__") == "__main__":
        sys.setprofile(None)
        install_runtime_probe()
sys.setprofile(_oal_arm_observer)
'''


def install_runtime_probe():
    """Private frozen-only observer; record Win32 loaded modules at UI shutdown."""
    import ctypes
    from ctypes import wintypes
    import hashlib
    import json
    import os
    from pathlib import Path
    import platform
    import sys
    from PySide6.QtWidgets import QApplication

    target = Path(os.environ["OAL_RECOMBINATION_RUNTIME_REPORT"])
    # Keep a strong reference until aboutToQuit; main uses this SAME application
    # and still constructs/renders the real read-only BuilderWindow itself.
    app = QApplication.instance() or QApplication([sys.argv[0]])

    def observe():
        try:
            kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel.GetModuleHandleW.argtypes = [wintypes.LPCWSTR]
            kernel.GetModuleHandleW.restype = wintypes.HMODULE
            kernel.GetModuleFileNameW.argtypes = [wintypes.HMODULE, wintypes.LPWSTR, wintypes.DWORD]
            kernel.GetModuleFileNameW.restype = wintypes.DWORD
            root = Path(sys._MEIPASS).resolve()

            def loaded(name, relative):
                handle = kernel.GetModuleHandleW(name)
                buffer = ctypes.create_unicode_buffer(32768)
                length = kernel.GetModuleFileNameW(handle, buffer, len(buffer)) if handle else 0
                if not length or length >= len(buffer):
                    raise ValueError("Missing loaded module")
                path = Path(buffer.value).resolve()
                return hashlib.sha256(path.read_bytes()).hexdigest(), path == (root / relative).resolve()

            core, bundled_core = loaded("Qt6Core.dll", "PySide6/Qt6Core.dll")
            plugin, bundled_plugin = loaded("qwindows.dll", "PySide6/plugins/platforms/qwindows.dll")
            result = {"system": platform.system(), "architecture": platform.machine().lower().replace("amd64", "x86_64"),
                      "frozen": bool(getattr(sys, "frozen", False)), "qtPlatform": app.platformName(),
                      "coreSha256": core, "pluginSha256": plugin,
                      "bundledCore": bundled_core, "bundledPlugin": bundled_plugin,
                      "builderWindow": any(type(w).__name__ == "BuilderWindow" and type(w).__module__ == "oal_builder.ui"
                                           for w in app.topLevelWidgets())}
        except Exception:
            result = {"failureCode": "WINDOWS_RECOMBINATION_LOADED_MODULE_PROBE"}
        with target.open("x", encoding="utf-8") as writer:
            json.dump(result, writer)

    app.aboutToQuit.connect(observe)


def probe_recombination(candidate, discovery, review, cache, work, output, *, build, windows):
    """Run once in private staging BEFORE materialize's fail-closed legal gate.

    The output is evidence to review, not authority to remove any blocker. The
    caller's inventory/coverage/spec and installed libraries remain untouched.
    """
    from PyInstaller.archive.readers import CArchiveReader

    report = {"schema": 1, "status": "failed", "notQualification": True,
              "scope": "native-windows-private-QtCore-overlay-replacement",
              "artifactSha256": discovery["artifactSha256"],
              "builderSourceSha256": discovery["builderSourceSha256"]}
    screenshot = output / "windows-recombination.png"
    phase = "WINDOWS_RECOMBINATION_PLATFORM"
    try:
        screenshot.unlink(missing_ok=True)
        if platform.system() != "Windows" or platform.machine().lower() not in {"amd64", "x86_64"}:
            raise ValueError("Native Windows x64 required")
        report["identity"] = windows.ci_identity()
        if build.sha256(candidate) != discovery["artifactSha256"]:
            raise ValueError("Original candidate changed")
        # Establish the baseline assignment, even when legal review is blocked.
        windows.audit(discovery, review)
        phase = "WINDOWS_RECOMBINATION_SUPPLIER"
        matches = [m for m in review["supplier_matches"] if m["payload"] == CORE]
        if len(matches) != 1 or matches[0]["member"] != CORE:
            raise ValueError("Unique official QtCore supplier required")
        match = matches[0]
        inputs = [s for s in review["supplier_inputs"] if s["path"] == match["input"]]
        if len(inputs) != 1:
            raise ValueError("Unique pinned supplier wheel required")
        wheel = build.coverage_asset(cache, inputs[0])
        with zipfile.ZipFile(wheel) as archive:
            if archive.namelist().count(CORE) != 1:
                raise ValueError("Ambiguous supplier member")
            original = archive.read(CORE)
        baseline = windows._payload_map(discovery["payload"])
        if digest(original) != match["sha256"] or digest(original) != baseline[CORE]["sha256"]:
            raise ValueError("Supplier differs from candidate")
        report.update(supplierWheelSha256=inputs[0]["sha256"], supplierMember=CORE)
        with tempfile.TemporaryDirectory(prefix="windows-recombine-", dir=work) as temp:
            private = Path(temp)
            replacement_dir = private / "replacement"
            replacement_dir.mkdir()
            clone = replacement_dir / "Qt6Core.dll"
            report.update(write_marked_clone(original, clone))
            hook = private / "recombination-hook.py"
            hook.write_text(runtime_hook_source(), encoding="utf-8")
            report["instrumentationSha256"] = build.sha256(hook)
            phase = "WINDOWS_RECOMBINATION_BUILD"
            spec = build.windows_spec(work / "source-snapshot" / build.ROOT.name, private,
                                      work / "builder-compliance.zip",
                                      replacement={"source": str(clone),
                                                   "originalSha256": report["originalSha256"],
                                                   "modifiedSha256": report["modifiedSha256"]},
                                      extra_runtime_hooks=[str(hook)])
            stage = private / "distribution"
            # No retries, no original work/cache/spec reuse and no recursion
            # through build.main (which would require release/legal approval).
            subprocess.run([sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean",
                            "--distpath", str(stage), "--workpath", str(private / "pyinstaller"), str(spec)],
                           check=True, timeout=900, cwd=build.ROOT,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            executable = stage / (build.NAME + ".exe")
            phase = "WINDOWS_RECOMBINATION_ARCHIVE"
            reader = CArchiveReader(str(executable))
            members = [n for n in reader.toc if n.replace("\\", "/") == CORE]
            if len(members) != 1:
                raise ValueError("Missing unique archived replacement")
            archived = reader.extract(members[0])
            if archived != original + MARKER or digest(archived) != report["modifiedSha256"]:
                raise ValueError("Archive lost or changed replacement")
            report["archivedCoreSha256"] = digest(archived)
            report["replacementArtifactSha256"] = build.sha256(executable)
            actual = build.native_inventory(executable)
            actual_map = windows._payload_map(actual)
            if (actual_map[CORE]["sha256"] != digest(archived)
                    or {p: e for p, e in actual_map.items() if p not in {CORE, "@bootloader"}}
                    != {p: e for p, e in baseline.items() if p not in {CORE, "@bootloader"}}):
                raise ValueError("Unexpected replacement inventory drift")
            # Exercise the actual shared final gate. Its missing-byte check runs
            # before unrelated Microsoft/source obligations, so reject Qt itself.
            phase = "WINDOWS_RECOMBINATION_ORIGINAL_COVERAGE"
            try:
                build.validate_native_coverage(actual, review, windows.BASE)
            except ValueError as error:
                if not str(error).startswith("Uncovered native payload: ") or CORE not in str(error).split(": ", 1)[1].split(", "):
                    raise ValueError("Original coverage did not reject changed QtCore") from error
            else:
                raise ValueError("Original coverage accepted modified QtCore")
            report["originalCoverageRejected"] = True
            phase = "WINDOWS_RECOMBINATION_RUNTIME"
            runtime_file = private / "runtime.json"
            png = private / "builder.png"
            workspace = private / "must-not-be-created"
            env = {key: value for key, value in os.environ.items()
                   if key not in {"QT_PLUGIN_PATH", "QT_QPA_PLATFORM_PLUGIN_PATH", "PYTHONPATH", "PYTHONHOME"}}
            env.update(QT_QPA_PLATFORM="windows", OAL_RECOMBINATION_RUNTIME_REPORT=str(runtime_file))
            subprocess.run([str(executable), "--smoke-test", "--workspace", str(workspace),
                            "--screenshot", str(png)], check=True, timeout=120, env=env,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            runtime = json.loads(runtime_file.read_text(encoding="utf-8"))
            expected = {"system": "Windows", "architecture": "x86_64", "frozen": True,
                        "qtPlatform": "windows", "coreSha256": report["modifiedSha256"],
                        "pluginSha256": baseline["PySide6/plugins/platforms/qwindows.dll"]["sha256"],
                        "bundledCore": True, "bundledPlugin": True, "builderWindow": True}
            if (runtime != expected or workspace.exists()
                    or not png.read_bytes().startswith(b"\x89PNG\r\n\x1a\n")):
                raise ValueError("Replacement runtime or read-only UI proof failed")
            report["runtime"] = expected  # fixed allowlist, never arbitrary child data
            shutil.copyfile(png, screenshot)
            report["screenshotSha256"] = build.sha256(screenshot)
        phase = "WINDOWS_RECOMBINATION_ORIGINAL_CHANGED"
        if build.sha256(candidate) != discovery["artifactSha256"]:
            raise ValueError("Original candidate changed during probe")
        report["status"] = "passed"
        return report
    except Exception as error:
        report["failureCode"] = phase
        screenshot.unlink(missing_ok=True)
        raise ValueError(phase) from error
    finally:
        (output / "windows-recombination.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

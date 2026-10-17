"""Disposable Windows-2022 clean-runtime probe, not distribution gate approval.

Python prepares the immutable request BEFORE removal. Inbox PowerShell owns
inventory, removal, actual installer UI, frozen wizard and recovery thereafter.
"""
from __future__ import annotations

import hashlib
import json
import os
import platform
import re
import subprocess
import time
from pathlib import Path

BASE = Path(__file__).resolve().parent
ACCEPTANCE = "disposable-ci-only-not-user-consent"


def require_disposable(env, *, system):
    expected = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted",
                "RUNNER_LABEL": "windows-2022", "ImageOS": "win22",
                "OAL_CLEAN_RUNTIME_TEST_ACCEPTANCE": ACCEPTANCE}
    if system != "Windows" or any(env.get(k) != v for k, v in expected.items()):
        raise ValueError("Clean-runtime removal requires explicitly authorized disposable Windows-2022 CI")


def runtime_absent(value):
    """An old/unregistered but present DLL is NOT an absent runtime."""
    if not isinstance(value, dict) or set(value) != {"registry", "dlls"}:
        return False
    registry, dlls = value["registry"], value["dlls"]
    return (len(registry) == 2 and len(dlls) == 3
            and {r.get("view") for r in registry} == {"Registry32", "Registry64"}
            and {r.get("name") for r in dlls} == {"vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll"}
            and all(r.get("keyPresent") is False and r.get("installed") == 0 and r.get("version") == "" for r in registry)
            and all(r.get("present") is False and r.get("version") == "" for r in dlls))


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


SCREENSHOTS = frozenset("windows-clean-" + name + ".png" for name in
                        ("decline", "cancel", "license", "installed", "builder-terms", "wizard"))


def validate_report(report, request, output):
    """Validate a live probe's internal contract, NEVER clear the coverage gate.

    Parent acceptance still needs same-run candidate identity and image review.
    Imported JSON alone is not native evidence or a signature.
    """
    if not __debug__:
        raise ValueError("Optimized Python cannot verify clean-runtime evidence")
    try:
        assert report["schema"] == 1 and report["notQualification"] is True
        assert report["identity"] == request["identity"] and report["binding"] == request["binding"]
        assert report["status"] == "passed" and report["phase"] == "complete" and report["failureCode"] == ""
        assert report["scope"] == "real-clean-runtime-ci-ui" and report["testAcceptance"] == ACCEPTANCE
        assert report["recovery"] == "not-needed"
        assert report["uacCancellation"] == report["restart"] == "not-tested"
        desktop = report["desktop"]
        assert desktop["interactive"] is True and desktop["sessionId"] > 0
        assert desktop["inputDesktop"] == "Default" and desktop["win32Error"] == 0
        assert desktop["uac"] == "not-exercised-already-elevated-runner"
        before = report["before"]
        assert len(before["registry"]) == 2 and len(before["dlls"]) == 3
        assert {r["view"] for r in before["registry"]} == {"Registry32", "Registry64"}
        assert {r["name"] for r in before["dlls"]} == {"vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll"}
        assert all(type(r["keyPresent"]) is bool and type(r["installed"]) is int for r in before["registry"])
        assert all(type(r["present"]) is bool and isinstance(r["version"], str) for r in before["dlls"])
        assert runtime_absent(report["absent"])
        scenarios = report["scenarios"]
        assert set(scenarios) == {"officialInstaller", "corruptHash", "decline", "cancel", "accept"}
        lock = json.loads((BASE / "provenance/windows-setup.lock.json").read_text())
        assert scenarios["officialInstaller"] == {"sha256": lock["runtime"]["sha256"],
            "authenticode": "valid-microsoft", "source": "pinned-official-download"}
        assert scenarios["corruptHash"] == {"rejectedBeforeExecution": True, "installerExecuted": False,
            "kind": "production-hash-function-fault-injection-not-installer-failure"}
        for key in ("decline", "cancel"):
            row = scenarios[key]
            assert type(row["outerExit"]) is int and row["outerExit"] != 0
            assert row["builderStarts"] == 0 and runtime_absent(row["runtime"])
        assert scenarios["decline"]["installerStarts"] == 0
        assert scenarios["cancel"]["installerStarts"] > 0
        accepted = scenarios["accept"]
        assert accepted["outerExit"] == 0 and accepted["builderStarts"] > 0
        assert accepted["runtimeReady"] is True and accepted["installerUi"] == "real-interactive"
        assert accepted["wizard"] == "actual-frozen-aab" and accepted["acceptance"] == ACCEPTANCE
        after = report["after"]
        assert len(after["registry"]) == 2 and len(after["dlls"]) == 3
        minimum = tuple(map(int, lock["runtime"]["version"].split(".")))
        assert {r["view"] for r in after["registry"]} == {"Registry32", "Registry64"}
        assert {r["name"] for r in after["dlls"]} == {"vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll"}
        assert any(r["installed"] == 1 and tuple(map(int, r["version"].split("."))) >= minimum for r in after["registry"])
        assert all(r["present"] is True and tuple(map(int, r["version"].split("."))) >= minimum for r in after["dlls"])
        assert set(report["screenshots"]) == SCREENSHOTS
        for name, value in report["screenshots"].items():
            image = output / name
            assert image.stat().st_size <= 8 * 1024 * 1024 and image.read_bytes().startswith(b"\x89PNG\r\n\x1a\n")
            assert digest(image) == value
    except (AssertionError, KeyError, TypeError, ValueError, OSError, AttributeError) as error:
        raise ValueError("Invalid or incomplete real clean-runtime evidence") from error
    return report


def verify_worker_source(source):
    import io
    import zipfile
    try:
        with zipfile.ZipFile(io.BytesIO(source)) as archive:
            for name in ("windows_clean_install.py", "windows_clean_install.ps1"):
                if archive.read("desktop-builder/packaging/" + name) != (BASE / name).read_bytes():
                    raise ValueError("Changed clean-runtime worker source")
    except (KeyError, zipfile.BadZipFile) as error:
        raise ValueError("Missing clean-runtime worker source") from error


def require_time_reserve(env, *, now):
    try:
        remaining = int(env["OAL_CLEAN_RUNTIME_STEP_DEADLINE"]) - now
        if remaining >= 1470:  # controller's 1350s plus 120s Actions/report margin
            return
    except (KeyError, ValueError, TypeError):
        pass
    raise ValueError("Insufficient CI step recovery reserve; no runtime mutation permitted")


def run_supervisor(command, env, output, request):
    """Never force-kill the inbox recovery owner, including on controller expiry.

    PS bounds its scenario to 900s, teardown to 30s, MSI drain to 60s,
    recovery to 240s, and final teardown to 30s (1260s total waits).
    The controller leaves 90s for startup/reporting. An exceptional supervisor
    stall fails closed; its independent recovery can still finish afterward.
    """
    process = subprocess.Popen(command, env=env, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL)
    try:
        return process.wait(timeout=1350)
    except subprocess.TimeoutExpired as error:
        # Separate terminal evidence cannot overwrite/race the native report.
        # The same file is a stop/fail signal read by the supervisor.
        report = {"schema": 1, "status": "failed", "notQualification": True,
                  "identity": request["identity"], "binding": request["binding"],
                  "failureCode": "CONTROLLER_DEADLINE_EXCEEDED",
                  "recovery": "supervisor-independent-unconfirmed"}
        try:
            (output / "windows-clean-controller-timeout.json").write_text(
                json.dumps(report, indent=2) + "\n", encoding="utf-8")
        except OSError:
            pass  # Best effort only; never replace timeout with success.
        raise ValueError("Clean-runtime supervisor deadline exceeded; recovery unconfirmed") from error


def probe(executable, staged, discovery, work, output, *, setup):
    """Run only inside the existing exact-candidate private bootstrap lifetime."""
    require_disposable(os.environ, system=platform.system())
    identity = setup.load_module("coverage_windows_ci").ci_identity()
    if identity is None:
        raise ValueError("Missing clean-runtime CI identity")
    for key in ("artifactSha256", "builderSourceSha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", discovery[key]):
            raise ValueError("Invalid clean-runtime source/candidate binding")
    if digest(staged / "builder.exe") != discovery["artifactSha256"]:
        raise ValueError("Changed clean-runtime candidate")
    setup.verify_snapshot(staged / "builder.exe", discovery["builderSourceSha256"])
    import io
    import zipfile
    from PyInstaller.archive.readers import CArchiveReader
    compliance = CArchiveReader(str(staged / "builder.exe")).extract("builder-compliance.zip")
    with zipfile.ZipFile(io.BytesIO(compliance)) as archive:
        source = archive.read("builder-source.zip")
    if hashlib.sha256(source).hexdigest() != discovery["builderSourceSha256"]:
        raise ValueError("Changed clean-runtime embedded source digest")
    verify_worker_source(source)
    output = output.resolve()
    request = {"schema": 1, "identity": identity,
               "binding": {"artifactSha256": discovery["artifactSha256"],
                           "builderSourceSha256": discovery["builderSourceSha256"],
                           "outerSha256": digest(executable), "bootstrapSha256": digest(staged / "bootstrap.ps1"),
                           "workerSha256": digest(BASE / "windows_clean_install.ps1")},
               "outer": str(executable.resolve()), "builder": str((staged / "builder.exe").resolve()),
               "bootstrap": str((staged / "bootstrap.ps1").resolve()), "output": str(output)}
    # Outside the candidate's temporary lifetime: a stalled controller must not
    # delete the independent supervisor's recovery inputs on exception unwind.
    private = output / ".clean-runtime-private"
    private.mkdir()
    worker = private / "windows_clean_install.ps1"
    worker.write_bytes((BASE / "windows_clean_install.ps1").read_bytes())
    bootstrap = private / "bootstrap.ps1"
    bootstrap.write_bytes((staged / "bootstrap.ps1").read_bytes())
    request["bootstrap"] = str(bootstrap)
    path = private / "request.json"
    path.write_text(json.dumps(request), encoding="utf-8")
    result = output / "windows-clean-install.json"
    receipt = output / "windows-clean-install-verified.json"
    result.unlink(missing_ok=True)
    receipt.unlink(missing_ok=True)
    for name in SCREENSHOTS:
        (output / name).unlink(missing_ok=True)
    powershell = Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"
    # No Python is launched between removal and PS-owned recovery. No raw process
    # output is published. Native failure reports are preserved unchanged.
    (output / "windows-clean-controller-timeout.json").unlink(missing_ok=True)
    require_time_reserve(os.environ, now=time.time())
    returncode = run_supervisor(
        [str(powershell), "-NoLogo", "-NoProfile", "-STA", "-ExecutionPolicy", "Bypass",
         "-File", str(worker), "-Request", str(path), "-Mode", "Supervisor"],
        setup.child_environment(QT_QPA_PLATFORM="windows"), output, request)
    if not result.is_file() or result.stat().st_size > 65536:
        raise ValueError("Clean-runtime native worker did not produce bounded evidence")
    report = json.loads(result.read_text(encoding="utf-8"))
    if returncode != 0:
        raise ValueError("Clean-runtime worker failed; inspect bounded native report")
    if digest(executable) != request["binding"]["outerSha256"] or digest(staged / "builder.exe") != discovery["artifactSha256"]:
        raise ValueError("Clean-runtime candidate changed during probe")
    validate_report(report, request, output)
    receipt.write_text(json.dumps({"schema": 1, "status": "evidence-verified-not-gate-approval",
                                  "reportSha256": digest(result), "identity": identity,
                                  "binding": request["binding"]}, indent=2) + "\n", encoding="utf-8")
    return report

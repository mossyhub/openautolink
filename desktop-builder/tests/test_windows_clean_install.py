"""Harness contract tests, NOT clean-Windows installation evidence."""
import importlib.util
from pathlib import Path

import pytest

PACKAGING = Path(__file__).resolve().parents[1] / "packaging"


def load():
    path = PACKAGING / "windows_clean_install.py"
    assert path.is_file(), "Real clean-runtime worker controller is missing"
    spec = importlib.util.spec_from_file_location("windows_clean_install_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_controller_refuses_local_and_self_hosted_before_any_execution():
    module = load()
    for env in ({}, {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "self-hosted"}):
        with pytest.raises(ValueError, match="disposable"):
            module.require_disposable(env, system="Windows")
    env = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted",
           "RUNNER_LABEL": "windows-2022", "ImageOS": "win22",
           "OAL_CLEAN_RUNTIME_TEST_ACCEPTANCE": "disposable-ci-only-not-user-consent"}
    module.require_disposable(env, system="Windows")
    with pytest.raises(ValueError, match="disposable"):
        module.require_disposable(env, system="Linux")
    for key in env:
        with pytest.raises(ValueError, match="disposable"):
            module.require_disposable({k: v for k, v in env.items() if k != key}, system="Windows")


def inventory(*, present=False):
    return {"registry": [{"view": view, "keyPresent": present, "installed": int(present),
                           "version": "14.44.35211.0" if present else ""}
                          for view in ("Registry32", "Registry64")],
            "dlls": [{"name": name, "present": present,
                      "version": "14.44.35211.0" if present else ""}
                     for name in ("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll")]}


def test_absence_requires_both_registry_views_and_all_actual_dlls():
    module = load()
    assert module.runtime_absent(inventory())
    assert not module.runtime_absent(inventory(present=True))
    for group in ("registry", "dlls"):
        value = inventory()
        value[group].pop()
        assert not module.runtime_absent(value)
    value = inventory()
    value["registry"][0]["keyPresent"] = True  # stale registration is NOT pristine
    assert not module.runtime_absent(value)
    value = inventory()
    value["dlls"][0].update(present=True, version="14.0.0.0")
    assert not module.runtime_absent(value)  # too-old != absent


def test_native_worker_has_independent_guard_and_real_ui_not_fake_runtime():
    script = PACKAGING / "windows_clean_install.ps1"
    assert script.is_file(), "Inbox PowerShell native worker is missing"
    text = script.read_text()
    for required in ("Assert-DisposableRunner", "OpenInputDesktop", "UIAutomationClient",
                     "Registry32", "Registry64", "Assert-MicrosoftSignature",
                     "Win32_ProcessStartTrace", "Test-GenuineAbsence", "Get-RuntimeInventory",
                     "disposable-ci-only-not-user-consent", "System32", "Get-FileHash"):
        assert required in text
    assert text.index("Assert-DisposableRunner -Recovery:") < text.index("Remove-TestRuntime\n")
    assert "Set-ItemProperty" not in text and "Remove-ItemProperty" not in text
    assert "Set-ExecutionPolicy" not in text
    assert "function Test-RuntimeReady" not in text  # production function must not be mocked


def test_pass_label_cannot_replace_real_scenario_inventory_and_identity():
    module = load()
    expected = {"identity": {"checkoutCommit": "a" * 40}, "binding": {"outerSha256": "b" * 64}}
    # This incomplete object must never be accepted, even with a success label.
    for report in ({"status": "passed"}, {**expected, "schema": 1, "status": "passed", "notQualification": True}):
        with pytest.raises(ValueError, match="clean-runtime"):
            module.validate_report(report, expected, PACKAGING)


def test_probe_is_opt_in_and_keeps_existing_production_assent_path():
    setup = (PACKAGING / "windows_setup.py").read_text()
    assert 'os.environ.get("OAL_CLEAN_RUNTIME_TEST_ACCEPTANCE")' in setup
    assert 'load_module("windows_clean_install").probe(' in setup
    production = (PACKAGING / "windows-setup/bootstrap.ps1").read_text()
    assert "/quiet" not in production and "/passive" not in production
    assert "OAL_CLEAN_RUNTIME_TEST_ACCEPTANCE" not in production


def test_source_snapshot_binds_worker_and_dedicated_workflow(tmp_path):
    import zipfile
    spec = importlib.util.spec_from_file_location("clean_build_test", PACKAGING / "build.py")
    build = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(build)
    target = tmp_path / "source.zip"
    build.source_archive(target)
    with zipfile.ZipFile(target) as archive:
        for name in ("desktop-builder/packaging/windows_clean_install.ps1",
                     ".github/workflows/qualify-windows-clean-runtime.yml"):
            assert name in archive.namelist()
            assert archive.read(name) == (PACKAGING.parents[1] / name).read_bytes()


def synthetic_contract(module, tmp_path):
    """Parser fixture only: not an installation report, never exported by CI."""
    import json
    request = {"identity": {"checkoutCommit": "a" * 40}, "binding": {"outerSha256": "b" * 64}}
    screenshots = {}
    for name in module.SCREENSHOTS:
        path = tmp_path / name
        path.write_bytes(b"\x89PNG\r\n\x1a\nTEST-FIXTURE-NOT-A-SCREENSHOT")
        screenshots[name] = module.digest(path)
    lock = json.loads((PACKAGING / "provenance/windows-setup.lock.json").read_text())
    report = {**request, "schema": 1, "notQualification": True, "status": "passed", "phase": "complete",
              "failureCode": "", "scope": "real-clean-runtime-ci-ui", "testAcceptance": module.ACCEPTANCE,
              "recovery": "not-needed", "uacCancellation": "not-tested", "restart": "not-tested",
              "desktop": {"interactive": True, "sessionId": 1, "inputDesktop": "Default", "win32Error": 0,
                          "uac": "not-exercised-already-elevated-runner"},
              "before": inventory(present=True), "absent": inventory(), "after": inventory(present=True),
              "screenshots": screenshots,
              "scenarios": {"officialInstaller": {"sha256": lock["runtime"]["sha256"], "authenticode": "valid-microsoft", "source": "pinned-official-download"},
                            "corruptHash": {"rejectedBeforeExecution": True, "installerExecuted": False, "kind": "production-hash-function-fault-injection-not-installer-failure"},
                            "decline": {"outerExit": 1, "builderStarts": 0, "runtime": inventory(), "installerStarts": 0},
                            "cancel": {"outerExit": 1, "builderStarts": 0, "runtime": inventory(), "installerStarts": 1},
                            "accept": {"outerExit": 0, "builderStarts": 2, "runtimeReady": True, "installerUi": "real-interactive", "wizard": "actual-frozen-aab", "acceptance": module.ACCEPTANCE}}}
    return request, report


def test_contract_validator_checks_all_scenarios_and_screenshots(tmp_path):
    import copy
    module = load()
    request, report = synthetic_contract(module, tmp_path)
    assert module.validate_report(report, request, tmp_path) is report
    mutations = [
        (("status",), "blocked"), (("phase",), "installer-cancel"),
        (("identity",), {}), (("binding",), {}), (("testAcceptance",), "user-consent"),
        (("before",), {}), (("absent",), inventory(present=True)), (("after",), inventory()),
        (("recovery",), "restored-not-qualification"), (("uacCancellation",), "passed"),
        (("desktop", "sessionId"), 0), (("desktop", "inputDesktop"), "not-default"),
        (("scenarios", "decline", "builderStarts"), 1),
        (("scenarios", "decline", "outerExit"), 0),
        (("scenarios", "decline", "installerStarts"), 1),
        (("scenarios", "cancel", "runtime"), inventory(present=True)),
        (("scenarios", "cancel", "installerStarts"), 0),
        (("scenarios", "accept", "runtimeReady"), False),
        (("scenarios", "accept", "wizard"), "terms-preview"),
        (("scenarios", "accept", "builderStarts"), 0),
        (("scenarios", "officialInstaller", "sha256"), "0" * 64),
        (("scenarios", "corruptHash", "installerExecuted"), True),
        (("screenshots",), {}),
    ]
    for keys, value in mutations:
        changed = copy.deepcopy(report)
        target = changed
        for key in keys[:-1]:
            target = target[key]
        target[keys[-1]] = value
        with pytest.raises(ValueError, match="clean-runtime"):
            module.validate_report(changed, request, tmp_path)
    (tmp_path / "windows-clean-wizard.png").write_bytes(b"tampered")
    with pytest.raises(ValueError, match="clean-runtime"):
        module.validate_report(report, request, tmp_path)


def test_worker_bytes_must_match_embedded_source(tmp_path):
    import io
    import zipfile
    module = load()
    source = io.BytesIO()
    with zipfile.ZipFile(source, "w") as archive:
        for name in ("windows_clean_install.py", "windows_clean_install.ps1"):
            archive.writestr("desktop-builder/packaging/" + name, (PACKAGING / name).read_bytes())
    module.verify_worker_source(source.getvalue())
    altered = io.BytesIO()
    with zipfile.ZipFile(altered, "w") as archive:
        archive.writestr("desktop-builder/packaging/windows_clean_install.py", b"stale")
    with pytest.raises(ValueError, match="worker source"):
        module.verify_worker_source(altered.getvalue())


def test_controller_timeout_does_not_kill_recovery_supervisor(tmp_path, monkeypatch):
    """Mock OS boundary only; exercise the real controller timeout path."""
    import json
    import subprocess
    module = load()
    calls = []

    class Process:
        def wait(self, timeout):
            calls.append(timeout)
            raise subprocess.TimeoutExpired("inbox-supervisor", timeout)

        def kill(self):
            pytest.fail("Controller must not kill the inbox recovery owner")

        def terminate(self):
            pytest.fail("Controller must not terminate the inbox recovery owner")

    monkeypatch.setattr(module.subprocess, "Popen", lambda *a, **kw: Process())
    with pytest.raises(ValueError, match="supervisor deadline"):
        module.run_supervisor(["inbox-supervisor"], {}, tmp_path, {"identity": {}, "binding": {}})
    assert calls == [1350]
    report = json.loads((tmp_path / "windows-clean-controller-timeout.json").read_text())
    assert report["status"] == "failed"
    assert report["failureCode"] == "CONTROLLER_DEADLINE_EXCEEDED"
    assert report["recovery"] == "supervisor-independent-unconfirmed"
    assert report["notQualification"] is True
    assert not (tmp_path / "windows-clean-install-verified.json").exists()


def run_mocked_powershell(code):
    """Portable PowerShell replay with fake OS boundaries, NOT native evidence."""
    import shutil
    import subprocess
    shell = shutil.which("powershell.exe") or shutil.which("pwsh")
    if shell is None:
        pytest.skip("PowerShell required for mocked lifecycle replay")
    worker = str(PACKAGING / "windows_clean_install.ps1").replace("'", "''")
    result = subprocess.run([shell, "-NoLogo", "-NoProfile", "-Command",
                             f". '{worker}'\n" + code + "\nexit 0"], capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stdout + result.stderr


@pytest.mark.parametrize('exit_code', [0, 3010, 1603])
def test_actual_removal_launch_chain_keeps_silent_arguments_and_rejects_nonzero(tmp_path, exit_code):
    request = str(tmp_path / 'request.json').replace("'", "''")
    run_mocked_powershell(r'''
$Request = 'REQUESTPATH'
$script:clock = [Diagnostics.Stopwatch]::StartNew()
$script:budgetSeconds = 840
$script:operation = $null
$script:report = [ordered]@{removal=@()}
Add-Type 'public class CleanOwnedJob {
 public static string[] Captured;
 public int ExitCode { get { return EXITCODE; } }
 public bool Empty { get { return true; } }
 public CleanOwnedJob(string file, string[] arguments) { Captured=arguments; }
 public bool WaitForExit(int ms) { return true; }
 public void Terminate() { }
 public void Dispose() { }
}'
function Get-RemovalTargets { [pscustomobject]@{kind='bundle';path='private installer.exe'} }
$observed = ''
try { Remove-TestRuntime } catch { $observed = $_.Exception.Message }
if ((EXITCODE -ne 0) -ne ($observed -eq 'UNINSTALL_FAILED_OR_RESTART_REQUIRED')) { throw 'REMOVAL_RESULT_WEAKENED' }
if (EXITCODE -eq 0 -and $observed) { throw $observed }
if ($script:report.removal.Count -ne 1 -or $script:report.removal[0].exitCode -ne EXITCODE) { throw 'REMOVAL_ROW_LOST' }
if (([CleanOwnedJob]::Captured[0..3] -join '|') -cne '/uninstall|/quiet|/norestart|/log' -or
    [CleanOwnedJob]::Captured.Count -ne 5) { throw 'ACTUAL_CONSTRUCTOR_ARGUMENTS_LOST' }
'''.replace('REQUESTPATH', request).replace('EXITCODE', str(exit_code)))


def test_recovery_diagnostic_is_bound_and_exported_without_replacing_primary(tmp_path):
    request = str(tmp_path / 'request.json').replace("'", "''")
    run_mocked_powershell(r'''
$Request = 'REQUESTPATH'
$script:req = [pscustomobject]@{identity=@{checkoutCommit='exact'};binding=@{workerSha256='exact'}}
$script:report = [ordered]@{status='failed';phase='supervisor';failureCode='';recovery='not-needed'}
function Test-MutationStarted { return $true }
function Test-ControllerTimeout { return $false }
function Stop-JobTree { return $true }
function Wait-MsiQuiescent { return $true }
function Start-WorkerJob($Mode) {
    $job = [pscustomobject]@{Mode=$Mode;ExitCode=1}
    $job | Add-Member ScriptMethod WaitForExit {
        param($ms)
        if ($this.Mode -eq 'Scenario') { return $false }
        $child = @{identity=$script:req.identity;binding=$script:req.binding;failureCode='RECOVERY_FAILED';
                   recoveryOperation=@{kind='recovery';phase='complete';exitCode=-2147023258}}
        [IO.File]::WriteAllText((Join-Path (Split-Path -Parent $Request) 'recovery-report.json'), ($child | ConvertTo-Json -Depth 8))
        return $true
    }
    return $job
}
Invoke-Supervisor
if ($script:report.failureCode -ne 'SCENARIO_DEADLINE_EXCEEDED' -or $script:report.recovery -ne 'failed-dispose-vm') { throw 'PRIMARY_FAILURE_LOST' }
if ($script:report.recoveryOperation.exitCode -ne -2147023258 -or $script:report.recoveryFailureCode -ne 'RECOVERY_FAILED') { throw 'RECOVERY_DIAGNOSTIC_LOST' }
$script:req.binding.workerSha256 = 'different'
try { Read-ScenarioReport -Mode Recovery; throw 'UNBOUND_DIAGNOSTIC_ACCEPTED' } catch {
    if ($_.Exception.Message -ne 'CHILD_REPORT_BINDING_INVALID') { throw }
}
'''.replace('REQUESTPATH', request))
    source = (PACKAGING / 'windows_clean_install.ps1').read_text()
    assert "Invoke-LoggedRuntimeOperation 'recovery'" in source
    assert "Write-BoundedReport (Join-Path (Split-Path -Parent $Request) 'recovery-report.json')" in source


@pytest.mark.parametrize('kind', ['bundle', 'msi', 'recovery'])
@pytest.mark.parametrize('timeout', [True, False])
def test_logged_runtime_operation_records_attempt_before_launch_and_keeps_failure(tmp_path, kind, timeout):
    request = str(tmp_path / 'request.json').replace("'", "''")
    run_mocked_powershell(r'''
$Request = 'REQUESTPATH'
$script:report = [ordered]@{removal=@()}
$script:operation = $null
$script:clock = [Diagnostics.Stopwatch]::StartNew()
$script:budgetSeconds = 840
function Start-OwnedOperation([string]$File, [string[]]$Arguments) {
    $d = if ('KIND' -eq 'recovery') { $script:report.recoveryOperation } else { $script:report.removal[0] }
    if ($d.kind -ne 'KIND' -or $d.phase -ne 'launch' -or $null -ne $d.exitCode) { throw 'ATTEMPT_NOT_RECORDED_BEFORE_LAUNCH' }
    $flag = if ('KIND' -eq 'msi') { '/L*v!' } else { '/log' }
    if ($Arguments.Count -ne 5 -or $Arguments[3] -cne $flag -or
        (Split-Path -Parent $Arguments[4]) -cne (Split-Path -Parent $Request)) { throw 'LOG_NOT_PRIVATE_OR_ARGUMENTS_LOST' }
    [IO.File]::WriteAllText($Arguments[4], '[1234:5678][time]e000: Error 0x80070020: private machine path')
    $script:operation = [pscustomobject]@{Empty=$true;ExitCode=3010}
    $script:operation | Add-Member ScriptMethod WaitForExit { param($ms) return -not TIMEOUT }
    $script:operation | Add-Member ScriptMethod Terminate { }
    $script:operation | Add-Member ScriptMethod Dispose { }
    return $script:operation
}
$observed = ''
try { $code = Invoke-LoggedRuntimeOperation 'KIND' 'private-installer' @('/uninstall','/quiet','/norestart') }
catch { $observed = $_.Exception.Message }
$d = if ('KIND' -eq 'recovery') { $script:report.recoveryOperation } else { $script:report.removal[0] }
if (TIMEOUT) {
    if ($observed -ne 'PROCESS_TIMEOUT' -or $d.failureCode -ne 'PROCESS_TIMEOUT' -or
        $d.phase -ne 'root-wait' -or $null -ne $d.exitCode) { throw 'TIMEOUT_DETAIL_LOST' }
} else {
    if ($observed -or $code -ne 3010 -or $d.exitCode -ne 3010 -or $d.phase -ne 'complete') { throw 'EXIT_DETAIL_LOST' }
}
if ($d.installerLog.errorCodes[0] -ne '0x80070020' -or $null -ne $script:operation) { throw 'LOG_OR_TEARDOWN_LOST' }
if (($script:report | ConvertTo-Json -Depth 8) -match 'private|machine|1234|5678') { throw 'PRIVATE_DATA_LEAK' }
'''.replace('REQUESTPATH', request).replace('KIND', kind)
       .replace('return -not TIMEOUT', 'return -not ' + ('$true' if timeout else '$false'))
       .replace('if (TIMEOUT)', 'if (' + ('$true' if timeout else '$false') + ')'))


@pytest.mark.parametrize('encoding', ['utf-8-sig', 'utf-16'])
def test_installer_log_summary_is_bounded_numeric_and_path_free(tmp_path, encoding):
    # Explicit parser fixture, not a native installer report.
    log = tmp_path / 'installer.log'
    log.write_text('''[1234:5678][2026-10-17T01:00:00]i009: Command Line: C:\\private\\machine /quiet
[1234:5678][2026-10-17T01:00:00]i100: Detect begin, 2 packages
[1234:5678][2026-10-17T01:00:00]i200: Plan begin, action: Uninstall
[1234:5678][2026-10-17T01:00:00]i010: Launching elevated engine process.
[1234:5678][2026-10-17T01:00:00]e000: Error 0x80070020: C:\\private\\machine in use
[1234:5678][2026-10-17T01:00:00]e000: Error 0x80070020: repeated
[1234:5678][2026-10-17T01:00:00]i399: Apply complete, result: 0x80070643, restart: Required, ba requested restart: No
MSI (s) (10:00) [01:00:00:000]: MainEngineThread is returning 1603
Error 1714. Private product name must not escape.
''', encoding=encoding)
    path = str(log).replace("'", "''")
    run_mocked_powershell(r'''
$s = Read-InstallerLogSummary 'LOGPATH'
if ($s.state -ne 'read' -or $s.truncated) { throw 'LOG_NOT_READ' }
if (($s.events -join ',') -cne 'i100,i200,i010,i399') { throw 'LIFECYCLE_LOST' }
if (($s.errorCodes -join ',') -cne '0x80070020,1714') { throw 'ERROR_CODES_LOST' }
if (($s.resultCodes -join ',') -cne '0x80070643,1603' -or $s.restart -ne 'Required') { throw 'RESULT_CODES_LOST' }
if (($s | ConvertTo-Json -Depth 8) -match 'private|machine|product|1234|5678|2026') { throw 'RAW_LOG_LEAK' }
$s = Read-InstallerLogSummary 'LOGPATH.missing'
if ($s.state -ne 'missing') { throw 'MISSING_LOG_FALSE_READ' }
'''.replace('LOGPATH', path))
    log.write_text('Error 1714. bounded\n' * 100000, encoding=encoding)
    run_mocked_powershell(r'''
$s = Read-InstallerLogSummary 'LOGPATH'
if (-not $s.truncated -or $s.bytesRead -gt 1048576 -or $s.errorCodes.Count -ne 1) { throw 'UNBOUNDED_LOG' }
if (($s | ConvertTo-Json -Depth 8).Length -gt 2048) { throw 'UNBOUNDED_SUMMARY' }
'''.replace('LOGPATH', path))
    log.write_text(''.join(f'Error {1000 + i}. result: 0x{i:08x}\n' for i in range(40)), encoding=encoding)
    run_mocked_powershell(r'''
$s = Read-InstallerLogSummary 'LOGPATH'
if (-not $s.truncated -or $s.errorCodes.Count -ne 16 -or $s.resultCodes.Count -ne 16) { throw 'UNBOUNDED_CODE_SETS' }
'''.replace('LOGPATH', path))


@pytest.mark.parametrize('outcome,phase,code,failure', [
    ('root-timeout', 'root-wait', None, 'PROCESS_TIMEOUT'),
    ('tree-timeout', 'tree-wait', 0, 'PROCESS_TREE_TIMEOUT'),
    ('restart', 'complete', 3010, ''),
    ('failure', 'complete', -2147023293, ''),
])
def test_wait_diagnostics_preserve_root_exit_before_owned_teardown(outcome, phase, code, failure):
    run_mocked_powershell(r'''
$script:operation = $null
$d = [ordered]@{phase='launch';exitCode=$null;rootExited=$null;jobEmpty=$null;failureCode='';teardownFailure=''}
function Get-RemainingMilliseconds { return 1 }
$p = [pscustomobject]@{Empty=('OUTCOME' -ne 'tree-timeout');ExitCode=CODE}
$p | Add-Member ScriptMethod WaitForExit { param($ms) return 'OUTCOME' -ne 'root-timeout' }
function Stop-OwnedOperation {
    # Disposal/termination may change the exit code; it must already be captured.
    $p.ExitCode = 1
}
$observed = ''
try { $null = Wait-Exit $p -Diagnostic $d } catch { $observed = $_.Exception.Message }
if ($observed -cne 'FAILURE' -or $d.failureCode -cne 'FAILURE') { throw 'WRONG_FAILURE' }
if ($d.phase -cne 'PHASE' -or $d.exitCode -ne EXPECTED) { throw 'DIAGNOSTIC_EXIT_OR_PHASE_LOST' }
if ($d.rootExited -ne ('OUTCOME' -ne 'root-timeout')) { throw 'ROOT_STATE_LOST' }
'''.replace('OUTCOME', outcome).replace('PHASE', phase).replace('FAILURE', failure)
       .replace('CODE', str(code or 0)).replace('EXPECTED', '$null' if code is None else str(code)))


def test_owned_operation_preserves_array_arguments_at_constructor_boundary():
    run_mocked_powershell(r'''
# Replace only the Win32 owner; run the actual PowerShell launch function.
Add-Type 'public class CleanOwnedJob {
 public string File; public string[] Arguments;
 public CleanOwnedJob(string file, string[] arguments) { File=file; Arguments=arguments; }
}'
$script:clock = [Diagnostics.Stopwatch]::StartNew()
$script:budgetSeconds = 840
$script:operation = $null
$file = 'C:\private cache\VC_redist.x64.exe'
$p = Start-OwnedOperation $file @('/uninstall', '/quiet', '/norestart')
if ($p.File -cne $file -or ($p.Arguments -join '|') -cne '/uninstall|/quiet|/norestart') { throw 'ARGUMENT_BINDING_LOST' }
$script:operation = $null
$p = Start-OwnedOperation $file @('/x', '{01234567-89AB-CDEF-0123-456789ABCDEF}', '/qn', '/norestart', '/log', 'C:\private cache\test.log')
if ($p.Arguments.Count -ne 6 -or $p.Arguments[5] -cne 'C:\private cache\test.log') { throw 'LOG_ARGUMENT_BINDING_LOST' }
$script:operation = $null
$p = Start-OwnedOperation $file
if ($p.Arguments.Count -ne 0) { throw 'EMPTY_ARGUMENTS_LOST' }
''')


@pytest.mark.parametrize('name', ['unrelated.exe', 'VC_redist.x64.exe', 'builder.exe'])
def test_pre_scenario_monitor_cleanup_has_initialized_receipts(name):
    run_mocked_powershell(r'''
$script:operation = $null
$script:report = @{status='failed';phase='runtime-removal';failureCode='PROCESS_TIMEOUT'}
function Unregister-Event { param($SourceIdentifier) }
function Get-Event {
    [pscustomobject]@{EventIdentifier=1;SourceEventArgs=[pscustomobject]@{
        NewEvent=[pscustomobject]@{ProcessID=12;ProcessName='NAME';TIME_CREATED=1L}}}
}
function Remove-Event { param($EventIdentifier) }
Finalize-ProcessMonitor
if ($script:report.ContainsKey('cleanupFailure')) { throw 'PRE_SCENARIO_CLEANUP_FAILED' }
if ($script:starts.Count -ne 1) { throw 'RECEIPT_NOT_SAVED' }
if ($script:report.failureCode -ne 'PROCESS_TIMEOUT' -or $script:report.phase -ne 'runtime-removal') { throw 'PRIMARY_FAILURE_LOST' }
'''.replace('NAME', name))


def test_mutation_timeout_tears_down_owned_job_before_throwing():
    run_mocked_powershell(r"""
$script:clock = [Diagnostics.Stopwatch]::StartNew()
$script:budgetSeconds = 840
$script:events = [Collections.Generic.List[string]]::new()
$p = [pscustomobject]@{}
$p | Add-Member ScriptMethod WaitForExit { param($ms) $script:events.Add('wait'); return $false }
function Stop-OwnedOperation { $script:events.Add('stop-owned') }
try { Wait-Exit $p 1; throw 'FALSE_SUCCESS' } catch {
    if ($_.Exception.Message -ne 'PROCESS_TIMEOUT') { throw }
}
if (($script:events -join ',') -ne 'wait,stop-owned') { throw 'TEARDOWN_ORDER' }
$script:budgetSeconds = 0
try { Get-RemainingMilliseconds 120; throw 'UNBOUNDED' } catch {
    if ($_.Exception.Message -ne 'SCENARIO_DEADLINE_EXCEEDED') { throw }
}
""")


def test_unrelated_process_events_do_not_count_as_owned_evidence():
    run_mocked_powershell(r"""
$script:starts = @(
    [pscustomobject]@{id=10;parent=1;name='builder.exe';owned=$false},
    [pscustomobject]@{id=11;parent=1;name='VC_redist.x64.exe';owned=$false},
    [pscustomobject]@{id=12;parent=2;name='builder.exe';owned=$true})
if ((Get-OwnedStartCount 'builder.exe') -ne 1) { throw 'UNRELATED_BUILDER_COUNTED' }
if ((Get-OwnedStartCount 'VC_redist.x64.exe') -ne 0) { throw 'UNRELATED_INSTALLER_COUNTED' }
""")


@pytest.mark.parametrize("stopped,idle,recovery_exits,expected", [
    (True, True, True, "restored-not-qualification"),
    (False, True, True, "skipped-owned-teardown-unconfirmed"),
    (True, False, True, "skipped-msi-not-quiescent"),
    (True, True, False, "failed-dispose-vm"),
])
def test_supervisor_reserves_recovery_and_never_overlaps_mutations(stopped, idle, recovery_exits, expected):
    boolean = lambda x: "$true" if x else "$false"
    run_mocked_powershell(r"""
$script:events = [Collections.Generic.List[string]]::new()
$script:report = [ordered]@{status='failed';phase='supervisor';failureCode='';recovery='not-needed'}
$script:req = [pscustomobject]@{output='unused'}
function Test-MutationStarted { return $true }
function Test-ControllerTimeout { return $false }
function Start-WorkerJob($Mode) {
    $script:events.Add("start-$Mode")
    $job = [pscustomobject]@{Mode=$Mode;ExitCode=0}
    $job | Add-Member ScriptMethod WaitForExit {
        param($ms)
        $script:events.Add("wait-$($this.Mode)-$ms")
        if ($this.Mode -eq 'Scenario') { return $false }
        return RECOVERY_EXITS
    }
    return $job
}
function Stop-JobTree($job) {
    if ($null -eq $job) { return $true }
    $script:events.Add("stop-$($job.Mode)")
    if ($job.Mode -eq 'Scenario') { return STOPPED }
    return $true
}
function Wait-MsiQuiescent {
    $script:events.Add('msi-idle-check')
    return IDLE
}
Invoke-Supervisor
if ($script:report.status -ne 'failed' -or $script:report.failureCode -ne 'SCENARIO_DEADLINE_EXCEEDED') { throw 'TIMEOUT_FALSE_SUCCESS' }
if ($script:report.recovery -ne 'EXPECTED') { throw 'RECOVERY_STATUS' }
$trace = $script:events -join ','
if (-not $trace.StartsWith('start-Scenario,wait-Scenario-900000,stop-Scenario')) { throw 'SCENARIO_BOUND_OR_TEARDOWN_ORDER' }
if ('EXPECTED' -like 'skipped-*' -and $trace.Contains('start-Recovery')) { throw 'RECOVERY_RACE' }
if ('EXPECTED' -notlike 'skipped-*' -and -not $trace.EndsWith('msi-idle-check,start-Recovery,wait-Recovery-240000,stop-Recovery')) { throw 'RECOVERY_BOUND_OR_TEARDOWN_ORDER' }
""".replace("STOPPED", boolean(stopped)).replace("IDLE", boolean(idle))
       .replace("RECOVERY_EXITS", boolean(recovery_exits)).replace("EXPECTED", expected))


def test_job_owner_compiles_without_invoking_windows_apis():
    run_mocked_powershell(r'''
Initialize-ProcessOwner
if ([CleanOwnedJob]::Quote('C:\space dir\') -cne '"C:\space dir\\"') { throw 'QUOTE_FAILED' }
''')


def test_root_exit_does_not_leave_live_descendants_before_next_mutation():
    run_mocked_powershell(r"""
$script:stopped = $false
function Get-RemainingMilliseconds { return 1 }
function Stop-OwnedOperation { $script:stopped = $true }
$p = [pscustomobject]@{Empty=$false;ExitCode=0}
$p | Add-Member ScriptMethod WaitForExit { param($ms) return $true }
try { Wait-Exit $p; throw 'ROOT_EXIT_FALSE_SUCCESS' } catch {
    if ($_.Exception.Message -ne 'PROCESS_TREE_TIMEOUT') { throw }
}
if (-not $script:stopped) { throw 'DESCENDANT_NOT_TORN_DOWN' }
""")


def test_job_teardown_uses_owned_handle_even_after_root_exit():
    run_mocked_powershell(r"""
$script:events = [Collections.Generic.List[string]]::new()
$job = [pscustomobject]@{Empty=$true}
$job | Add-Member ScriptMethod Terminate { $script:events.Add('terminate-owned-job') }
$job | Add-Member ScriptMethod Dispose { $script:events.Add('dispose') }
if (-not (Stop-JobTree $job)) { throw 'TEARDOWN_FAILED' }
if (($script:events -join ',') -ne 'terminate-owned-job,dispose') { throw 'OWNERSHIP_LOST' }
$job | Add-Member ScriptMethod Terminate { throw 'DENIED' } -Force
if (Stop-JobTree $job) { throw 'UNCONFIRMED_TEARDOWN_ACCEPTED' }
""")


def test_machine_wide_events_are_filtered_at_collection():
    run_mocked_powershell(r"""
$script:starts = [Collections.Generic.List[object]]::new()
$script:operation = [pscustomobject]@{}
$script:operation | Add-Member ScriptMethod GetLifetime {
    param($id) return [pscustomobject]@{Created=1L;Exited=0L;Observed=3L;Owned=($id -eq 12)}
}
function Get-Event {
    foreach ($id in @(10, 11, 12)) {
        [pscustomobject]@{EventIdentifier=$id;SourceEventArgs=[pscustomobject]@{NewEvent=[pscustomobject]@{ProcessID=$id;ProcessName='builder.exe';TIME_CREATED=2L}}}
    }
}
function Remove-Event { param($EventIdentifier) }
Drain-ProcessEvents
if ((Get-OwnedStartCount 'builder.exe') -ne 1) { throw 'MACHINE_WIDE_EVENTS_COUNTED' }
""")


# These fixtures replace OS/UI boundaries only. The real drain, wait, teardown,
# rejected-scenario completion and count functions run in portable PowerShell.
EVENT_REPLAY = r"""
$script:clock = [Diagnostics.Stopwatch]::StartNew()
$script:budgetSeconds = 840
$script:scenarioActive = $true
$script:scenarioStarted = [DateTime]::UtcNow.AddSeconds(-10).ToFileTimeUtc()
$script:starts = [Collections.Generic.List[object]]::new()
$script:queue = [Collections.Generic.List[object]]::new()
$script:disposed = $false
$script:created = [DateTime]::UtcNow.AddSeconds(-2).ToFileTimeUtc()
$script:eventTime = [DateTime]::UtcNow.AddSeconds(-1).ToFileTimeUtc()
function Queue-Start([string]$Name = 'builder.exe') {
    $script:queue.Add([pscustomobject]@{EventIdentifier=1;SourceEventArgs=[pscustomobject]@{
        NewEvent=[pscustomobject]@{ProcessID=12;ProcessName=$Name;TIME_CREATED=$script:eventTime}}})
}
function Get-Event { $script:queue.ToArray() }
function Remove-Event { param($EventIdentifier) $script:queue.Clear() }
function Wait-Control { [pscustomobject]@{control='mock-ui'} }
function Invoke-Control { }
function Get-RuntimeInventory {
    @{registry=@(@{keyPresent=$false;installed=0;version=''}, @{keyPresent=$false;installed=0;version=''});
      dlls=@(@{present=$false;version=''}, @{present=$false;version=''}, @{present=$false;version=''})}
}
$script:operation = [pscustomobject]@{Empty=$true;ExitCode=1}
$script:operation | Add-Member ScriptMethod Contains { param($id) return -not $script:disposed }
$script:operation | Add-Member ScriptMethod GetLifetime {
    param($id)
    if ($script:disposed) { throw 'QUERY_AFTER_DISPOSE' }
    return [pscustomobject]@{Created=$script:created;Exited=0;Observed=[DateTime]::UtcNow.ToFileTimeUtc();Owned=$true}
}
$script:operation | Add-Member ScriptMethod Terminate { }
$script:operation | Add-Member ScriptMethod Dispose { $script:disposed = $true }
$script:outerProcess = $script:operation
"""


def test_final_cleanup_uses_validating_drain_before_report_write():
    source = (PACKAGING / 'windows_clean_install.ps1').read_text()
    cleanup = source.rsplit('} finally {', 1)[1]
    assert 'Finalize-ProcessMonitor' in cleanup
    assert 'Remove-Event' not in cleanup
    assert cleanup.index('Finalize-ProcessMonitor') < cleanup.index('Write-BoundedReport')


@pytest.mark.parametrize('delivery', ['before-finalize', 'during-unregister'])
def test_final_monitor_cleanup_rejects_late_relevant_receipts(delivery):
    run_mocked_powershell(EVENT_REPLAY + r"""
Queue-Start
Drain-ProcessEvents
$script:operation = $null
$script:report = @{status='passed';phase='complete';failureCode=''}
function Unregister-Event {
    param($SourceIdentifier)
    if ('DELIVERY' -eq 'during-unregister') { Queue-Start 'VC_redist.x64.exe' }
}
if ('DELIVERY' -eq 'before-finalize') { Queue-Start 'VC_redist.x64.exe' }
Finalize-ProcessMonitor
if ($script:report.status -ne 'failed' -or $script:report.failureCode -ne 'PROCESS_START_EVIDENCE_UNRESOLVED') { throw 'FINAL_CLEANUP_FALSE_PASS' }
if ($script:starts.Count -ne 2 -or $script:queue.Count -ne 0) { throw 'FINAL_RECEIPT_DISCARDED' }
""".replace('DELIVERY', delivery))


def test_final_monitor_cleanup_preserves_original_failure():
    run_mocked_powershell(EVENT_REPLAY + r"""
$script:report = @{status='failed';phase='runtime-removal';failureCode='UNINSTALL_FAILED'}
function Unregister-Event { param($SourceIdentifier) throw 'cleanup text with private path' }
Finalize-ProcessMonitor
if ($script:report.phase -ne 'runtime-removal' -or $script:report.failureCode -ne 'UNINSTALL_FAILED') { throw 'PRIMARY_FAILURE_LOST' }
if ($script:report.cleanupFailure.code -ne 'PROCESS_MONITOR_FINALIZATION_FAILED') { throw 'CLEANUP_NOT_RECORDED' }
if (($script:report | ConvertTo-Json -Depth 8) -match 'private path') { throw 'RAW_ERROR_LEAK' }
""")


def test_final_monitor_cleanup_preserves_valid_success():
    run_mocked_powershell(EVENT_REPLAY + r"""
Queue-Start
Drain-ProcessEvents
$script:operation = $null
$script:report = @{status='passed';phase='complete';failureCode=''}
function Unregister-Event { param($SourceIdentifier) }
Finalize-ProcessMonitor
if ($script:report.status -ne 'passed' -or $script:starts.Count -ne 1) { throw 'VALID_SUCCESS_LOST' }
""")


@pytest.mark.parametrize("delivery", ["exit", "settle"])
def test_late_builder_event_cannot_qualify_rejected_scenario(delivery):
    run_mocked_powershell(EVENT_REPLAY + r"""
$script:operation | Add-Member ScriptMethod WaitForExit {
    param($ms)
    if ('DELIVERY' -eq 'exit') { Queue-Start }
    return $true
}
$script:delivered = $false
function Start-Sleep {
    param($Seconds, $Milliseconds)
    if ('DELIVERY' -eq 'settle' -and -not $script:delivered) {
        $script:delivered = $true
        Queue-Start
    }
}
try { Complete-RejectedScenario; throw 'LATE_BUILDER_FALSE_SUCCESS' } catch {
    if ($_.Exception.Message -ne 'REJECTION_LAUNCHED_BUILDER_OR_SUCCEEDED') { throw }
}
if (-not $script:disposed -or $null -ne $script:operation) { throw 'TEARDOWN_LOST' }
if ($script:starts.Count -ne 1 -or -not $script:starts[0].owned) { throw 'OWNED_RECEIPT_LOST' }
""".replace("DELIVERY", delivery))


@pytest.mark.parametrize("name", ["builder.exe", "VC_redist.x64.exe"])
@pytest.mark.parametrize("lifetime", ["exited", "reused-owned", "reused-unowned"])
def test_unresolved_relevant_start_never_becomes_zero_evidence(name, lifetime):
    run_mocked_powershell(EVENT_REPLAY + r"""
$script:operation | Add-Member ScriptMethod Contains { param($id) return 'LIFETIME' -eq 'reused-owned' } -Force
$script:operation | Add-Member ScriptMethod GetLifetime {
    param($id)
    # OpenProcess failed for an already exited process, or now opens a DIFFERENT
    # lifetime with the same PID. Its current job membership is not authority.
    if ('LIFETIME' -eq 'exited') { return $null }
    return [pscustomobject]@{Created=$script:eventTime+1;Exited=0;
        Observed=[DateTime]::UtcNow.ToFileTimeUtc();Owned=('LIFETIME' -eq 'reused-owned')}
} -Force
$script:operation | Add-Member ScriptMethod WaitForExit { param($ms) Queue-Start 'NAME'; return $true }
function Start-Sleep { }
try { Complete-RejectedScenario; throw 'UNRESOLVED_EVENT_FALSE_SUCCESS' } catch {
    if ($_.Exception.Message -ne 'PROCESS_START_EVIDENCE_UNRESOLVED') { throw }
}
if (-not $script:disposed -or $null -ne $script:operation) { throw 'UNCERTAINTY_SKIPPED_TEARDOWN' }
if ($script:starts.Count -ne 1 -or $null -ne $script:starts[0].owned) { throw 'UNCERTAINTY_NOT_RETAINED' }
""".replace("NAME", name).replace("LIFETIME", lifetime))


@pytest.mark.parametrize("delivery", ["settle", "after-dispose"])
def test_wait_exit_rechecks_uncertainty_after_teardown(delivery):
    run_mocked_powershell(EVENT_REPLAY + r"""
# Accepted path already observed a builder; later uncertainty must still fail.
Queue-Start
Drain-ProcessEvents
if ((Get-OwnedStartCount 'builder.exe') -ne 1) { throw 'INITIAL_RECEIPT_MISSING' }
$script:operation | Add-Member ScriptMethod GetLifetime { return $null } -Force
$script:operation | Add-Member ScriptMethod WaitForExit { param($ms) return $true }
$script:delivered = $false
function Deliver-Late {
    if (-not $script:delivered) { $script:delivered = $true; Queue-Start 'VC_redist.x64.exe' }
}
function Start-Sleep { if ('DELIVERY' -eq 'settle') { Deliver-Late } }
function Get-Event {
    if ('DELIVERY' -eq 'after-dispose' -and $script:disposed) { Deliver-Late }
    $script:queue.ToArray()
}
try { Wait-Exit $script:outerProcess 30; throw 'TEARDOWN_UNCERTAINTY_FALSE_SUCCESS' } catch {
    if ($_.Exception.Message -ne 'PROCESS_START_EVIDENCE_UNRESOLVED') { throw }
}
if (-not $script:disposed -or $null -ne $script:operation) { throw 'TEARDOWN_LOST' }
if ($script:starts.Count -ne 2 -or -not $script:starts[0].owned -or $null -ne $script:starts[1].owned) {
    throw 'RECEIPTS_NOT_PRESERVED'
}
""".replace("DELIVERY", delivery))


@pytest.mark.parametrize("known", [True, False])
def test_delayed_pre_scenario_event_cannot_borrow_current_ownership(known):
    run_mocked_powershell(EVENT_REPLAY + r"""
$script:scenarioActive = $false
$script:req = [pscustomobject]@{outer='mock-outer'}
function Start-OwnedOperation {
    # Event occurred before this scenario, even if a lookup finds an owned PID.
    $script:eventTime = $script:scenarioStarted - 1
    Queue-Start
    return $script:operation
}
$script:operation | Add-Member ScriptMethod GetLifetime {
    if (-not KNOWN) { return $null }
    return [pscustomobject]@{Created=1L;Exited=0L;Observed=[DateTime]::UtcNow.ToFileTimeUtc();Owned=$true}
} -Force
Start-Scenario
Drain-ProcessEvents
if ((Get-OwnedStartCount 'builder.exe') -ne 0) { throw 'OLD_EVENT_BORROWED_OWNERSHIP' }
""".replace("KNOWN", "$true" if known else "$false"))


def test_new_scenario_cannot_erase_unresolved_previous_receipt():
    run_mocked_powershell(EVENT_REPLAY + r"""
$script:operation = $null
$script:req = [pscustomobject]@{outer='mock-outer'}
$script:launched = $false
function Start-OwnedOperation { $script:launched = $true; return $null }
Queue-Start
try { Start-Scenario; throw 'PREVIOUS_EVIDENCE_ERASED' } catch {
    if ($_.Exception.Message -ne 'PROCESS_START_EVIDENCE_UNRESOLVED') { throw }
}
if ($script:launched -or $script:starts.Count -ne 1) { throw 'PRIOR_EVIDENCE_LOST' }
""")


def test_queued_owned_receipt_is_saved_before_termination():
    run_mocked_powershell(EVENT_REPLAY + r"""
Queue-Start
$script:operation | Add-Member ScriptMethod WaitForExit { param($ms) return $true }
$script:operation | Add-Member ScriptMethod Terminate {
    # Once the kernel destroys the process, querying its lifetime is too late.
    $this | Add-Member ScriptMethod GetLifetime { return $null } -Force
} -Force
function Start-Sleep { }
try { Complete-RejectedScenario; throw 'QUEUED_RECEIPT_LOST' } catch {
    if ($_.Exception.Message -ne 'REJECTION_LAUNCHED_BUILDER_OR_SUCCEEDED') { throw }
}
if (-not $script:disposed -or $script:starts.Count -ne 1 -or -not $script:starts[0].owned) {
    throw 'OBSERVED_RECEIPT_NOT_PRESERVED'
}
""")


@pytest.mark.parametrize("timing", ["missing", "invalid", "after-exit", "after-observation"])
def test_relevant_event_requires_valid_lifetime_timing(timing):
    run_mocked_powershell(EVENT_REPLAY + r"""
Queue-Start
Drain-ProcessEvents
$first = $script:starts[0]
Queue-Start
switch ('TIMING') {
    'missing' { $script:queue[0].SourceEventArgs.NewEvent.PSObject.Properties.Remove('TIME_CREATED') }
    'invalid' { $script:queue[0].SourceEventArgs.NewEvent.TIME_CREATED = 'not-a-filetime' }
    'after-exit' {
        $script:operation | Add-Member ScriptMethod GetLifetime {
            return [pscustomobject]@{Created=$script:created;Exited=$script:eventTime-1;
                Observed=[DateTime]::UtcNow.ToFileTimeUtc();Owned=$true}
        } -Force
    }
    'after-observation' {
        $script:operation | Add-Member ScriptMethod GetLifetime {
            return [pscustomobject]@{Created=$script:created;Exited=0;Observed=$script:eventTime-1;Owned=$true}
        } -Force
    }
}
Drain-ProcessEvents
try { Get-OwnedStartCount 'builder.exe'; throw 'INVALID_TIMING_COUNTED' } catch {
    if ($_.Exception.Message -ne 'PROCESS_START_EVIDENCE_UNRESOLVED') { throw }
}
if ($script:starts.Count -ne 2 -or -not $first.owned -or $first.time -ne $script:eventTime -or
    $first.lifetime.Created -ne $script:created -or $null -ne $script:starts[1].owned) {
    throw 'LIFETIME_RECEIPTS_CHANGED'
}
""".replace("TIMING", timing))


def test_late_previous_scenario_event_rejects_instead_of_disappearing():
    run_mocked_powershell(EVENT_REPLAY + r"""
$script:req = [pscustomobject]@{outer='mock-outer'}
$script:previousEventTime = $script:eventTime
function Start-OwnedOperation {
    # Delivered only AFTER the next scenario starts; never relabel as its start.
    $script:eventTime = $script:previousEventTime
    Queue-Start
    return $script:operation
}
Start-Scenario
Drain-ProcessEvents
try { Get-OwnedStartCount 'builder.exe'; throw 'LATE_PRIOR_EVENT_ERASED' } catch {
    if ($_.Exception.Message -ne 'PROCESS_START_EVIDENCE_UNRESOLVED') { throw }
}
if ($script:starts.Count -ne 1 -or $null -ne $script:starts[0].owned) { throw 'PRIOR_RECEIPT_MISATTRIBUTED' }
""")


def test_workflow_deadline_reserves_supervision_before_mutation():
    module = load()
    module.require_time_reserve({"OAL_CLEAN_RUNTIME_STEP_DEADLINE": "2470"}, now=1000)
    for env in ({}, {"OAL_CLEAN_RUNTIME_STEP_DEADLINE": "2469"},
                {"OAL_CLEAN_RUNTIME_STEP_DEADLINE": "invalid"}):
        with pytest.raises(ValueError, match="recovery reserve"):
            module.require_time_reserve(env, now=1000)


def test_native_powershell_functions_and_host_guard():
    import shutil
    import subprocess
    powershell = shutil.which("powershell.exe")
    if powershell is None:
        pytest.skip("Requires inbox Windows PowerShell; portable/static tests are not native evidence")
    worker = str(PACKAGING / "windows_clean_install.ps1").replace("'", "''")
    code = f"""
. '{worker}'
$env:OAL_CLEAN_RUNTIME_TEST_ACCEPTANCE = ''
try {{ Assert-DisposableRunner; throw 'GUARD_DID_NOT_REJECT' }} catch {{
    if ($_.Exception.Message -ne 'DISPOSABLE_RUNNER_REQUIRED') {{ throw }}
}}
$state = @{{ registry = @(@{{keyPresent=$false;installed=0;version=''}}, @{{keyPresent=$false;installed=0;version=''}}); dlls = @(@{{present=$false;version=''}}, @{{present=$false;version=''}}, @{{present=$false;version=''}}) }}
if (-not (Test-GenuineAbsence $state)) {{ throw 'ABSENCE_CHECK_FAILED' }}
$state.dlls[0].present = $true
if (Test-GenuineAbsence $state) {{ throw 'PRESENT_DLL_FALSE_PASS' }}
'Native guard/absence functions passed; no installation attempted'
"""
    result = subprocess.run([powershell, "-NoLogo", "-NoProfile", "-Command", code], capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stdout + result.stderr

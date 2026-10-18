# CI ONLY. Never ship or invoke this worker on a recipient machine.
# All mutation/recovery runs in inbox Windows PowerShell, independent of VC/Python.
param([string]$Request, [ValidateSet('Supervisor', 'Scenario', 'Recovery')][string]$Mode = 'Supervisor')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-DisposableRunner {
    param([switch]$Recovery)
    if ($env:GITHUB_ACTIONS -cne 'true' -or $env:RUNNER_ENVIRONMENT -cne 'github-hosted' -or
        $env:RUNNER_LABEL -cne 'windows-2022' -or $env:ImageOS -cne 'win22' -or
        $env:OAL_CLEAN_RUNTIME_TEST_ACCEPTANCE -cne 'disposable-ci-only-not-user-consent' -or
        $env:USERNAME -cne 'runneradmin' -or [IntPtr]::Size -ne 8 -or
        $PSVersionTable.PSEdition -ne 'Desktop' -or $PSVersionTable.PSVersion.Major -ne 5) {
        throw 'DISPOSABLE_RUNNER_REQUIRED'
    }
    $admin = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $admin.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'CI_ADMIN_REQUIRED' }
    if (-not $Recovery -and (Test-Path 'HKCU:\Software\OpenAutoLink')) { throw 'TEST_USER_SETTINGS_NOT_FRESH' }
    foreach ($name in @('OAL_SETUP_EXTRACT_ONLY', 'OAL_SETUP_SMOKE_SCREENSHOT', 'QT_PLUGIN_PATH', 'QT_QPA_PLATFORM_PLUGIN_PATH', 'PYTHONHOME', 'PYTHONPATH')) {
        if ([Environment]::GetEnvironmentVariable($name)) { throw 'CONTAMINATED_CHILD_ENVIRONMENT' }
    }
}

function Get-RuntimeInventory {
    $registry = @()
    foreach ($view in @([Microsoft.Win32.RegistryView]::Registry32, [Microsoft.Win32.RegistryView]::Registry64)) {
        $base = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine, $view)
        try {
            $key = $base.OpenSubKey('SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', $false)
            $row = [ordered]@{ view = "$view"; keyPresent = ($null -ne $key); installed = 0; version = '' }
            if ($null -ne $key) {
                try {
                    $row.installed = [int]$key.GetValue('Installed', 0)
                    $value = ([string]$key.GetValue('Version', '')).TrimStart('v')
                    if ($value -and $value -notmatch '^14\.\d{1,5}\.\d{1,5}\.\d{1,5}$') { throw 'INVENTORY_VERSION_INVALID' }
                    $row.version = $value
                } finally { $key.Dispose() }
            }
            $registry += $row
        } finally { $base.Dispose() }
    }
    $dlls = @()
    foreach ($name in @('vcruntime140.dll', 'vcruntime140_1.dll', 'msvcp140.dll')) {
        # This worker is explicitly 64-bit: SystemDirectory is actual System32.
        $path = Join-Path ([Environment]::SystemDirectory) $name
        $present = Test-Path -LiteralPath $path -PathType Leaf
        $version = ''
        if ($present) {
            $info = [Diagnostics.FileVersionInfo]::GetVersionInfo($path)
            $version = '{0}.{1}.{2}.{3}' -f $info.FileMajorPart, $info.FileMinorPart, $info.FileBuildPart, $info.FilePrivatePart
        }
        $dlls += [ordered]@{ name = $name; present = [bool]$present; version = $version }
    }
    return [ordered]@{ registry = $registry; dlls = $dlls }
}

function Test-GenuineAbsence($Inventory) {
    if (@($Inventory.registry).Count -ne 2 -or @($Inventory.dlls).Count -ne 3) { return $false }
    foreach ($row in $Inventory.registry) { if ($row.keyPresent -or $row.installed -ne 0 -or $row.version) { return $false } }
    foreach ($row in $Inventory.dlls) { if ($row.present -or $row.version) { return $false } }
    return $true
}

function Get-RemovalTargets {
    # No Win32_Product: it repairs unrelated MSI products. No arbitrary uninstall strings.
    $seen = @{}
    foreach ($view in @([Microsoft.Win32.RegistryView]::Registry32, [Microsoft.Win32.RegistryView]::Registry64)) {
        $base = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine, $view)
        try {
            $root = $base.OpenSubKey('SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall', $false)
            try {
                foreach ($id in $root.GetSubKeyNames()) {
                    $key = $root.OpenSubKey($id, $false)
                    try {
                        $name = [string]$key.GetValue('DisplayName', '')
                        $version = [string]$key.GetValue('DisplayVersion', '')
                        $publisher = [string]$key.GetValue('Publisher', '')
                        if ($publisher -cne 'Microsoft Corporation' -or $version -notmatch '^14\.\d+\.\d+(?:\.\d+)?$') { continue }
                        $bundle = $name -match '^Microsoft Visual C\+\+ (?:2015|2017|2019|2022|2015-2019|2015-2022) Redistributable \(x64\) - 14\.'
                        $msi = $name -match '^Microsoft Visual C\+\+ (?:2015|2017|2019|2022|2015-2019|2015-2022) X64 (?:Minimum|Additional) Runtime - 14\.'
                        if (-not ($bundle -or $msi) -or $seen.ContainsKey($id)) { continue }
                        if ($id -notmatch '^\{[0-9A-Fa-f-]{36}\}$') { throw 'UNINSTALL_ID_INVALID' }
                        $seen[$id] = $true
                        if ($bundle) {
                            $cache = [string]$key.GetValue('BundleCachePath', '')
                            if (-not $cache -or -not (Test-Path -LiteralPath $cache -PathType Leaf)) { throw 'UNINSTALL_CACHE_UNAVAILABLE' }
                            Assert-MicrosoftSignature $cache
                            [pscustomobject]@{ kind = 'bundle'; id = $id; path = $cache }
                        } elseif ([int]$key.GetValue('WindowsInstaller', 0) -eq 1) {
                            [pscustomobject]@{ kind = 'msi'; id = $id; path = '' }
                        }
                    } finally { $key.Dispose() }
                }
            } finally { $root.Dispose() }
        } finally { $base.Dispose() }
    }
}

function Initialize-ProcessOwner {
    # Suspended creation closes the spawn/assignment race. Nested jobs are
    # supported on this explicitly Windows-2022-only harness. Never use taskkill
    # by PID: a launcher can exit before its descendants, and PIDs can be reused.
    Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public sealed class CleanOwnedJob : IDisposable {
 public sealed class Lifetime {
  public long Created, Exited, Observed;
  public bool Owned;
 }
 [StructLayout(LayoutKind.Sequential)] struct STARTUPINFO {
  public int cb; public IntPtr reserved, desktop, title;
  public int x,y,xsize,ysize,xchars,ychars,fill,flags;
  public short show,reserved2; public IntPtr reservedPtr,input,output,error;
 }
 [StructLayout(LayoutKind.Sequential)] struct PROCESSINFO { public IntPtr process,thread; public int pid,tid; }
 [StructLayout(LayoutKind.Sequential)] struct BASICLIMIT {
  public long processTime,jobTime; public uint flags; public UIntPtr min,max;
  public uint active; public UIntPtr affinity; public uint priority,scheduling;
 }
 [StructLayout(LayoutKind.Sequential)] struct IOCOUNTERS { public ulong a,b,c,d,e,f; }
 [StructLayout(LayoutKind.Sequential)] struct EXTENDEDLIMIT {
  public BASICLIMIT basic; public IOCOUNTERS io; public UIntPtr processMemory,jobMemory,peakProcess,peakJob;
 }
 [StructLayout(LayoutKind.Sequential)] struct ACCOUNTING {
  public long user,kernel,periodUser,periodKernel; public uint faults,total,active,terminated;
 }
 [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr a,string name);
 [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetInformationJobObject(IntPtr j,int c,ref EXTENDEDLIMIT v,int size);
 [DllImport("kernel32.dll", SetLastError=true)] static extern bool QueryInformationJobObject(IntPtr j,int c,out ACCOUNTING v,int size,IntPtr length);
 [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool CreateProcess(string file,StringBuilder cmd,IntPtr pa,IntPtr ta,bool inherit,uint flags,IntPtr env,string cwd,ref STARTUPINFO si,out PROCESSINFO pi);
 [DllImport("kernel32.dll", SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr j,IntPtr p);
 [DllImport("kernel32.dll", SetLastError=true)] static extern uint ResumeThread(IntPtr t);
 [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr h,uint ms);
 [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr h,out uint code);
 [DllImport("kernel32.dll")] static extern bool TerminateJobObject(IntPtr j,uint code);
 [DllImport("kernel32.dll")] static extern bool TerminateProcess(IntPtr p,uint code);
 [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
 [DllImport("kernel32.dll")] static extern IntPtr OpenProcess(uint access,bool inherit,int pid);
 [DllImport("kernel32.dll")] static extern bool IsProcessInJob(IntPtr p,IntPtr j,out bool member);
 [DllImport("kernel32.dll")] static extern bool GetProcessTimes(IntPtr p,out long created,out long exited,out long kernel,out long user);
 IntPtr job, process;
 public int Id { get; private set; }
 public static string Quote(string value) {
  // CRT quoting, including trailing backslashes; never invoke cmd.exe.
  StringBuilder b = new StringBuilder("\""); int slashes=0;
  foreach(char c in value) {
   if(c=='\\') { slashes++; continue; }
   b.Append('\\', c=='"' ? slashes*2+1 : slashes); b.Append(c); slashes=0;
  }
  b.Append('\\', slashes*2); b.Append('"'); return b.ToString();
 }
 public CleanOwnedJob(string file,string[] args) {
  job=CreateJobObject(IntPtr.Zero,null);
  if(job==IntPtr.Zero) throw new Exception("JOB_CREATE_FAILED");
  PROCESSINFO pi=new PROCESSINFO();
  try {
   EXTENDEDLIMIT limit=new EXTENDEDLIMIT(); limit.basic.flags=0x2000; // KILL_ON_JOB_CLOSE, no breakaway
   if(!SetInformationJobObject(job,9,ref limit,Marshal.SizeOf(limit))) throw new Exception("JOB_LIMIT_FAILED");
   StringBuilder cmd=new StringBuilder(Quote(file)); foreach(string a in args) cmd.Append(" ").Append(Quote(a));
   STARTUPINFO si=new STARTUPINFO(); si.cb=Marshal.SizeOf(si);
   if(!CreateProcess(file,cmd,IntPtr.Zero,IntPtr.Zero,false,4,IntPtr.Zero,null,ref si,out pi)) throw new Exception("OWNED_CREATE_FAILED");
   process=pi.process; Id=pi.pid;
   if(!AssignProcessToJobObject(job,process)) throw new Exception("JOB_ASSIGN_FAILED");
   if(ResumeThread(pi.thread)==0xffffffff) throw new Exception("OWNED_RESUME_FAILED");
  } catch { if(pi.process!=IntPtr.Zero) TerminateProcess(pi.process,1); Dispose(); throw; }
  finally { if(pi.thread!=IntPtr.Zero) CloseHandle(pi.thread); }
 }
 public bool WaitForExit(int ms) {
  uint result=WaitForSingleObject(process,(uint)ms);
  if(result==0xffffffff) throw new Exception("OWNED_WAIT_FAILED"); return result==0;
 }
 public int ExitCode { get { uint code; if(!GetExitCodeProcess(process,out code)) throw new Exception("OWNED_EXIT_FAILED"); return unchecked((int)code); } }
 public bool Empty { get {
  ACCOUNTING a; if(!QueryInformationJobObject(job,1,out a,Marshal.SizeOf(typeof(ACCOUNTING)),IntPtr.Zero)) throw new Exception("JOB_QUERY_FAILED");
  return a.active==0;
 } }
 public bool Contains(int pid) {
  IntPtr p=OpenProcess(0x1000,false,pid); if(p==IntPtr.Zero) return false;
  try { bool member; return IsProcessInJob(p,job,out member) && member; } finally { CloseHandle(p); }
 }
 public Lifetime GetLifetime(int pid) {
  // Both timing and membership come from the SAME held process handle. A PID
  // lookup alone could now refer to a different lifetime (even in this job).
  IntPtr p=OpenProcess(0x1000,false,pid); if(p==IntPtr.Zero) return null;
  try {
   Lifetime v=new Lifetime(); long kernel,user;
   if(!GetProcessTimes(p,out v.Created,out v.Exited,out kernel,out user) ||
      !IsProcessInJob(p,job,out v.Owned)) return null;
   v.Observed=DateTime.UtcNow.ToFileTimeUtc(); return v;
  } finally { CloseHandle(p); }
 }
 public void Terminate() { if(!TerminateJobObject(job,1)) throw new Exception("JOB_TERMINATE_FAILED"); }
 public void Dispose() {
  if(job!=IntPtr.Zero) { CloseHandle(job); job=IntPtr.Zero; }
  if(process!=IntPtr.Zero) { CloseHandle(process); process=IntPtr.Zero; }
 }
}
'@
}

function Get-RemainingMilliseconds([int]$Seconds) {
    $remaining = [int][Math]::Floor($script:budgetSeconds * 1000 - $script:clock.Elapsed.TotalMilliseconds)
    if ($remaining -le 0) { throw 'SCENARIO_DEADLINE_EXCEEDED' }
    return [Math]::Min($Seconds * 1000, $remaining)
}

function Stop-JobTree($Job, [switch]$CollectEvents) {
    if ($null -eq $Job) { return $true }
    $clock = [Diagnostics.Stopwatch]::StartNew()
    try {
        $Job.Terminate()
        do {
            if ($Job.Empty) { return $true }
            Start-Sleep -Milliseconds 100
        } while ($clock.Elapsed.TotalSeconds -lt 30)
        return $false
    } catch { return $false }
    finally {
        try {
            if ($CollectEvents) {
                # Keep the job authority until queued start receipts are saved,
                # including notifications delivered during this bounded settle.
                Drain-ProcessEvents
                $settle = [int][Math]::Max(0, [Math]::Min(1000, 30000 - $clock.ElapsedMilliseconds))
                if ($settle -gt 0) { Start-Sleep -Milliseconds $settle }
                Drain-ProcessEvents
            }
        } finally { $Job.Dispose() }
    }
}

function Stop-OwnedOperation {
    $job = $script:operation
    try {
        if (-not (Stop-JobTree $job -CollectEvents:$script:scenarioActive)) { throw 'OWNED_TEARDOWN_UNCONFIRMED' }
    } finally { $script:operation = $null }
}

function Start-OwnedOperation([string]$File, [string[]]$Arguments = @()) {
    [void](Get-RemainingMilliseconds 1)
    if ($null -ne $script:operation) { throw 'OVERLAPPING_OWNED_OPERATION' }
    $script:operation = [CleanOwnedJob]::new($File, $Arguments)
    return $script:operation
}

function Wait-Exit($Process, [int]$Seconds = 180, $Diagnostic = $null) {
    try {
        $milliseconds = Get-RemainingMilliseconds $Seconds
        $clock = [Diagnostics.Stopwatch]::StartNew()
        if ($script:scenarioActive) { Drain-ProcessEvents }
        if ($null -ne $Diagnostic) { $Diagnostic.phase = 'root-wait' }
        $exited = $Process.WaitForExit($milliseconds)
        if ($null -ne $Diagnostic) { $Diagnostic.rootExited = [bool]$exited }
        if (-not $exited) { throw 'PROCESS_TIMEOUT' }
        # Capture the root's real exit before teardown (never report STILL_ACTIVE
        # or our own termination code as an installer result).
        $code = $Process.ExitCode
        if ($null -ne $Diagnostic) { $Diagnostic.exitCode = $code; $Diagnostic.phase = 'tree-wait' }
        # Root exit is not tree exit. Do not permit the next mutation while a
        # Burn/MSI descendant is still live, even after its launcher exited.
        while ($true) {
            $empty = $Process.Empty
            if ($null -ne $Diagnostic) { $Diagnostic.jobEmpty = [bool]$empty }
            if ($empty) { break }
            if ($clock.ElapsedMilliseconds -ge $milliseconds) { throw 'PROCESS_TREE_TIMEOUT' }
            Start-Sleep -Milliseconds 100
        }
        if ($script:scenarioActive) { Drain-ProcessEvents }
    } catch {
        if ($null -ne $Diagnostic) {
            $message = $_.Exception.Message
            $Diagnostic.failureCode = if ($message -cmatch '^[A-Z][A-Z0-9_]{2,80}$') { $message } else { 'OWNED_WAIT_FAILED' }
        }
        Stop-OwnedOperation
        throw
    }
    Stop-OwnedOperation
    if ($script:scenarioActive) {
        Drain-ProcessEvents
        [void](Get-OwnedStartCount 'builder.exe')
    }
    if ($null -ne $Diagnostic) { $Diagnostic.phase = 'complete' }
    return $code
}

function Get-OwnedStartCount([string]$Name) {
    # Unknown is not zero, even when the caller asks about the other executable.
    # Reject the whole scenario rather than guessing from parent/current PIDs.
    if (@($script:starts | Where-Object {
        $null -eq $_.owned -and $_.name -iin @('builder.exe', 'VC_redist.x64.exe')
    }).Count -ne 0) { throw 'PROCESS_START_EVIDENCE_UNRESOLVED' }
    return @($script:starts | Where-Object { $_.owned -and $_.name -ieq $Name }).Count
}

function Read-InstallerLogSummary([string]$Path) {
    # Private test logs only. Export whitelisted event IDs/numbers, never text,
    # paths, package names or command lines. A missing/truncated log proves nothing.
    $summary = [ordered]@{ state = 'missing'; bytesRead = 0; truncated = $false;
        events = @(); errorCodes = @(); resultCodes = @(); restart = 'unknown' }
    $stream = $null; $reader = $null
    try {
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $summary }
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        $size = [int][Math]::Min(1048576, $stream.Length)
        $summary.truncated = $stream.Length -gt $size
        $buffer = [byte[]]::new($size)
        while ($summary.bytesRead -lt $size) {
            $read = $stream.Read($buffer, $summary.bytesRead, $size - $summary.bytesRead)
            if ($read -eq 0) { break }
            $summary.bytesRead += $read
        }
        $reader = [IO.StreamReader]::new([IO.MemoryStream]::new($buffer, 0, $summary.bytesRead), [Text.Encoding]::UTF8, $true)
        $text = $reader.ReadToEnd()
        # Ignore a cut line rather than parsing an incomplete numeric result.
        if ($summary.truncated) {
            $last = $text.LastIndexOf("`n")
            $text = if ($last -ge 0) { $text.Substring(0, $last + 1) } else { '' }
        }
        foreach ($line in ($text -split "`n")) {
            if ($line.Length -gt 4096) { $summary.truncated = $true; continue }
            if ($line -match '\]((?:i010|i011|i012|i100|i199|i200|i299|i300|i301|i319|i399|i500)):\s') {
                if ($summary.events -cnotcontains $Matches[1]) { $summary.events += $Matches[1] }
            }
            foreach ($pair in @(@('errorCodes', '\bError (0x[0-9a-fA-F]{1,8}|[0-9]{3,5})\b'),
                                @('resultCodes', '(?:result: |exit code: |MainEngineThread is returning )(0x[0-9a-fA-F]{1,8}|[0-9]{1,5})\b'))) {
                if ($line -match $pair[1]) {
                    $value = $Matches[1].ToLowerInvariant()
                    if ($summary[$pair[0]] -cnotcontains $value) {
                        if ($summary[$pair[0]].Count -lt 16) { $summary[$pair[0]] += $value }
                        else { $summary.truncated = $true }
                    }
                }
            }
            if ($line -cmatch '\bi399: .*?, restart: (None|Required|Initiated),') { $summary.restart = $Matches[1] }
        }
        $summary.state = 'read'
    } catch { $summary.state = 'unreadable' }
    finally {
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $stream) { $stream.Dispose() }
    }
    return $summary
}

function Invoke-LoggedRuntimeOperation([ValidateSet('bundle', 'msi', 'recovery')][string]$Kind,
                                       [string]$File, [string[]]$Arguments) {
    $row = [ordered]@{ kind = $Kind; phase = 'launch'; exitCode = $null;
        rootExited = $null; jobEmpty = $null; failureCode = ''; elapsedMilliseconds = 0; installerLog = $null }
    # Retain the attempt even if launch/wait throws, rather than an empty removal list.
    if ($Kind -eq 'recovery') { $script:report.recoveryOperation = $row }
    else {
        if ($script:report.removal.Count -ge 16) { throw 'REMOVAL_OPERATION_LIMIT' }
        $script:report.removal += $row
    }
    $log = Join-Path (Split-Path -Parent $Request) ($Kind + '-' + [Guid]::NewGuid().ToString('N') + '.log')
    $clock = [Diagnostics.Stopwatch]::StartNew()
    try {
        # Microsoft's redistributable supports /log filename; MSI additionally
        # supports verbose + flush-per-line. Neither switch changes consent in
        # production: this helper is called only by the disposable test worker.
        $flag = if ($Kind -eq 'msi') { '/L*v!' } else { '/log' }
        $p = Start-OwnedOperation -File $File -Arguments ($Arguments + @($flag, $log))
        return Wait-Exit $p -Diagnostic $row
    } catch {
        if (-not $row.failureCode) {
            $message = $_.Exception.Message
            $row.failureCode = if ($message -cmatch '^[A-Z][A-Z0-9_]{2,80}$') { $message } else { 'RUNTIME_OPERATION_FAILED' }
        }
        throw
    } finally {
        $row.elapsedMilliseconds = [long]$clock.ElapsedMilliseconds
        $row.installerLog = Read-InstallerLogSummary $log
    }
}

function Remove-TestRuntime {
    # Only this guarded ephemeral test uninstalls. Production installer stays interactive.
    foreach ($item in @(Get-RemovalTargets | Where-Object kind -eq 'bundle')) {
        $code = Invoke-LoggedRuntimeOperation 'bundle' $item.path @('/uninstall', '/quiet', '/norestart')
        if ($code -ne 0) { throw 'UNINSTALL_FAILED_OR_RESTART_REQUIRED' }
    }
    foreach ($item in @(Get-RemovalTargets | Where-Object kind -eq 'msi')) {
        $code = Invoke-LoggedRuntimeOperation 'msi' (Join-Path ([Environment]::SystemDirectory) 'msiexec.exe') @('/x', $item.id, '/qn', '/norestart')
        if ($code -notin @(0, 1605)) { throw 'UNINSTALL_FAILED_OR_RESTART_REQUIRED' }
    }
}

function Initialize-Desktop {
    Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Windows.Forms, System.Drawing
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class CleanDesktop {
 [DllImport("user32.dll", SetLastError=true)] public static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
 [DllImport("user32.dll")] public static extern bool CloseDesktop(IntPtr desktop);
 [DllImport("user32.dll", SetLastError=true, CharSet=CharSet.Unicode)] public static extern bool GetUserObjectInformation(IntPtr h, int index, System.Text.StringBuilder text, int size, out int needed);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
}
'@
    $script:report.desktop = [ordered]@{ interactive = [Environment]::UserInteractive; sessionId = (Get-Process -Id $PID).SessionId; inputDesktop = ''; win32Error = 0; elevated = $true; uac = 'not-exercised-already-elevated-runner' }
    if (-not [Environment]::UserInteractive -or $script:report.desktop.sessionId -eq 0) { throw 'NO_INTERACTIVE_SESSION' }
    Assert-InputDesktop
    # Actual UIA availability, not an environment-flag guess.
    $form = [Windows.Forms.Form]::new()
    try {
        $form.Text = 'OpenAutoLink disposable CI desktop probe'
        $form.Show()
        [Windows.Forms.Application]::DoEvents()
        $node = [Windows.Automation.AutomationElement]::FromHandle($form.Handle)
        if ($null -eq $node -or $node.Current.ProcessId -ne $PID) { throw 'UIAUTOMATION_UNAVAILABLE' }
    } finally { $form.Close(); $form.Dispose() }
}

function Assert-InputDesktop {
    $handle = [CleanDesktop]::OpenInputDesktop(0, $false, 1)
    if ($handle -eq [IntPtr]::Zero) {
        $script:report.desktop.win32Error = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
        throw 'INPUT_DESKTOP_UNAVAILABLE'
    }
    try {
        $text = [Text.StringBuilder]::new(128); $needed = 0
        if (-not [CleanDesktop]::GetUserObjectInformation($handle, 2, $text, 256, [ref]$needed)) { throw 'INPUT_DESKTOP_QUERY_FAILED' }
        if ($text.ToString() -cne 'Default') { $script:report.desktop.inputDesktop = 'not-default'; throw 'SECURE_OR_NONDEFAULT_DESKTOP' }
        $script:report.desktop.inputDesktop = 'Default'
    } finally { [void][CleanDesktop]::CloseDesktop($handle) }
}

function Drain-ProcessEvents {
    foreach ($event in @(Get-Event -SourceIdentifier OALCleanStarts -ErrorAction SilentlyContinue)) {
        $p = $event.SourceEventArgs.NewEvent
        if ($script:starts.Count -ge 4096) { throw 'PROCESS_EVENT_LIMIT' }
        # TIME_CREATED is the WMI event's UTC FILETIME, not delivery time.
        # Preserve an immutable receipt before teardown; an exited/unqueryable
        # or reused PID cannot silently become unrelated/zero evidence.
        $owned = $null; $time = 0L; $lifetime = $null
        try {
            $time = [long]$p.TIME_CREATED
            if ($time -gt 0 -and $time -lt $script:monitorStarted) {
                Remove-Event -EventIdentifier $event.EventIdentifier
                continue
            }
            # A notification from an earlier scenario is still relevant to the
            # run, but cannot borrow the current job's authority. Leave unknown.
            if ($time -ge $script:scenarioStarted -and $null -ne $script:operation) {
                $lifetime = $script:operation.GetLifetime([int]$p.ProcessID)
                if ($null -ne $lifetime -and $lifetime.Created -gt 0 -and
                    $time -ge $lifetime.Created -and $time -le $lifetime.Observed -and
                    ($lifetime.Exited -eq 0 -or $time -le $lifetime.Exited)) {
                    $owned = [bool]$lifetime.Owned
                }
            }
        } catch { $owned = $null }
        $script:starts.Add([pscustomobject]@{
            id = [int]$p.ProcessID; name = [string]$p.ProcessName;
            time = $time; lifetime = $lifetime; owned = $owned
        })
        Remove-Event -EventIdentifier $event.EventIdentifier
    }
}

function Finalize-ProcessMonitor {
    # Stop delivery before the final drain. Never delete receipts just because
    # the owned process has exited: unresolved relevant starts invalidate success.
    try {
        Unregister-Event -SourceIdentifier OALCleanStarts -ErrorAction Stop
        Drain-ProcessEvents
        # Before the first UI scenario these are runtime-removal/machine starts,
        # not rejection/acceptance evidence. Never validate them as a UI scenario.
        if ($script:scenarioActive) { [void](Get-OwnedStartCount 'builder.exe') }
    } catch {
        $code = $_.Exception.Message
        if ($code -cnotmatch '^[A-Z][A-Z0-9_]{2,80}$') { $code = 'PROCESS_MONITOR_FINALIZATION_FAILED' }
        $script:report['cleanupFailure'] = [ordered]@{code=$code;line=[int]$_.InvocationInfo.ScriptLineNumber}
        if (-not $script:report.failureCode) {
            $script:report.phase = 'monitor-finalization'
            $script:report.failureCode = $code
        }
        $script:report.status = 'failed'
    }
}

function Start-Scenario {
    if ($script:scenarioActive) {
        Drain-ProcessEvents
        [void](Get-OwnedStartCount 'builder.exe')
    } else {
        Get-Event -SourceIdentifier OALCleanStarts -ErrorAction SilentlyContinue | Remove-Event
    }
    $script:starts = [Collections.Generic.List[object]]::new()
    $script:scenarioStarted = [DateTime]::UtcNow.ToFileTimeUtc()
    if (-not $script:scenarioActive) { $script:monitorStarted = $script:scenarioStarted }
    $script:scenarioActive = $true
    $script:outerProcess = Start-OwnedOperation $script:req.outer
}

function Get-OwnedWindows {
    Drain-ProcessEvents
    $roots = [Windows.Automation.AutomationElement]::RootElement.FindAll([Windows.Automation.TreeScope]::Children, [Windows.Automation.Condition]::TrueCondition)
    foreach ($root in $roots) {
        if ($null -ne $script:operation -and $script:operation.Contains($root.Current.ProcessId) -and -not $root.Current.IsOffscreen) { $root }
    }
}

function Find-Control($Root, [string]$Name, [string]$Type = 'Button') {
    $controls = $Root.FindAll([Windows.Automation.TreeScope]::Descendants, [Windows.Automation.Condition]::TrueCondition)
    foreach ($control in $controls) {
        if ($control.Current.ControlType.ProgrammaticName -eq "ControlType.$Type" -and
            $control.Current.Name.Replace('&', '') -match $Name -and $control.Current.IsEnabled -and -not $control.Current.IsOffscreen) { return $control }
    }
    return $null
}

function Wait-Control([string]$WindowName, [string]$Name, [string]$Type = 'Button', [int]$Seconds = 120) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        [void](Get-RemainingMilliseconds 1)
        Assert-InputDesktop
        foreach ($root in @(Get-OwnedWindows)) {
            if ($script:report.phase -eq 'accepted-test-install' -and $root.Current.Name -eq 'OpenAutoLink Builder could not start') {
                $messages = $root.FindAll([Windows.Automation.TreeScope]::Descendants, [Windows.Automation.Condition]::TrueCondition)
                foreach ($message in $messages) {
                    $text = $message.Current.Name
                    if ($text -match 'Microsoft requires a Windows restart') { throw 'INSTALLER_RESTART_REQUIRED' }
                    if ($text -match 'installation was declined or failed \(code (-?\d{1,10})\)') {
                        $script:report.observedInstallerExit = [long]$Matches[1]
                        throw 'INSTALLER_NONZERO_EXIT_OBSERVED'
                    }
                    if ($text -match 'required x64 runtime is still unavailable') { throw 'INSTALLER_POSTCHECK_FAILED' }
                    if ($text -match 'failed its SHA-256 check') { throw 'PRODUCTION_HASH_REJECTED' }
                    if ($text -match 'Could not download the Microsoft runtime') { throw 'PRODUCTION_DOWNLOAD_FAILED' }
                    if ($text -match 'Microsoft runtime signature is missing') { throw 'PRODUCTION_SIGNATURE_REJECTED' }
                }
            }
            if ($root.Current.Name -match $WindowName) {
                $control = Find-Control $root $Name $Type
                if ($null -ne $control) { return [pscustomobject]@{ root = $root; control = $control } }
            }
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'EXPECTED_UI_CONTROL_UNAVAILABLE'
}

function Invoke-Control($Control) {
    $pattern = $Control.GetCurrentPattern([Windows.Automation.InvokePattern]::Pattern)
    $pattern.Invoke()
}

function Save-Window($Root, [string]$Name, [switch]$RedactWorkspace) {
    # Window-only screenshots: never capture desktop/taskbar/credentials.
    [void][CleanDesktop]::SetForegroundWindow([IntPtr]$Root.Current.NativeWindowHandle)
    Start-Sleep -Milliseconds 300
    $r = $Root.Current.BoundingRectangle
    if ($r.Width -lt 50 -or $r.Height -lt 50 -or $r.Width -gt 2048 -or $r.Height -gt 2048) { throw 'SCREENSHOT_BOUNDS_INVALID' }
    $bitmap = [Drawing.Bitmap]::new([int]$r.Width, [int]$r.Height)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CopyFromScreen([int]$r.X, [int]$r.Y, 0, 0, $bitmap.Size)
        if ($RedactWorkspace) {
            $field = Find-Control $Root '^Managed work folder$' 'Edit'
            if ($null -eq $field) { throw 'SCREENSHOT_REDACTION_UNAVAILABLE' }
            $box = $field.Current.BoundingRectangle
            $graphics.FillRectangle([Drawing.Brushes]::Black, [int]($box.X - $r.X), [int]($box.Y - $r.Y), [int]$box.Width, [int]$box.Height)
        }
        $path = Join-Path $script:req.output $Name
        $bitmap.Save($path, [Drawing.Imaging.ImageFormat]::Png)
        $script:report.screenshots[$Name] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}

function Complete-RejectedScenario {
    $dialog = Wait-Control '^OpenAutoLink Builder could not start$' '^OK$'
    Invoke-Control $dialog.control
    $outer = Wait-Control '^OpenAutoLink Builder$' '^OK$'
    Invoke-Control $outer.control
    $code = Wait-Exit $script:outerProcess 30
    Drain-ProcessEvents
    $builder = Get-OwnedStartCount 'builder.exe'
    if ($code -eq 0 -or $builder -ne 0) { throw 'REJECTION_LAUNCHED_BUILDER_OR_SUCCEEDED' }
    $state = Get-RuntimeInventory
    if (-not (Test-GenuineAbsence $state)) { throw 'REJECTION_CHANGED_RUNTIME' }
    return [ordered]@{ outerExit = $code; builderStarts = $builder; runtime = $state }
}

function Test-MutationStarted {
    return Test-Path -LiteralPath (Join-Path (Split-Path -Parent $Request) 'mutation-started')
}

function Test-ControllerTimeout {
    return Test-Path -LiteralPath (Join-Path $script:req.output 'windows-clean-controller-timeout.json')
}

function Wait-MsiQuiescent {
    # Windows Installer service processes are NOT necessarily job descendants.
    # Never kill them by name or assume killing the client cancels rollback.
    # Conservatively require *no* msiexec process for a continuous 5s window;
    # even an unrelated/idle service keeps recovery blocked after 60s.
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $quiet = [Diagnostics.Stopwatch]::StartNew()
    do {
        $running = @(Get-Process -ErrorAction Stop | Where-Object ProcessName -ieq 'msiexec')
        if ($running.Count -ne 0) { $quiet.Restart() }
        elseif ($quiet.Elapsed.TotalSeconds -ge 5) { return $true }
        Start-Sleep -Milliseconds 200
    } while ($clock.Elapsed.TotalSeconds -lt 60)
    return $false
}

function Start-WorkerJob([string]$Mode) {
    $shell = Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe'
    return [CleanOwnedJob]::new($shell, [string[]]@('-NoLogo', '-NoProfile', '-STA', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath, '-Request', $Request, '-Mode', $Mode))
}

function Read-ScenarioReport([ValidateSet('Scenario', 'Recovery')][string]$Mode = 'Scenario') {
    $path = Join-Path (Split-Path -Parent $Request) ($Mode.ToLowerInvariant() + '-report.json')
    if ((Get-Item -LiteralPath $path).Length -gt 65536) { throw 'REPORT_BOUND_EXCEEDED' }
    $child = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if ($child.identity.checkoutCommit -cne $script:req.identity.checkoutCommit -or
        $child.binding.workerSha256 -cne $script:req.binding.workerSha256) { throw 'CHILD_REPORT_BINDING_INVALID' }
    return $child
}

function Invoke-Supervisor {
    $job = $null
    try {
        $job = Start-WorkerJob 'Scenario'
        if (-not $job.WaitForExit(900000)) { throw 'SCENARIO_DEADLINE_EXCEEDED' }
        $child = Read-ScenarioReport
        $script:report = $child
        if ($job.ExitCode -ne 0 -and $script:report.status -eq 'passed') { throw 'SCENARIO_EXIT_FAILED' }
    } catch {
        $script:report.status = 'failed'
        $code = $_.Exception.Message
        if ($code -cmatch '^[A-Z][A-Z0-9_]{2,80}$') { $script:report.failureCode = $code }
        else { $script:report.failureCode = 'SUPERVISOR_OPERATION_FAILED' }
    } finally {
        # Independent of the scenario worker's finally block, including hangs
        # in COM, UIAutomation, download or an installer. Recovery is NOT in this
        # job, so terminating a stalled scenario cannot destroy recovery/reporting.
        $stopped = Stop-JobTree $job
        if (-not $stopped) {
            $script:report.status = 'failed'
            if (-not $script:report.failureCode) { $script:report.failureCode = 'OWNED_TEARDOWN_UNCONFIRMED' }
        }
        if (Test-ControllerTimeout) {
            $script:report.status = 'failed'
            $script:report.failureCode = 'CONTROLLER_DEADLINE_EXCEEDED'
        }
        if ((Test-MutationStarted) -and $script:report.status -ne 'passed') {
            $script:report.recovery = 'failed-dispose-vm'
            $recoveryJob = $null
            try {
                if (-not $stopped) { $script:report.recovery = 'skipped-owned-teardown-unconfirmed' }
                elseif (-not (Wait-MsiQuiescent)) { $script:report.recovery = 'skipped-msi-not-quiescent' }
                else {
                    $recoveryJob = Start-WorkerJob 'Recovery'
                    if ($recoveryJob.WaitForExit(240000)) {
                        if ($recoveryJob.ExitCode -eq 0) { $script:report.recovery = 'restored-not-qualification' }
                        # Diagnostics cannot replace the original scenario failure
                        # or determine success; that still requires recovery exit 0.
                        try {
                            $detail = Read-ScenarioReport -Mode Recovery
                            $script:report.recoveryOperation = $detail.recoveryOperation
                            $script:report.recoveryFailureCode = $detail.failureCode
                        } catch { $script:report.recoveryFailureCode = 'RECOVERY_REPORT_UNAVAILABLE' }
                    } else {
                        $script:report.recoveryFailureCode = 'RECOVERY_WORKER_TIMEOUT'
                    }
                }
            } catch { $script:report.recovery = 'failed-dispose-vm' }
            finally {
                if (-not (Stop-JobTree $recoveryJob)) { $script:report.recovery = 'failed-dispose-vm' }
            }
        }
    }
}

function Write-BoundedReport([string]$Path) {
    $json = $script:report | ConvertTo-Json -Depth 12
    if ($json.Length -gt 65536) { throw 'REPORT_BOUND_EXCEEDED' }
    # Rename prevents the supervisor reading a half-written child report.
    [IO.File]::WriteAllText(($Path + '.pending'), $json, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath ($Path + '.pending') -Destination $Path -Force
}

$script:scenarioActive = $false
# The monitor is registered before runtime removal, not only at Start-Scenario.
$script:starts = [Collections.Generic.List[object]]::new()
$script:scenarioStarted = 0L
$script:monitorStarted = 0L
if ($MyInvocation.InvocationName -eq '.') { return }

# No output contains arbitrary exception text, paths, registry names, or UI dumps.
$script:report = [ordered]@{ schema = 1; status = 'failed'; notQualification = $true;
    scope = 'real-clean-runtime-ci-ui'; testAcceptance = 'disposable-ci-only-not-user-consent';
    identity = $null; binding = $null; phase = 'guard'; failureCode = ''; desktop = $null;
    before = $null; absent = $null; after = $null; removal = @(); scenarios = [ordered]@{};
    screenshots = [ordered]@{}; recovery = 'not-needed'; recoveryOperation = $null; recoveryFailureCode = '';
    uacCancellation = 'not-tested'; restart = 'not-tested' }
$script:req = $null
$script:outerProcess = $null
$script:operation = $null
$script:clock = [Diagnostics.Stopwatch]::StartNew()
$script:budgetSeconds = 840
$recoveryLock = $null
$registered = $false
try {
    Assert-DisposableRunner -Recovery:($Mode -eq 'Recovery')
    $script:req = Get-Content -LiteralPath $Request -Raw | ConvertFrom-Json
    $script:report.identity = $script:req.identity
    $script:report.binding = $script:req.binding
    foreach ($entry in @(@($script:req.bootstrap, $script:req.binding.bootstrapSha256), @($PSCommandPath, $script:req.binding.workerSha256))) {
        if ((Get-FileHash -LiteralPath $entry[0] -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry[1]) { throw 'CANDIDATE_CHANGED' }
    }
    Initialize-ProcessOwner
    if ($Mode -eq 'Supervisor') {
        $script:report.phase = 'supervised-scenarios'
        Invoke-Supervisor
        if ($script:report.status -eq 'passed') { exit 0 }
        exit 1
    }
    # Original production functions, including real registry checks/hash/signature.
    . $script:req.bootstrap
    if ($Mode -eq 'Recovery') {
        if (-not (Test-MutationStarted)) { throw 'RECOVERY_WITHOUT_MUTATION_REFUSED' }
        $script:budgetSeconds = 210
        $recovery = Join-Path (Split-Path -Parent $Request) 'verified-recovery.exe'
        $recoveryLock = [IO.File]::Open($recovery, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        Assert-FileHash $recovery $script:RuntimeHash
        Assert-MicrosoftSignature $recovery
        $code = Invoke-LoggedRuntimeOperation 'recovery' $recovery @('/install', '/quiet', '/norestart')
        if ($code -ne 0 -or -not (Test-RuntimeReady)) { throw 'RECOVERY_FAILED' }
        $script:report.status = 'passed'
        exit 0
    }
    foreach ($entry in @(@($script:req.outer, $script:req.binding.outerSha256), @($script:req.builder, $script:req.binding.artifactSha256))) {
        Assert-FileHash $entry[0] $entry[1]
    }
    $script:report.before = Get-RuntimeInventory
    $script:report.phase = 'desktop-preflight'
    Initialize-Desktop
    $env:QT_QPA_PLATFORM = 'windows'
    $script:report.phase = 'recovery-preflight'
    $recovery = Join-Path (Split-Path -Parent $Request) 'verified-recovery.exe'
    Receive-Runtime $recovery
    $recoveryLock = [IO.File]::Open($recovery, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    Assert-FileHash $recovery $script:RuntimeHash
    Assert-MicrosoftSignature $recovery
    $script:report.scenarios.officialInstaller = [ordered]@{ sha256 = $script:RuntimeHash; authenticode = 'valid-microsoft'; source = 'pinned-official-download' }
    # Preload WMI, event monitor and UI assemblies BEFORE uninstalling Python's runtime.
    Register-WmiEvent -Class Win32_ProcessStartTrace -SourceIdentifier OALCleanStarts | Out-Null
    $registered = $true
    $script:report.phase = 'runtime-removal'
    [void](Get-RemainingMilliseconds 1)
    [IO.File]::WriteAllText((Join-Path (Split-Path -Parent $Request) 'mutation-started'), 'recovery-required')
    Remove-TestRuntime
    $script:report.absent = Get-RuntimeInventory
    if (-not (Test-GenuineAbsence $script:report.absent)) { throw 'RUNTIME_NOT_GENUINELY_ABSENT' }
    if (Test-RuntimeReady) { throw 'PRODUCTION_RUNTIME_CHECK_DISAGREES' }

    $script:report.phase = 'corrupt-hash-control'
    # Fault injection into a COPY, using the unmodified production hash verifier.
    # This is pre-execution integrity evidence, NOT a real installer failure.
    $corrupt = Join-Path (Split-Path -Parent $Request) 'corrupt-download.exe'
    [IO.File]::WriteAllBytes($corrupt, [byte[]]@(0, 1, 2, 3))
    $rejected = $false
    try { Assert-FileHash $corrupt $script:RuntimeHash } catch { $rejected = $true }
    Remove-Item -LiteralPath $corrupt -Force
    if (-not $rejected) { throw 'CORRUPT_HASH_ACCEPTED' }
    $script:report.scenarios.corruptHash = [ordered]@{ rejectedBeforeExecution = $true; installerExecuted = $false; kind = 'production-hash-function-fault-injection-not-installer-failure' }

    $script:report.phase = 'download-decline'
    Start-Scenario
    $prompt = Wait-Control '^Microsoft runtime required$' '^No$'
    Save-Window $prompt.root 'windows-clean-decline.png'
    Invoke-Control $prompt.control
    $script:report.scenarios.decline = Complete-RejectedScenario
    if ((Get-OwnedStartCount 'VC_redist.x64.exe') -ne 0) { throw 'DECLINE_STARTED_INSTALLER' }
    $script:report.scenarios.decline.installerStarts = 0

    $script:report.phase = 'installer-cancel'
    Start-Scenario
    $prompt = Wait-Control '^Microsoft runtime required$' '^Yes$'
    Invoke-Control $prompt.control
    $license = Wait-Control '^Microsoft Visual C\+\+' 'agree' 'CheckBox'
    $toggle = $license.control.GetCurrentPattern([Windows.Automation.TogglePattern]::Pattern)
    if ($toggle.Current.ToggleState -ne [Windows.Automation.ToggleState]::Off) { throw 'INSTALLER_LICENSE_PREACCEPTED' }
    Save-Window $license.root 'windows-clean-cancel.png'
    $cancel = Find-Control $license.root '^Cancel$'
    if ($null -eq $cancel) { throw 'INSTALLER_CANCEL_UNAVAILABLE' }
    Invoke-Control $cancel
    # WiX Burn may ask to confirm cancellation. Never click Yes elsewhere.
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        [void](Get-RemainingMilliseconds 1)
        foreach ($window in @(Get-OwnedWindows)) {
            if ($window.Current.Name -match '^Microsoft Visual C\+\+') {
                $yes = Find-Control $window '^Yes$'
                if ($null -ne $yes) { Invoke-Control $yes }
            }
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline -and $null -eq (@(Get-OwnedWindows | Where-Object { $_.Current.Name -eq 'OpenAutoLink Builder could not start' }) | Select-Object -First 1))
    $script:report.scenarios.cancel = Complete-RejectedScenario
    $installerStarts = Get-OwnedStartCount 'VC_redist.x64.exe'
    if ($installerStarts -lt 1) { throw 'INSTALLER_NOT_OBSERVED' }
    $script:report.scenarios.cancel.installerStarts = $installerStarts

    $script:report.phase = 'accepted-test-install'
    Start-Scenario
    $prompt = Wait-Control '^Microsoft runtime required$' '^Yes$'
    Invoke-Control $prompt.control
    $license = Wait-Control '^Microsoft Visual C\+\+' 'agree' 'CheckBox'
    $toggle = $license.control.GetCurrentPattern([Windows.Automation.TogglePattern]::Pattern)
    if ($toggle.Current.ToggleState -ne [Windows.Automation.ToggleState]::Off) { throw 'INSTALLER_LICENSE_PREACCEPTED' }
    Save-Window $license.root 'windows-clean-license.png'
    # Explicit disposable CI acceptance ONLY. Never evidence of recipient assent.
    $toggle.Toggle()
    $install = Wait-Control '^Microsoft Visual C\+\+' '^Install$'
    Invoke-Control $install.control
    $close = Wait-Control '^Microsoft Visual C\+\+' '^Close$' 'Button' 240
    $success = Find-Control $close.root 'Setup Successful' 'Text'
    if ($null -eq $success) { throw 'INSTALLER_SUCCESS_UI_NOT_OBSERVED' }
    Save-Window $close.root 'windows-clean-installed.png'
    Invoke-Control $close.control
    $terms = Wait-Control '^Microsoft runtime terms' '^Accept$'
    Save-Window $terms.root 'windows-clean-builder-terms.png'
    Invoke-Control $terms.control
    $wizard = Wait-Control '^OpenAutoLink Builder$' '^Continue$'
    $identity = Find-Control $wizard.root '^Application ID$' 'Edit'
    $bundleText = Find-Control $wizard.root 'Build a signed Android App Bundle' 'Text'
    if ($null -eq $identity -or $null -eq $bundleText) { throw 'ACTUAL_AAB_WIZARD_NOT_OBSERVED' }
    Save-Window $wizard.root 'windows-clean-wizard.png' -RedactWorkspace
    $script:report.after = Get-RuntimeInventory
    if (-not (Test-RuntimeReady)) { throw 'INSTALLED_RUNTIME_NOT_READY' }
    Drain-ProcessEvents
    $builderStarts = Get-OwnedStartCount 'builder.exe'
    if ($builderStarts -lt 1) { throw 'BUILDER_PROCESS_NOT_OBSERVED' }
    $windowPattern = $wizard.root.GetCurrentPattern([Windows.Automation.WindowPattern]::Pattern)
    $windowPattern.Close()
    $code = Wait-Exit $script:outerProcess 30
    if ($code -ne 0) { throw 'ACCEPTED_OUTER_FAILED' }
    $script:report.scenarios.accept = [ordered]@{ outerExit = $code; builderStarts = $builderStarts; runtimeReady = $true; installerUi = 'real-interactive'; wizard = 'actual-frozen-aab'; acceptance = 'disposable-ci-only-not-user-consent' }
    foreach ($entry in @(@($script:req.outer, $script:req.binding.outerSha256), @($script:req.builder, $script:req.binding.artifactSha256))) {
        Assert-FileHash $entry[0] $entry[1]
    }
    $script:report.phase = 'complete'
    $script:report.status = 'passed'
} catch {
    $script:report['failureLine'] = [int]$_.InvocationInfo.ScriptLineNumber
    $type = $_.Exception.GetType().FullName
    $script:report['failureType'] = if ($type -cmatch '^[A-Za-z.]{1,160}$') { $type } else { 'unknown' }
    $code = $_.Exception.Message
    if ($code -cmatch '^[A-Z][A-Z0-9_]{2,80}$') { $script:report.failureCode = $code }
    else { $script:report.failureCode = 'NATIVE_OPERATION_FAILED' }
} finally {
    if ($registered) {
        Finalize-ProcessMonitor
    }
    # Worker cleanup is best effort; the independent supervisor repeats teardown
    # of the enclosing job before considering any recovery operation.
    try { Stop-OwnedOperation } catch {
        $script:report.status = 'failed'
        $script:report.failureCode = 'OWNED_TEARDOWN_UNCONFIRMED'
    }
    if ($null -ne $recoveryLock) { $recoveryLock.Dispose() }
    if ($null -ne $script:req) {
        if ($Mode -eq 'Supervisor') {
            Write-BoundedReport (Join-Path $script:req.output 'windows-clean-install.json')
        } elseif ($Mode -eq 'Recovery') {
            Write-BoundedReport (Join-Path (Split-Path -Parent $Request) 'recovery-report.json')
        } else {
            Write-BoundedReport (Join-Path (Split-Path -Parent $Request) 'scenario-report.json')
        }
    }
}
if ($script:report.status -eq 'passed') { exit 0 }
exit 1

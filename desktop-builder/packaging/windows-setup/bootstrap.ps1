# Executed only by inbox 64-bit Windows PowerShell 5.1, without a user profile.
# Runtime licensing is presented by Microsoft's installer, never accepted here.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:MinimumRuntime = [version]'@RUNTIME_VERSION@'
$script:RuntimeUrl = '@RUNTIME_URL@'
$script:RuntimeHash = '@RUNTIME_SHA256@'
$script:BuilderHash = '@BUILDER_SHA256@'

function Assert-SupportedWindows {
    $key = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion'
    if ([IntPtr]::Size -ne 8 -or $env:PROCESSOR_ARCHITECTURE -ne 'AMD64' -or
        [int]$key.CurrentBuildNumber -lt 17763) {
        throw 'Windows 10 version 1809 or newer, or Windows 11, on an x64 PC is required.'
    }
}

function Test-RuntimeReady {
    # VC runtime installers can register x64 state in the 32-bit registry view.
    # Require a real installed product AND the actual 64-bit System32 DLLs.
    try {
        $installed = $false
        foreach ($view in @([Microsoft.Win32.RegistryView]::Registry32, [Microsoft.Win32.RegistryView]::Registry64)) {
            $base = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine, $view)
            try {
                $key = $base.OpenSubKey('SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', $false)
                if ($null -ne $key) {
                    try {
                        $version = [string]$key.GetValue('Version', '')
                        if ($key.GetValue('Installed', 0) -eq 1 -and $version -and
                            [version]($version.TrimStart('v')) -ge $script:MinimumRuntime) { $installed = $true }
                    } finally { $key.Dispose() }
                }
            } finally { $base.Dispose() }
        }
        if (-not $installed) { return $false }
        foreach ($name in @('vcruntime140.dll', 'vcruntime140_1.dll', 'msvcp140.dll')) {
            $info = [Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path ([Environment]::SystemDirectory) $name))
            $version = [version]::new($info.FileMajorPart, $info.FileMinorPart, $info.FileBuildPart, $info.FilePrivatePart)
            if ($version -lt $script:MinimumRuntime) { return $false }
        }
        return (Test-Path -LiteralPath (Join-Path ([Environment]::SystemDirectory) 'ucrtbase.dll') -PathType Leaf)
    } catch { return $false }
}

function Assert-FileHash([string]$Path, [string]$Expected) {
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Expected) {
        throw 'A downloaded or embedded file failed its SHA-256 check. Nothing was executed. Download a fresh builder from the project.'
    }
}

function Assert-MicrosoftSignature([string]$Path) {
    $sig = Get-AuthenticodeSignature -LiteralPath $Path
    if ($sig.Status -ne 'Valid' -or $sig.SignatureType -ne 'Authenticode' -or
        $null -eq $sig.SignerCertificate -or
        $sig.SignerCertificate.GetNameInfo([Security.Cryptography.X509Certificates.X509NameType]::SimpleName, $false) -cne 'Microsoft Corporation' -or
        $sig.SignerCertificate.Subject -notmatch '(?:^|,\s*)O=Microsoft Corporation(?:,|$)') {
        throw 'The Microsoft runtime signature is missing, invalid, or not Microsoft Corporation. Check your Windows trust updates and network, then retry.'
    }
}

function Confirm-RuntimeDownload {
    Add-Type -AssemblyName System.Windows.Forms
    return ([Windows.Forms.MessageBox]::Show(
        "OpenAutoLink Builder needs Microsoft Visual C++ x64 runtime @RUNTIME_VERSION@ or newer.`n`nDownload the pinned installer directly from Microsoft? Microsoft's normal installer will show its own license and may ask for administrator approval. You can decline there; the builder will not open.`n`nOnly Microsoft installs this shared runtime. Builder tools and work remain user-scoped.",
        'Microsoft runtime required', [Windows.Forms.MessageBoxButtons]::YesNo,
        [Windows.Forms.MessageBoxIcon]::Information, [Windows.Forms.MessageBoxDefaultButton]::Button2) -eq [Windows.Forms.DialogResult]::Yes)
}

function Receive-Runtime([string]$Path) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    # Default Windows proxy/certificate validation; no insecure certificate callback.
    $client = [Net.WebClient]::new()
    try { $client.DownloadFile($script:RuntimeUrl, $Path) }
    catch { throw 'Could not download the Microsoft runtime. Check your Internet/proxy connection and try again. No runtime was installed.' }
    finally { $client.Dispose() }
}

function Invoke-RuntimeInstaller([string]$Path) {
    # Explicitly interactive. No preaccepted EULA, registry consent, or automation.
    $process = Start-Process -FilePath $Path -ArgumentList @('/install', '/norestart') -Verb RunAs -Wait -PassThru
    return $process.ExitCode
}

function Ensure-Runtime {
    if (Test-RuntimeReady) { return }
    if ($env:OAL_SETUP_SMOKE_SCREENSHOT) { throw 'Native smoke requires an already-installed runtime; automated Microsoft assent is prohibited.' }
    if (-not (Confirm-RuntimeDownload)) { throw 'Microsoft runtime download declined. The builder has not been started. Open this file again when ready.' }
    $installer = Join-Path $PSScriptRoot 'VC_redist.x64.exe'
    try {
        Receive-Runtime $installer
        # Keep a read-only sharing lock through verification and process completion.
        # Prevent another process replacing/writing the verified file before launch.
        $lock = [IO.File]::Open($installer, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        try {
            Assert-FileHash $installer $script:RuntimeHash
            Assert-MicrosoftSignature $installer
            $code = Invoke-RuntimeInstaller $installer
        } finally { $lock.Dispose() }
        if ($code -in @(3010, 1641)) { throw 'Microsoft requires a Windows restart. Restart Windows, then open this same builder file again. The builder was not started.' }
        if ($code -ne 0) { throw "Microsoft runtime installation was declined or failed (code $code). The builder was not started. Open this file again to retry." }
        if (-not (Test-RuntimeReady)) { throw 'Microsoft installation completed but the required x64 runtime is still unavailable. Restart Windows and retry; the builder was not started.' }
    } finally { Remove-Item -LiteralPath $installer -Force -ErrorAction SilentlyContinue }
}

function Start-Builder {
    $builder = Join-Path $PSScriptRoot 'builder.exe'
    $lock = [IO.File]::Open($builder, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        Assert-FileHash $builder $script:BuilderHash
        if ($env:OAL_SETUP_SMOKE_SCREENSHOT) {
            $image = $env:OAL_SETUP_SMOKE_SCREENSHOT
            if (-not [IO.Path]::IsPathRooted($image) -or $image.Contains('"') -or -not $image.EndsWith('.png')) { throw 'Invalid native smoke screenshot path.' }
            $args = @('--smoke-test', '--workspace', ('"' + (Join-Path $PSScriptRoot 'smoke-workspace') + '"'), '--screenshot', ('"' + $image + '"'))
            $process = Start-Process -FilePath $builder -ArgumentList $args -WorkingDirectory $PSScriptRoot -Wait -PassThru
            if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'smoke-workspace')) {
                throw 'Read-only smoke unexpectedly created a workspace.'
            }
        } else {
            $process = Start-Process -FilePath $builder -WorkingDirectory $PSScriptRoot -Wait -PassThru
        }
        return $process.ExitCode
    } finally { $lock.Dispose() }
}

function Invoke-Bootstrap {
    Assert-SupportedWindows
    Ensure-Runtime
    return (Start-Builder)
}

# Dot-sourcing defines functions for native offline control-flow tests only.
if ($MyInvocation.InvocationName -ne '.') {
    try { exit (Invoke-Bootstrap) }
    catch {
        Add-Type -AssemblyName System.Windows.Forms
        [void][Windows.Forms.MessageBox]::Show($_.Exception.Message, 'OpenAutoLink Builder could not start',
            [Windows.Forms.MessageBoxButtons]::OK, [Windows.Forms.MessageBoxIcon]::Error)
        exit 1
    }
}

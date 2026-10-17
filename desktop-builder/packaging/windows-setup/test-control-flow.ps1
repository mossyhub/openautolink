# Offline orchestration replay only. Never invokes a real installer or accepts terms.
param([Parameter(Mandatory=$true)][string]$Bootstrap,
      [Parameter(Mandatory=$true)][string]$Case)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. $Bootstrap
$script:Events = [Collections.Generic.List[string]]::new()
$script:ReadyChecks = 0
function Assert-SupportedWindows { $script:Events.Add('platform') }
function Test-RuntimeReady {
    $script:ReadyChecks++
    return ($Case -eq 'ready' -or ($Case -eq 'success' -and $script:ReadyChecks -gt 1))
}
function Confirm-RuntimeDownload {
    $script:Events.Add('confirm')
    return ($Case -ne 'decline')
}
function Receive-Runtime([string]$Path) {
    $script:Events.Add('download')
    if ($Case -eq 'download-failure') { throw 'simulated network failure' }
    [IO.File]::WriteAllText($Path, 'non-executable control-flow fixture')
}
function Assert-FileHash([string]$Path, [string]$Expected) {
    $script:Events.Add('hash')
    if ($Case -eq 'hash') { throw 'simulated digest mismatch' }
}
function Assert-MicrosoftSignature([string]$Path) {
    $script:Events.Add('signature')
    if ($Case -eq 'signature') { throw 'simulated invalid signer' }
}
function Invoke-RuntimeInstaller([string]$Path) {
    $script:Events.Add('installer')
    switch ($Case) {
        'cancel' { return 1602 }
        'restart' { return 3010 }
        'install-failure' { return 1 }
        default { return 0 }
    }
}
function Start-Builder { $script:Events.Add('builder'); return 0 }
if ($Case -eq 'smoke') { $env:OAL_SETUP_SMOKE_SCREENSHOT = 'not-a-real-screenshot.png' }
$failed = $false
try { $null = Invoke-Bootstrap } catch { $failed = $true }
$expected = switch ($Case) {
    'ready' { 'platform,builder' }
    'smoke' { 'platform' }
    'decline' { 'platform,confirm' }
    'download-failure' { 'platform,confirm,download' }
    'hash' { 'platform,confirm,download,hash' }
    'signature' { 'platform,confirm,download,hash,signature' }
    'success' { 'platform,confirm,download,hash,signature,installer,builder' }
    default { 'platform,confirm,download,hash,signature,installer' }
}
if (($script:Events -join ',') -cne $expected) { throw 'Unexpected bootstrap call order' }
if ($failed -ne ($Case -notin @('ready', 'success'))) { throw 'Unexpected bootstrap result' }
if (Test-Path -LiteralPath (Join-Path (Split-Path -Parent $Bootstrap) 'VC_redist.x64.exe')) {
    throw 'Installer temporary file was not removed'
}
Write-Output ('CONTROL_FLOW_PASSED:' + $Case)

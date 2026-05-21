<#
.SYNOPSIS
    Put the CPC200 dongle into lean AP mode (kill CarPlay/iAP2/HiCar/BT services).

.DESCRIPTION
    Uploads scripts/cpc200/deploy/lean-ap-mode.sh and runs it on the CPC200.
    Result: hostapd + udhcpd stay up (AP is still the same SSID/password),
    but BT advertising, iAP2, HiCar, AppleCarPlay, mDNS, ARMadb-driver are
    all killed and neutralized in /tmp/bin/ so they cannot respawn until reboot.

    iPhone joining the AP after running this will NOT see the CPC200 as a
    CarPlay adapter — it joins as a plain WiFi client.

    Reboot the CPC200 to restore stock CarPlay behavior.

.PARAMETER Host
    SSH host. Defaults to 192.168.43.1 (CPC200 AP gateway).

.PARAMETER KeyPath
    SSH private key. Defaults to $env:USERPROFILE\.ssh\id_rsa_cpc200.

.EXAMPLE
    .\scripts\cpc200\Enter-LeanMode.ps1
#>
[CmdletBinding()]
param(
    [string]$RemoteHost = '192.168.43.1',
    [string]$KeyPath   = (Join-Path $env:USERPROFILE '.ssh\id_rsa_cpc200')
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$script   = Join-Path $repoRoot 'scripts\cpc200\deploy\lean-ap-mode.sh'

if (-not (Test-Path $script)) {
    throw "Missing lean-ap-mode.sh at $script"
}
if (-not (Test-Path $KeyPath)) {
    throw "Missing SSH key at $KeyPath"
}

Write-Host "[Enter-LeanMode] uploading lean-ap-mode.sh to $RemoteHost" -ForegroundColor Cyan
& scp -i $KeyPath -o ConnectTimeout=10 $script "root@${RemoteHost}:/tmp/lean-ap-mode.sh"
if ($LASTEXITCODE -ne 0) { throw "scp failed (exit $LASTEXITCODE)" }

Write-Host "[Enter-LeanMode] running on $RemoteHost" -ForegroundColor Cyan
& ssh -i $KeyPath -o ConnectTimeout=10 "root@${RemoteHost}" 'sh /tmp/lean-ap-mode.sh'
if ($LASTEXITCODE -ne 0) { throw "remote script failed (exit $LASTEXITCODE)" }

Write-Host "[Enter-LeanMode] done. Reboot CPC200 to restore stock behavior." -ForegroundColor Green

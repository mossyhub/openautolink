<#
.SYNOPSIS
  Flash the HFP helper firmware to a connected TinyS3.

.PARAMETER Port
  COM port the TinyS3 enumerates as. Find it via Device Manager → Ports.
  Some TinyS3 boards expose two COM ports (one for ROM, one for CDC); use
  the one that appears when the board is in bootloader mode (hold BOOT,
  press RESET, release BOOT).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$Port,
    [int]$BaudRate = 921600
)

$ErrorActionPreference = 'Stop'

if (-not $env:IDF_PATH) {
    Write-Host "ERROR: `$env:IDF_PATH is not set. Open 'ESP-IDF PowerShell' first." -ForegroundColor Red
    exit 1
}

$here = Split-Path -Parent $PSCommandPath
$firmware = Join-Path (Split-Path -Parent $here) 'firmware'

Push-Location $firmware
try {
    Write-Host "[flash] idf.py -p $Port -b $BaudRate flash" -ForegroundColor Cyan
    & idf.py -p $Port -b $BaudRate flash
    if ($LASTEXITCODE -ne 0) { throw "idf.py flash exited $LASTEXITCODE" }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Flashed. Press RESET on the TinyS3 to run. To watch serial logs:" -ForegroundColor Green
Write-Host "  .\hfp_helper\scripts\monitor.ps1 $Port" -ForegroundColor Green

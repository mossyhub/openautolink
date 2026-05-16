<#
.SYNOPSIS
  Open the ESP-IDF serial monitor for the HFP helper.

.PARAMETER Port
  COM port the TinyS3 enumerates as.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$Port
)

$ErrorActionPreference = 'Stop'

if (-not $env:IDF_PATH) {
    Write-Host "ERROR: `$env:IDF_PATH is not set. Open 'ESP-IDF PowerShell' first." -ForegroundColor Red
    exit 1
}

$here = Split-Path -Parent $PSCommandPath
$firmware = Join-Path (Split-Path -Parent $here) 'firmware'

Write-Host "[monitor] Ctrl+] to exit." -ForegroundColor Cyan
Push-Location $firmware
try {
    & idf.py -p $Port monitor
} finally {
    Pop-Location
}

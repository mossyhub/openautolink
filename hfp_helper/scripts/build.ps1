<#
.SYNOPSIS
  Build the HFP helper firmware for ESP32-S3 (TinyS3).

.DESCRIPTION
  Wraps `idf.py build`. Requires ESP-IDF to be installed and
  $env:IDF_PATH to be set (the official Windows ESP-IDF installer
  ships a "ESP-IDF X.Y PowerShell" Start Menu entry that does this).

  Run this from the repo root or from inside hfp_helper/.
#>
[CmdletBinding()]
param(
    [switch]$Clean,
    [string]$Target = "esp32s3"
)

$ErrorActionPreference = 'Stop'

if (-not $env:IDF_PATH) {
    Write-Host "ERROR: `$env:IDF_PATH is not set." -ForegroundColor Red
    Write-Host "Open 'ESP-IDF X.Y PowerShell' from the Start Menu, or run:" -ForegroundColor Yellow
    Write-Host "  & `$env:USERPROFILE\esp\esp-idf\export.ps1" -ForegroundColor Yellow
    exit 1
}

$here = Split-Path -Parent $PSCommandPath
$firmware = Join-Path (Split-Path -Parent $here) 'firmware'

Push-Location $firmware
try {
    if ($Clean) {
        Write-Host "[clean] idf.py fullclean" -ForegroundColor Cyan
        & idf.py fullclean
    }
    Write-Host "[target] $Target" -ForegroundColor Cyan
    & idf.py set-target $Target
    Write-Host "[build] idf.py build" -ForegroundColor Cyan
    & idf.py build
    if ($LASTEXITCODE -ne 0) { throw "idf.py build exited $LASTEXITCODE" }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Build OK. To flash:" -ForegroundColor Green
Write-Host "  .\hfp_helper\scripts\flash.ps1 COM5" -ForegroundColor Green

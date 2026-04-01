param(
    [string]$SbcHost = "192.168.137.197",
    [string]$SbcUser = "khadas",
    [switch]$Build,
    [switch]$Clean,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$BinaryPath = Join-Path $RepoRoot "build-bridge-arm64\openautolink-headless-stripped"

Write-Host "=== OpenAutoLink Bridge Deploy ===" -ForegroundColor Cyan
Write-Host ""

# Step 1: Build in WSL (unless skipped)
if (-not $SkipBuild) {
    Write-Host ">>> Building in WSL..." -ForegroundColor Yellow
    $wslRepo = "/mnt/" + ($RepoRoot -replace '\\','/' -replace '^(\w):','$1').ToLower()
    $buildCmd = "cd '$wslRepo' && bash scripts/build-bridge-wsl.sh"
    if ($Clean) { $buildCmd += " clean" }

    wsl -d Ubuntu-24.04 -- bash -c $buildCmd
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: WSL build failed." -ForegroundColor Red
        exit 1
    }
    Write-Host ""
}

# Check binary exists
if (-not (Test-Path $BinaryPath)) {
    Write-Host "ERROR: Binary not found at $BinaryPath" -ForegroundColor Red
    Write-Host "Run with -Build or build manually first." -ForegroundColor Red
    exit 1
}

$size = (Get-Item $BinaryPath).Length / 1MB
Write-Host ">>> Binary: $([math]::Round($size, 1)) MB" -ForegroundColor Green

# Step 2: Stop service, SCP directly to /opt, restart
Write-Host ">>> Deploying to ${SbcUser}@${SbcHost}..." -ForegroundColor Yellow
ssh "${SbcUser}@${SbcHost}" "sudo systemctl stop openautolink.service 2>/dev/null; sleep 1"
if ($LASTEXITCODE -ne 0) {
    Write-Host "WARNING: Could not stop service (may not be running)." -ForegroundColor Yellow
}

scp $BinaryPath "${SbcUser}@${SbcHost}:/opt/openautolink/bin/openautolink-headless"
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: SCP failed. Is the SBC reachable at ${SbcHost}?" -ForegroundColor Red
    exit 1
}

# Step 3: Set permissions and restart service
Write-Host ">>> Restarting service..." -ForegroundColor Yellow
ssh "${SbcUser}@${SbcHost}" @"
    sudo chmod +x /opt/openautolink/bin/openautolink-headless
    sudo systemctl start openautolink.service
    echo '--- Service status ---'
    systemctl is-active openautolink.service
"@

if ($LASTEXITCODE -ne 0) {
    Write-Host "WARNING: SSH command had issues. Check service status manually." -ForegroundColor Yellow
} else {
    Write-Host ""
    Write-Host "=== Deployed successfully ===" -ForegroundColor Green
}

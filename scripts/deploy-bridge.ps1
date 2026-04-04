param(
    [string]$SbcHost = "192.168.137.2",
    [string]$SbcUser = "openautolink",
    [switch]$Clean,
    [switch]$SkipBuild,
    [switch]$Full
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$BinaryPath = Join-Path $RepoRoot "build-bridge-arm64\openautolink-headless-stripped"
$SbcDir = Join-Path $RepoRoot "bridge\sbc"
$Target = "${SbcUser}@${SbcHost}"

function Invoke-Ssh {
    param([string]$Command)
    $oldEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    ssh $Target $Command
    $ErrorActionPreference = $oldEAP
    if ($LASTEXITCODE -ne 0) { throw "SSH command failed: $Command" }
}

function Invoke-Scp {
    param([string]$Local, [string]$Remote)
    scp $Local "${Target}:${Remote}"
    if ($LASTEXITCODE -ne 0) { throw "SCP failed: $Local -> $Remote" }
}

Write-Host "=== OpenAutoLink Bridge Deploy ===" -ForegroundColor Cyan
Write-Host "  Target: $Target" -ForegroundColor DarkGray
Write-Host "  Mode:   $(if ($Full) { 'FULL (binary + scripts + services + config)' } else { 'binary only' })" -ForegroundColor DarkGray
Write-Host ""

# ── Step 1: Build in WSL (unless skipped) ─────────────────────────────
if (-not $SkipBuild) {
    Write-Host ">>> Building in WSL..." -ForegroundColor Yellow
    $wslRepo = "/mnt/" + ($RepoRoot -replace '\\','/' -replace '^(\w):','$1').ToLower()
    $buildCmd = "cd '$wslRepo' && tr -d '\r' < scripts/build-bridge-wsl.sh | REPO_ROOT='$wslRepo' bash -s -- "
    if ($Clean) { $buildCmd += "clean" }

    $oldEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    wsl -d Ubuntu-24.04 -- bash -c $buildCmd
    $ErrorActionPreference = $oldEAP
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: WSL build failed." -ForegroundColor Red
        exit 1
    }
    Write-Host ""
}

# Check binary exists
if (-not (Test-Path $BinaryPath)) {
    Write-Host "ERROR: Binary not found at $BinaryPath" -ForegroundColor Red
    Write-Host "Run without -SkipBuild to build first." -ForegroundColor Red
    exit 1
}

$size = (Get-Item $BinaryPath).Length / 1MB
Write-Host ">>> Binary: $([math]::Round($size, 1)) MB" -ForegroundColor Green

# ── Step 2: Stop existing service ─────────────────────────────────────
Write-Host ">>> Stopping service..." -ForegroundColor Yellow
ssh $Target "sudo systemctl stop openautolink.service 2>/dev/null; true"

# ── Step 3: Full provisioning (only with -Full) ──────────────────────
if ($Full) {
    Write-Host ">>> Installing system packages..." -ForegroundColor Yellow
    Invoke-Ssh "sudo apt-get update -qq && sudo apt-get install -y -qq hostapd dnsmasq bluez libbluetooth3 python3-dbus python3-gi avahi-daemon avahi-utils openssl"
    Write-Host ""

    Write-Host ">>> Creating directory structure..." -ForegroundColor Yellow
    Invoke-Ssh "sudo mkdir -p /opt/openautolink/bin /opt/openautolink/scripts /etc/avahi/services && sudo rm -rf /tmp/oal-deploy && mkdir -p /tmp/oal-deploy"

    # SCP everything to a staging dir (lance can write to /tmp), then sudo mv
    Write-Host ">>> Deploying scripts..." -ForegroundColor Yellow
    $filesToDeploy = @(
        @{ Local = "$SbcDir\run-openautolink.sh";  Final = "/opt/openautolink/run-openautolink.sh" }
        @{ Local = "$SbcDir\setup-network.sh";     Final = "/opt/openautolink/setup-network.sh" }
        @{ Local = "$SbcDir\start-wireless.sh";    Final = "/opt/openautolink/start-wireless.sh" }
        @{ Local = "$SbcDir\stop-wireless.sh";     Final = "/opt/openautolink/stop-wireless.sh" }
    )

    # BT script
    $btScript = Join-Path $RepoRoot "bridge\openautolink\scripts\aa_bt_all.py"
    if (Test-Path $btScript) {
        $filesToDeploy += @{ Local = $btScript; Final = "/opt/openautolink/scripts/aa_bt_all.py" }
    }

    # Systemd service files
    foreach ($svc in @("openautolink.service", "openautolink-network.service", "openautolink-wireless.service", "openautolink-bt.service")) {
        $svcPath = Join-Path $SbcDir $svc
        if (Test-Path $svcPath) {
            $filesToDeploy += @{ Local = $svcPath; Final = "/etc/systemd/system/$svc" }
        }
    }

    # Avahi mDNS service
    $avahiSvc = Join-Path $RepoRoot "bridge\openautolink\headless\avahi\openautolink.service"
    if (Test-Path $avahiSvc) {
        $filesToDeploy += @{ Local = $avahiSvc; Final = "/etc/avahi/services/openautolink.service" }
    }

    # Env config (only if missing on SBC)
    $envExists = ssh $Target "test -f /etc/openautolink.env && echo 'exists' || echo 'missing'"
    if ($envExists.Trim() -eq "missing") {
        $filesToDeploy += @{ Local = "$SbcDir\openautolink.env"; Final = "/etc/openautolink.env" }
    } else {
        Write-Host "  /etc/openautolink.env (already exists - preserved)" -ForegroundColor DarkGray
    }

    # SCP all files to staging dir
    foreach ($f in $filesToDeploy) {
        $stageName = ($f.Final -replace '[/\\]', '_').TrimStart('_')
        scp $f.Local "${Target}:/tmp/oal-deploy/$stageName"
        if ($LASTEXITCODE -ne 0) { throw "SCP failed: $($f.Local)" }
    }

    # Move from staging to final locations, fix CRLF, set permissions
    $moveCommands = ($filesToDeploy | ForEach-Object {
        $stageName = ($_.Final -replace '[/\\]', '_').TrimStart('_')
        "sudo cp /tmp/oal-deploy/$stageName '$($_.Final)'"
    }) -join "; "

    Invoke-Ssh "$moveCommands"

    Write-Host ">>> Fixing line endings and permissions..." -ForegroundColor Yellow
    Invoke-Ssh "sudo sed -i 's/\r$//' /opt/openautolink/*.sh /opt/openautolink/scripts/*.py /etc/systemd/system/openautolink*.service /etc/openautolink.env 2>/dev/null; sudo chmod +x /opt/openautolink/*.sh; sudo rm -rf /tmp/oal-deploy"

    foreach ($f in $filesToDeploy) {
        Write-Host "  $($f.Final)" -ForegroundColor DarkGray
    }

    # Note: aasdk has embedded TLS certs (JVC Kenwood AA cert) that work with all phones.
    # Do NOT generate custom certs — custom self-signed certs cause SSL handshake failures.
    # If /etc/aasdk/ exists with bad certs, remove it so aasdk falls back to embedded ones.

    # Enable services
    Write-Host ">>> Enabling systemd services..." -ForegroundColor Yellow
    Invoke-Ssh "sudo systemctl daemon-reload && sudo systemctl enable openautolink-network openautolink openautolink-wireless openautolink-bt 2>/dev/null"
    Write-Host ""
}

# ── Step 4: Deploy binary ─────────────────────────────────────────────
Write-Host ">>> Deploying binary..." -ForegroundColor Yellow
# Ensure bin dir exists (for non-Full deploys after a manual mkdir)
ssh $Target "sudo mkdir -p /opt/openautolink/bin" 2>$null
# SCP to /tmp then sudo mv (lance user can't write to /opt directly)
scp $BinaryPath "${Target}:/tmp/openautolink-headless"
if ($LASTEXITCODE -ne 0) { throw "SCP binary failed" }
Invoke-Ssh "sudo mv /tmp/openautolink-headless /opt/openautolink/bin/openautolink-headless && sudo chmod +x /opt/openautolink/bin/openautolink-headless"

# ── Step 5: Start service ─────────────────────────────────────────────
Write-Host ">>> Starting service..." -ForegroundColor Yellow
Invoke-Ssh "sudo systemctl start openautolink.service && echo '--- Service status ---' && systemctl is-active openautolink.service"

Write-Host ""
Write-Host "=== Deployed successfully ===" -ForegroundColor Green
if ($Full) {
    Write-Host "  Full provisioning complete. Services enabled for auto-start on boot." -ForegroundColor DarkGray
}

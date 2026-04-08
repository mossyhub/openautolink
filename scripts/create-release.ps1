<#
.SYNOPSIS
    Creates a GitHub Release with the bridge binary attached.

.DESCRIPTION
    - Reads version from secrets/version.properties
    - Creates a GitHub release with tag v<versionName>
    - Uploads the bridge binary from build-bridge-arm64/ (as openautolink-headless)
    - The AAB is NOT uploaded — it goes to Google Play Console separately
    - Requires: gh CLI authenticated

.EXAMPLE
    # After building:
    bash scripts/build-bridge-wsl.sh
    .\scripts\create-release.ps1
#>
param(
    [string]$Notes = "",
    [switch]$Draft
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

# Read version
$versionFile = Join-Path $repoRoot 'secrets\version.properties'
if (-not (Test-Path $versionFile)) {
    throw "Version file not found: $versionFile. Run bundle-release.ps1 first."
}

$version = @{}
Get-Content $versionFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*?)\s*$') {
        $version[$Matches[1]] = $Matches[2]
    }
}

$tag = "v$($version['versionName'])"
Write-Host "[release] Creating release: $tag"

# Find bridge binary — uploaded as 'openautolink-headless' (the name the app downloads)
$bridgeBinary = Join-Path $repoRoot 'build-bridge-arm64\openautolink-headless-stripped'
$bridgeAsset = Join-Path $repoRoot 'build-bridge-arm64\openautolink-headless'
if (Test-Path $bridgeBinary) {
    # Copy stripped binary with the release asset name the app expects
    Copy-Item -Path $bridgeBinary -Destination $bridgeAsset -Force
    Write-Host "[release] Bridge binary: $bridgeAsset"
} else {
    throw "No bridge binary found at $bridgeBinary - run build-bridge-wsl.sh first"
}

# Create release
$ghArgs = @('release', 'create', $tag, '--title', $tag)
if ($Draft) { $ghArgs += '--draft' }
if ($Notes) {
    $ghArgs += '--notes'
    $ghArgs += $Notes
} else {
    $ghArgs += '--generate-notes'
}
$ghArgs += $bridgeAsset

Write-Host "[release] gh $($ghArgs -join ' ')"
& gh @ghArgs

Write-Host ""
Write-Host "[release] Release $tag created with bridge binary"

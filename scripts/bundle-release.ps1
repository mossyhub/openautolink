<#
.SYNOPSIS
    Builds a signed release AAB with automatic version increment.

.DESCRIPTION
    - Reads version from secrets/version.properties (per-clone, gitignored).
      Creates it with defaults if missing.
    - Increments versionCode and patches versionName on each build.
    - Uses saved DPAPI credentials (from save-signing-credentials.ps1) if
      available, otherwise falls back to interactive password prompts.

.EXAMPLE
    # First time - save credentials (one-time):
    .\scripts\save-signing-credentials.ps1

    # Then build (no prompts, auto-increments version):
    .\scripts\bundle-release.ps1

    # Skip version increment:
    .\scripts\bundle-release.ps1 -NoIncrement
#>
param(
    [string]$KeystorePath = '.\secrets\upload-key.jks',
    [string]$Alias = 'upload',
    [switch]$NoIncrement
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$credentialsPath = Join-Path $repoRoot 'secrets\signing-credentials.xml'
$versionFile = Join-Path $repoRoot 'secrets\version.properties'

# --- Version management ---

function Read-VersionProperties {
    param([string]$Path)
    $props = @{}
    if (Test-Path $Path) {
        Get-Content $Path | ForEach-Object {
            if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*?)\s*$') {
                $props[$Matches[1]] = $Matches[2]
            }
        }
    }
    return $props
}

function Write-VersionProperties {
    param([string]$Path, [hashtable]$Props)
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $lines = @(
        "# Auto-managed by bundle-release.ps1 - do not edit during builds"
        "# This file is per-clone (gitignored via secrets/) so each contributor"
        "# tracks their own version independently."
        "versionCode=$($Props['versionCode'])"
        "versionName=$($Props['versionName'])"
    )
    Set-Content -Path $Path -Value ($lines -join "`n") -Encoding UTF8 -NoNewline
}

# Read or initialize
$version = Read-VersionProperties -Path $versionFile
if (-not $version['versionCode']) { $version['versionCode'] = '1' }
if (-not $version['versionName']) { $version['versionName'] = '0.1.0' }

if (-not $NoIncrement) {
    # Increment versionCode
    $version['versionCode'] = [string]([int]$version['versionCode'] + 1)

    # Bump patch in versionName (e.g. 0.1.2 -> 0.1.3)
    $parts = $version['versionName'] -split '\.'
    if ($parts.Count -ge 3) {
        $parts[2] = [string]([int]$parts[2] + 1)
    }
    $version['versionName'] = $parts -join '.'

    Write-VersionProperties -Path $versionFile -Props $version
    Write-Host "[bundle] Version incremented: versionCode=$($version['versionCode']), versionName=$($version['versionName'])"
}
else {
    Write-Host "[bundle] Version (no increment): versionCode=$($version['versionCode']), versionName=$($version['versionName'])"
}

# --- Credentials ---

function ConvertTo-PlainText {
    param([Parameter(Mandatory)][Security.SecureString]$SecureString)
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureString)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally {
        if ($bstr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        }
    }
}

function Resolve-Keystore {
    param([string]$Path, [string]$Base)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return [System.IO.Path]::GetFullPath((Join-Path $Base $Path))
}

$resolvedKeystorePath = Resolve-Keystore -Path $KeystorePath -Base $repoRoot
if (-not (Test-Path $resolvedKeystorePath)) {
    throw "Keystore not found: $resolvedKeystorePath"
}

$buildScript = Join-Path $PSScriptRoot 'android\build-gradle.ps1'

$versionArgs = @(
    "-PoalVersionCode=$($version['versionCode'])"
    "-PoalVersionName=$($version['versionName'])"
)

if (Test-Path $credentialsPath) {
    Write-Host '[bundle] Using saved DPAPI credentials from secrets/signing-credentials.xml'
    $cred = Import-Clixml -Path $credentialsPath

    $storePassword = ConvertTo-PlainText -SecureString $cred.StorePassword
    $keyPassword   = ConvertTo-PlainText -SecureString $cred.KeyPassword
    $resolvedAlias = if ($cred.KeyAlias) { $cred.KeyAlias } else { $Alias }

    try {
        & $buildScript `
            -ProjectRoot $repoRoot `
            -Tasks 'bundleRelease' `
            -StoreFile $resolvedKeystorePath `
            -StorePassword $storePassword `
            -KeyAlias $resolvedAlias `
            -KeyPassword $keyPassword `
            -AdditionalGradleArgs $versionArgs
    }
    finally {
        $storePassword = $null
        $keyPassword = $null
    }
}
else {
    Write-Host '[bundle] No saved credentials found - prompting interactively.'
    Write-Host '[bundle] Run save-signing-credentials.ps1 to save credentials for next time.'
    Write-Host ''

    $sharedScript = Join-Path $PSScriptRoot 'android\build-bundle.ps1'

    & $sharedScript `
        -ProjectRoot $repoRoot `
        -KeystorePath $KeystorePath `
        -Alias $Alias `
        -AdditionalGradleArgs $versionArgs
}

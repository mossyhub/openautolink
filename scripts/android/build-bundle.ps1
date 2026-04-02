param(
    [string]$ProjectRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$KeystorePath = '.\secrets\upload-key.jks',
    [string]$Alias = 'upload',
    [string]$Task = 'bundleRelease',
    [string]$BuildScriptPath = (Join-Path $PSScriptRoot 'build-gradle.ps1'),
    [string[]]$AdditionalGradleArgs = @()
)

$ErrorActionPreference = 'Stop'

function ConvertTo-PlainText {
    param(
        [Parameter(Mandatory = $true)]
        [Security.SecureString]$SecureString
    )

    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureString)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    }
    finally {
        if ($bstr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        }
    }
}

function Resolve-AbsolutePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$BasePath
    )

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    }

    return [System.IO.Path]::GetFullPath((Join-Path $BasePath $Path))
}

$resolvedProjectRoot = Resolve-AbsolutePath -Path $ProjectRoot -BasePath (Get-Location).Path
$resolvedKeystorePath = Resolve-AbsolutePath -Path $KeystorePath -BasePath $resolvedProjectRoot
if (-not (Test-Path $resolvedKeystorePath)) {
    throw "Keystore not found: $resolvedKeystorePath"
}

$resolvedBuildScriptPath = Resolve-AbsolutePath -Path $BuildScriptPath -BasePath $resolvedProjectRoot
if (-not (Test-Path $resolvedBuildScriptPath)) {
    throw "Build script not found: $resolvedBuildScriptPath"
}

$storePasswordSecure = Read-Host 'Enter keystore password' -AsSecureString
$samePassword = Read-Host 'Use the same password for the key? (Y/n)'

$storePassword = ConvertTo-PlainText -SecureString $storePasswordSecure
if ([string]::IsNullOrWhiteSpace($samePassword) -or $samePassword -match '^[Yy]') {
    $keyPassword = $storePassword
}
else {
    $keyPasswordSecure = Read-Host 'Enter key password' -AsSecureString
    $keyPassword = ConvertTo-PlainText -SecureString $keyPasswordSecure
}

try {
    & $resolvedBuildScriptPath `
        -ProjectRoot $resolvedProjectRoot `
        -Tasks $Task `
        -StoreFile $resolvedKeystorePath `
        -StorePassword $storePassword `
        -KeyAlias $Alias `
        -KeyPassword $keyPassword `
        -AdditionalGradleArgs $AdditionalGradleArgs
}
finally {
    $storePassword = $null
    $keyPassword = $null
}
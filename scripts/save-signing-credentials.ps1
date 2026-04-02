<#
.SYNOPSIS
    Saves signing credentials encrypted with Windows DPAPI for automated AAB builds.

.DESCRIPTION
    Prompts for keystore and key passwords, then saves them to
    secrets/signing-credentials.xml encrypted with the current Windows user's
    DPAPI key. Only this user on this machine can decrypt the file — it is safe
    to leave on disk and is already covered by .gitignore (secrets/).

    After running this once, bundle-release.ps1 will use the saved
    credentials automatically without prompting.

.EXAMPLE
    .\scripts\save-signing-credentials.ps1
    .\scripts\save-signing-credentials.ps1 -Alias mykey
#>
param(
    [string]$OutputPath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'secrets\signing-credentials.xml'),
    [string]$Alias = 'upload'
)

$ErrorActionPreference = 'Stop'

$outputDir = Split-Path -Parent $OutputPath
if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
}

Write-Host ''
Write-Host 'This will save your signing credentials encrypted with Windows DPAPI.'
Write-Host 'Only YOUR Windows account on THIS machine can decrypt them.'
Write-Host ''

$storePassword = Read-Host 'Enter keystore (store) password' -AsSecureString
$samePassword = Read-Host 'Use the same password for the key? (Y/n)'

if ([string]::IsNullOrWhiteSpace($samePassword) -or $samePassword -match '^[Yy]') {
    $keyPassword = $storePassword.Copy()
}
else {
    $keyPassword = Read-Host 'Enter key password' -AsSecureString
}

$credentials = [PSCustomObject]@{
    StorePassword = $storePassword
    KeyPassword   = $keyPassword
    KeyAlias      = $Alias
}

$credentials | Export-Clixml -Path $OutputPath

Write-Host ''
Write-Host "Credentials saved to: $OutputPath"
Write-Host 'This file is DPAPI-encrypted and only usable by your Windows account.'
Write-Host 'It is covered by .gitignore (secrets/) and will NOT be committed.'
Write-Host ''
Write-Host 'You can now run bundle-release.ps1 without password prompts.'

param(
    [string]$ProjectRoot,
    [string]$Task,
    [string]$JavaHome = 'C:\Program Files\Android\Android Studio\jbr',
    [string]$AndroidSdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$GradleUserHome,
    [string]$KeystorePath = '.\secrets\upload-key.jks',
    [string]$KeyAlias = 'upload'
)

$ErrorActionPreference = 'Stop'

function Resolve-AbsolutePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [string]$BasePath
    )

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    }

    if ([string]::IsNullOrWhiteSpace($BasePath)) {
        return $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    }

    return [System.IO.Path]::GetFullPath((Join-Path $BasePath $Path))
}

function Prompt-WithDefault {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prompt,
        [string]$DefaultValue
    )

    if ([string]::IsNullOrWhiteSpace($DefaultValue)) {
        $response = Read-Host $Prompt
    }
    else {
        $response = Read-Host "$Prompt [$DefaultValue]"
    }

    if ([string]::IsNullOrWhiteSpace($response)) {
        return $DefaultValue
    }

    return $response
}

function Select-Task {
    param(
        [string]$CurrentTask
    )

    if (-not [string]::IsNullOrWhiteSpace($CurrentTask)) {
        return $CurrentTask
    }

    Write-Host 'Select an Android build task:'
    Write-Host '  1. assembleDebug'
    Write-Host '  2. assembleRelease'
    Write-Host '  3. bundleRelease'
    Write-Host '  4. custom task'

    $selection = Prompt-WithDefault -Prompt 'Choice' -DefaultValue '1'
    switch ($selection) {
        '1' { return 'assembleDebug' }
        '2' { return 'assembleRelease' }
        '3' { return 'bundleRelease' }
        '4' { return (Prompt-WithDefault -Prompt 'Enter exact Gradle task' -DefaultValue ':app:assembleDebug') }
        default { throw "Unknown task selection: $selection" }
    }
}

$workspaceRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$projectBasePath = if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $workspaceRoot } else { (Get-Location).Path }
$projectRootDefault = if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $workspaceRoot } else { $ProjectRoot }
$selectedProjectRoot = Prompt-WithDefault -Prompt 'Android project root' -DefaultValue $projectRootDefault
$resolvedProjectRoot = Resolve-AbsolutePath -Path $selectedProjectRoot -BasePath $projectBasePath

if (-not (Test-Path $resolvedProjectRoot)) {
    throw "Project root does not exist: $resolvedProjectRoot"
}

if (-not (Test-Path (Join-Path $resolvedProjectRoot 'gradlew.bat'))) {
    throw "gradlew.bat was not found in: $resolvedProjectRoot"
}

$selectedTask = Select-Task -CurrentTask $Task
$buildScript = Join-Path $PSScriptRoot 'build-gradle.ps1'
$bundleScript = Join-Path $PSScriptRoot 'build-bundle.ps1'

Write-Host "[invoke-build] Project root: $resolvedProjectRoot"
Write-Host "[invoke-build] Task: $selectedTask"

if ($selectedTask -eq 'bundleRelease') {
    $selectedKeystorePath = Prompt-WithDefault -Prompt 'Keystore path' -DefaultValue $KeystorePath
    $selectedKeyAlias = Prompt-WithDefault -Prompt 'Key alias' -DefaultValue $KeyAlias

    & $bundleScript `
        -ProjectRoot $resolvedProjectRoot `
        -KeystorePath $selectedKeystorePath `
        -Alias $selectedKeyAlias `
        -Task $selectedTask
    return
}

& $buildScript `
    -ProjectRoot $resolvedProjectRoot `
    -Tasks $selectedTask `
    -JavaHome $JavaHome `
    -AndroidSdkRoot $AndroidSdkRoot `
    -GradleUserHome $GradleUserHome
[CmdletBinding()]
param(
    [string]$AndroidSdkRoot = $env:ANDROID_HOME,
    [string]$OutputDirectory,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

function Find-SdkTool {
    param(
        [string]$SdkRoot,
        [string]$Name
    )

    $tool = Get-ChildItem -LiteralPath (Join-Path $SdkRoot "build-tools") -Recurse -Filter $Name -File |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if ($null -eq $tool) { throw "$Name was not found under $SdkRoot\\build-tools" }
    return $tool.FullName
}

$workspace = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    $AndroidSdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
if (-not (Test-Path -LiteralPath $AndroidSdkRoot -PathType Container)) {
    throw "Android SDK was not found: $AndroidSdkRoot"
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssfffZ")
    $OutputDirectory = Join-Path $workspace "scratch\release\reviewer-$timestamp"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $workspace $OutputDirectory
}
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)

if (Test-Path -LiteralPath $OutputDirectory) {
    if (@(Get-ChildItem -LiteralPath $OutputDirectory -Force | Select-Object -First 1).Count -gt 0) {
        throw "OutputDirectory must be empty to avoid overwriting an existing release artifact: $OutputDirectory"
    }
} else {
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
}

if (-not $SkipBuild) {
    $buildScript = Join-Path $PSScriptRoot "build-android.ps1"
    & $buildScript -Task assembleReviewer -AndroidSdkRoot $AndroidSdkRoot
}

$apk = Join-Path $workspace "android\app\build\outputs\apk\reviewer\app-reviewer.apk"
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
    throw "Reviewer APK was not found: $apk. Run assembleReviewer first or omit -SkipBuild."
}

$apksigner = Find-SdkTool -SdkRoot $AndroidSdkRoot -Name "apksigner.bat"
$aapt2 = Find-SdkTool -SdkRoot $AndroidSdkRoot -Name "aapt2.exe"
$jar = Join-Path $env:JAVA_HOME "bin\jar.exe"
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "jar.exe was not found under JAVA_HOME: $jar"
}

$signature = @(& $apksigner verify --verbose --print-certs $apk 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "Reviewer APK signature verification failed: $($signature -join [Environment]::NewLine)"
}
if (-not ($signature -match "Signer #1 certificate DN: CN=Android Debug")) {
    throw "Reviewer APK is not signed with the expected Android Debug certificate"
}

$badging = @(& $aapt2 dump badging $apk)
$badgingText = $badging -join [Environment]::NewLine
$packageMatch = [regex]::Match($badgingText, "package: name='([^']+)'[^\r\n]*versionName='([^']+)'")
if (-not $packageMatch.Success) {
    throw "Unable to read package name and version from reviewer APK"
}
$packageName = $packageMatch.Groups[1].Value
$versionName = $packageMatch.Groups[2].Value

$nativeLibraries = @(& $jar tf $apk | Where-Object { $_ -like "lib/*/*.so" })
$abis = @($nativeLibraries | ForEach-Object { ($_ -split "/")[1] } | Sort-Object -Unique)
if ($abis.Count -ne 1 -or $abis[0] -ne "arm64-v8a") {
    throw "Reviewer APK must contain only arm64-v8a native libraries; found: $($abis -join ', ')"
}

$artifactName = "Arm-Mobile-AI-Optimization-Workbench-v$versionName-arm64-v8a.apk"
$artifactPath = Join-Path $OutputDirectory $artifactName
if (Test-Path -LiteralPath $artifactPath) {
    throw "Refusing to overwrite existing artifact: $artifactPath"
}
Copy-Item -LiteralPath $apk -Destination $artifactPath
$artifact = Get-Item -LiteralPath $artifactPath
$sha256 = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()

$shaPath = Join-Path $OutputDirectory "SHA256SUMS.txt"
$manifestPath = Join-Path $OutputDirectory "reviewer-artifact-manifest.json"
"$sha256  $artifactName" | Set-Content -LiteralPath $shaPath -Encoding ascii -NoNewline
([ordered]@{
    schema = "arm-mobile-ai-reviewer-artifact/v1"
    generated_utc = (Get-Date).ToUniversalTime().ToString("o")
    package = $packageName
    version_name = $versionName
    signing = "android-debug"
    abi = $abis
    source_apk = $apk
    artifact = $artifactName
    bytes = $artifact.Length
    sha256 = $sha256
} | ConvertTo-Json -Depth 4) | Set-Content -LiteralPath $manifestPath -Encoding utf8

Write-Output "APK: $artifactPath"
Write-Output "SHA256SUMS: $shaPath"
Write-Output "Manifest: $manifestPath"

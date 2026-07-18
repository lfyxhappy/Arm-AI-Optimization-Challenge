param(
    [ValidateSet("cpu", "snapdragon-opencl", "snapdragon-htp")]
    [string]$Backend = "cpu",
    [ValidateSet("assembleDebug", "assembleRelease", "testDebugUnitTest")]
    [string]$Task = "assembleDebug",
    [string]$AndroidSdkRoot = $env:ANDROID_HOME
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    $AndroidSdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
if (-not (Test-Path (Join-Path $AndroidSdkRoot "platform-tools\adb.exe"))) {
    throw "Android SDK was not found: $AndroidSdkRoot"
}

$gitBash = "C:\Program Files\Git\bin\bash.exe"
if (-not (Test-Path $gitBash)) {
    throw "Git Bash was not found: $gitBash"
}

$driveLetter = $null
foreach ($candidate in @("Z", "Y", "X", "W", "V", "U", "T", "S", "R", "Q")) {
    if (-not (Get-PSDrive -Name $candidate -ErrorAction SilentlyContinue)) {
        $driveLetter = $candidate
        break
    }
}
if ($null -eq $driveLetter) {
    throw "No free drive letter is available for the temporary Gradle mapping."
}

$drive = "$driveLetter`:"
$previousAndroidHome = $env:ANDROID_HOME
$mapped = $false
try {
    & subst.exe $drive $root
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to map $drive to $root"
    }
    $mapped = $true
    $env:ANDROID_HOME = $AndroidSdkRoot

    # Gradle's Windows test worker can fail to load classes from non-ASCII paths.
    $bashCommand = "cd '/$($driveLetter.ToLowerInvariant())/android' && ./gradlew :app:$Task -PaichatBackend=$Backend --console=plain"
    & $gitBash -lc $bashCommand
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle task failed: :app:$Task (backend=$Backend)"
    }
} finally {
    if ($null -eq $previousAndroidHome) {
        Remove-Item Env:ANDROID_HOME -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_HOME = $previousAndroidHome
    }
    if ($mapped) {
        & subst.exe $drive /D | Out-Null
    }
}

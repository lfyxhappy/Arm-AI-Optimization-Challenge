param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$Package = "com.lfyxhappy.armai",
    [string]$OutputRoot = (Join-Path $PSScriptRoot "..\benchmarks\output"),
    [switch]$SkipComparison
)

$ErrorActionPreference = "Stop"

function Find-Adb {
    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    $candidate = Join-Path $sdkRoot "platform-tools\adb.exe"
    if (-not (Test-Path $candidate)) {
        throw "adb.exe was not found at $candidate"
    }
    return (Resolve-Path $candidate).Path
}

function Copy-PrivateFile {
    param(
        [string]$Adb,
        [string]$DeviceSerial,
        [string]$AppPackage,
        [string]$RemotePath,
        [string]$Destination
    )

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo.FileName = $Adb
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    foreach ($argument in @("-s", $DeviceSerial, "exec-out", "run-as", $AppPackage, "cat", $RemotePath)) {
        [void]$process.StartInfo.ArgumentList.Add($argument)
    }

    if (-not $process.Start()) {
        throw "Unable to start adb for $RemotePath"
    }
    try {
        $stream = [System.IO.File]::Open($Destination, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try {
            $process.StandardOutput.BaseStream.CopyTo($stream)
        } finally {
            $stream.Dispose()
        }
        $process.WaitForExit()
        $stderr = $process.StandardError.ReadToEnd().Trim()
        if ($process.ExitCode -ne 0) {
            throw "adb failed while copying $RemotePath (exit $($process.ExitCode)): $stderr"
        }
    } finally {
        $process.Dispose()
    }
}

$adb = Find-Adb
$state = (& $adb -s $Serial get-state 2>$null).Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne "device") {
    throw "ADB target '$Serial' is not ready (state: $state)"
}

$remoteDirectory = "files/benchmark-stages"
$remoteFiles = @(& $adb -s $Serial shell run-as $Package ls -1 $remoteDirectory 2>$null)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to list $remoteDirectory. Run at least one auto-tune stage in the debuggable app first."
}
$stageFiles = $remoteFiles | Where-Object { $_ -match '\.(json|csv|html)$' }
if (-not $stageFiles) {
    throw "No benchmark stage archives are available in $remoteDirectory"
}

$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssfffZ")
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$destination = Join-Path (Resolve-Path $OutputRoot) "device-stage-archive-$timestamp"
New-Item -ItemType Directory -Path $destination -Force | Out-Null

$manifest = foreach ($name in $stageFiles) {
    $local = Join-Path $destination $name
    Copy-PrivateFile -Adb $adb -DeviceSerial $Serial -AppPackage $Package -RemotePath "$remoteDirectory/$name" -Destination $local
    $item = Get-Item -LiteralPath $local
    [pscustomobject]@{
        file = $item.Name
        bytes = $item.Length
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $local).Hash.ToLowerInvariant()
    }
}

$manifestPath = Join-Path $destination "manifest.json"
@($manifest) | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $manifestPath -Encoding utf8
Write-Output "Copied $(@($manifest).Count) benchmark archive files to $destination"
Write-Output "Manifest: $manifestPath"

if (-not $SkipComparison) {
    $comparisonScript = Join-Path $PSScriptRoot "compare-benchmark-stages.ps1"
    if (-not (Test-Path -LiteralPath $comparisonScript)) {
        throw "Stage comparison helper was not found: $comparisonScript"
    }
    & $comparisonScript -InputRoot (Resolve-Path $OutputRoot).Path
}

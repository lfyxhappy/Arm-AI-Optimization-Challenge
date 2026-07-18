param(
    [Parameter(Mandatory = $true)][string]$ModelDirectory,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$LlamaDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) "third_party\llama.cpp"),
    [switch]$Rebuild
)

$ErrorActionPreference = "Stop"
$workspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ModelDirectory = (Resolve-Path $ModelDirectory).Path
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$OutputDirectory = (Resolve-Path $OutputDirectory).Path
$name = Split-Path $ModelDirectory -Leaf
$f16 = Join-Path $OutputDirectory "$name-F16.gguf"
$q8 = Join-Path $OutputDirectory "$name-Q8_0.gguf"
$q4 = Join-Path $OutputDirectory "$name-Q4_K_M.gguf"
$converter = Join-Path $LlamaDirectory "convert_hf_to_gguf.py"
$quantizerCandidates = @(
    (Join-Path $LlamaDirectory "build\bin\llama-quantize.exe"),
    (Join-Path $LlamaDirectory "build-host\bin\llama-quantize.exe"),
    (Join-Path $LlamaDirectory "build-host-no-server\bin\llama-quantize.exe")
)
$quantizer = $quantizerCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not (Test-Path $converter)) { throw "llama.cpp conversion script not found: $converter" }
if ($null -eq $quantizer) { throw "Build llama.cpp first so llama-quantize.exe exists under build/bin or build-host/bin" }

if (-not $ModelDirectory.StartsWith($workspaceRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
    -not $OutputDirectory.StartsWith($workspaceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "ModelDirectory and OutputDirectory must stay under this workspace so an ASCII drive mapping can be used."
}

$driveLetter = $null
foreach ($candidate in @("Z", "Y", "X", "W", "V", "U", "T", "S", "R", "Q")) {
    if (-not (Get-PSDrive -Name $candidate -ErrorAction SilentlyContinue)) {
        $driveLetter = $candidate
        break
    }
}
if ($null -eq $driveLetter) { throw "No free drive letter is available for temporary model preparation mapping." }

$drive = "$driveLetter`:"
$mapped = $false
try {
    & subst.exe $drive $workspaceRoot
    if ($LASTEXITCODE -ne 0) { throw "Failed to map $drive to $workspaceRoot" }
    $mapped = $true

    $relativeModel = $ModelDirectory.Substring($workspaceRoot.Length).TrimStart('\', '/')
    $relativeOutput = $OutputDirectory.Substring($workspaceRoot.Length).TrimStart('\', '/')
    $mappedModel = Join-Path "$drive\" $relativeModel
    $mappedOutput = Join-Path "$drive\" $relativeOutput
    $mappedF16 = Join-Path $mappedOutput (Split-Path $f16 -Leaf)
    $mappedQ8 = Join-Path $mappedOutput (Split-Path $q8 -Leaf)
    $mappedQ4 = Join-Path $mappedOutput (Split-Path $q4 -Leaf)

    if ($Rebuild -or -not (Test-Path $f16)) {
        & python $converter $mappedModel --outfile $mappedF16 --outtype f16
        if ($LASTEXITCODE -ne 0) { throw "F16 conversion failed" }
    } else {
        Write-Output "Reusing existing F16 GGUF: $f16"
    }
    if ($Rebuild -or -not (Test-Path $q8)) {
        & $quantizer $mappedF16 $mappedQ8 Q8_0
        if ($LASTEXITCODE -ne 0) { throw "Q8_0 quantization failed" }
    } else {
        Write-Output "Reusing existing Q8_0 GGUF: $q8"
    }
    if ($Rebuild -or -not (Test-Path $q4)) {
        & $quantizer $mappedF16 $mappedQ4 Q4_K_M
        if ($LASTEXITCODE -ne 0) { throw "Q4_K_M quantization failed" }
    } else {
        Write-Output "Reusing existing Q4_K_M GGUF: $q4"
    }
} finally {
    if ($mapped) { & subst.exe $drive /D | Out-Null }
}

$manifest = Get-ChildItem $f16, $q8, $q4 | ForEach-Object {
    [PSCustomObject]@{
        filename = $_.Name
        bytes = $_.Length
        sha256 = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        created_utc = $_.LastWriteTimeUtc.ToString("o")
    }
}
$generatedAt = (Get-Date).ToUniversalTime()
$artifactRecord = [ordered]@{
    schema = "arm-mobile-ai-model-artifact/v1"
    generated_utc = $generatedAt.ToString("o")
    source_model_directory = $ModelDirectory
    llama_converter_sha256 = (Get-FileHash -LiteralPath $converter -Algorithm SHA256).Hash.ToLowerInvariant()
    llama_quantizer_sha256 = (Get-FileHash -LiteralPath $quantizer -Algorithm SHA256).Hash.ToLowerInvariant()
    rebuild_requested = [bool]$Rebuild
    artifacts = @($manifest)
}
$stableManifest = Join-Path $OutputDirectory "$name-manifest.json"
$immutableManifest = Join-Path $OutputDirectory ("$name-manifest-{0}-{1}.json" -f $generatedAt.ToString("yyyyMMddTHHmmssfffZ"), $manifest[0].sha256.Substring(0, 12))
$artifactRecord | ConvertTo-Json -Depth 4 | Set-Content -Path $stableManifest -Encoding utf8
$artifactRecord | ConvertTo-Json -Depth 4 | Set-Content -Path $immutableManifest -Encoding utf8
$manifest | Format-Table -AutoSize
Write-Output "Artifact manifest: $immutableManifest"

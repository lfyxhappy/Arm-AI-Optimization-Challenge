param(
    [Parameter(Mandatory = $true)][string]$ModelDirectory,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$LlamaDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) "third_party\llama.cpp")
)

$ErrorActionPreference = "Stop"
$ModelDirectory = (Resolve-Path $ModelDirectory).Path
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$OutputDirectory = (Resolve-Path $OutputDirectory).Path
$name = Split-Path $ModelDirectory -Leaf
$f16 = Join-Path $OutputDirectory "$name-F16.gguf"
$q8 = Join-Path $OutputDirectory "$name-Q8_0.gguf"
$q4 = Join-Path $OutputDirectory "$name-Q4_K_M.gguf"
$converter = Join-Path $LlamaDirectory "convert_hf_to_gguf.py"
$quantizer = Join-Path $LlamaDirectory "build\bin\llama-quantize.exe"

if (-not (Test-Path $converter)) { throw "llama.cpp conversion script not found: $converter" }
if (-not (Test-Path $quantizer)) { throw "Build llama.cpp first so llama-quantize.exe exists: $quantizer" }

& python $converter $ModelDirectory --outfile $f16 --outtype f16
if ($LASTEXITCODE -ne 0) { throw "F16 conversion failed" }
& $quantizer $f16 $q8 Q8_0
if ($LASTEXITCODE -ne 0) { throw "Q8_0 quantization failed" }
& $quantizer $f16 $q4 Q4_K_M
if ($LASTEXITCODE -ne 0) { throw "Q4_K_M quantization failed" }

$manifest = Get-ChildItem $f16, $q8, $q4 | ForEach-Object {
    [PSCustomObject]@{
        filename = $_.Name
        bytes = $_.Length
        sha256 = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        created_utc = $_.LastWriteTimeUtc.ToString("o")
    }
}
$manifest | ConvertTo-Json | Set-Content -Path (Join-Path $OutputDirectory "$name-manifest.json") -Encoding utf8
$manifest | Format-Table -AutoSize

param(
    [string]$Commit = "e8f19cc0ad70a243c8012bf17b4be601abfc8ea2"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root "third_party\llama.cpp"
$archive = Join-Path $env:TEMP "llama.cpp-$Commit.zip"
$extract = Join-Path $env:TEMP "llama.cpp-$Commit"

if (Test-Path (Join-Path $target "CMakeLists.txt")) {
    Write-Host "Pinned llama.cpp source already present at $target"
    exit 0
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
Invoke-WebRequest -Uri "https://codeload.github.com/ggml-org/llama.cpp/zip/$Commit" -OutFile $archive
Expand-Archive -LiteralPath $archive -DestinationPath $extract -Force
$source = Get-ChildItem $extract -Directory | Where-Object { $_.Name -eq "llama.cpp-$Commit" } | Select-Object -First 1
if ($null -eq $source) { throw "Pinned source directory was not found after extraction" }
Copy-Item -Path (Join-Path $source.FullName "*") -Destination $target -Recurse -Force
Write-Host "Materialized llama.cpp commit $Commit at $target"

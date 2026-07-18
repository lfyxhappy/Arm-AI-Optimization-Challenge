[CmdletBinding()]
param(
    [string]$InputRoot = (Join-Path $PSScriptRoot "..\benchmarks\output"),
    [string]$OutputRoot = (Join-Path $PSScriptRoot "..\benchmarks\output\comparisons"),
    [string]$BaselineStage,
    [switch]$IncludePartial
)

$ErrorActionPreference = "Stop"

function Get-PropertyValue {
    param(
        [object]$Object,
        [string]$Name,
        [object]$Default = $null
    )

    if ($null -eq $Object) { return $Default }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return $Default }
    return $property.Value
}

function Get-TextValue {
    param([object]$Object, [string]$Name, [string]$Default = "")
    return [string](Get-PropertyValue -Object $Object -Name $Name -Default $Default)
}

function Get-DoubleValue {
    param([object]$Object, [string]$Name, [double]$Default = -1.0)
    try { return [double](Get-PropertyValue -Object $Object -Name $Name -Default $Default) }
    catch { return $Default }
}

function Get-IntValue {
    param([object]$Object, [string]$Name, [int]$Default = 0)
    try { return [int](Get-PropertyValue -Object $Object -Name $Name -Default $Default) }
    catch { return $Default }
}

function Get-Sha256Text {
    param([string]$Value)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    $hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return ([System.BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()
}

function Get-Mean {
    param([double[]]$Values, [double]$Default = -1.0)
    if ($null -eq $Values -or $Values.Count -eq 0) { return $Default }
    return [double](($Values | Measure-Object -Average).Average)
}

function Format-Number {
    param([object]$Value)
    if ($null -eq $Value) { return "" }
    if ($Value -is [double] -or $Value -is [single] -or $Value -is [decimal]) {
        return ([double]$Value).ToString("0.00", [System.Globalization.CultureInfo]::InvariantCulture)
    }
    return [string]$Value
}

function Html {
    param([object]$Value)
    return [System.Net.WebUtility]::HtmlEncode((Format-Number $Value))
}

function New-HtmlTable {
    param(
        [object[]]$Rows,
        [string[]]$Columns,
        [hashtable]$Headings
    )

    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.Append("<table><thead><tr>")
    foreach ($column in $Columns) {
        $heading = if ($Headings.ContainsKey($column)) { $Headings[$column] } else { $column }
        [void]$builder.Append("<th>").Append((Html $heading)).Append("</th>")
    }
    [void]$builder.Append("</tr></thead><tbody>")
    foreach ($row in @($Rows)) {
        [void]$builder.Append("<tr>")
        foreach ($column in $Columns) {
            [void]$builder.Append("<td>").Append((Html (Get-PropertyValue -Object $row -Name $column -Default ""))).Append("</td>")
        }
        [void]$builder.Append("</tr>")
    }
    [void]$builder.Append("</tbody></table>")
    return $builder.ToString()
}

function New-LegacySession {
    param(
        [object]$Document,
        [System.IO.FileInfo]$SourceFile,
        [string]$SourceSha256
    )

    $measurements = @(Get-PropertyValue -Object $Document -Name "measurements" -Default @())
    $first = if ($measurements.Count -gt 0) { $measurements[0] } else { $null }
    $stage = Get-TextValue -Object $first -Name "stage"
    if ([string]::IsNullOrWhiteSpace($stage)) { $stage = "legacy-" + $SourceFile.Directory.Name }
    return [pscustomobject]@{
        id = "legacy-" + $SourceSha256.Substring(0, 16)
        stage = $stage
        startedAt = Get-TextValue -Object $first -Name "timestamp"
        complete = $false
        appVersion = "legacy-unknown"
        appApkSha256 = "legacy-unknown"
        deviceFingerprint = "legacy-unknown"
        abi = "unknown"
        runtimeConfig = [pscustomobject]@{
            threads = Get-IntValue -Object $first -Name "threads"
            temperature = Get-DoubleValue -Object $first -Name "temperature"
            backendPreference = Get-TextValue -Object $first -Name "backendPreference"
            flashAttention = "unknown"
            kvCacheType = "unknown"
            batchSize = Get-IntValue -Object $first -Name "batchSize"
            ubatchSize = Get-IntValue -Object $first -Name "ubatchSize"
        }
    }
}

function New-StageSummary {
    param(
        [object]$Record,
        [switch]$SummarizePartial
    )

    $session = $Record.session
    $allMeasurements = @(Get-PropertyValue -Object $Record.document -Name "measurements" -Default @())
    $complete = (Get-PropertyValue -Object $session -Name "complete" -Default $false) -eq $true
    $legacy = (Get-PropertyValue -Object $Record -Name "legacy" -Default $false) -eq $true
    $inputs = Get-PropertyValue -Object $session -Name "inputs" -Default $null
    $systemPromptHash = Get-TextValue -Object $inputs -Name "systemPromptSha256"
    $userPromptHash = Get-TextValue -Object $inputs -Name "userPromptSha256"
    $inputProtocol = Get-TextValue -Object $inputs -Name "protocol"
    $maxOutputTokens = Get-IntValue -Object $inputs -Name "maxOutputTokens"
    $hasInputFingerprint = -not [string]::IsNullOrWhiteSpace($inputProtocol) -and
        -not [string]::IsNullOrWhiteSpace($systemPromptHash) -and
        -not [string]::IsNullOrWhiteSpace($userPromptHash) -and $maxOutputTokens -gt 0

    $runtimeConfig = Get-PropertyValue -Object $session -Name "runtimeConfig" -Default $null
    $temperature = Get-DoubleValue -Object $runtimeConfig -Name "temperature"
    $metadataSample = if ($allMeasurements.Count -gt 0) { $allMeasurements[0] } else { $null }
    $modelSha256 = Get-TextValue -Object $metadataSample -Name "sha256"
    $modelBytes = Get-PropertyValue -Object $metadataSample -Name "modelBytes" -Default 0
    $deviceFingerprint = Get-TextValue -Object $session -Name "deviceFingerprint"

    $validMeasurements = @()
    if ($complete -or $SummarizePartial -or $legacy) {
        $validMeasurements = @($allMeasurements | Where-Object {
            (Get-PropertyValue -Object $_ -Name "valid" -Default $false) -eq $true -and
            (Get-PropertyValue -Object $_ -Name "warmup" -Default $false) -ne $true
        })
    }

    $threadSummaries = [System.Collections.Generic.List[object]]::new()
    foreach ($group in @($validMeasurements | Group-Object -Property threads)) {
        $samples = @($group.Group)
        $ttft = @($samples | ForEach-Object { Get-DoubleValue -Object $_ -Name "ttftMs" } | Where-Object { $_ -ge 0.0 })
        $tokensPerSecond = @($samples | ForEach-Object { Get-DoubleValue -Object $_ -Name "tokensPerSecond" })
        $peakMemory = @($samples | ForEach-Object { Get-PropertyValue -Object $_ -Name "peakMemoryKb" -Default 0 } | Measure-Object -Maximum)
        $threadSummaries.Add([pscustomobject]@{
            threads = [int]$group.Name
            valid_runs = $samples.Count
            mean_ttft_ms = Get-Mean -Values $ttft
            mean_tokens_per_second = Get-Mean -Values $tokensPerSecond
            max_peak_memory_kb = [long]$peakMemory.Maximum
        })
    }

    $orderedThreads = @($threadSummaries | Sort-Object threads)
    $baseline = $orderedThreads | Select-Object -First 1
    $recommended = $orderedThreads |
        Sort-Object @{ Expression = "mean_tokens_per_second"; Descending = $true }, @{ Expression = { if ($_.mean_ttft_ms -ge 0.0) { $_.mean_ttft_ms } else { [double]::PositiveInfinity } }; Descending = $false } |
        Select-Object -First 1
    $recommendedSample = if ($null -eq $recommended) { $null } else {
        $validMeasurements | Where-Object { (Get-IntValue -Object $_ -Name "threads") -eq $recommended.threads } | Select-Object -First 1
    }

    $comparisonGroup = "legacy-inputs-missing"
    if ($hasInputFingerprint -and -not [string]::IsNullOrWhiteSpace($deviceFingerprint) -and -not [string]::IsNullOrWhiteSpace($modelSha256)) {
        $temperatureText = $temperature.ToString("R", [System.Globalization.CultureInfo]::InvariantCulture)
        $comparisonGroup = "cmp-" + (Get-Sha256Text "$deviceFingerprint|$modelSha256|$modelBytes|$inputProtocol|$systemPromptHash|$userPromptHash|$maxOutputTokens|$temperatureText").Substring(0, 16)
    }

    $comparisonState = if ($legacy) {
        "legacy-inputs-missing"
    } elseif (-not $complete) {
        if ($SummarizePartial) { "partial-included" } else { "partial-excluded" }
    } elseif (-not $hasInputFingerprint) {
        "legacy-inputs-missing"
    } elseif ($validMeasurements.Count -eq 0) {
        "no-valid-samples"
    } else {
        "comparable"
    }

    $configText = "pref={0}; flash={1}; kv={2}; batch={3}/{4}" -f `
        (Get-TextValue -Object $runtimeConfig -Name "backendPreference"),
        (Get-TextValue -Object $runtimeConfig -Name "flashAttention"),
        (Get-TextValue -Object $runtimeConfig -Name "kvCacheType"),
        (Get-PropertyValue -Object $runtimeConfig -Name "batchSize" -Default ""),
        (Get-PropertyValue -Object $runtimeConfig -Name "ubatchSize" -Default "")

    return [pscustomobject]@{
        session_id = Get-TextValue -Object $session -Name "id"
        stage = Get-TextValue -Object $session -Name "stage"
        started_at = Get-TextValue -Object $session -Name "startedAt"
        session_complete = if ($legacy) { $null } else { $complete }
        comparison_state = $comparisonState
        comparison_group = $comparisonGroup
        schema = Get-TextValue -Object $Record.document -Name "schema"
        app_version = Get-TextValue -Object $session -Name "appVersion"
        app_apk_sha256 = Get-TextValue -Object $session -Name "appApkSha256"
        device_fingerprint = $deviceFingerprint
        abi = Get-TextValue -Object $session -Name "abi"
        model_sha256 = $modelSha256
        model_bytes = [long]$modelBytes
        input_protocol = $inputProtocol
        system_prompt_sha256 = $systemPromptHash
        system_prompt_utf8_bytes = Get-IntValue -Object $inputs -Name "systemPromptUtf8Bytes"
        user_prompt_sha256 = $userPromptHash
        user_prompt_utf8_bytes = Get-IntValue -Object $inputs -Name "userPromptUtf8Bytes"
        max_output_tokens = $maxOutputTokens
        temperature = $temperature
        configuration = $configText
        actual_backend = Get-TextValue -Object $recommendedSample -Name "backend"
        backend_profile = Get-TextValue -Object $recommendedSample -Name "backendProfile"
        requested_device = Get-TextValue -Object $recommendedSample -Name "requestedDevice"
        active_device = Get-TextValue -Object $recommendedSample -Name "activeDevice"
        baseline_threads = if ($null -eq $baseline) { $null } else { $baseline.threads }
        baseline_valid_runs = if ($null -eq $baseline) { 0 } else { $baseline.valid_runs }
        baseline_mean_ttft_ms = if ($null -eq $baseline) { -1.0 } else { $baseline.mean_ttft_ms }
        baseline_mean_tokens_per_second = if ($null -eq $baseline) { -1.0 } else { $baseline.mean_tokens_per_second }
        optimized_threads = if ($null -eq $recommended) { $null } else { $recommended.threads }
        optimized_valid_runs = if ($null -eq $recommended) { 0 } else { $recommended.valid_runs }
        optimized_mean_ttft_ms = if ($null -eq $recommended) { -1.0 } else { $recommended.mean_ttft_ms }
        optimized_mean_tokens_per_second = if ($null -eq $recommended) { -1.0 } else { $recommended.mean_tokens_per_second }
        optimized_max_peak_memory_kb = if ($null -eq $recommended) { 0 } else { $recommended.max_peak_memory_kb }
        source_sha256 = $Record.source_sha256
        source_paths = ($Record.source_paths -join ";")
        thread_summaries = @($orderedThreads)
        baseline_session_id = $null
        delta_optimized_tokens_per_second_pct = $null
        delta_optimized_ttft_pct = $null
    }
}

if (-not (Test-Path -LiteralPath $InputRoot -PathType Container)) {
    throw "Input root does not exist: $InputRoot"
}
$resolvedInputRoot = (Resolve-Path -LiteralPath $InputRoot).Path
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$resolvedOutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path

$rawArtifacts = [System.Collections.Generic.List[object]]::new()
$sessionsById = @{}
$candidateFiles = @(Get-ChildItem -LiteralPath $resolvedInputRoot -Recurse -File -Filter "*.json")
foreach ($file in $candidateFiles) {
    $document = $null
    try { $document = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json }
    catch { continue }
    $schema = Get-TextValue -Object $document -Name "schema"
    if ($schema -notmatch "^arm-mobile-ai-benchmark/v[123]$") { continue }

    $session = Get-PropertyValue -Object $document -Name "session" -Default $null
    $measurements = Get-PropertyValue -Object $document -Name "measurements" -Default $null
    # The output root also contains evidence summaries that may repeat the
    # benchmark schema as a reference. Only formal archive documents have a
    # measurements payload and, for v2/v3, a formal session object.
    if ($null -eq $measurements) { continue }
    $sourceHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $legacy = $schema -eq "arm-mobile-ai-benchmark/v1"
    if ($legacy) { $session = New-LegacySession -Document $document -SourceFile $file -SourceSha256 $sourceHash }
    if (-not $legacy -and $null -eq $session) { continue }
    $sessionId = Get-TextValue -Object $session -Name "id"
    if ([string]::IsNullOrWhiteSpace($sessionId)) { throw "Benchmark archive is missing session.id: $($file.FullName)" }
    $rawArtifacts.Add([pscustomobject]@{
        path = $file.FullName
        bytes = $file.Length
        sha256 = $sourceHash
        session_id = $sessionId
        schema = $schema
    })

    if ($sessionsById.ContainsKey($sessionId)) {
        $existing = $sessionsById[$sessionId]
        if ($existing.source_sha256 -ne $sourceHash) {
            throw "Conflicting archive content for session '$sessionId': $($existing.source_paths[0]) and $($file.FullName)"
        }
        $existing.source_paths.Add($file.FullName)
        continue
    }

    $record = [pscustomobject]@{
        document = $document
        session = $session
        legacy = $legacy
        source_sha256 = $sourceHash
        source_paths = [System.Collections.Generic.List[string]]::new()
    }
    $record.source_paths.Add($file.FullName)
    $sessionsById[$sessionId] = $record
}

if ($sessionsById.Count -eq 0) {
    throw "No arm-mobile-ai-benchmark/v1, v2, or v3 JSON archives were found under $resolvedInputRoot"
}

$stageRows = [System.Collections.Generic.List[object]]::new()
foreach ($record in $sessionsById.Values) {
    $stageRows.Add((New-StageSummary -Record $record -SummarizePartial:$IncludePartial))
}

if (-not [string]::IsNullOrWhiteSpace($BaselineStage)) {
    foreach ($group in @($stageRows | Group-Object comparison_group)) {
        if ($group.Name -eq "legacy-inputs-missing") { continue }
        $baseline = @($group.Group | Where-Object {
            $_.comparison_state -eq "comparable" -and $_.stage -eq $BaselineStage
        } | Sort-Object started_at -Descending | Select-Object -First 1)
        if ($baseline.Count -eq 0) { continue }
        $baselineRow = $baseline[0]
        foreach ($row in $group.Group) {
            if ($row.comparison_state -ne "comparable") { continue }
            $row.baseline_session_id = $baselineRow.session_id
            if ($baselineRow.optimized_mean_tokens_per_second -gt 0.0) {
                $row.delta_optimized_tokens_per_second_pct = 100.0 *
                    ($row.optimized_mean_tokens_per_second - $baselineRow.optimized_mean_tokens_per_second) /
                    $baselineRow.optimized_mean_tokens_per_second
            }
            if ($baselineRow.optimized_mean_ttft_ms -gt 0.0 -and $row.optimized_mean_ttft_ms -ge 0.0) {
                $row.delta_optimized_ttft_pct = 100.0 *
                    ($baselineRow.optimized_mean_ttft_ms - $row.optimized_mean_ttft_ms) /
                    $baselineRow.optimized_mean_ttft_ms
            }
        }
    }
}

$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssfffZ")
$destination = Join-Path $resolvedOutputRoot "comparison-$timestamp-$PID"
New-Item -ItemType Directory -Path $destination -ErrorAction Stop | Out-Null

$csvColumns = @(
    "session_id", "stage", "started_at", "session_complete", "comparison_state", "comparison_group",
    "schema", "app_version", "app_apk_sha256", "device_fingerprint", "abi", "model_sha256", "model_bytes",
    "input_protocol", "system_prompt_sha256", "system_prompt_utf8_bytes", "user_prompt_sha256", "user_prompt_utf8_bytes", "max_output_tokens", "temperature",
    "configuration", "actual_backend", "backend_profile", "requested_device", "active_device",
    "baseline_threads", "baseline_valid_runs", "baseline_mean_ttft_ms", "baseline_mean_tokens_per_second",
    "optimized_threads", "optimized_valid_runs", "optimized_mean_ttft_ms", "optimized_mean_tokens_per_second", "optimized_max_peak_memory_kb",
    "baseline_session_id", "delta_optimized_tokens_per_second_pct", "delta_optimized_ttft_pct",
    "source_sha256", "source_paths"
)
$csvPath = Join-Path $destination "stage-comparison.csv"
@($stageRows | Sort-Object comparison_group, started_at, stage) | Select-Object $csvColumns |
    Export-Csv -Path $csvPath -NoTypeInformation -Encoding utf8

$displayColumns = @(
    "stage", "comparison_state", "app_version", "app_apk_sha256", "configuration", "actual_backend", "optimized_threads",
    "optimized_valid_runs", "optimized_mean_ttft_ms", "optimized_mean_tokens_per_second", "optimized_max_peak_memory_kb",
    "delta_optimized_tokens_per_second_pct", "delta_optimized_ttft_pct", "session_id"
)
$headings = @{
    stage = "Stage"; comparison_state = "State"; app_version = "App"; app_apk_sha256 = "APK SHA-256"; configuration = "Requested configuration"; actual_backend = "Actual backend";
    optimized_threads = "Recommended threads"; optimized_valid_runs = "Valid runs"; optimized_mean_ttft_ms = "Mean TTFT (ms)";
    optimized_mean_tokens_per_second = "Mean tokens/s"; optimized_max_peak_memory_kb = "Peak memory max (KB)";
    delta_optimized_tokens_per_second_pct = "tokens/s delta (%)"; delta_optimized_ttft_pct = "TTFT improvement (%)"; session_id = "Session"
}
$htmlBuilder = [System.Text.StringBuilder]::new()
[void]$htmlBuilder.Append('<!doctype html><html><head><meta charset="utf-8"><title>Arm Mobile AI Stage Comparison</title><style>body{font-family:system-ui,sans-serif;margin:32px;color:#17212b}table{border-collapse:collapse;width:100%;margin:12px 0 28px}th,td{padding:8px;border:1px solid #b9c2ca;text-align:left;vertical-align:top}th{background:#e8f1f3}code{background:#f2f4f5;padding:2px 4px}</style></head><body>')
[void]$htmlBuilder.Append("<h1>Arm Mobile AI stage comparison</h1><p>Generated UTC: <code>").Append((Html (Get-Date).ToUniversalTime().ToString("o"))).Append("</code>. Raw archives are never rewritten; duplicate pulls of identical session JSON are de-duplicated by session ID and SHA-256.</p>")
[void]$htmlBuilder.Append("<p>Comparison group requires the same device fingerprint, model hash/size, input protocol, system/user prompt fingerprints, maximum output tokens, and temperature. <code>legacy-inputs-missing</code> archives remain visible but are not considered controlled cross-stage evidence.</p>")
if ([string]::IsNullOrWhiteSpace($BaselineStage)) {
    [void]$htmlBuilder.Append("<p>No baseline stage was selected, so delta columns are intentionally blank. Re-run with <code>-BaselineStage &lt;stage-name&gt;</code> to calculate deltas within each compatible group.</p>")
} else {
    [void]$htmlBuilder.Append("<p>Delta baseline stage: <code>").Append((Html $BaselineStage)).Append("</code>. Positive TTFT improvement means lower TTFT.</p>")
}
foreach ($group in @($stageRows | Sort-Object comparison_group | Group-Object comparison_group)) {
    [void]$htmlBuilder.Append("<h2>").Append((Html $group.Name)).Append("</h2>")
    [void]$htmlBuilder.Append((New-HtmlTable -Rows @($group.Group | Sort-Object started_at, stage) -Columns $displayColumns -Headings $headings))
}
[void]$htmlBuilder.Append("</body></html>")
$htmlPath = Join-Path $destination "stage-comparison.html"
Set-Content -LiteralPath $htmlPath -Value $htmlBuilder.ToString() -Encoding utf8

$manifest = [ordered]@{
    schema = "arm-mobile-ai-stage-comparison/v1"
    generated_utc = (Get-Date).ToUniversalTime().ToString("o")
    input_root = $resolvedInputRoot
    script_sha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    raw_archive_file_count = $rawArtifacts.Count
    unique_session_count = $stageRows.Count
    baseline_stage = $BaselineStage
    include_partial = [bool]$IncludePartial
    raw_archives = @($rawArtifacts)
    stages = @($stageRows)
}
$manifestPath = Join-Path $destination "source-manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding utf8

Write-Output "Compared $($stageRows.Count) unique session(s) from $($rawArtifacts.Count) raw archive file(s)."
Write-Output "CSV: $csvPath"
Write-Output "HTML: $htmlPath"
Write-Output "Manifest: $manifestPath"

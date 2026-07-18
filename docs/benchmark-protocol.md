# Benchmark Protocol

## Controlled inputs

Use the same GGUF, Chinese prompt, system prompt, temperature, maximum output length, Android build, and device state for every compared configuration. The app's default candidate thread counts are `2`, `4`, `6`, and `8`. Record the compiled backend profile and runtime preference (`auto`, `cpu`, or `accelerator`) as controlled inputs too.

Every new formal archive uses schema `arm-mobile-ai-benchmark/v3` and retains the SHA-256 plus UTF-8 byte length of the system prompt and benchmark prompt, the prompt protocol label, and maximum output tokens. Prompt text is deliberately not exported. This gives the host comparison tool enough evidence to reject an accidental prompt mismatch without leaking the prompt itself. Existing v1/v2 archives remain valid historical evidence, but are labelled `legacy-inputs-missing` rather than silently mixed into controlled cross-stage conclusions.

Each experiment must have a human-readable stage name, such as `cpu-q4-baseline`, `cpu-q4-fa-on-f16-kv`, or `cpu-q4-fa-on-q8-kv`. A stage changes exactly one hypothesis at a time whenever possible. Flash Attention policy, KV cache type, `batch`, and `ubatch` are first-class controlled inputs, not informal notes.

## Run sequence

For every thread count, the workbench creates a fresh model context, completes one warm-up run, then records five runs. A fresh context avoids contamination from the previous run's KV cache. Interactive chat may reuse its loaded model and KV context, but those measurements are never mixed into the formal stage benchmark. TTFT starts immediately before the user prompt is submitted and ends on the first streamed non-empty token. tokens/s is emitted output-token count divided by that elapsed stream interval.

When a full `2/4/6/8` pass becomes thermally confounded, use **Repeat selected thread (1 warm-up + 5)** for a cooled control series. It preserves the same immutable v3 JSON/CSV/HTML evidence but contains only the selected candidate. A targeted series is valid only for a same-thread, same-build, same-model, same-input comparison; it does not replace a complete auto-tune stage or establish a new device-wide thread recommendation.

Each record also preserves native model-load, context-init, system-prompt prefill, user-prompt prefill, native decode, TTFT, and end-to-end timings. Do not derive a prefill conclusion from TTFT alone.

For a CPU-versus-accelerator comparison, use the same model hash and preferably the same accelerator-capable APK: run the CPU preference first, then the compiled accelerator preference. Keep batch/ubatch, context configuration, and sampling parameters constant unless the experiment explicitly studies one of them.

## Validity

The workbench samples `PowerManager.currentThermalStatus` before and after each run. A run at `THERMAL_STATUS_SEVERE` or higher is retained in JSON/CSV with `valid=false` and is excluded from the recommendation. A run skipped before native inference because it was already severe has zero timing/output/memory sentinel values; it is an audit record, not evidence of zero memory use. Battery temperature and `BATTERY_PROPERTY_CURRENT_NOW` are recorded where the device exposes them. `VmHWM` comes from `/proc/self/status` and represents peak process resident memory observed by Linux.

An accelerator-requested run is also invalid for an accelerator claim if it reports a fallback reason, if the active device differs from the requested device, or if no positive layer-offload log was observed. It remains useful as a negative/fallback artifact, but it must not enter the accelerator recommendation.

## Recommendation

From valid, non-warm-up results, the selected thread count maximizes mean tokens/s. Mean TTFT breaks a speed tie. The HTML report presents the lowest tested valid thread setting as baseline and the selected setting as optimized, with valid-run count, mean TTFT, mean tokens/s, and the maximum observed peak memory for each group. Do not compare results taken with different prompts or sampling parameters.

## Required evidence for OnePlus 13

1. Run the protocol for Base F16, Q8_0, and Q4_K_M GGUF artifacts.
2. The app automatically archives every completed or partially completed auto-tune session as immutable JSON, CSV, and HTML files in app-private `benchmark-stages/` storage. A partial archive is explicitly marked `complete=false` and must not be compared as a full 24-slot stage. Export the all-stage CSV and preserve it under an ignored local artifact directory.
3. Build a result table from only valid samples, with model SHA-256, device build, app version, and installed APK SHA-256.
4. Run Qwen3-4B Instruct Q4_K_M through the chat screen as a Chinese smoke test.
5. Preserve `backendProfile`, preference, requested/active device, registered backends/devices, layer offload, fallback reason, Flash Attention policy, KV cache type, batch, ubatch, controlled-input fingerprints, and stage/session identifiers beside every exported measurement.

Use `tools/pull-benchmark-stages.ps1 -Serial <adb-serial>` after each stage to copy the app-private archive into a timestamped ignored `benchmarks/output/` directory with a SHA-256 manifest. This is required before reinstalling or clearing the debug app because app-private archives are otherwise device-local. The pull helper then creates an immutable comparison directory containing a de-duplicated cross-stage CSV, HTML report, and source SHA-256 manifest. To calculate relative values against a named stage without changing any raw data, run:

```powershell
./tools/compare-benchmark-stages.ps1 -BaselineStage cpu-q4-fa-auto-f16
```

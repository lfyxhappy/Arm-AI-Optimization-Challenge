# Modern Mobile LLM Acceleration Plan

Last updated: 2026-07-18

Status: The CPU Debug path has a real OnePlus 13 Q4 baseline and corrected peak-memory telemetry as of 2026-07-18. Flash Attention + Q8_0 KV-cache now has an initial same-model device comparison with immutable stage archives; 2/4-thread rows are fully valid, while the 6-thread F16 control needs a cold five-versus-five repeat. OpenCL/HTP SDK builds and device-side accelerator evidence are still pending.

## Implementation checkpoint

- [x] Add `cpu`, `snapdragon-opencl`, and `snapdragon-htp` profile selection with early SDK/ABI validation.
- [x] Preserve a CPU-only runtime option within an accelerator-capable APK, so a same-build CPU control can be recorded.
- [x] Expose profile, preference, requested/active device, registered backends/devices, observed layer offload, fallback reason, and `batch`/`ubatch` in the UI and export schema.
- [x] Package HTP skeleton targets into the Android native output path in CMake (source-level implementation only; it still requires an SDK build and APK inspection).
- [x] Pass the CPU Debug regression build with the pinned CPU/KleidiAI/OpenMP path; invalid profile, missing OpenCL SDK, and missing Hexagon SDK fail early with actionable errors.
- [x] Capture a real OnePlus 13 CPU Q4 benchmark with 24 records, valid-only aggregation, thermal invalidation, JSON/CSV/HTML exports, and non-zero `VmHWM` for executed runs.
- [x] Add CPU experiment controls for Flash Attention, F16/Q8_0/Q4_0 KV cache, and `batch`/`ubatch`; record split native timings and archive every auto-tune stage without overwriting earlier samples.
- [x] Add controlled-input fingerprints and a host-side de-duplicated stage comparison report with source SHA-256 manifests, so baseline and optimized sessions can be audited without exporting prompt contents.
- [-] Initial Flash Attention/Q8_0 KV comparison captured on the OnePlus 13 with deterministic Chinese inputs, matching APK/model hashes, and raw archives. Repeat a cold/randomized F16 6-thread control before making a 6-thread throughput claim; see `docs/oneplus13-flash-q8-kv-results-20260718.md`.
- [ ] Build and inspect a real OpenCL APK after the Qualcomm SDK is installed.
- [ ] Build, package-inspect, and quality-gate a real HTP APK after the Hexagon SDK is installed.
- [ ] Capture the first physical-device CPU-vs-accelerator comparison. Until then, no accelerator performance claim is valid.

## Decision

The entry will no longer present CPU quantization and thread tuning as its sole optimization story. It will become a reproducible **multi-backend mobile LLM workbench** that measures the trade-offs between latency, throughput, memory, thermals, and device compatibility on an Arm Android phone.

The integrated story is:

`CPU KleidiAI baseline -> Snapdragon GPUOpenCL/HTP matrix -> attention/KV memory optimization -> speculative decoding -> thermal-aware backend policy`

The existing CPU implementation remains essential. It is the portable fallback, the reference implementation for every comparison, and the path that keeps the app usable if an accelerator is unavailable.

## Scope and Boundaries

- Target: an `arm64-v8a` Android phone. The connected OnePlus device's SoC, GPU, Android version, OpenCL capabilities, and thermal interfaces must be recorded through ADB before accelerator claims are made.
- CPU reference: retain the current `llama.cpp` CPU backend with KleidiAI and OpenMP.
- Main accelerator path: use the pinned `llama.cpp` Snapdragon backend to evaluate CPU, Adreno GPUOpenCL, and Hexagon HTP with a common GGUF-oriented application path. Retain CPU fallback for every device.
- GPU release candidate: prioritize Adreno GPUOpenCL because it can first be tested with the existing Q4_K_M artifact and has a smaller integration delta.
- NPU path: treat native ggml Hexagon HTP as a quality-gated experiment, not a generic QNN claim. HTP needs separately recorded Q4_0/Q8_0/MXFP4 artifacts and begins with `n_ubatch <= 16` because of the current OnePlus/Qwen prefill regression boundary.
- QNN/ExecuTorch path: retain as a separate, time-boxed AOT-compiled-model alternative only if native HTP cannot yield reproducible evidence. It is no longer the first NPU integration step.
- Optional runtime alternative: assess MNN only if both native Snapdragon and QNN experiments fail to produce a reproducible Android result. Do not integrate multiple replacement runtimes in the first submission.
- Vulkan: keep as an optional comparative experiment, not the primary Android GPU route. It must not delay OpenCL or the CPU release.
- Model quality is a release gate. A faster backend is not eligible for the default path if it fails the fixed Chinese smoke prompts or produces materially different output under deterministic settings.

## Optimization Stack

| Layer | Current reference | Planned optimization | Required evidence |
| --- | --- | --- | --- |
| Compute backend | CPU + KleidiAI | Adreno GPUOpenCL and Hexagon HTP profiles; QNN only as fallback PoC | Actual backend/device log, repeated on-device measurements |
| Weight storage | F16, Q8_0, Q4_K_M GGUF matrix | Pareto selection by quality, model size, memory, and speed | Model hash, size, quality smoke result, benchmark table |
| Attention and memory | Default context and F16 KV cache | Flash Attention, F16/Q8/Q4 KV cache candidates, tuned prefill batch size | Long-context TTFT, decode rate, peak memory, OOM result |
| Decode algorithm | One target-model token per decode step | Draft-model, MTP, or n-gram speculative decoding, selected only after compatibility testing | Draft acceptance, effective decode tokens/s, memory overhead |
| Session lifecycle | Fresh model/context is acceptable for benchmark isolation | Persistent model and KV cache for interactive multi-turn chat | Cold first-turn and warm multi-turn TTFT comparison |
| Runtime control | Fixed manual thread choice | Thermal-aware CPU/GPU/thread policy with hysteresis and a safe fallback | Sustained-run curve, thermal state, policy decision trace |

## Execution Order

### Phase 0 - Evidence and Baseline Closure

Purpose: turn the existing CPU path into a trustworthy reference before comparing newer techniques.

- Reconnect the OnePlus device and capture `getprop`, GPU renderer, OpenCL availability, CPU topology, Android build, battery state, and thermal state.
- Install the latest Debug APK and repeat the clean CPU Q4 benchmark: one warm-up plus five recorded runs for `2/4/6/8` threads. Done on 2026-07-18; 8-thread slots were invalidated by `SEVERE` thermals and excluded from the published table.
- Fix `peakMemoryKb` before using it in a submission result. Cross-check the in-app value against `adb shell dumpsys meminfo` / process data. Done; executed runs reported `VmHWM` up to 9,139,016 KB and the post-run PSS snapshot was retained separately.
- Keep interactive chat results separate from formal benchmarks. Formal benchmarks continue to use fresh contexts; only chat may reuse a context.
- Archive JSON, CSV, HTML, app APK hash, model hash, and device fingerprint in an ignored local results directory.

Exit gate: CPU results contain 24 records, clear warm-up/valid flags, correct exported extensions, non-zero credible memory data for executed runs, and no `SEVERE` thermal samples in the published comparison. This gate is satisfied for the 2/4/6-thread Q4 comparison; the 8-thread result remains an explicitly invalid thermal artifact.

### Phase 1 - CPU Runtime and Memory Optimization

Purpose: establish modern software-side improvements that remain valid without a proprietary accelerator.

- Change interactive chat so the loaded model, system prompt state, and KV cache survive across turns. Reset only when the model or settings that invalidate the context change.
- Add explicit native configuration for Flash Attention, `n_batch` / `n_ubatch`, and candidate KV cache types. Expose only configurations supported by the active backend.
- Benchmark F16, Q8_0, and Q4_K_M weights separately from KV-cache choices. Avoid a full factorial matrix; first choose the best valid CPU baseline, then vary one dimension at a time.
- Split timing into model-load, prompt-prefill, first-token, decode, and end-to-end elapsed time. Record context length and prompt/output token counts.
- Add multi-turn tests to show the difference between cold first turn and reused-session turn without mixing either into the formal fresh-context benchmark.

Exit gate: every enabled configuration has a clear runtime capability label, produces a valid response, and reports memory and timing fields needed to explain its trade-off.

### Phase 2 - Snapdragon GPU/NPU Accelerator Matrix

Purpose: make heterogeneous CPU/GPU/NPU inference the primary performance experiment while preserving a usable app.

- Maintain three build profiles: `cpu`, `snapdragon-opencl`, and `snapdragon-htp`. Keep the CPU profile buildable and installable without Qualcomm SDKs.
- At runtime, enumerate backend devices and show the requested device, registered backend, actual layer offload, profile, ubatch, and fallback reason in the app and exported artifact.
- Test GPUOpenCL first with the existing Q4_K_M artifact. Test HTP only with separately hashed Q4_0/Q8_0/MXFP4 candidates; do not compare an HTP result against a differently quantized CPU result without a matching control.
- Start every HTP quality smoke test at `n_ubatch=16`, run upstream backend-op checks, and only expand the batch after deterministic Chinese output is coherent.
- Test a small, ordered offload sweep: CPU only, low partial offload, medium partial offload, and full offload when it fits. Do not assume full offload is fastest for every prompt length.
- Measure prompt-prefill and token decode separately; GPU/HTP setup and transfer can improve one and hurt the other.
- Use the same model hash, prompt, context, sampling, app build, and thermal validity rule for comparisons.
- If GPUOpenCL or HTP fails, is unstable, produces mismatched output, or loses to CPU on valid repeated runs, retain it as an experimental result and keep CPU as default.

Exit gate: at least one Snapdragon configuration has a real backend log, repeated valid runs, a quality smoke pass, and an exported comparison against the same-quantization CPU baseline. A negative result is acceptable if it is documented honestly.

### Phase 3 - Speculative Decoding

Purpose: improve decode throughput beyond kernel-level acceleration without changing the final target model's output contract.

- First test zero-extra-model n-gram speculation because it has the lowest integration and memory cost.
- Evaluate the Qwen3-4B EAGLE-3 sidecar before any other learned draft model because the pinned upstream documents explicit target compatibility.
- Treat Qwen3-4B DFlash as a current research experiment: it is block-diffusion based and potentially GPU/HTP friendly, but it needs a prompt-specific acceptance-rate gate and must not be a default feature while upstream regressions remain open.
- Evaluate MTP only if the target Qwen artifact exposes compatible MTP heads and the pinned `llama.cpp` API supports it on the selected backend.
- Export draft type, draft model hash when used, drafted-token count, accepted-token count/rate, and additional memory use.
- Compare normal and speculative decoding under deterministic sampling, then repeat a representative Chinese interactive prompt for product behavior.

Exit gate: a speculative mode is user-selectable only if it improves effective decode tokens/s across repeated valid runs without unacceptable memory growth or quality regressions. Otherwise retain its measured negative result as an experiment, not as the default feature.

### Phase 4 - AOT QNN Proof of Concept (Only if Native HTP Is Insufficient)

Purpose: validate whether an AOT QNN path can provide a compelling, reproducible alternative when native HTP is not sufficient.

- Use ExecuTorch's Qualcomm backend and the matching QNN SDK to export a model for the verified target chipset; record the QNN and Android runtime versions.
- Keep its converted artifact and runtime dependencies distinct from the GGUF/llama.cpp path. Document each license and whether each dependency can be included in a public APK/repository.
- Begin with a minimal official LlamaDemo-style load-and-generate smoke test, then add the shared benchmark schema only after it is stable.
- Measure startup/model compilation cost separately from steady-state inference. QNN context creation must not be hidden from a cold-start claim.
- Time-box the proof of concept. If conversion, operator coverage, runtime packaging, or reproducibility cannot be resolved quickly, freeze it as a documented exploration and ship the CPU/OpenCL workbench.

Exit gate: QNN enters the submission narrative only with a public reproducible build, target-device log, model provenance, and comparable valid metrics. Otherwise it remains a future-work note.

### Phase 5 - Thermal-Aware Policy and Product Integration

Purpose: make the result useful in a real mobile session, rather than only maximizing an early benchmark number.

- Define three policies: `Performance`, `Balanced`, and `Sustained`. Each chooses backend, offload level, threads, batch size, and cache precision from the measured capability table.
- Add hysteresis: do not switch configurations on a single thermal sample. Change only after a stable threshold and record every decision/reason in the exported trace.
- Never silently invalidate a chat session. A configuration change that invalidates the context must explain the reset and preserve the conversation text.
- Run a fixed sustained test with periodic measurements. Compare fixed peak performance against the sustained policy by valid aggregate throughput, thermal status, and an energy proxy.
- Treat battery current/temperature as device-exposed telemetry, not calibrated power measurement. State that limitation in every report.

Exit gate: the app can explain why it selected a configuration, fall back safely to CPU, and demonstrate that sustained mode avoids or delays thermal invalidation relative to a fixed aggressive configuration.

### Phase 6 - Release and Submission

- Make the source repository reproducible from a clean clone, retain MIT attribution, upstream pin, third-party notices, and model terms.
- Publish an arm64 Release APK and SHA-256 through GitHub Release.
- Publish a concise result table covering CPU baseline, best GPU candidate, memory/cache experiment, and speculative/QNN result when valid.
- Record an English demonstration under three minutes: device capability detection, model import, chat, benchmark, policy selection, and report export.
- Re-check the Devpost rules and deadline immediately before submission.

## Benchmark Design

The existing `docs/benchmark-protocol.md` remains the CPU reference protocol until the corresponding code lands. The multi-backend protocol will version each run with:

- device fingerprint: SoC, CPU/GPU name, Android build, ABI, app version, APK hash;
- runtime configuration: backend, offload level, threads, batch values, Flash Attention state, KV cache types, speculative mode, and policy;
- model provenance: target/draft model name, source, byte size, and SHA-256;
- timing: load time, prompt-prefill throughput, TTFT, decode throughput, output count, total latency, and cold/warm session label;
- resources: app memory, thermal status before/after, battery temperature/current when exposed, and OOM/fallback events;
- validity: warm-up flag, invalid reason, raw repetition data, plus median and spread for published comparisons.

Use one controlled Chinese prompt and deterministic sampling for backend/algorithm comparisons. Use five valid measured runs after warm-up whenever thermals permit. Randomize configuration order or cool the device between blocks to avoid assigning a thermal trend to one backend.

## Decision Rules

- No configuration is called "optimized" without an on-device comparison to the same CPU baseline and model hash.
- A candidate becomes the product default only after it wins the selected objective in repeated valid runs and passes the quality smoke test.
- A backend that is unavailable, unstable, or slower is reported as a result and automatically falls back to CPU; it is not hidden.
- Do not claim GPU/NPU support from a compiled library alone. Require runtime backend logs and real device output.
- Do not claim energy savings from current or temperature alone. Call it battery/thermal telemetry unless calibrated external power evidence exists.
- Do not let experimental HTP, AOT QNN, Vulkan, or an incompatible draft model block the CPU/OpenCL submission path.

## Planned Code Areas

| Area | Planned responsibility |
| --- | --- |
| `android/lib/src/main/cpp/ai_chat.cpp` | Persistent session state, context/cache settings, backend capability reporting, split timers, and accelerator configuration |
| `android/lib/src/main/java/com/arm/aichat/` | Stable runtime configuration and measurement schema shared by chat and benchmark paths |
| `android/app/src/main/java/com/example/llama/MainActivity.kt` | Backend/policy UI, benchmark orchestration, thermal policy trace, import/export presentation |
| `android/lib/build.gradle.kts` and native CMake files | Separate CPU/OpenCL/HTP build profiles with explicit SDK/dependency provenance; QNN remains a later alternative |
| `docs/benchmark-protocol.md` | Versioned multi-backend protocol after implementation is verified |
| `docs/submission.md` and `README.md` | Evidence tables, reproducibility instructions, limitations, and attribution |

## Near-Term Queue

1. Capture the OnePlus GPU renderer, OpenCL availability, CPU topology, and accelerator capabilities.
2. Implement persistent chat session/KV reuse and split timing.
3. Build the isolated Adreno OpenCL profile and complete the first CPU vs GPU comparison.
4. Build the HTP profile only after the Hexagon SDK is available; enforce the Q4_0/Q8_0 and `n_ubatch=16` smoke gate.
5. Decide from real results whether EAGLE-3, DFlash, or AOT QNN is the next investment.

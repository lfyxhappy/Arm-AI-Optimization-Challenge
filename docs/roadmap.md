# Competition Roadmap

Last updated: 2026-07-18

## Current direction

The detailed active plan is [Modern Mobile LLM Acceleration Plan](modern-mobile-llm-acceleration-plan.md). It upgrades the project from a CPU-only thread-tuning workbench to a measured multi-backend mobile LLM workbench:

`CPU KleidiAI baseline -> Snapdragon GPUOpenCL/HTP matrix -> attention/KV memory optimization -> speculative decoding -> thermal-aware backend policy`

The CPU phases below remain the baseline and release-fallback work. GPUOpenCL is the first accelerator candidate; native Hexagon HTP is a separately quality-gated experiment, while AOT QNN is now a later fallback path. See [Snapdragon Backend Build Profiles](snapdragon-backend-profiles.md).

The profile/runtime plumbing is now in place and a real OnePlus 13 CPU Q4 baseline has been captured. This is not accelerator validation: the next hardware-dependent work is to install the Qualcomm SDKs, produce APKs, inspect their native libraries, and collect comparable GPU/HTP evidence.

## Competition requirements to satisfy

The entry is for Track 3: Mobile AI. The submission must be a public, open-source project that runs on an Arm Android device and demonstrates new work beyond an upstream open-source base.

- Optimize and explain mobile AI behavior with evidence: model size, memory, TTFT, responsiveness, offline/privacy behavior, and power or thermal awareness.
- Open-source reuse is allowed, but licences and third-party notices must be preserved. A repackaged llama.cpp demo is insufficient; this project must clearly identify its added quantization workflow, device-specific thread tuning, thermal-aware measurements, and portable reports.
- Publish the source, assets, build instructions, test instructions, model preparation scripts, and a detectable MIT or Apache 2.0 licence in the public GitHub repository.
- Make a free test build or functional project available to judges through the judging period. The intended artifact is an arm64 APK in a GitHub Release with SHA-256.
- Disclose third-party SDKs, runtimes, models, data, and licences. Do not claim llama.cpp, KleidiAI, or OpenMP as original work.
- Provide a short English demonstration, recommended to stay under three minutes, showing the target device and real feature output.
- Re-check the Devpost rules before submission. The currently tracked deadline is 2026-08-15 07:00 GMT+8.

The judging emphasis previously checked for this event is technical implementation (40%), UX/DX (15%), impact (20%), and wow factor (25%). The evidence should therefore show both reproducible numbers and a clear, usable device workflow.

## Execution plan

### Phase 1 — Device-ready smoke test (current)

- [x] Build and install the arm64 Debug APK on the connected OnePlus device.
- [x] Download and hash the verified `Qwen3-4B-Q4_K_M.gguf` (`7485fe6f...`).
- [x] Push the model to the device's Download directory.
- [x] Add a debug-only private-storage staging path for ADB testing when the ColorOS picker hides `.gguf` files.
- [x] Re-push the verified model after ADB reconnect and verify metadata, CPU load, and no crash during the benchmark flow.
- [ ] Re-run the Chinese response and cancellation smoke test on the Release candidate.

### Phase 2 — Real benchmark evidence

- [x] Run the fixed Q4 Instruct benchmark flow: 24 records with one warm-up plus five measured slots per thread setting; the 8-thread block was invalidated by `SEVERE` thermal status.
- [x] Export JSON, CSV, and the HTML Baseline vs Optimized report.
- [x] Preserve device build, app version, model hash, thermal status, memory, TTFT, and tokens/s with the valid CPU result table.

### Phase 3 — Base model matrix

- [ ] Convert the existing local `Qwen3-4B-Base` safetensors to F16 GGUF.
- [ ] Quantize the F16 artifact to Q8_0 and Q4_K_M with the project script.
- [ ] Hash and record all three artifacts without committing model files.
- [ ] Repeat the valid OnePlus benchmark for F16/Q8_0/Q4_K_M and make the comparison table.

### Phase 4 — Release hardening

- [ ] Replace the generated upstream snapshot with a true submodule at the pinned llama.cpp commit, or document and verify the exact snapshot workflow for a clean clone.
- [ ] Remove the local proxy-only TLS workaround from release instructions; vendor or fetch KleidiAI through a trusted chain.
- [ ] Run a Release build, install it on the OnePlus, and verify import, switching, cancellation, export, OOM/context/thermal error messaging.
- [ ] Check APK size and SHA-256; attach the APK to a public GitHub Release.

### Phase 5 — Submission package

- [x] Update the English README with the Q4 CPU result and known thermal limitation.
- [ ] Add the final third-party notices and model terms.
- [ ] Record the three-minute English demo: import, chat, auto tune, report, export.
- [ ] Re-check the rules and deadline, then submit the public repository, Release APK, results, and Devpost explanation.

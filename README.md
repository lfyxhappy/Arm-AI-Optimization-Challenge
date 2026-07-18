# Arm Mobile AI Optimization Workbench

An offline Android workbench for reproducible on-device LLM quantization, CPU thread tuning, and quality-gated Snapdragon backend experiments on Arm64 phones. It is a Track 3: Mobile AI entry for the [Arm AI Optimization Challenge](https://arm-ai-optimization-challenge.devpost.com/).

The app extends the official `llama.cpp` Android sample with functionality that is owned by this project: deterministic model preparation, automatic `2/4/6/8` thread exploration, thermal-aware UI inference measurements, and JSON/CSV/HTML result exports. It does **not** claim `llama.cpp`, KleidiAI, or OpenMP as original work.

## What It Measures

- GGUF import into private app storage with SHA-256 verification and metadata display.
- Offline streaming chat, system prompt, temperature, maximum output tokens, and stop generation.
- TTFT, output token count, UI-streaming tokens/s, process `VmHWM`, Android thermal state, battery temperature, and available battery current.
- Auto tuning: each thread setting receives one warm-up plus five measured runs. A run at `THERMAL_STATUS_SEVERE` or above is exported as invalid and excluded from the recommendation.
- Each auto-tune action starts a fresh benchmark session. Interactive chat and stopped-generation measurements remain separate, so they cannot affect the exported benchmark, recommendation, or comparison report.
- Context experiment controls: Flash Attention policy, shared F16/Q8_0/Q4_0 KV-cache type, and `batch`/`ubatch` are applied before model loading and recorded with every run. Quantized KV-cache experiments require Flash Attention to be `Auto` or `On`.
- Each formal auto-tune stage is automatically archived as immutable JSON, CSV, and HTML in app-private storage. The app can export the current stage or a cumulative all-stage CSV, so a later experiment cannot overwrite its baseline.
- In each APK, a runtime selector records the compiled profile, CPU/accelerator preference, requested and active device, registered backends/devices, observed layer offload, fallback reason, and batch settings. These fields are included in JSON, CSV, and HTML exports.
- One-click JSON, CSV, and `Baseline vs Optimized` HTML exports.

## Scope

The portable reference is **`arm64-v8a` CPU inference** with the upstream `llama.cpp` CPU path, KleidiAI, and OpenMP. The repository also exposes opt-in `snapdragon-opencl` and `snapdragon-htp` build profiles. They are integration paths, not performance claims: no accelerator result is publishable until the expected device, actual layer-offload log, coherent Chinese smoke response, and repeated thermally valid measurements have been captured. QNN and Vulkan remain later comparative experiments.

## Build

Prerequisites: JDK 17, Android SDK Platform 36, NDK `28.1.13356709`, CMake `3.31.6`, and Git Bash on Windows.

1. Materialize the fixed upstream source:

   ```powershell
   ./tools/fetch-llama.ps1
   ```

   This fetches `ggml-org/llama.cpp` commit `e8f19cc0ad70a243c8012bf17b4be601abfc8ea2` into `third_party/llama.cpp`.

2. Build an arm64 debug APK:

   ```powershell
   ./tools/build-android.ps1
   ```

   The helper maps the checkout to a temporary ASCII drive letter before invoking Gradle. This avoids a Windows test-worker classpath failure when the repository path contains Chinese or other non-ASCII characters. Run the aggregation regression test with:

   ```powershell
   ./tools/build-android.ps1 -Task testDebugUnitTest
   ```

   If the upstream KleidiAI release cannot be fetched reliably, point CMake at a separately verified unpacked `v1.24.0` source release (it must contain `kai/`):

   ```powershell
   $env:AICHAT_KLEIDIAI_SOURCE_DIR = 'C:\path\to\kleidiai-v1.24.0-src'
   ./tools/build-android.ps1 -Backend cpu
   ```

   The default remains the upstream llama.cpp FetchContent download. See [Snapdragon Backend Build Profiles](docs/snapdragon-backend-profiles.md) for CPU, OpenCL, and HTP commands and SDK gates.

3. Install on an Arm64 Android device:

   ```powershell
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```

The model is deliberately not packaged in the APK. Import it using the Android file picker; for development, first copy a GGUF file to the device with `adb push`.

## Reviewer Test APK

The public [v0.1.0 reviewer APK release](https://github.com/lfyxhappy/Arm-AI-Optimization-Challenge/releases/tag/v0.1.0-reviewer) includes an `arm64-v8a` APK and `SHA256SUMS.txt`. It is deliberately marked as a pre-release because it uses the standard Android debug keystore for evaluator testing; it is not a production-signed distribution. Models are not bundled and must be imported separately.

## Model Matrix

| Model | Required artifacts | Purpose |
| --- | --- | --- |
| `Qwen3-4B-Base` | F16 GGUF, Q8_0 GGUF, Q4_K_M GGUF | Size and performance matrix |
| `Qwen3-4B` | Q4_K_M GGUF | Chinese chat demonstration |

Download source weights only from the model publisher and accept its licence before use. `models/`, generated GGUF files, APKs, and local benchmark results are ignored by Git. See [tools/prepare-model.ps1](tools/prepare-model.ps1) for conversion, quantization, and a SHA-256 manifest.

## Benchmark Protocol

The app fixes the Chinese benchmark prompt, system prompt, temperature, and maximum output tokens while it tests 2, 4, 6, and 8 threads. For each setting it executes one warm-up and five measured runs. The recommendation first maximizes the mean valid tokens/s and then minimizes mean TTFT. Full details and reporting fields are in [docs/benchmark-protocol.md](docs/benchmark-protocol.md).

Use a physical OnePlus 13 for submission numbers. Emulator results and a successful build do not validate Arm performance or thermals.

After every completed stage, pull the app-private archive before changing configuration or reinstalling the debug app:

```powershell
./tools/pull-benchmark-stages.ps1 -Serial <adb-serial>
```

The helper writes a timestamped directory with all stage JSON/CSV/HTML files and a SHA-256 manifest under ignored `benchmarks/output/` storage.

## Verified CPU Result

On 2026-07-18, the CPU Debug APK was measured on a OnePlus 13 with the verified `Qwen3-4B-Q4_K_M` GGUF. The CPU/KleidiAI/OpenMP path selected 6 threads from five valid measured runs per candidate:

| Threads | Valid runs | Mean TTFT (ms) | Mean tokens/s |
| ---: | ---: | ---: | ---: |
| 2 | 5 | 1847.92 | 6.12 |
| 4 | 5 | 1211.44 | 8.80 |
| 6 | 5 | 856.28 | 12.37 |

The 8-thread block reached `THERMAL_STATUS_SEVERE` and is retained only as invalid telemetry, not as a performance comparison. See [the full OnePlus result record](docs/oneplus13-cpu-q4-results-20260718.md) for hashes, device fingerprint, memory evidence, and limitations.

## Third-Party Attribution

- [llama.cpp](https://github.com/ggml-org/llama.cpp), MIT. Fixed upstream commit: `e8f19cc0ad70a243c8012bf17b4be601abfc8ea2`.
- KleidiAI and OpenMP are enabled by the upstream Android CPU build configuration; they are third-party/upstream capabilities, not this project's claimed invention.
- Qwen models are provided by Qwen under their model terms.

See [NOTICE.md](NOTICE.md) and [third_party/UPSTREAM.md](third_party/UPSTREAM.md).

## Submission Checklist

Before Devpost submission: publish this repository, attach a release APK plus SHA-256, populate real OnePlus 13 F16/Q8/Q4 results, and record a sub-three-minute English device demonstration. The required evidence outline is in [docs/submission.md](docs/submission.md).

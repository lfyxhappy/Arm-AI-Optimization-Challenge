# OnePlus 13 CPU Q4 Result

## Scope

This is the first device-side CPU reference result for the Arm Mobile AI Optimization Workbench. It measures the upstream llama.cpp CPU path with KleidiAI and OpenMP; it is not a GPU, HTP, QNN, or energy-efficiency claim.

## Device And Build

| Field | Value |
| --- | --- |
| Device | OnePlus 13 (`PJZ110`) |
| SoC property | `SM8750` |
| Android | Android 16, API 36 |
| Build fingerprint | `OnePlus/PJZ110/OP5D0DL1:16/BP2A.250605.015/V.4e5c566-2a38f4c-2a4ca91:user/release-keys` |
| App | `com.lfyxhappy.armai` 0.1.0 Debug |
| APK | `app-debug.apk`, 56,342,955 bytes, SHA-256 `48ac8831c30509a7e875c81fcfadd1767e9303c1b38bcf0bb3952270c65dac33` |
| Model | `Qwen3-4B-Q4_K_M.gguf`, 2,497,280,256 bytes |
| Model SHA-256 | `7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5` |
| Runtime | CPU profile, `auto` preference resolving to CPU, 512 batch / 512 ubatch |

The app used the fixed Chinese benchmark prompt, default system prompt, temperature 0.3, maximum 96 output tokens, and fresh context per run.

The final UI-layout validation rebuild has SHA-256 `bcd136cb4b4644774c1b95b2887f6bd42d307b8ed9540e080474c3e86755a60f`. It only changes the export-control layout to prevent button text wrapping; the benchmark result above remains tied to the earlier APK hash.

## Valid Result

Each thread count received one warm-up slot plus five measurement slots. Only valid, non-warm-up samples contribute to the table and recommendation.

| Threads | Valid runs | Mean TTFT (ms) | Mean tokens/s | Max `VmHWM` (KB) |
| ---: | ---: | ---: | ---: | ---: |
| 2 | 5 | 1847.92 | 6.12 | 9,139,016 |
| 4 | 5 | 1211.44 | 8.80 | 9,139,016 |
| 6 | 5 | 856.28 | 12.37 | 9,139,016 |

The workbench selected 6 threads. Relative to the 2-thread baseline, that is a 101.98% increase in mean generation throughput and a 991.63 ms (53.66%) reduction in mean TTFT.

## Thermal Boundary

The 8-thread warm-up ended at `THERMAL_STATUS_SEVERE`. The app retained that warm-up as invalid and skipped the following five measurement slots before native inference. Those six records remain in the raw JSON and CSV for auditability, but they are not a zero-memory or zero-performance result and are excluded from the table above.

The post-run `dumpsys meminfo` snapshot recorded total PSS of 6,184,429 KB. PSS and `VmHWM` are different Linux/Android memory measures and are preserved separately rather than conflated.

## Export Evidence

The raw files are intentionally ignored by Git under `benchmarks/output/oneplus13-20260718-v3/`.

| Artifact | SHA-256 |
| --- | --- |
| JSON | `53f05d6d9a075b3af97925f032072758d8c3fe2811423ad55fab40ac56de7f96` |
| CSV | `1e7d98309671dfbf01facbf307f8db67da9c2a0b0d1a6c092a51060d28f49274` |
| HTML report | `7c0846bd3a7430d9299d84304f6b8f40e4b3ef4cc23e5d8eb220301625187bc2` |

The app loaded the model, completed the benchmark/export flow, and the package-scoped log check found no Java or native crash signature.

## Limitations

- This is one Q4 CPU baseline, not the required F16/Q8_0/Q4_K_M model matrix.
- The thermal ceiling prevented a valid 8-thread comparison; cool-down and randomized-order repeats are needed before making sustained-performance claims.
- No Qualcomm OpenCL, HTP, QNN, Vulkan, or power-meter result is claimed here.

# OnePlus 13 Device Capability Evidence

## Scope

This is a complete ADB capability capture made at `2026-07-18T14:38:29Z` for the physical OnePlus 13 used by this project. It establishes what hardware and system interfaces are present before a Snapdragon backend is attempted. It is **not** an OpenCL, HTP, Vulkan, QNN, or inference-performance result.

## Device

| Field | Observed value |
| --- | --- |
| Product | OnePlus `PJZ110` / `OP5D0DL1` |
| SoC property | Qualcomm `SM8750` (`ro.soc.manufacturer=QTI`) |
| Android | Android 16, API 36 |
| ABI | `arm64-v8a` |
| Build fingerprint | `OnePlus/PJZ110/OP5D0DL1:16/BP2A.250605.015/V.4e5c566-2a38f4c-2a4ca91:user/release-keys` |
| GPU renderer | Qualcomm Adreno (TM) 830, OpenGL ES 3.2 |

## CPU and GPU observations

The kernel exposes eight online CPU entries. The reported maximum frequencies are `3,532,800 kHz` for CPU 0–5 and `4,320,000 kHz` for CPU 6–7. `/proc/cpuinfo` includes Arm dot-product, FP16, `i8mm`, and `bf16` feature flags. These are maximum-frequency and capability observations, not a sustained-clock guarantee.

The system properties identify Adreno EGL and Vulkan drivers. The package feature list includes `android.hardware.vulkan.compute`; the vendor library inventory includes `libOpenCL.so`, `libOpenCL_adreno.so`, `libEGL_adreno.so`, and `libGLESv2_adreno.so`.

## Engineering decision

The device is a valid candidate for the planned `snapdragon-opencl` experiment, so GPUOpenCL remains the first accelerator path. The host currently has no configured Qualcomm OpenCL SDK, Hexagon SDK, or Hexagon tools installation. A vendor loader library on the phone does not prove that the app can build, load, offload layers to, or produce correct output with an accelerator backend.

The next valid accelerator claim requires all of the following:

- a successful SDK-gated APK build and package inspection;
- a runtime log showing the expected backend, active device, and positive layer offload;
- a coherent fixed Chinese smoke response;
- repeated non-`SEVERE` measurements against the same-model CPU control.

HTP remains a separate experiment: the GPU/OpenCL observations do not establish HTP runtime availability or model compatibility.

## Raw evidence

The complete ignored capture is retained locally in `benchmarks/output/device-capabilities-20260718T143827213Z/`. Its `manifest.json` records the capture timestamp and the `getprop`, SurfaceFlinger, CPU, vendor-library, and feature inventories. The prior capability directory with a failed shell quoting loop is intentionally not used for these conclusions.

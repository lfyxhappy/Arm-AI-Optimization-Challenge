# Snapdragon Backend Build Profiles

Last updated: 2026-07-18

The app uses one portable CPU profile and two opt-in Snapdragon profiles. All profiles retain the same Kotlin/UI code and the same GGUF import flow. The selected profile is a **build-time capability set**, not evidence that the accelerator has produced correct output on a device. Within an accelerator-capable APK, the UI can request the compiled accelerator, force a CPU baseline, or use the profile default; every choice is exported with its outcome.

| Gradle property | Native backend | Default device | Intended model formats | Status |
| --- | --- | --- | --- | --- |
| `cpu` | CPU + KleidiAI + OpenMP | CPU | F16, Q8_0, Q4_K_M | Stable reference |
| `snapdragon-opencl` | ggml OpenCL with Adreno kernels | `GPUOpenCL` | Existing Q4_K_M plus comparison artifacts | First accelerator candidate |
| `snapdragon-htp` | ggml Hexagon HTP | `HTP0` | Q4_0, Q8_0, MXFP4 candidates | Experimental, quality-gated |

## Build Commands

Use the Windows build helper, which runs Gradle from a temporary ASCII drive mapping so non-ASCII checkout paths work reliably.

```powershell
# Portable reference build (default)
./tools/build-android.ps1 -Backend cpu

# Adreno OpenCL build after installing/configuring the Qualcomm OpenCL SDK
$env:OPENCL_SDK_ROOT = 'C:\path\to\OpenCL-SDK'
./tools/build-android.ps1 -Backend snapdragon-opencl

# Hexagon HTP build after installing/configuring the Hexagon SDK
$env:HEXAGON_SDK_ROOT = 'C:\path\to\Hexagon-SDK'
$env:HEXAGON_TOOLS_ROOT = 'C:\path\to\Hexagon-tools' # optional when the SDK metadata resolves it
./tools/build-android.ps1 -Backend snapdragon-htp
```

The OpenCL profile requires a valid Android OpenCL loader/header setup discoverable from `OPENCL_SDK_ROOT`. The HTP profile requires the Hexagon SDK and Android prebuilt libraries. Neither SDK is bundled, downloaded automatically, or committed to this repository.

For a slow or offline KleidiAI fetch, set `AICHAT_KLEIDIAI_SOURCE_DIR` to a separately verified unpacked KleidiAI `v1.24.0` source tree containing `kai/`. This is an explicit local FetchContent override; a clean build without it still uses the upstream URL.

## Packaging and Runtime Checks

The native layer dynamically scans `ApplicationInfo.nativeLibraryDir`. A successful accelerator APK must contain the expected backend libraries in `lib/arm64-v8a/`:

- OpenCL: `libggml-opencl.so` and any required OpenCL loader dependency.
- HTP: `libggml-hexagon.so` plus the generated `libggml-htp-v*.so` skeleton libraries. The project CMake file explicitly makes the skeleton targets dependencies and copies them into the Android native output directory; inspect the final APK before treating that as verified.

Before app integration claims, run the upstream backend-op test and a deterministic Chinese prompt on the physical target. The app and export report the build profile, preference, requested device, active device, registered backends/devices, observed layer offload, `batch`/`ubatch`, and fallback reason. A requested accelerator without an observed layer-offload log is deliberately reported as `unverified`, not as an active accelerator.

## HTP Safety Gate

`snapdragon-htp` starts with `n_ubatch=16`. A public OnePlus 13/Qwen reproduction reported corrupted prefill output at `n_ubatch >= 32`; do not increase this value until the exact device, upstream pin, model quantization, and prompt pass a quality smoke test.

HTP experiments must use a separately recorded Q4_0/Q8_0/MXFP4 artifact. Do not assume the existing Q4_K_M artifact is the correct HTP format. HTP has a per-session mapped-memory limit around 3.5 GB, so preserve context size, model size, and session count in every result.

## Acceptance Gate

An accelerator profile may enter a public report only after all of the following pass on the target phone:

1. The app reports the expected registered backend and requested device.
2. The model loads without CPU fallback and logs actual layer offload.
3. A deterministic Chinese smoke response is coherent and matches the CPU behavior qualitatively.
4. Five thermally valid runs use the same model hash, prompt, sampling parameters, and context configuration as the CPU reference.
5. JSON/CSV/HTML export records backend, offload setting, batch/ubatch, cache precision, and any fallback reason.

If any gate fails, keep the result as an experimental negative finding and use the CPU profile as the release fallback.

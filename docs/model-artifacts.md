# Downloaded Model Artifacts

Models are ignored by Git and must not be committed. The following artifact was downloaded for the OnePlus 13 smoke test.

| Artifact | Source | Bytes | SHA-256 | Status |
| --- | --- | ---: | --- | --- |
| `Qwen3-4B-Q4_K_M.gguf` | [Qwen/Qwen3-4B-GGUF](https://hf-mirror.com/Qwen/Qwen3-4B-GGUF) | 2,497,280,256 | `7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5` | Re-downloaded, host smoke-tested, staged, and measured on the OnePlus 13 CPU path |

The file header was verified as `GGUF`. The first local download had the expected byte count but a different SHA-256 and produced unreadable output in both Android and host `llama.cpp`; it is retained locally with a `.corrupt-3f665bfd` suffix for diagnosis. The replacement matches the Hugging Face mirror's Git-LFS object hash and produced a normal Chinese response in host `llama.cpp`. On the tested ColorOS file picker, an ADB-created `.gguf` file was not listed, so the debug-only private-storage staging path remains the device-test route.

## Qwen3-4B-Base Quantization Matrix

The local reproducible preparation run completed on 2026-07-18. These are model-artifact facts, not device-performance claims.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `Qwen3-4B-Base-F16.gguf` | 8,051,284,768 | `b92f4e115f74d9041dbc1ecb688c7db916483e4f23487b6c08555448327bdef4` |
| `Qwen3-4B-Base-Q8_0.gguf` | 4,280,404,768 | `502aca05edd5e53975011766052a49a458808811a4f9b2e5bda3cb884384d3dd` |
| `Qwen3-4B-Base-Q4_K_M.gguf` | 2,497,280,288 | `e06a70c714daf40d69906c2dbc6ac5c181fdd7378dd5c743b0a0638013323013` |

`tools/prepare-model.ps1` now writes both a current `<model>-manifest.json` and a timestamped immutable manifest beside the ignored GGUF files. It reuses an existing artifact by default; only `-Rebuild` intentionally regenerates it. Each manifest contains the hashes of the converter and quantizer executable, so later results can be tied to the exact preparation toolchain.

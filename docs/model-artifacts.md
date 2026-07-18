# Downloaded Model Artifacts

Models are ignored by Git and must not be committed. The following artifact was downloaded for the OnePlus 13 smoke test.

| Artifact | Source | Bytes | SHA-256 | Status |
| --- | --- | ---: | --- | --- |
| `Qwen3-4B-Q4_K_M.gguf` | [Qwen/Qwen3-4B-GGUF](https://hf-mirror.com/Qwen/Qwen3-4B-GGUF) | 2,497,280,256 | `7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5` | Re-downloaded, host smoke-tested, staged, and measured on the OnePlus 13 CPU path |

The file header was verified as `GGUF`. The first local download had the expected byte count but a different SHA-256 and produced unreadable output in both Android and host `llama.cpp`; it is retained locally with a `.corrupt-3f665bfd` suffix for diagnosis. The replacement matches the Hugging Face mirror's Git-LFS object hash and produced a normal Chinese response in host `llama.cpp`. On the tested ColorOS file picker, an ADB-created `.gguf` file was not listed, so the debug-only private-storage staging path remains the device-test route.

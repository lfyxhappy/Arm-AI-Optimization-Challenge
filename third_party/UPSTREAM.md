# Upstream Source Pin

- Repository: `https://github.com/ggml-org/llama.cpp`
- Commit: `e8f19cc0ad70a243c8012bf17b4be601abfc8ea2`
- Source path used by CMake: `third_party/llama.cpp`
- Materialization command: `../tools/fetch-llama.ps1`

The directory itself is generated locally and ignored so that this repository does not duplicate the upstream source history. The fetch script downloads the exact GitHub codeload archive and verifies that the extracted directory corresponds to the pinned commit. For the public release, replace this generated checkout with a Git submodule at the same URL and commit when the Git transport is available.

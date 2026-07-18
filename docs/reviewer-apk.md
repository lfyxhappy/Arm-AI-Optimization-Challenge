# Reviewer APK Packaging

## Purpose

`reviewer` is a reproducible arm64 evaluator build. It inherits the optimized `release` configuration, has the version suffix `-reviewer`, and is signed with Android's standard debug signing configuration. It deliberately needs no private keystore, password, or secret in this repository.

It is suitable for a disclosed GitHub pre-release evaluator APK. It is **not** a production-signed distribution and must not be described as one.

## Build and verify

```powershell
./tools/package-reviewer-apk.ps1
```

The helper runs `assembleReviewer`, verifies the Android Debug certificate and sole `arm64-v8a` ABI, then writes a timestamped ignored directory under `scratch/release/`. It contains the renamed APK, `SHA256SUMS.txt`, and `reviewer-artifact-manifest.json`. The GitHub Release should contain the APK plus the SHA-256 file.

To package a build that has already completed, use `./tools/package-reviewer-apk.ps1 -SkipBuild`. Pass `-OutputDirectory <empty-directory>` only when an explicit output location is needed; the helper refuses to overwrite non-empty directories or an existing artifact.

## Production boundary

`./tools/build-android.ps1 -Task assembleRelease` intentionally writes `app-release-unsigned.apk` unless a private production signing configuration is supplied outside this repository. Do not upload, distribute, or ask reviewers to install that unsigned file.

A production signing key and its passwords must remain outside source control. Production signing setup is a release-owner action; this project does not generate, store, or print keystores or credentials.

# Wallet-Rescue-V2

## Robat2 Offline AI

Robat2 is being converted into an on-device coding assistant. The offline branch removes the GitHub token and cloud API workflow from the Android app. The app manifest intentionally has no INTERNET permission.

### Offline architecture
- Native inference: llama.cpp built for Android arm64-v8a with Android NDK.
- Model: Qwen2.5-Coder-3B-Instruct GGUF, Q3_K_M quantization.
- Model asset is downloaded and SHA-256 verified at build time, then bundled into the Robat2 APK.
- On first launch the model is copied into app-private storage and loaded locally.
- Runtime prompts and generated responses remain on the device; no network client is included in the Robat2 app.

### Build
The Android Gradle build downloads the model from the official Qwen Hugging Face repository, verifies SHA-256, builds llama.cpp through CMake, and packages the model into the APK. The workflow requires Android NDK 27.2.12479018 and CMake 3.22.1.

Model file: qwen2.5-coder-3b-instruct-q3_k_m.gguf
Expected SHA-256: fc3937db7dda9d9ef68ce1f63b5a84ac850ec3c07578461d645e5a88509348e3

### Important device requirements
The model is approximately 1.72 GB. The APK is correspondingly very large, and first launch needs additional free storage to copy the model into app-private storage. Inference also needs several GB of available RAM; performance varies by device and thermal state. This is an initial 3B model, not an unlimited or frontier-scale coding model.

### Wallet Rescue
Wallet Rescue remains a separate Android module. Its blockchain balance and transaction checks still require internet access. The offline Robat2 module does not gain access to wallet secrets and must never request seed phrases or private keys.

# TTS SDK

Kotlin Multiplatform on-device Text-to-Speech library using ONNX Runtime.
Replaces CopiloTTS with a new in-house KMP library targeting the supertonic-2 model.

Targets: Android (API 26+), iOS (16+)

## Project Structure

```
tts-sdk/
  library/                  # KMP library module
    commonMain/             # Shared API + orchestration
    androidMain/            # ONNX Runtime Android + AudioTrack playback
    iosMain/                # Kotlin bridge to Swift TTSEngineHandler
  tts-model-pack/           # Bundled supertonic-2 model files (Android asset pack)
  sample/                   # Android Compose sample app
  iosApp/                   # iOS SwiftUI sample app
```

## Quick Start — Android

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 35

### Model Setup

The supertonic-2 model files are bundled as an Android asset pack in `tts-model-pack/`.
The ONNX files in the repo may be Git LFS pointers. To get the real model files:

```bash
cd tts-model-pack/src/main/assets/tts_models/Supertone_supertonic-2/
# Replace LFS pointers with actual model files from your local copy:
cp ~/Downloads/supertonic-2/onnx/*.onnx .
cp ~/Downloads/supertonic-2/onnx/*.json .
cp ~/Downloads/supertonic-2/voice_styles/*.json .
```

### Steps

1. Open the project root in Android Studio.
2. Wait for Gradle sync to complete.
3. Select the `sample` run configuration.
4. Run on a device or emulator (API 26+).

The sample app automatically extracts the bundled model from assets to internal storage
on first launch (same pattern as the Kima app).

## Quick Start — iOS

### Prerequisites
- Xcode 15+ with iOS 16 SDK
- [xcodegen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`)

### Steps

1. Build the KMP framework:
   ```bash
   ./gradlew :library:linkDebugFrameworkIosSimulatorArm64
   ```

2. Generate the Xcode project:
   ```bash
   cd iosApp
   ./generate-xcode-project.sh
   ```

3. Add the supertonic-2 model directory to the Xcode project as a resource folder.

4. Select an iOS 16+ simulator and run.

## Library API

```kotlin
val sdk = TtsSdk(TtsConfig(
    modelDir = "/path/to/supertonic-2/onnx",
    defaultVoiceStyle = "M2",
))
sdk.initialize()

sdk.setCallback(ttsCallback {
    onStart { id -> println("Started: $id") }
    onDone  { id -> println("Done: $id") }
    onError { id, err -> println("Error: $err") }
})

sdk.speak("Hello, world!")
// sdk.speak(SpeakRequest(text = "Bonjour", voiceStyle = "F1", language = "fr"))
// sdk.stop()
// sdk.shutdown()
```

### Bundled Model Loading

The sample app uses the bundled model support to auto-extract models from assets:

```kotlin
// In Application.onCreate():
AndroidContextHolder.set(this)

// Then at runtime:
val modelPath = getBundledModelPath("Supertone/supertonic-2")
val sdk = TtsSdk(TtsConfig(modelDir = modelPath!!))
sdk.initialize()
```

## Dependencies

| Dependency | Artifact | Version |
|---|---|---|
| ONNX Runtime (Android) | `com.microsoft.onnxruntime:onnxruntime-android` | 1.19.2 |
| ONNX Runtime (iOS) | SPM: `onnxruntime-swift-package-manager` | 1.19.2+ |
| Kotlin Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.9.0 |
| Compose BOM (sample) | `androidx.compose:compose-bom` | 2024.12.01 |

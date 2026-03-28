# TTS Supertonic 2

An on-device text-to-speech library for [Android](https://www.android.com/)
and [iOS](https://developer.apple.com/ios/) via
[Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html).
Powered by the [Supertone supertonic-2](https://huggingface.co/Supertone/supertonic-2) model
running locally through ONNX Runtime — no network calls at inference time.

10 built-in voices (5 male, 5 female) with support for English, Korean, Spanish, Portuguese,
and French.

## Quick Start

Add the dependency to your `build.gradle.kts`:

```kotlin
implementation("coach.kima.tts:tts-supertonic-2:1.0.0")
```

## Model Setup

The supertonic-2 ONNX model files (~255 MB) must be bundled with your app. Download them from
[HuggingFace](https://huggingface.co/Supertone/supertonic-2) and place them in your app's
assets (Android) and bundle resources (iOS).

The model is licensed under [OpenRAIL-M](https://huggingface.co/Supertone/supertonic-2/blob/main/LICENSE).

### Android

Place the model files in your Android assets directory:

```
app/src/main/assets/tts_models/Supertone_supertonic-2/
  ├── duration_predictor.onnx
  ├── text_encoder.onnx
  ├── vector_estimator.onnx
  ├── vocoder.onnx
  ├── tts.json
  ├── unicode_indexer.json
  └── F1.json, F2.json, ... M5.json   (voice styles)
```

Initialise the context holder in your `Application.onCreate()`:

```kotlin
AndroidContextHolder.set(this)
```

Then load the bundled model:

```kotlin
val modelPath = getBundledModelPath("Supertone/supertonic-2")
val sdk = TtsSdk(TtsConfig(modelDir = modelPath!!))
sdk.initialize()
```

The SDK extracts model files from assets to internal storage on first launch.

### iOS

Add the `Supertone_supertonic-2/` directory to your Xcode project as a folder reference
so it's included in the app bundle. Then initialize with the bundle path:

```swift
let modelDir = Bundle.main.path(forResource: "Supertone_supertonic-2", ofType: nil)!
```

## Usage

### Basic speech

```kotlin
val sdk = TtsSdk(TtsConfig(
    modelDir = modelPath,
    defaultVoiceStyle = "M2",
))
sdk.initialize()

sdk.speak("Hello, world!")
```

### Callbacks

```kotlin
sdk.setCallback(ttsCallback {
    onStart { id -> println("Started: $id") }
    onDone  { id -> println("Done: $id") }
    onError { id, err -> println("Error: $err") }
})
```

### Voice selection

```kotlin
// List available voices
val voices = sdk.availableVoices.value  // F1–F5, M1–M5

// Set voice by ID
sdk.setVoiceStyle("F1")

// Or by TtsVoice object
sdk.setVoice(voices.first { it.gender == "FEMALE" })
```

### Language and options

```kotlin
sdk.speak(SpeakRequest(
    text = "Bonjour, comment allez-vous?",
    voiceStyle = "F3",
    language = "fr",
    speechRate = 1.1f,
    volume = 0.8f,
))
```

### Stop and clean up

```kotlin
sdk.stop()      // Cancel current speech
sdk.shutdown()  // Release all resources
```

### Observing state

```kotlin
// In Compose
val isSpeaking by sdk.isSpeaking.collectAsState()
val voices by sdk.availableVoices.collectAsState()
```

## Available Voices

| ID | Gender |
|----|--------|
| F1–F5 | Female |
| M1–M5 | Male |

## Supported Languages

English (`en`), Korean (`ko`), Spanish (`es`), Portuguese (`pt`), French (`fr`).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for project structure, build instructions,
and development setup.

## License

    MIT License

    Copyright (c) 2026 Nate Holland

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in
    all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.

Model weights are licensed separately under [OpenRAIL-M](https://huggingface.co/Supertone/supertonic-2/blob/main/LICENSE) by Supertone.

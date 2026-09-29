# TTS Supertonic 3

An on-device text-to-speech library for [Android](https://www.android.com/)
and [iOS](https://developer.apple.com/ios/) via
[Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html).
Powered by the [Supertone supertonic-3](https://huggingface.co/Supertone/supertonic-3) model
running locally through ONNX Runtime — no network calls at inference time.

10 built-in voices (5 male, 5 female) with support for 31 languages.

## Quick Start

Add the dependency to your `build.gradle.kts`:

```kotlin
implementation("coach.kima.tts:tts-supertonic-3:1.1.0")
```

## Model Setup

The supertonic-3 ONNX model files (~400 MB) must be bundled with your app. Download them from
[HuggingFace](https://huggingface.co/Supertone/supertonic-3) and place them in your app's
assets (Android) and bundle resources (iOS).

The model is licensed under [OpenRAIL-M](https://huggingface.co/Supertone/supertonic-3/blob/main/LICENSE).

### Android

Place the model files in your Android assets directory:

```
app/src/main/assets/tts_models/Supertone_supertonic-3/
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
val modelPath = getBundledModelPath("Supertone/supertonic-3")
val sdk = TtsSdk(TtsConfig(modelDir = modelPath!!))
sdk.initialize()
```

The SDK extracts model files from assets to internal storage on first launch.

### iOS

Add the `Supertone_supertonic-3/` directory to your Xcode project as a folder reference
so it's included in the app bundle. Then initialize with the bundle path:

```swift
let modelDir = Bundle.main.path(forResource: "Supertone_supertonic-3", ofType: nil)!
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

### Save to file

Synthesise speech directly to a WAV file without playing it:

```kotlin
val request = SpeakRequest(
    text = "This will be saved to a file.",
    voiceStyle = "F1",
    language = "en",
)

// Write to a WAV file
val sampleRate = sdk.synthesiseToFile(request, "/path/to/output.wav")

// Or get the WAV bytes in memory (e.g. for uploading)
val wavBytes: ByteArray = sdk.synthesiseToBytes(request)

// Or get raw PCM float samples for custom processing
val pcmSamples: FloatArray = sdk.synthesiseToFloatArray(request)
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

supertonic-3 supports 31 languages:

| Code | Language | Code | Language | Code | Language |
|---|---|---|---|---|---|
| `en` | English | `hr` | Croatian | `ro` | Romanian |
| `ko` | Korean | `hu` | Hungarian | `ru` | Russian |
| `ja` | Japanese | `id` | Indonesian | `sk` | Slovak |
| `ar` | Arabic | `it` | Italian | `sl` | Slovenian |
| `bg` | Bulgarian | `lt` | Lithuanian | `sv` | Swedish |
| `cs` | Czech | `lv` | Latvian | `tr` | Turkish |
| `da` | Danish | `nl` | Dutch | `uk` | Ukrainian |
| `de` | German | `pl` | Polish | `vi` | Vietnamese |
| `el` | Greek | `pt` | Portuguese | | |
| `es` | Spanish | `et` | Estonian | | |
| `fi` | Finnish | `fr` | French | | |
| `hi` | Hindi | | | | |

Codes are ISO 639-1. An unrecognised code falls back to `en`. The full list is
available at runtime from `ModelConfig.supportedLanguages`, or as
`SupportedLanguages.all` (code + English display name).

### Text normalisation

Input text is NFKD-normalised before tokenisation, matching the official
reference implementation. This is required, not cosmetic: supertonic's character
vocabulary is built on *decomposed* codepoints, so a precomposed "ä" or a Hangul
syllable such as "안" is not in the vocabulary at all. Without decomposition those
characters are dropped — Korean loses roughly half its input and synthesises
near-silence. Any character still outside the vocabulary after normalisation is
dropped rather than passed through, since the unsupported marker (`-1`) would
otherwise be read by the embedding lookup as an index from the end of the table
and silently substitute an unrelated character.

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

Model weights are licensed separately under [OpenRAIL-M](https://huggingface.co/Supertone/supertonic-3/blob/main/LICENSE) by Supertone.

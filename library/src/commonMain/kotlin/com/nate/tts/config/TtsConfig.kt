package com.nate.tts.config

data class TtsConfig(
    val modelDir: String,
    val defaultVoiceStyle: String = "M2",
    val defaultSpeechRate: Float = 1.0f,
    val defaultVolume: Float = 1.0f,
    /**
     * Where rendered phrases are kept between launches (see TtsSdk.prerender). Null keeps the
     * phrase cache in memory only. Entries are keyed by model, voice data, rate and text, so a
     * changed voice never replays the old one's audio.
     */
    val phraseCacheDir: String? = null,
    /**
     * Flow-matching steps per render when nothing asks for less: the starting value of
     * `TtsSdk.flowSteps`, and what `TtsSdk.prerender` always renders at. The model was released
     * at 5; each step is roughly a fifth of the render. Where the floor is depends on the model
     * and device: with the int8 flow network, 4 still sounded right on a Galaxy A36 and 3
     * didn't, while on an iPhone 17 Pro 4 already did not (Oct 2026 listening test). An app
     * that must share the CPU lowers `TtsSdk.flowSteps` at runtime rather than this.
     */
    val flowSteps: Int = 5,
    /**
     * ONNX intra-op threads per model session, read once at `TtsSdk.initialize()`. Null is the
     * platform default (Android: the core count clamped to 2..6; iOS: 2). Fewer threads leave
     * more of the CPU to whatever runs alongside synthesis, at the cost of render time; a
     * device debug override (`debug.tts.intra` / `-tts_intra`) wins over this.
     */
    val inferenceThreads: Int? = null,
)

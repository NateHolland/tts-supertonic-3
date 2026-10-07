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
     * Flow-matching steps per render. The model was released at 5; each step is roughly a
     * fifth of the render. Where the floor is depends on the model and device: with the int8
     * flow network, 4 still sounded right on a Galaxy A36 and 3 didn't, while on an iPhone 17 Pro
     * 4 already did not (Oct 2026 listening test).
     */
    val flowSteps: Int = 5,
)

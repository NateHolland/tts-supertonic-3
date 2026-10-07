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
)

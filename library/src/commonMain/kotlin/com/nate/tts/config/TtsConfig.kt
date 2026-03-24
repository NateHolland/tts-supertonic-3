package com.nate.tts.config

data class TtsConfig(
    val modelDir: String,
    val defaultVoiceStyle: String = "M2",
    val defaultSpeechRate: Float = 1.0f,
    val defaultVolume: Float = 1.0f,
)

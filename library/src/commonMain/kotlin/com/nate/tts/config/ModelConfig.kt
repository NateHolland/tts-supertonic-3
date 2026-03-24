package com.nate.tts.config

import com.nate.tts.model.TtsVoice

data class ModelConfig(
    val sampleRate: Int = 44100,
    val voices: List<TtsVoice> = emptyList(),
    val supportedLanguages: List<String> = listOf("en", "ko", "es", "pt", "fr"),
)

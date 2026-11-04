package com.nate.tts.model

data class TtsVoice(
    val id: String,
    val name: String,
    val gender: String? = null,
)

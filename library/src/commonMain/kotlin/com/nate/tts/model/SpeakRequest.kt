package com.nate.tts.model

data class SpeakRequest(
    val text: String,
    val voiceStyle: String? = null,
    val language: String? = null,
    val speechRate: Float = 1.0f,
    val volume: Float = 1.0f,
)

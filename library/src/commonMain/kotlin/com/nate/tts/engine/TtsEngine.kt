package com.nate.tts.engine

import com.nate.tts.config.ModelConfig

interface TtsEngine {
    fun loadModel(modelDir: String): ModelConfig
    fun synthesise(text: String, voiceStyleId: String, language: String): FloatArray
    fun playAudio(pcmData: FloatArray, sampleRate: Int, volume: Float)
    fun stopAudio()
    fun release()
    val isPlaying: Boolean
}

/**
 * Value `unicode_indexer.json` stores for a codepoint outside the model's
 * character vocabulary. It is not a usable token id — see the tokenisers.
 */
internal const val UNSUPPORTED_TOKEN = -1

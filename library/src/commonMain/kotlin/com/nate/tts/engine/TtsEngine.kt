package com.nate.tts.engine

import com.nate.tts.config.ModelConfig

interface TtsEngine {
    fun loadModel(modelDir: String): ModelConfig
    fun synthesise(text: String, voiceStyleId: String, language: String): FloatArray

    /**
     * Warm the audio output path ahead of [playAudio]. Called before [synthesise] so the
     * warm-up overlaps synthesis (already-silent time) and adds no latency, avoiding the
     * cold-start clipping of a clip's first word. Safe to no-op where a platform doesn't need it.
     */
    fun prepareStream(sampleRate: Int)

    fun playAudio(pcmData: FloatArray, sampleRate: Int, volume: Float)
    fun stopAudio()
    fun release()
    val isPlaying: Boolean
}

package com.nate.tts.engine

import com.nate.tts.config.ModelConfig

interface TtsEngine {
    /**
     * @param inferenceThreads ONNX intra-op threads per session
     *   ([com.nate.tts.config.TtsConfig.inferenceThreads]); null picks the platform default.
     */
    fun loadModel(modelDir: String, inferenceThreads: Int? = null): ModelConfig

    /**
     * @param speechRate how fast to speak, 1.0 being the model's own pace; it
     *   scales the predicted duration, so 2.0 takes half as long. Must be > 0.
     * @param flowSteps flow-matching steps for this render. Fewer is faster and, past a
     *   point, audibly worse; a debug override on the device wins over this.
     */
    fun synthesise(
        text: String, voiceStyleId: String, language: String, speechRate: Float = 1.0f,
        flowSteps: Int = 5,
    ): FloatArray

    /**
     * Install a style built at runtime, so a voice designed in the app can be
     * spoken without first being written to disk as a .json. Replaces any
     * style already registered under [id].
     */
    fun registerVoice(id: String, styleTtl: FloatArray, styleDp: FloatArray)

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

/**
 * Value `unicode_indexer.json` stores for a codepoint outside the model's
 * character vocabulary. It is not a usable token id — see the tokenisers.
 */
internal const val UNSUPPORTED_TOKEN = -1

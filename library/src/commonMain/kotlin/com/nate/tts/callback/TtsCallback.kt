package com.nate.tts.callback

import com.nate.tts.error.TtsError

interface TtsCallback {
    fun onStart(id: String)

    /**
     * Synthesis has finished and the audio is about to play. An app can show
     * "rendering" between [onStart] and this, and "speaking" after it.
     * Default does nothing, so existing callbacks need not change.
     */
    fun onPlaying(id: String) {}

    /**
     * One piece of the utterance (see SpeechChunker) is ready: [synthMs] is how long rendering it
     * took, 0 and [cached] true when it came from the phrase cache. For an app's diagnostics:
     * synthesis competes with everything else on the CPU, so this is when it ran.
     */
    fun onPieceRendered(id: String, text: String, synthMs: Long, cached: Boolean) {}
    fun onDone(id: String)
    fun onError(id: String, error: TtsError)
}

class TtsCallbackBuilder {
    private var onStartFn: ((String) -> Unit)? = null
    private var onPlayingFn: ((String) -> Unit)? = null
    private var onDoneFn: ((String) -> Unit)? = null
    private var onPieceRenderedFn: ((String, String, Long, Boolean) -> Unit)? = null
    private var onErrorFn: ((String, TtsError) -> Unit)? = null

    fun onStart(block: (id: String) -> Unit) { onStartFn = block }
    fun onPlaying(block: (id: String) -> Unit) { onPlayingFn = block }
    fun onDone(block: (id: String) -> Unit) { onDoneFn = block }
    fun onPieceRendered(block: (id: String, text: String, synthMs: Long, cached: Boolean) -> Unit) { onPieceRenderedFn = block }
    fun onError(block: (id: String, error: TtsError) -> Unit) { onErrorFn = block }

    internal fun build(): TtsCallback = object : TtsCallback {
        override fun onStart(id: String) { onStartFn?.invoke(id) }
        override fun onPlaying(id: String) { onPlayingFn?.invoke(id) }
        override fun onDone(id: String) { onDoneFn?.invoke(id) }
        override fun onPieceRendered(id: String, text: String, synthMs: Long, cached: Boolean) {
            onPieceRenderedFn?.invoke(id, text, synthMs, cached)
        }
        override fun onError(id: String, error: TtsError) { onErrorFn?.invoke(id, error) }
    }
}

fun ttsCallback(block: TtsCallbackBuilder.() -> Unit): TtsCallback =
    TtsCallbackBuilder().apply(block).build()

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
    fun onDone(id: String)
    fun onError(id: String, error: TtsError)
}

class TtsCallbackBuilder {
    private var onStartFn: ((String) -> Unit)? = null
    private var onPlayingFn: ((String) -> Unit)? = null
    private var onDoneFn: ((String) -> Unit)? = null
    private var onErrorFn: ((String, TtsError) -> Unit)? = null

    fun onStart(block: (id: String) -> Unit) { onStartFn = block }
    fun onPlaying(block: (id: String) -> Unit) { onPlayingFn = block }
    fun onDone(block: (id: String) -> Unit) { onDoneFn = block }
    fun onError(block: (id: String, error: TtsError) -> Unit) { onErrorFn = block }

    internal fun build(): TtsCallback = object : TtsCallback {
        override fun onStart(id: String) { onStartFn?.invoke(id) }
        override fun onPlaying(id: String) { onPlayingFn?.invoke(id) }
        override fun onDone(id: String) { onDoneFn?.invoke(id) }
        override fun onError(id: String, error: TtsError) { onErrorFn?.invoke(id, error) }
    }
}

fun ttsCallback(block: TtsCallbackBuilder.() -> Unit): TtsCallback =
    TtsCallbackBuilder().apply(block).build()

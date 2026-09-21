package com.nate.tts.stt

/** Event callbacks for [com.nate.tts.api.SttSdk]. Mirrors [com.nate.tts.callback.TtsCallback]. */
interface SttCallback {
    /** Live partial hypothesis for the utterance in progress. */
    fun onPartial(text: String)

    /** A finalized, non-blank utterance — one spoken turn from the player. */
    fun onUtterance(text: String)

    /** Recognition failed (after any transient no-match retries in continuous mode). */
    fun onError(error: SttError)
}

class SttCallbackBuilder {
    private var onPartialFn: ((String) -> Unit)? = null
    private var onUtteranceFn: ((String) -> Unit)? = null
    private var onErrorFn: ((SttError) -> Unit)? = null

    fun onPartial(block: (text: String) -> Unit) { onPartialFn = block }
    fun onUtterance(block: (text: String) -> Unit) { onUtteranceFn = block }
    fun onError(block: (error: SttError) -> Unit) { onErrorFn = block }

    internal fun build(): SttCallback = object : SttCallback {
        override fun onPartial(text: String) { onPartialFn?.invoke(text) }
        override fun onUtterance(text: String) { onUtteranceFn?.invoke(text) }
        override fun onError(error: SttError) { onErrorFn?.invoke(error) }
    }
}

fun sttCallback(block: SttCallbackBuilder.() -> Unit): SttCallback =
    SttCallbackBuilder().apply(block).build()

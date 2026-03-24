package com.nate.tts.callback

import com.nate.tts.error.TtsError

interface TtsCallback {
    fun onStart(id: String)
    fun onDone(id: String)
    fun onError(id: String, error: TtsError)
}

class TtsCallbackBuilder {
    private var onStartFn: ((String) -> Unit)? = null
    private var onDoneFn: ((String) -> Unit)? = null
    private var onErrorFn: ((String, TtsError) -> Unit)? = null

    fun onStart(block: (id: String) -> Unit) { onStartFn = block }
    fun onDone(block: (id: String) -> Unit) { onDoneFn = block }
    fun onError(block: (id: String, error: TtsError) -> Unit) { onErrorFn = block }

    internal fun build(): TtsCallback = object : TtsCallback {
        override fun onStart(id: String) { onStartFn?.invoke(id) }
        override fun onDone(id: String) { onDoneFn?.invoke(id) }
        override fun onError(id: String, error: TtsError) { onErrorFn?.invoke(id, error) }
    }
}

fun ttsCallback(block: TtsCallbackBuilder.() -> Unit): TtsCallback =
    TtsCallbackBuilder().apply(block).build()

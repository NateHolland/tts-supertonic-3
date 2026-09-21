package com.nate.tts.stt

/** Recognizer lifecycle state, exposed by [com.nate.tts.api.SttSdk.status]. */
enum class SttStatus { IDLE, LISTENING, UNAVAILABLE }

/**
 * Platform speech recognizer. Event-driven: [start] captures microphone audio and invokes
 * [SttEngineListener] as hypotheses arrive. Implementations wrap Android `SpeechRecognizer`
 * and iOS `SFSpeechRecognizer` + `AVAudioEngine`.
 *
 * One [start] handles a SINGLE utterance (the platform recognizers endpoint and stop after
 * the speaker pauses). Continuous hands-free turn-taking is layered on top by
 * [com.nate.tts.api.SttSdk], which re-arms the engine after each final result. Kept minimal
 * and imperative to match [com.nate.tts.engine.TtsEngine].
 */
interface SttEngine {
    /** True if speech recognition is supported on this device for [locale]. */
    fun isAvailable(locale: String): Boolean

    /**
     * Request microphone + speech-recognition authorization. Returns true if granted.
     *
     * On iOS this presents the system prompts (`SFSpeechRecognizer` + record permission).
     * On Android the `RECORD_AUDIO` runtime prompt needs an `Activity`, so the host app owns
     * it; this only reports the current grant state.
     */
    suspend fun requestAuthorization(): Boolean

    /** Begin capturing + recognizing one utterance. [listener] receives partials/final/errors. */
    fun start(
        locale: String,
        partialResults: Boolean,
        preferOnDevice: Boolean,
        listener: SttEngineListener,
    )

    /** Stop capturing. Any in-flight audio is finalized via [SttEngineListener.onFinal] if possible. */
    fun stop()

    /** Release native resources. */
    fun release()

    val isListening: Boolean
}

/**
 * Callbacks from an [SttEngine] during a recognition turn. Fire on an unspecified thread —
 * [com.nate.tts.api.SttSdk] marshals them onto its state flows.
 */
interface SttEngineListener {
    /** Live partial hypothesis for the current utterance. */
    fun onPartial(text: String)

    /** The recognizer finalized the current utterance and stopped. [text] may be blank (no match). */
    fun onFinal(text: String)

    /** Recognition failed for this turn. */
    fun onError(error: SttError)
}

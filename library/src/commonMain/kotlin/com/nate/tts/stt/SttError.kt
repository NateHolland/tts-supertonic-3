package com.nate.tts.stt

/** Errors surfaced by the speech-to-text subsystem. Mirrors [com.nate.tts.error.TtsError]. */
sealed class SttError(message: String) : Exception(message) {
    /** Speech recognition isn't supported on this device / for the configured locale. */
    class Unavailable(msg: String = "Speech recognition unavailable on this device") : SttError(msg)

    /** Microphone or speech-recognition authorization was denied. */
    class PermissionDenied(msg: String = "Microphone or speech-recognition permission denied") : SttError(msg)

    /** The recognizer finished a turn with no intelligible speech (silence / noise). */
    class NoMatch(msg: String = "No speech recognized") : SttError(msg)

    /**
     * A transient not-ready condition (recognizer busy, or the recognition service briefly
     * disconnected while re-arming). Retryable — continuous listening re-arms through these.
     */
    class Busy(msg: String = "Recognizer temporarily unavailable") : SttError(msg)

    /** Microphone capture failed (device busy, audio session, etc.). */
    class AudioFailed(msg: String) : SttError(msg)

    /** The recognition service itself failed (network, server, timeout). */
    class RecognitionFailed(msg: String) : SttError(msg)
}

package com.nate.tts.error

sealed class TtsError(message: String) : Exception(message) {
    class ModelNotLoaded(msg: String = "Model not loaded") : TtsError(msg)
    class InferenceFailed(msg: String) : TtsError(msg)
    class AudioPlaybackFailed(msg: String) : TtsError(msg)
    class InvalidText(msg: String) : TtsError(msg)
}

package com.nate.tts.engine

actual object PlatformEngineFactory {
    actual fun create(): TtsEngine = AndroidTtsEngine()
}

package com.nate.tts.stt

/** Creates the platform [SttEngine]. Mirrors [com.nate.tts.engine.PlatformEngineFactory]. */
expect object PlatformSttEngineFactory {
    fun create(): SttEngine
}

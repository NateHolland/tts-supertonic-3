package com.nate.tts.engine

expect object PlatformEngineFactory {
    fun create(): TtsEngine
}

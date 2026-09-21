package com.nate.tts.stt

actual object PlatformSttEngineFactory {
    actual fun create(): SttEngine = IosSttEngine()
}

package com.nate.tts.sample

import platform.Foundation.NSTemporaryDirectory

actual fun platformInit() {
    // No-op on iOS — no context initialization needed
}

actual fun getRecordingOutputPath(): String {
    return NSTemporaryDirectory() + "tts_output.wav"
}

actual fun openInMediaPlayer(path: String) {
    // No-op on iOS for now
}

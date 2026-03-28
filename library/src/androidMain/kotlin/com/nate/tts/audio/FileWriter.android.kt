package com.nate.tts.audio

import java.io.FileOutputStream

actual fun writeBytes(path: String, data: ByteArray) {
    FileOutputStream(path).use { it.write(data) }
}

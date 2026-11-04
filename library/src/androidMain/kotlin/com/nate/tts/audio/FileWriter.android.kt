package com.nate.tts.audio

import java.io.FileOutputStream

actual fun writeBytes(path: String, data: ByteArray) {
    FileOutputStream(path).use { it.write(data) }
}

actual fun readBytesOrNull(path: String): ByteArray? =
    runCatching { java.io.File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()

actual fun ensureDirectory(path: String) {
    java.io.File(path).mkdirs()
}

actual fun listFileNames(path: String): List<String> =
    java.io.File(path).listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()

actual fun deleteFile(path: String) {
    java.io.File(path).delete()
}

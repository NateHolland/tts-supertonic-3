@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nate.tts.audio

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.writeToFile

actual fun writeBytes(path: String, data: ByteArray) {
    val nsData = data.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = data.size.toULong())
    }
    nsData.writeToFile(path, atomically = true)
}

actual fun readBytesOrNull(path: String): ByteArray? {
    val data = NSData.create(contentsOfFile = path) ?: return null
    val size = data.length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length) }
    return out
}

actual fun ensureDirectory(path: String) {
    platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(
        path, withIntermediateDirectories = true, attributes = null, error = null,
    )
}

actual fun listFileNames(path: String): List<String> =
    platform.Foundation.NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, error = null)
        ?.filterIsInstance<String>() ?: emptyList()

actual fun deleteFile(path: String) {
    platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(path, error = null)
}

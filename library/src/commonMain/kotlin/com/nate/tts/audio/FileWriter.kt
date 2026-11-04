package com.nate.tts.audio

/** Platform-specific byte array → file writer. */
expect fun writeBytes(path: String, data: ByteArray)

/** The file's bytes, or null if it doesn't exist or can't be read. */
expect fun readBytesOrNull(path: String): ByteArray?

/** Creates the directory (and parents) if missing. */
expect fun ensureDirectory(path: String)

/** Names of the files directly in [path]; empty if it doesn't exist. */
expect fun listFileNames(path: String): List<String>

/** Deletes the file if it exists. */
expect fun deleteFile(path: String)

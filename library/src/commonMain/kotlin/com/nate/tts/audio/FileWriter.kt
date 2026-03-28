package com.nate.tts.audio

/** Platform-specific byte array → file writer. */
expect fun writeBytes(path: String, data: ByteArray)

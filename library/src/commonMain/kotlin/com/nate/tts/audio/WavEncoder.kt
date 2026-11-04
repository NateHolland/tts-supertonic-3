package com.nate.tts.audio

/**
 * Encodes PCM float data as a WAV byte array (16-bit mono PCM).
 *
 * The input [pcmFloat] should contain normalised samples in the range [-1.0, 1.0].
 */
object WavEncoder {

    /**
     * Encode PCM float samples to a complete WAV file as a [ByteArray].
     *
     * @param pcmFloat Audio samples in [-1.0, 1.0] range
     * @param sampleRate Sample rate in Hz (e.g. 44100)
     * @return Complete WAV file contents as bytes
     */
    fun encode(pcmFloat: FloatArray, sampleRate: Int): ByteArray {
        val numSamples = pcmFloat.size
        val dataSize = numSamples * 2           // 16-bit = 2 bytes per sample
        val fileSize = 44 + dataSize            // 44-byte header + data
        val buffer = ByteArray(fileSize)
        var offset = 0

        fun writeBytes(bytes: ByteArray) {
            bytes.copyInto(buffer, offset)
            offset += bytes.size
        }

        fun writeInt32LE(value: Int) {
            buffer[offset++] = (value and 0xFF).toByte()
            buffer[offset++] = ((value shr 8) and 0xFF).toByte()
            buffer[offset++] = ((value shr 16) and 0xFF).toByte()
            buffer[offset++] = ((value shr 24) and 0xFF).toByte()
        }

        fun writeInt16LE(value: Int) {
            buffer[offset++] = (value and 0xFF).toByte()
            buffer[offset++] = ((value shr 8) and 0xFF).toByte()
        }

        // RIFF header
        writeBytes("RIFF".encodeToByteArray())
        writeInt32LE(fileSize - 8)
        writeBytes("WAVE".encodeToByteArray())

        // fmt chunk
        writeBytes("fmt ".encodeToByteArray())
        writeInt32LE(16)                        // chunk size
        writeInt16LE(1)                         // PCM format
        writeInt16LE(1)                         // mono
        writeInt32LE(sampleRate)                // sample rate
        writeInt32LE(sampleRate * 2)            // byte rate
        writeInt16LE(2)                         // block align
        writeInt16LE(16)                        // bits per sample

        // data chunk
        writeBytes("data".encodeToByteArray())
        writeInt32LE(dataSize)

        // PCM samples — clamp and convert float → 16-bit signed integer
        for (sample in pcmFloat) {
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            val int16 = (clamped * Short.MAX_VALUE).toInt().toShort()
            buffer[offset++] = (int16.toInt() and 0xFF).toByte()
            buffer[offset++] = ((int16.toInt() shr 8) and 0xFF).toByte()
        }

        return buffer
    }
}

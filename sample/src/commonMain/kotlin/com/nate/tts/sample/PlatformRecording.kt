package com.nate.tts.sample

/**
 * Returns a platform-appropriate file path for saving a TTS recording.
 * The file name includes a timestamp to avoid collisions.
 */
expect fun getRecordingOutputPath(fileName: String = "tts_recording.wav"): String

/**
 * Opens the given audio file in the platform's default media player
 * or presents a share/open dialog.
 */
expect fun openInMediaPlayer(path: String)

/** Lines of a text file (the `wavs` auto-run's phrase list); empty if it can't be read. */
expect fun readLinesOf(path: String): List<String>

/** Creates a directory (and parents) if missing. */
expect fun ensureDir(path: String)

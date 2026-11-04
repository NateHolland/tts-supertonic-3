package com.nate.tts.stt

/**
 * Configuration for [com.nate.tts.api.SttSdk].
 *
 * @param locale BCP-47 language tag the recognizer transcribes (e.g. "en-US").
 * @param partialResults emit live partial hypotheses as the player speaks, not only the
 *   finalized utterance. Drives the on-screen "listening…" text.
 * @param continuous after each finalized utterance, automatically resume listening for the
 *   next turn — the hands-free back-and-forth that Audio conversation (feature #50) needs.
 *   When false, recognition stops after a single utterance.
 * @param preferOnDevice ask the platform to keep recognition on-device when it can (lower
 *   latency, works offline, no audio leaves the phone). Silently falls back to server
 *   recognition where on-device is unavailable for the [locale].
 */
data class SttConfig(
    val locale: String = "en-US",
    val partialResults: Boolean = true,
    val continuous: Boolean = true,
    val preferOnDevice: Boolean = true,
)

package com.nate.tts.sample

import com.nate.tts.api.SttSdk
import com.nate.tts.api.TtsSdk
import com.nate.tts.model.SpeakRequest
import com.nate.tts.stt.SttConfig
import com.nate.tts.stt.sttCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** One phrase's round trip: what we sent to TTS vs what STT heard back. */
data class RoundTripResult(
    val index: Int,
    val sent: String,
    val heard: String,
    /** Word error rate, 0.0 = perfect. 1.0 when nothing was heard. */
    val wordErrorRate: Double,
    val matched: Boolean,
    val timedOut: Boolean,
)

data class RoundTripSummary(
    val results: List<RoundTripResult>,
    val matchRate: Double,
    val meanWer: Double,
) {
    val matched: Int get() = results.count { it.matched }
    val total: Int get() = results.size
}

/**
 * Acoustic TTS → STT round-trip harness.
 *
 * Plays each phrase through the device speaker with [TtsSdk] and listens back through the mic
 * with [SttSdk], then scores what was heard against what was sent. Because the garble bug is in
 * the Android **playback** path (the synthesized PCM is clean), only this acoustic loop —
 * speaker to mic — reproduces it; a digital PCM round trip would always match.
 *
 * Two modes:
 * - [runSequential] — one phrase at a time, waiting for each to be heard. Measures clean
 *   round-trip fidelity (the "consistent round trip" acceptance test).
 * - [runRapid] — fires cues back-to-back the way the coach does, which is what triggers the
 *   overlapping-playback garble. Compares how many sent phrases survive.
 *
 * Requires `RECORD_AUDIO` (Android) / mic + speech authorization (iOS) already granted.
 */
class RoundTripRig(
    private val tts: TtsSdk,
    private val matchThreshold: Double = 0.34,
) {

    /**
     * Speak each phrase, wait until it's heard (or [perPhraseTimeoutMs]), score it, move on.
     * [onResult] is invoked as each phrase completes so a UI can stream progress.
     */
    suspend fun runSequential(
        phrases: List<String>,
        voiceStyle: String?,
        perPhraseTimeoutMs: Long = 12_000,
        onResult: (RoundTripResult) -> Unit = {},
    ): RoundTripSummary {
        val stt = SttSdk(SttConfig(continuous = false, partialResults = false, preferOnDevice = false))
        val results = mutableListOf<RoundTripResult>()
        try {
            for ((i, phrase) in phrases.withIndex()) {
                val heard = captureOne(stt, phrase, voiceStyle, perPhraseTimeoutMs)
                val wer = wordErrorRate(phrase, heard ?: "")
                val res = RoundTripResult(
                    index = i,
                    sent = phrase,
                    heard = heard ?: "",
                    wordErrorRate = wer,
                    matched = heard != null && wer <= matchThreshold,
                    timedOut = heard == null,
                )
                results += res
                logResult("sequential", res)
                onResult(res)
            }
        } finally {
            stt.shutdown()
        }
        return summarize(results).also { logSummary("sequential", it) }
    }

    /**
     * Fire every phrase through TTS with only [gapMs] between calls — no waiting — while STT
     * listens continuously. Then match each sent phrase to the best utterance STT recovered.
     * With overlapping playback the audio garbles and few phrases survive; once playback is
     * serialized they should all come through.
     */
    suspend fun runRapid(
        phrases: List<String>,
        voiceStyle: String?,
        gapMs: Long = 600,
        drainMs: Long = 8_000,
        onResult: (RoundTripResult) -> Unit = {},
    ): RoundTripSummary {
        val stt = SttSdk(SttConfig(continuous = true, partialResults = false, preferOnDevice = false))
        val heard = mutableListOf<String>()
        try {
            stt.setCallback(sttCallback { onUtterance { heard += it } })
            stt.start()
            delay(400) // let the recognizer settle before the first cue
            for (phrase in phrases) {
                tts.speak(SpeakRequest(text = phrase, voiceStyle = voiceStyle))
                delay(gapMs)
            }
            delay(drainMs) // let the tail of the audio + recognition finish
            stt.stop()
        } finally {
            stt.shutdown()
        }

        // Greedily match each sent phrase to its closest surviving utterance.
        val pool = heard.toMutableList()
        val results = phrases.mapIndexed { i, phrase ->
            val best = pool.minByOrNull { wordErrorRate(phrase, it) }
            val wer = best?.let { wordErrorRate(phrase, it) } ?: 1.0
            if (best != null && wer <= matchThreshold) pool.remove(best)
            RoundTripResult(
                index = i,
                sent = phrase,
                heard = if (best != null && wer <= matchThreshold) best else "",
                wordErrorRate = wer,
                matched = wer <= matchThreshold,
                timedOut = best == null,
            ).also { logResult("rapid", it); onResult(it) }
        }
        return summarize(results).also { logSummary("rapid", it) }
    }

    /**
     * The realistic garble scenario: a cue arrives while a previous one is still being spoken.
     * For each phrase, start a long filler cue, let it get into playback, then interrupt with
     * the target phrase — and check the target is heard cleanly. Under the garble bug the
     * interrupting cue overlaps the filler and comes out corrupted; once playback is serialized
     * it cleanly supersedes. STT is reset right at the interrupt so it scores the target.
     */
    suspend fun runCollision(
        phrases: List<String>,
        voiceStyle: String?,
        filler: String = "okay here is something to keep in mind while you keep shooting the ball",
        interruptAfterMs: Long = 2200,
        perPhraseTimeoutMs: Long = 12_000,
        onResult: (RoundTripResult) -> Unit = {},
    ): RoundTripSummary {
        val stt = SttSdk(SttConfig(continuous = false, partialResults = false, preferOnDevice = false))
        val results = mutableListOf<RoundTripResult>()
        try {
            for ((i, phrase) in phrases.withIndex()) {
                val heard = captureCollision(stt, filler, phrase, voiceStyle, interruptAfterMs, perPhraseTimeoutMs)
                val wer = wordErrorRate(phrase, heard ?: "")
                val res = RoundTripResult(
                    index = i, sent = phrase, heard = heard ?: "",
                    wordErrorRate = wer, matched = heard != null && wer <= matchThreshold,
                    timedOut = heard == null,
                )
                results += res
                logResult("collision", res)
                onResult(res)
            }
        } finally {
            stt.shutdown()
        }
        return summarize(results).also { logSummary("collision", it) }
    }

    private suspend fun captureCollision(
        stt: SttSdk,
        filler: String,
        target: String,
        voiceStyle: String?,
        interruptAfterMs: Long,
        timeoutMs: Long,
    ): String? {
        stt.stop()
        tts.speak(SpeakRequest(text = filler, voiceStyle = voiceStyle))
        delay(interruptAfterMs) // let the filler get into playback
        // Reset STT so it hears the interrupting target, not the filler that preceded it.
        val done = CompletableDeferred<String?>()
        stt.setCallback(sttCallback {
            onUtterance { text -> done.complete(text) }
            onError { _ -> done.complete(null) }
        })
        stt.start()
        delay(150)
        tts.speak(SpeakRequest(text = target, voiceStyle = voiceStyle)) // interrupt mid-filler
        val heard = withTimeoutOrNull(timeoutMs) { done.await() }
        stt.stop()
        tts.stop()
        return heard
    }

    /** Speak one phrase and return the first utterance STT hears, or null on timeout. */
    private suspend fun captureOne(
        stt: SttSdk,
        phrase: String,
        voiceStyle: String?,
        timeoutMs: Long,
    ): String? {
        stt.stop()
        val done = CompletableDeferred<String?>()
        stt.setCallback(sttCallback {
            onUtterance { text -> done.complete(text) }
            onError { _ -> done.complete(null) }
        })
        stt.start()
        delay(300) // ensure the recognizer is listening before audio plays
        tts.speak(SpeakRequest(text = phrase, voiceStyle = voiceStyle))
        val heard = withTimeoutOrNull(timeoutMs) { done.await() }
        stt.stop()
        tts.stop()
        return heard
    }

    // Logged to stdout → Android logcat (tag "System.out"); grep for "KimaRig|" to read a
    // headless run's results without the on-screen UI.
    private fun logResult(mode: String, r: RoundTripResult) {
        println("KimaRig| [$mode] #${r.index} matched=${r.matched} wer=${pctInt(r.wordErrorRate)} " +
            "sent=\"${r.sent}\" heard=\"${r.heard}\"")
    }

    private fun logSummary(mode: String, s: RoundTripSummary) {
        println("KimaRig| SUMMARY [$mode] matched=${s.matched}/${s.total} " +
            "matchRate=${pctInt(s.matchRate)} meanWer=${pctInt(s.meanWer)}")
    }

    private fun pctInt(x: Double): String = "${(x * 100 + 0.5).toInt()}%"

    private fun summarize(results: List<RoundTripResult>): RoundTripSummary {
        if (results.isEmpty()) return RoundTripSummary(results, 0.0, 0.0)
        return RoundTripSummary(
            results = results,
            matchRate = results.count { it.matched }.toDouble() / results.size,
            meanWer = results.sumOf { it.wordErrorRate } / results.size,
        )
    }
}

/** Word error rate between a reference and hypothesis: word-edit-distance / reference length. */
fun wordErrorRate(reference: String, hypothesis: String): Double {
    val ref = normalizeWords(reference)
    val hyp = normalizeWords(hypothesis)
    if (ref.isEmpty()) return if (hyp.isEmpty()) 0.0 else 1.0
    return wordEditDistance(ref, hyp).toDouble() / ref.size
}

private fun normalizeWords(s: String): List<String> =
    s.lowercase()
        .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotBlank() }

/** Levenshtein distance over word lists. */
private fun wordEditDistance(a: List<String>, b: List<String>): Int {
    val prev = IntArray(b.size + 1) { it }
    val cur = IntArray(b.size + 1)
    for (i in 1..a.size) {
        cur[0] = i
        for (j in 1..b.size) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        }
        prev.indices.forEach { prev[it] = cur[it] }
    }
    return prev[b.size]
}

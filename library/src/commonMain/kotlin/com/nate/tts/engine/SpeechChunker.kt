package com.nate.tts.engine

/**
 * Splits a line into pieces that are rendered and played one after another, so speaking can
 * start before the whole line is rendered. Sentence boundaries first; a very short sentence is
 * joined to the next (a lone "Yes." then a pause sounds clipped); a long sentence is split at
 * a comma so the first piece stays quick to render.
 */
object SpeechChunker {
    private const val MIN_WORDS = 4
    private const val MAX_WORDS = 18

    fun split(text: String): List<String> {
        val sentences = Regex("""(?<=[.!?])\s+""").split(text.trim()).filter { it.isNotBlank() }
        val pieces = mutableListOf<String>()
        var pending = ""
        for (s in sentences.flatMap { splitLong(it) }) {
            pending = if (pending.isEmpty()) s else "$pending $s"
            if (words(pending) >= MIN_WORDS) { pieces += pending; pending = "" }
        }
        if (pending.isNotEmpty()) {
            if (pieces.isNotEmpty() && words(pending) < MIN_WORDS) pieces[pieces.lastIndex] += " $pending"
            else pieces += pending
        }
        return pieces.ifEmpty { listOf(text) }
    }

    private fun splitLong(sentence: String): List<String> {
        if (words(sentence) <= MAX_WORDS) return listOf(sentence)
        val parts = sentence.split(Regex("""(?<=,)\s+"""))
        val out = mutableListOf<String>()
        var cur = ""
        for (p in parts) {
            val next = if (cur.isEmpty()) p else "$cur $p"
            if (cur.isNotEmpty() && words(next) > MAX_WORDS) { out += cur; cur = p } else cur = next
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun words(s: String) = s.split(Regex("\\s+")).count { it.isNotBlank() }
}

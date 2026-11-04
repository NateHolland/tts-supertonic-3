package com.nate.tts.engine

import com.nate.tts.config.SupportedLanguages

/**
 * Text normalisation applied before tokenisation.
 *
 * Ported from `UnicodeProcessor._preprocess_text` in the official supertonic
 * reference implementation (https://github.com/supertone-inc/supertonic,
 * `py/helper.py`), which supertonic-3's character vocabulary assumes has been
 * run over the input.
 *
 * The NFKD step in [preprocess] is load-bearing — see [nfkdNormalize].
 */
object TextPreprocessor {

    /** English abbreviation expansions — applied before the reference pipeline. */
    private val abbreviations = mapOf(
        "Mr." to "Mister",
        "Mrs." to "Misses",
        "Dr." to "Doctor",
        "St." to "Street",
        "Ave." to "Avenue",
        "Jr." to "Junior",
        "Sr." to "Senior",
        "vs." to "versus",
        "etc." to "etcetera",
        "e.g." to "for example",
        "i.e." to "that is",
    )

    /** Emoji and pictograph blocks the reference strips outright. */
    private val emojiRegex = Regex(
        "[😀-🙏" +   // emoticons
            "🌀-🗿" + // symbols & pictographs
            "🚀-🛿" + // transport & map
            "🤀-🧿" + // supplemental symbols
            "🨀-🫿" +
            "🇦-🇿" + // regional indicators
            "☀-➿]+"             // misc symbols & dingbats
    )

    /** Characters normalised to a plain ASCII equivalent. */
    private val replacements = listOf(
        "–" to "-",  // en dash
        "‑" to "-",  // non-breaking hyphen
        "—" to "-",  // em dash
        "_" to " ",
        "“" to "\"", // left double quote
        "”" to "\"", // right double quote
        "‘" to "'",  // left single quote
        "’" to "'",  // right single quote
        "´" to "'",  // acute accent
        "`" to "'",
        "[" to " ",
        "]" to " ",
        "|" to " ",
        "/" to " ",
        "#" to " ",
        "→" to " ",  // right arrow
        "←" to " ",  // left arrow
    )

    private val symbolsToStrip = Regex("[♥☆♡©\\\\]")

    private val expressions = listOf(
        "@" to " at ",
        "e.g.," to "for example, ",
        "i.e.," to "that is, ",
    )

    private val spacingFixes = listOf(
        " ," to ",", " ." to ".", " !" to "!", " ?" to "?",
        " ;" to ";", " :" to ":", " '" to "'",
    )

    /** Punctuation that already ends a sentence — no period is appended after these. */
    private val endsWithPunctuation =
        Regex("[.!?;:,'\"')\\]}…。」』】〉》›»]$")

    private val whitespace = Regex("\\s+")

    /**
     * Normalise [text] for synthesis. Returns `null` if nothing is left to speak.
     *
     * Does not add the language tag — see [tagForModel].
     */
    fun preprocess(text: String): String? {
        var result = text.trim()
        if (result.isEmpty()) return null

        abbreviations.forEach { (abbr, expansion) ->
            result = result.replace(abbr, expansion)
        }

        // Decompose before anything inspects individual characters.
        result = nfkdNormalize(result)

        result = emojiRegex.replace(result, "")

        replacements.forEach { (from, to) -> result = result.replace(from, to) }
        result = symbolsToStrip.replace(result, "")
        expressions.forEach { (from, to) -> result = result.replace(from, to) }
        spacingFixes.forEach { (from, to) -> result = result.replace(from, to) }

        while (result.contains("\"\"")) result = result.replace("\"\"", "\"")
        while (result.contains("''")) result = result.replace("''", "'")

        result = whitespace.replace(result, " ").trim()

        return result.ifEmpty { null }
    }

    /**
     * Wrap already-[preprocess]ed [text] in the language tag the model expects,
     * e.g. `<fr>Bonjour.</fr>`, appending a period when the text does not already
     * end in sentence punctuation.
     *
     * An unrecognised [language] falls back to [SupportedLanguages.DEFAULT] rather
     * than emitting a tag the model was never trained on.
     */
    fun tagForModel(text: String, language: String): String {
        val requested = language.ifBlank { SupportedLanguages.DEFAULT }
        val lang = if (SupportedLanguages.isSupported(requested)) requested else SupportedLanguages.DEFAULT
        val body = if (endsWithPunctuation.containsMatchIn(text)) text else "$text."
        return "<$lang>$body</$lang>"
    }
}

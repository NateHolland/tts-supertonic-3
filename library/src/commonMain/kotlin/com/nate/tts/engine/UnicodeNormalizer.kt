package com.nate.tts.engine

/**
 * Unicode NFKD (compatibility decomposition) normalisation.
 *
 * The supertonic character vocabulary is built on *decomposed* codepoints: an
 * "ä" is stored as `a` + U+0308, and a Hangul syllable as its constituent jamo.
 * The precomposed forms are not in the indexer at all — they resolve to the
 * unsupported marker (-1) — so text must be decomposed before tokenisation or
 * every accented character in the input is lost.
 *
 * Matches `unicodedata.normalize("NFKD", text)` in the official Python
 * reference implementation.
 */
internal expect fun nfkdNormalize(text: String): String

package com.nate.tts.engine

object TextPreprocessor {

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

    fun preprocess(text: String): String? {
        var result = text.trim()
        if (result.isEmpty()) return null

        abbreviations.forEach { (abbr, expansion) ->
            result = result.replace(abbr, expansion)
        }

        result = result.replace(Regex("\\s+"), " ").trim()

        return result.ifEmpty { null }
    }
}

package com.nate.tts.config

/**
 * The 31 languages supported by supertonic-3, keyed by ISO 639-1 code.
 *
 * The code is what the engine wraps the input text in — `<en>Hello.</en>` — so it
 * must match one of the tags the model was trained on. supertonic-2 shipped 5
 * languages (en, ko, es, pt, fr); supertonic-3 expands that set to 31.
 */
object SupportedLanguages {

    /** ISO 639-1 code paired with its English display name. */
    data class Language(val code: String, val name: String)

    val all: List<Language> = listOf(
        Language("en", "English"),
        Language("ko", "Korean"),
        Language("ja", "Japanese"),
        Language("ar", "Arabic"),
        Language("bg", "Bulgarian"),
        Language("cs", "Czech"),
        Language("da", "Danish"),
        Language("de", "German"),
        Language("el", "Greek"),
        Language("es", "Spanish"),
        Language("et", "Estonian"),
        Language("fi", "Finnish"),
        Language("fr", "French"),
        Language("hi", "Hindi"),
        Language("hr", "Croatian"),
        Language("hu", "Hungarian"),
        Language("id", "Indonesian"),
        Language("it", "Italian"),
        Language("lt", "Lithuanian"),
        Language("lv", "Latvian"),
        Language("nl", "Dutch"),
        Language("pl", "Polish"),
        Language("pt", "Portuguese"),
        Language("ro", "Romanian"),
        Language("ru", "Russian"),
        Language("sk", "Slovak"),
        Language("sl", "Slovenian"),
        Language("sv", "Swedish"),
        Language("tr", "Turkish"),
        Language("uk", "Ukrainian"),
        Language("vi", "Vietnamese"),
    )

    val codes: List<String> = all.map { it.code }

    /** Default used when a request omits the language. */
    const val DEFAULT = "en"

    fun isSupported(code: String): Boolean = codes.contains(code)

    fun nameFor(code: String): String? = all.firstOrNull { it.code == code }?.name
}

package com.nate.tts.engine

import java.text.Normalizer

internal actual fun nfkdNormalize(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKD)

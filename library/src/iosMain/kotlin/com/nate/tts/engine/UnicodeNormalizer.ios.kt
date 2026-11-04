package com.nate.tts.engine

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCompatibilityMapping

internal actual fun nfkdNormalize(text: String): String =
    (text as NSString).decomposedStringWithCompatibilityMapping

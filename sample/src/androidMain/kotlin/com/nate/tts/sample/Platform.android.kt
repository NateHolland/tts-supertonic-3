package com.nate.tts.sample

import android.app.Application
import com.nate.tts.engine.AndroidContextHolder

actual fun platformInit() {
    // No-op on Android — context is set in SampleApplication.onCreate()
}

package com.nate.tts.sample

import android.app.Application
import com.nate.tts.engine.AndroidContextHolder

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidContextHolder.set(this)
    }
}

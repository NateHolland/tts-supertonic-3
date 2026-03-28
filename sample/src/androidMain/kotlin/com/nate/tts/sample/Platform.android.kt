package com.nate.tts.sample

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import com.nate.tts.engine.AndroidContextHolder
import java.io.File

actual fun platformInit() {
    // No-op on Android — context is set in SampleApplication.onCreate()
}

actual fun getRecordingOutputPath(): String {
    val context = AndroidContextHolder.get()
    val dir = File(context.filesDir, "recordings")
    dir.mkdirs()
    return File(dir, "tts_output.wav").absolutePath
}

actual fun openInMediaPlayer(path: String) {
    val context = AndroidContextHolder.get()
    val file = File(path)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "audio/wav")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

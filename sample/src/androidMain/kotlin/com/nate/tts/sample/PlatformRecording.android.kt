package com.nate.tts.sample

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.nate.tts.engine.AndroidContextHolder
import java.io.File

actual fun getRecordingOutputPath(fileName: String): String {
    val context = AndroidContextHolder.get()
    val dir = File(context.cacheDir, "tts_recordings")
    dir.mkdirs()
    return File(dir, fileName).absolutePath
}

actual fun openInMediaPlayer(path: String) {
    val context = AndroidContextHolder.get()
    val file = File(path)
    val uri: Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "audio/wav")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

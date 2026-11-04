package com.nate.tts.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AndroidContextHolder {
    private var appContext: Context? = null

    fun set(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context = appContext
        ?: throw IllegalStateException(
            "AndroidContextHolder not initialised. Call AndroidContextHolder.set(context) in Application.onCreate()."
        )
}

actual suspend fun getBundledModelPath(modelId: String): String? = withContext(Dispatchers.IO) {
    val dirName = modelIdToDirName(modelId)
    val assetPrefix = "tts_models/$dirName"
    val context = AndroidContextHolder.get()

    val assetFiles = try {
        context.assets.list(assetPrefix) ?: emptyArray()
    } catch (_: Exception) {
        return@withContext null
    }

    if (assetFiles.isEmpty()) {
        return@withContext null
    }

    val outputDir = File(context.filesDir, "tts_models/$dirName")
    outputDir.mkdirs()

    for (filename in assetFiles) {
        val outputFile = File(outputDir, filename)
        val needsCopy = if (!outputFile.exists()) {
            true
        } else {
            val assetSize = context.assets.open("$assetPrefix/$filename").use { it.available().toLong() }
            outputFile.length() != assetSize
        }
        if (needsCopy) {
            context.assets.open("$assetPrefix/$filename").use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    outputDir.absolutePath
}

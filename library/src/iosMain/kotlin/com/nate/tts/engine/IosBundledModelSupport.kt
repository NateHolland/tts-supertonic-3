package com.nate.tts.engine

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager

@OptIn(ExperimentalForeignApi::class)
actual suspend fun getBundledModelPath(modelId: String): String? {
    val dirName = modelIdToDirName(modelId)
    val bundlePath = NSBundle.mainBundle.resourcePath ?: return null
    val modelPath = "$bundlePath/$dirName"

    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(modelPath)) {
        return null
    }

    val contents = fileManager.contentsOfDirectoryAtPath(modelPath, null) ?: return null
    val hasOnnx = contents.any { (it as? String)?.endsWith(".onnx") == true }
    return if (hasOnnx) modelPath else null
}

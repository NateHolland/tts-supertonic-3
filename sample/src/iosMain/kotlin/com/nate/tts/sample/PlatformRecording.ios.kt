@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.nate.tts.sample

import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

actual fun getRecordingOutputPath(fileName: String): String {
    return NSTemporaryDirectory() + fileName
}

actual fun openInMediaPlayer(path: String) {
    val fileUrl = NSURL.fileURLWithPath(path)
    val activityVC = UIActivityViewController(
        activityItems = listOf(fileUrl),
        applicationActivities = null,
    )

    val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController
    rootVC?.presentViewController(activityVC, animated = true, completion = null)
}

actual fun readLinesOf(path: String): List<String> =
    (platform.Foundation.NSString.create(contentsOfFile = path, encoding = platform.Foundation.NSUTF8StringEncoding, error = null)
        ?.toString() ?: "").lines()

actual fun ensureDir(path: String) {
    platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(
        path, withIntermediateDirectories = true, attributes = null, error = null,
    )
}

package com.nate.tts.sample

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask

/**
 * Launch arguments mirror the Android intent extras: `-roundtrip wavs:<label>` and
 * `-model_dir <dir>`, where a relative dir is taken under the app's Documents (the container
 * path changes with every install, so a rig can't know it in advance).
 */
fun MainViewController() = ComposeUIViewController {
    val defaults = NSUserDefaults.standardUserDefaults
    val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
    val modelDir = defaults.stringForKey("model_dir")?.let { if (it.startsWith("/") || docs == null) it else "$docs/$it" }
    App(autoRun = defaults.stringForKey("roundtrip"), modelDirOverride = modelDir)
}

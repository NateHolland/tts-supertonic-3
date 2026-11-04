package com.nate.tts.engine

expect suspend fun getBundledModelPath(modelId: String): String?

fun modelIdToDirName(modelId: String): String = modelId.replace("/", "_")

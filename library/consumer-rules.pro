# ONNX Runtime — keep native methods and tensor classes
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# TTS SDK public API
-keep class com.nate.tts.api.** { *; }
-keep class com.nate.tts.config.** { *; }
-keep class com.nate.tts.model.** { *; }
-keep class com.nate.tts.callback.** { *; }
-keep class com.nate.tts.error.** { *; }

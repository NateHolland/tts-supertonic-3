package com.nate.tts.stt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.nate.tts.engine.AndroidContextHolder

/**
 * Android [SttEngine] backed by `SpeechRecognizer`.
 *
 * `SpeechRecognizer` must be created and driven on the main thread and delivers its callbacks
 * there, so every native call is marshalled onto the main [Looper]. It recognizes a single
 * utterance per session and stops at the endpoint (`onResults`); [SttSdk][com.nate.tts.api.SttSdk]
 * re-arms it for continuous conversation.
 *
 * Requires the host app to declare `RECORD_AUDIO` and hold the runtime grant, and to have
 * called `AndroidContextHolder.set(context)` (already done for the TTS model loader).
 */
internal class AndroidSttEngine : SttEngine {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: SttEngineListener? = null

    @Volatile private var listening = false
    override val isListening: Boolean get() = listening

    override fun isAvailable(locale: String): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(AndroidContextHolder.get()) }
            .getOrDefault(false)

    override suspend fun requestAuthorization(): Boolean {
        // The RECORD_AUDIO runtime prompt needs an Activity, which this library doesn't own —
        // the host app requests it. Here we only report whether it's already granted.
        val ctx = AndroidContextHolder.get()
        return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun start(
        locale: String,
        partialResults: Boolean,
        preferOnDevice: Boolean,
        listener: SttEngineListener,
    ) {
        this.listener = listener
        runOnMain {
            val ctx = AndroidContextHolder.get()
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                listener.onError(SttError.Unavailable())
                return@runOnMain
            }
            // Reuse ONE recognizer across turns. Destroying + recreating it on every start
            // churns the bound recognition service, which then rejects the next start with
            // ERROR_SERVER_DISCONNECTED / ERROR_RECOGNIZER_BUSY (it killed ~half the turns).
            // Instead keep the instance and just cancel() it back to idle before re-arming.
            //
            // Use the DEFAULT recognizer, not createOnDeviceSpeechRecognizer: the on-device
            // path hard-fails with ERROR_LANGUAGE_UNAVAILABLE when the language pack isn't
            // downloaded, whereas the default recognizer uses on-device when it's present and
            // falls back to Google's online recognizer otherwise. [preferOnDevice] is passed
            // through as the EXTRA_PREFER_OFFLINE hint below rather than a hard requirement.
            val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also {
                it.setRecognitionListener(recognitionListener)
                recognizer = it
            }
            runCatching { rec.cancel() }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults)
                // Ask for offline recognition when the caller prefers on-device (also honoured
                // by some server recognizers to avoid a round-trip).
                if (preferOnDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            listening = true
            println("KimaStt| startListening locale=$locale partial=$partialResults preferOffline=$preferOnDevice")
            runCatching { rec.startListening(intent) }
                .onFailure {
                    listening = false
                    println("KimaStt| startListening threw: ${it.message}")
                    listener.onError(SttError.AudioFailed(it.message ?: "startListening failed"))
                }
        }
    }

    override fun stop() {
        runOnMain {
            listening = false
            // cancel() (not destroy) returns the recognizer to idle while keeping the service
            // bound, so the next start() re-arms cleanly.
            recognizer?.let { runCatching { it.cancel() } }
        }
    }

    override fun release() {
        runOnMain {
            listening = false
            recognizer?.let {
                runCatching { it.cancel() }
                runCatching { it.destroy() }
            }
            recognizer = null
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { println("KimaStt| onReadyForSpeech") }
        override fun onBeginningOfSpeech() { println("KimaStt| onBeginningOfSpeech") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { println("KimaStt| onEndOfSpeech") }

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { listener?.onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = firstResult(results).orEmpty()
            println("KimaStt| onResults '$text'")
            listener?.onFinal(text)
        }

        override fun onError(error: Int) {
            listening = false
            println("KimaStt| onError code=$error")
            listener?.onError(mapError(error))
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun mapError(code: Int): SttError = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SttError.NoMatch()
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SttError.PermissionDenied()
        SpeechRecognizer.ERROR_AUDIO -> SttError.AudioFailed("Audio recording error")
        // Transient: the recognizer wasn't ready or the service briefly dropped while
        // re-arming. ERROR_SERVER_DISCONNECTED is 11; referenced by literal since the constant
        // isn't present on all API levels.
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        11 -> SttError.Busy("Recognizer busy/disconnected ($code)")
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_CLIENT -> SttError.RecognitionFailed("Recognizer error $code")
        else -> SttError.RecognitionFailed("Recognizer error $code")
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}

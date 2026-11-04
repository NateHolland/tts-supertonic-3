@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.nate.tts.stt

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSLocale
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionResult
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import kotlin.coroutines.resume

/**
 * iOS [SttEngine] backed by `SFSpeechRecognizer` + `AVAudioEngine`.
 *
 * Captures microphone audio via an input-node tap, streams the PCM buffers into an
 * `SFSpeechAudioBufferRecognitionRequest`, and reports partial/final transcriptions. One
 * recognition task per utterance; [SttSdk][com.nate.tts.api.SttSdk] re-arms it for continuous
 * conversation.
 *
 * The host app's `Info.plist` must declare `NSMicrophoneUsageDescription` and
 * `NSSpeechRecognitionUsageDescription`, or the OS terminates the app when authorization is
 * requested.
 */
internal class IosSttEngine : SttEngine {

    private var recognizer: SFSpeechRecognizer? = null
    private val audioEngine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null
    private var listener: SttEngineListener? = null

    private var listening = false
    override val isListening: Boolean get() = listening

    private fun recognizerFor(locale: String): SFSpeechRecognizer? {
        recognizer?.let { return it }
        return SFSpeechRecognizer(locale = NSLocale(localeIdentifier = locale)).also { recognizer = it }
    }

    override fun isAvailable(locale: String): Boolean =
        recognizerFor(locale)?.available ?: false

    override suspend fun requestAuthorization(): Boolean {
        val speechOk = suspendCancellableCoroutine { cont ->
            SFSpeechRecognizer.requestAuthorization { status: SFSpeechRecognizerAuthorizationStatus ->
                cont.resume(status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized)
            }
        }
        if (!speechOk) return false
        return suspendCancellableCoroutine { cont ->
            AVAudioSession.sharedInstance().requestRecordPermission { granted ->
                cont.resume(granted)
            }
        }
    }

    override fun start(
        locale: String,
        partialResults: Boolean,
        preferOnDevice: Boolean,
        listener: SttEngineListener,
    ) {
        this.listener = listener
        val rec = recognizerFor(locale)
        if (rec == null || !rec.available) {
            listener.onError(SttError.Unavailable())
            return
        }

        // Route the audio session for simultaneous record + TTS playback (duck others so the
        // coach's voice and the mic coexist).
        val session = AVAudioSession.sharedInstance()
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                withOptions = AVAudioSessionCategoryOptionDuckOthers,
                error = err.ptr,
            )
            session.setActive(true, err.ptr)
        }

        val req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = partialResults
        if (preferOnDevice && rec.supportsOnDeviceRecognition()) {
            req.requiresOnDeviceRecognition = true
        }
        request = req

        val inputNode = audioEngine.inputNode
        val format = inputNode.outputFormatForBus(0.toULong())
        inputNode.installTapOnBus(0.toULong(), 1024.toUInt(), format) { buffer: AVAudioPCMBuffer?, _ ->
            buffer?.let { req.appendAudioPCMBuffer(it) }
        }

        audioEngine.prepare()
        val started = memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            audioEngine.startAndReturnError(err.ptr)
        }
        if (!started) {
            teardownAudio()
            listener.onError(SttError.AudioFailed("AVAudioEngine failed to start"))
            return
        }

        listening = true
        task = rec.recognitionTaskWithRequest(req) { result: SFSpeechRecognitionResult?, error: NSError? ->
            if (result != null) {
                val text = result.bestTranscription.formattedString
                if (result.isFinal()) {
                    listening = false
                    teardownAudio()
                    listener.onFinal(text)
                } else {
                    listener.onPartial(text)
                }
            }
            // An error after a final result is just the task closing out — only surface it
            // while we're still listening.
            if (error != null && listening) {
                listening = false
                teardownAudio()
                listener.onError(SttError.RecognitionFailed(error.localizedDescription))
            }
        }
    }

    override fun stop() {
        // Flush any buffered audio so the recognizer can emit a final result, then tear down.
        request?.endAudio()
        task?.cancel()
        teardownAudio()
        listening = false
    }

    override fun release() {
        stop()
        recognizer = null
    }

    private fun teardownAudio() {
        if (audioEngine.running) audioEngine.stop()
        audioEngine.inputNode.removeTapOnBus(0.toULong())
    }
}

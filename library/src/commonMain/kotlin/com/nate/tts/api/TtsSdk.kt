package com.nate.tts.api

import com.nate.tts.audio.WavEncoder
import com.nate.tts.audio.writeBytes
import com.nate.tts.callback.TtsCallback
import com.nate.tts.config.ModelConfig
import com.nate.tts.config.TtsConfig
import com.nate.tts.engine.PlatformEngineFactory
import com.nate.tts.engine.TextPreprocessor
import com.nate.tts.engine.TtsEngine
import com.nate.tts.error.TtsError
import com.nate.tts.model.SpeakRequest
import com.nate.tts.model.TtsVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Main entry point for the TTS Supertonic 3 SDK.
 *
 * Loads a supertonic-3 ONNX model from a directory, runs on-device inference,
 * and plays audio through the platform audio system.
 */
class TtsSdk(private val config: TtsConfig) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: TtsEngine? = null
    private var modelConfig: ModelConfig? = null
    private var callback: TtsCallback? = null
    private var currentJob: Job? = null
    private var utteranceCounter = 0L

    private var currentVoiceStyle: String = config.defaultVoiceStyle

    private val _availableVoices = MutableStateFlow<List<TtsVoice>>(emptyList())
    val availableVoices: StateFlow<List<TtsVoice>> = _availableVoices.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    fun initialize() {
        engine?.release()
        val eng = PlatformEngineFactory.create()
        val parsedConfig = eng.loadModel(config.modelDir)
        engine = eng
        modelConfig = parsedConfig

        _availableVoices.value = parsedConfig.voices
        if (parsedConfig.voices.none { it.id == currentVoiceStyle }) {
            currentVoiceStyle = parsedConfig.voices.firstOrNull()?.id ?: "M2"
        }
    }

    fun speak(text: String) {
        speak(
            SpeakRequest(
                text = text,
                voiceStyle = null,
                language = null,
                speechRate = config.defaultSpeechRate,
                volume = config.defaultVolume,
            )
        )
    }

    fun speak(request: SpeakRequest) {
        val eng = engine ?: run {
            val id = nextUtteranceId()
            callback?.onError(id, TtsError.ModelNotLoaded())
            return
        }

        cancelCurrentJob()
        eng.stopAudio()

        val id = nextUtteranceId()
        val voiceStyle = request.voiceStyle ?: currentVoiceStyle
        val language = request.language ?: "en"
        val mc = modelConfig ?: return

        currentJob = scope.launch {
            try {
                _isSpeaking.value = true
                callback?.onStart(id)

                val cleanText = TextPreprocessor.preprocess(request.text)
                if (cleanText == null) {
                    callback?.onError(id, TtsError.InvalidText("Text is empty after preprocessing"))
                    return@launch
                }

                val pcm = eng.synthesise(cleanText, voiceStyle, language)
                eng.playAudio(pcm, mc.sampleRate, request.volume)

                callback?.onDone(id)
            } catch (e: TtsError) {
                callback?.onError(id, e)
            } catch (e: Exception) {
                callback?.onError(id, TtsError.InferenceFailed(e.message ?: "Unknown error"))
            } finally {
                _isSpeaking.value = false
            }
        }
    }

    /**
     * Synthesise text and write the result to a WAV file.
     *
     * This is a suspend function that runs inference on the current coroutine context,
     * writes the WAV file, and returns the output file path.
     *
     * @param request The speak request (text, voice, language, etc.)
     * @param outputPath Absolute path for the output WAV file
     * @return The sample rate of the output audio
     * @throws TtsError if inference or file writing fails
     */
    suspend fun synthesiseToFile(request: SpeakRequest, outputPath: String): Int {
        val eng = engine ?: throw TtsError.ModelNotLoaded()
        val mc = modelConfig ?: throw TtsError.ModelNotLoaded()

        val voiceStyle = request.voiceStyle ?: currentVoiceStyle
        val language = request.language ?: "en"

        val cleanText = TextPreprocessor.preprocess(request.text)
            ?: throw TtsError.InvalidText("Text is empty after preprocessing")

        val pcm = eng.synthesise(cleanText, voiceStyle, language)
        val wavBytes = WavEncoder.encode(pcm, mc.sampleRate)

        try {
            writeBytes(outputPath, wavBytes)
        } catch (e: Exception) {
            throw TtsError.FileWriteFailed("Failed to write WAV to $outputPath: ${e.message}")
        }

        return mc.sampleRate
    }

    /**
     * Synthesise text and return the raw WAV file bytes.
     *
     * Useful when you need the audio data without writing to disk,
     * e.g. for streaming, uploading, or in-memory processing.
     *
     * @param request The speak request (text, voice, language, etc.)
     * @return WAV file contents as a byte array
     * @throws TtsError if inference fails
     */
    suspend fun synthesiseToBytes(request: SpeakRequest): ByteArray {
        val eng = engine ?: throw TtsError.ModelNotLoaded()
        val mc = modelConfig ?: throw TtsError.ModelNotLoaded()

        val voiceStyle = request.voiceStyle ?: currentVoiceStyle
        val language = request.language ?: "en"

        val cleanText = TextPreprocessor.preprocess(request.text)
            ?: throw TtsError.InvalidText("Text is empty after preprocessing")

        val pcm = eng.synthesise(cleanText, voiceStyle, language)
        return WavEncoder.encode(pcm, mc.sampleRate)
    }

    /**
     * Synthesise text and return the raw PCM float samples.
     *
     * The returned samples are normalised in the range [-1.0, 1.0] at the model's
     * native sample rate (available via [modelConfig]).
     *
     * @param request The speak request (text, voice, language, etc.)
     * @return PCM float samples
     * @throws TtsError if inference fails
     */
    suspend fun synthesiseToFloatArray(request: SpeakRequest): FloatArray {
        val eng = engine ?: throw TtsError.ModelNotLoaded()

        val voiceStyle = request.voiceStyle ?: currentVoiceStyle
        val language = request.language ?: "en"

        val cleanText = TextPreprocessor.preprocess(request.text)
            ?: throw TtsError.InvalidText("Text is empty after preprocessing")

        return eng.synthesise(cleanText, voiceStyle, language)
    }

    /** The sample rate of the loaded model, or null if not initialised. */
    val sampleRate: Int? get() = modelConfig?.sampleRate

    fun stop() {
        cancelCurrentJob()
        engine?.stopAudio()
        _isSpeaking.value = false
    }

    fun shutdown() {
        stop()
        engine?.release()
        engine = null
        scope.cancel()
    }

    fun setVoice(voice: TtsVoice) {
        currentVoiceStyle = voice.id
    }

    fun setVoiceStyle(styleId: String) {
        currentVoiceStyle = styleId
    }

    fun setCallback(callback: TtsCallback) {
        this.callback = callback
    }

    private fun nextUtteranceId(): String {
        utteranceCounter++
        return "utt_$utteranceCounter"
    }

    private fun cancelCurrentJob() {
        currentJob?.cancel()
        currentJob = null
    }
}

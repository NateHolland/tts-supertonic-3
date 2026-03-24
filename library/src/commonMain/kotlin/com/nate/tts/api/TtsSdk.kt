package com.nate.tts.api

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
 * Main entry point for the TTS Supertonic 2 SDK.
 *
 * Loads a supertonic-2 ONNX model from a directory, runs on-device inference,
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

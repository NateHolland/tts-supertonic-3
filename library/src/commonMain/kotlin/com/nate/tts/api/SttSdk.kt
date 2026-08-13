package com.nate.tts.api

import com.nate.tts.stt.PlatformSttEngineFactory
import com.nate.tts.stt.SttCallback
import com.nate.tts.stt.SttConfig
import com.nate.tts.stt.SttEngine
import com.nate.tts.stt.SttEngineListener
import com.nate.tts.stt.SttError
import com.nate.tts.stt.SttStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Speech-to-text entry point for the TTS Supertonic 2 SDK — the counterpart to [TtsSdk].
 *
 * Wraps the platform speech recognizer ([com.nate.tts.stt.SttEngine]) and turns its
 * single-utterance API into a continuous, hands-free listen loop: it captures the player's
 * voice, emits live [partialText] while they speak, emits each finished turn on [utterances],
 * then automatically re-arms for the next turn (when [SttConfig.continuous]). This is the
 * input half of Audio conversation (feature #50); the reply half reuses [TtsSdk].
 *
 * Observe [status] / [partialText] / [utterances], or register an [SttCallback]. Recognition
 * runs on-device where the platform supports it (see [SttConfig.preferOnDevice]).
 */
class SttSdk(private val config: SttConfig = SttConfig()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine: SttEngine = PlatformSttEngineFactory.create()
    private var callback: SttCallback? = null

    /** True while the caller wants the continuous loop to keep running (survives re-arms). */
    private var active = false

    /** Consecutive transient re-arms with no successful recognition; bounds a broken-service loop. */
    private var transientRetries = 0
    private val maxTransientRetries = 30

    private val _status = MutableStateFlow(SttStatus.IDLE)
    val status: StateFlow<SttStatus> = _status.asStateFlow()

    /** Live partial hypothesis for the utterance in progress; "" between turns. */
    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    /**
     * Finalized spoken turns, one per endpointed utterance. A [SharedFlow] (not a StateFlow)
     * so two identical utterances in a row both emit — each is a distinct turn to feed the
     * coach-chat pipeline.
     */
    private val _utterances = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val utterances: SharedFlow<String> = _utterances.asSharedFlow()

    /** Whether the platform supports STT for the configured locale. */
    val isAvailable: Boolean get() = engine.isAvailable(config.locale)

    /** True while the microphone is actively capturing. */
    val isListening: Boolean get() = engine.isListening

    private val listener = object : SttEngineListener {
        override fun onPartial(text: String) {
            transientRetries = 0
            _partialText.value = text
            callback?.onPartial(text)
        }

        override fun onFinal(text: String) {
            _partialText.value = ""
            val turn = text.trim()
            if (turn.isNotEmpty()) {
                transientRetries = 0
                callback?.onUtterance(turn)
                _utterances.tryEmit(turn)
            }
            // Continuous turn-taking: resume listening for the next turn.
            if (active && config.continuous) reArm() else finishLoop()
        }

        override fun onError(error: SttError) {
            _partialText.value = ""
            // NoMatch (the player paused) and Busy (recognizer not ready / service briefly
            // dropped while re-arming) are expected during a continuous conversation — resume
            // listening rather than surfacing them, up to a bound that stops a broken-service
            // loop from spinning forever.
            val transient = error is SttError.NoMatch || error is SttError.Busy
            if (active && config.continuous && transient && transientRetries < maxTransientRetries) {
                transientRetries++
                reArm()
                return
            }
            callback?.onError(error)
            active = false
            _status.value = if (error is SttError.Unavailable) SttStatus.UNAVAILABLE else SttStatus.IDLE
        }
    }

    /** Request microphone + speech-recognition authorization. Returns true if granted. */
    suspend fun requestAuthorization(): Boolean = engine.requestAuthorization()

    /**
     * Start listening. In continuous mode this begins a hands-free loop that runs until [stop].
     * No-op if already active. Emits [SttError.Unavailable] via the callback if STT isn't
     * supported for the configured locale.
     */
    fun start() {
        if (active) return
        if (!engine.isAvailable(config.locale)) {
            _status.value = SttStatus.UNAVAILABLE
            callback?.onError(SttError.Unavailable())
            return
        }
        active = true
        armEngine()
    }

    /** Stop listening and end the continuous loop. Idempotent. */
    fun stop() {
        active = false
        engine.stop()
        _partialText.value = ""
        _status.value = SttStatus.IDLE
    }

    /** Release native resources and cancel the internal scope. The SDK is unusable afterwards. */
    fun shutdown() {
        active = false
        engine.release()
        scope.cancel()
    }

    fun setCallback(callback: SttCallback) {
        this.callback = callback
    }

    private fun armEngine() {
        _status.value = SttStatus.LISTENING
        engine.start(config.locale, config.partialResults, config.preferOnDevice, listener)
    }

    /**
     * Fully tear the recognizer down before re-arming — the platform recognizers can't
     * reliably start() again while a previous session is still winding down.
     */
    private fun reArm() {
        scope.launch {
            engine.stop()
            if (active) armEngine()
        }
    }

    private fun finishLoop() {
        active = false
        _status.value = SttStatus.IDLE
    }
}

package com.nate.tts.api

import com.nate.tts.audio.deleteFile
import com.nate.tts.audio.listFileNames
import com.nate.tts.audio.ensureDirectory
import com.nate.tts.audio.readBytesOrNull
import com.nate.tts.audio.WavEncoder
import com.nate.tts.audio.writeBytes
import com.nate.tts.callback.TtsCallback
import com.nate.tts.config.ModelConfig
import com.nate.tts.config.TtsConfig
import com.nate.tts.engine.PlatformEngineFactory
import com.nate.tts.engine.SpeechChunker
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Main entry point for the TTS Supertonic 3 SDK.
 *
 * Loads a supertonic-3 ONNX model from a directory, runs on-device inference,
 * and plays audio through the platform audio system.
 */
class TtsSdk(private val config: TtsConfig) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Serialises synth+playback so overlapping speak() calls never drive the shared ONNX
    // sessions / AudioTrack concurrently (the cause of overlapping, garbled output).
    private val speakMutex = Mutex()

    /**
     * Rendered pieces by (text, voice, language, rate): coaching repeats short cues ("Again.",
     * "Good.") constantly, and a cached one plays with no synthesis at all. Small and in-memory
     * (a 2 s piece is ~350 KB of floats).
     */
    private val pcmCache = LinkedHashMap<String, FloatArray>()
    private val cacheLock = Mutex()

    /** Pieces from [prerender] when there's no [TtsConfig.phraseCacheDir]: kept in memory. */
    private val pinnedCache = HashMap<String, FloatArray>()

    /** Per voice id, what its audio depends on; see [registerVoice]. */
    private val voiceFingerprints = HashMap<String, String>()

    private fun phrasePath(voiceStyle: String, language: String, rate: Float, text: String): String? {
        val dir = config.phraseCacheDir ?: return null
        val voice = voiceFingerprints[voiceStyle] ?: voiceStyle
        return "$dir/${fnv64("${config.modelDir}|${config.flowSteps}|$voice|$language|$rate|$text")}.s16"
    }

    private fun readPhrase(voiceStyle: String, language: String, rate: Float, text: String): FloatArray? {
        val bytes = phrasePath(voiceStyle, language, rate, text)?.let { readBytesOrNull(it) } ?: return null
        if (bytes.isEmpty() || bytes.size % 2 != 0) return null
        // 16-bit little-endian: half the size of the float samples, and inaudible for speech
        // (103 coaching lines went from 23 MB to ~11 MB).
        return FloatArray(bytes.size / 2) { i ->
            val v = (bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)
            v.toShort() / 32768f
        }
    }

    private fun writePhrase(path: String, pcm: FloatArray) {
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[i * 2] = v.toByte(); bytes[i * 2 + 1] = (v shr 8).toByte()
        }
        config.phraseCacheDir?.let { ensureDirectory(it) }
        writeBytes(path, bytes)
    }

    private suspend fun cachedSynthesise(
        eng: TtsEngine, text: String, voiceStyle: String, language: String, rate: Float, id: String? = null,
    ): FloatArray {
        val key = "$voiceStyle|$language|$rate|$text"
        cacheLock.withLock {
            pinnedCache[key]?.let {
                if (id != null) callback?.onPieceRendered(id, text, 0, cached = true)
                return it
            }
            // Least-recently-used: a hit moves to the back, the front is evicted.
            pcmCache.remove(key)?.let {
                pcmCache[key] = it
                if (id != null) callback?.onPieceRendered(id, text, 0, cached = true)
                return it
            }
        }
        readPhrase(voiceStyle, language, rate, text)?.let { pcm ->
            cacheLock.withLock { pcmCache[key] = pcm }
            if (id != null) callback?.onPieceRendered(id, text, 0, cached = true)
            return pcm
        }
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val pcm = eng.synthesise(text, voiceStyle, language, rate)
        if (id != null) callback?.onPieceRendered(id, text, started.elapsedNow().inWholeMilliseconds, cached = false)
        cacheLock.withLock {
            pcmCache[key] = pcm
            while (pcmCache.size > 40) pcmCache.remove(pcmCache.keys.first())
        }
        return pcm
    }
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
        eng.flowSteps = config.flowSteps
        engine = eng
        modelConfig = parsedConfig

        _availableVoices.value = parsedConfig.voices
        if (parsedConfig.voices.none { it.id == currentVoiceStyle }) {
            currentVoiceStyle = parsedConfig.voices.firstOrNull()?.id ?: "M2"
        }
    }

    /**
     * Make a runtime-built voice speakable under [id]; pass that id as the
     * voice style in a [SpeakRequest]. Used by the designer, where the style
     * changes on every edit and never exists as a file.
     */
    fun registerVoice(id: String, styleTtl: FloatArray, styleDp: FloatArray) {
        val eng = engine ?: throw TtsError.ModelNotLoaded("initialize() first")
        eng.registerVoice(id, styleTtl, styleDp)
        // A runtime voice keeps its id when its data changes (an app's "remote" voice), so the
        // phrase cache keys on the data, never the id.
        val h = StringBuilder()
        h.append(styleTtl.size).append(':').append(styleDp.size)
        for (f in styleTtl) h.append(',').append(f.toRawBits())
        for (f in styleDp) h.append(',').append(f.toRawBits())
        voiceFingerprints[id] = "$id#${fnv64(h.toString())}"
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

        // Interrupt the current utterance: cancel its coroutine and stop its playback NOW, so
        // the replacement can't overlap it. stopAudio() promptly wakes a blocked playAudio()
        // so the cancelled job releases [speakMutex] quickly.
        cancelCurrentJob()
        eng.stopAudio()

        val id = nextUtteranceId()
        val voiceStyle = request.voiceStyle ?: currentVoiceStyle
        val language = request.language ?: "en"
        val mc = modelConfig ?: return

        val job = scope.launch {
            speakMutex.withLock {
                try {
                    ensureActive() // already superseded → don't start
                    _isSpeaking.value = true
                    callback?.onStart(id)

                    val cleanText = TextPreprocessor.preprocess(request.text)
                    if (cleanText == null) {
                        callback?.onError(id, TtsError.InvalidText("Text is empty after preprocessing"))
                        return@withLock
                    }

                    // Warm the audio output BEFORE synthesising, so the warm-up runs during the
                    // (otherwise silent) ~1s synthesis window and the first word isn't clipped.
                    eng.prepareStream(mc.sampleRate)
                    // Sentence by sentence: the first piece plays as soon as it is rendered and
                    // the next renders while it plays, so a long line starts as fast as a short
                    // one. One piece of look-ahead; cancelling this job cancels the renderer.
                    val pieces = SpeechChunker.split(cleanText)
                    val rendered = kotlinx.coroutines.channels.Channel<FloatArray>(capacity = 1)
                    kotlinx.coroutines.coroutineScope {
                        launch {
                            for (piece in pieces) {
                                ensureActive()
                                rendered.send(cachedSynthesise(eng, piece, voiceStyle, language, request.speechRate, id))
                            }
                            rendered.close()
                        }
                        var first = true
                        for (pcm in rendered) {
                            ensureActive() // superseded during synthesis → don't play a stale clip
                            if (first) { callback?.onPlaying(id); first = false }
                            eng.playAudio(pcm, mc.sampleRate, request.volume)
                        }
                    }

                    ensureActive()
                    callback?.onDone(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TtsError) {
                    callback?.onError(id, e)
                } catch (e: Exception) {
                    callback?.onError(id, TtsError.InferenceFailed(e.message ?: "Unknown error"))
                } finally {
                    // Only the current (not-superseded) job owns the speaking flag.
                    if (coroutineContext[Job] === currentJob) _isSpeaking.value = false
                }
            }
        }
        currentJob = job
    }

    /**
     * Render [texts] ahead of time so speaking them later costs no synthesis at all.
     *
     * For the lines an app says constantly ("Good.", "Again."), rendered while the CPU is free
     * rather than at the moment they're needed. Pieces are split and keyed exactly as [speak]
     * does, so a later speak() of the same text, voice and rate is a pure cache hit. One piece at
     * a time, and a piece is skipped (not waited for) while something is being spoken, so this
     * never delays real speech; cancel the calling coroutine to stop between pieces. With a
     * [TtsConfig.phraseCacheDir] the pieces go to disk and are rendered once, ever, per voice;
     * without one they're kept in memory for this SDK's lifetime. Returns how many pieces were
     * rendered (0 when everything was already cached).
     */
    suspend fun prerender(texts: List<String>, voiceStyle: String? = null, speechRate: Float = config.defaultSpeechRate): Int {
        val eng = engine ?: return 0
        val style = voiceStyle ?: currentVoiceStyle
        val pieces = texts.mapNotNull { TextPreprocessor.preprocess(it) }.flatMap { SpeechChunker.split(it) }.distinct()
        // The directory holds exactly this set: whatever an earlier voice, rate or line list
        // left behind is deleted rather than kept forever.
        config.phraseCacheDir?.let { dir ->
            val keep = pieces.mapNotNull { phrasePath(style, "en", speechRate, it)?.substringAfterLast('/') }.toSet()
            listFileNames(dir).filter { it !in keep }.forEach { deleteFile("$dir/$it") }
        }
        var rendered = 0
        for (piece in pieces) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val key = "$style|en|$speechRate|$piece"
            val path = phrasePath(style, "en", speechRate, piece)
            val have = if (path != null) readBytesOrNull(path) != null
                else cacheLock.withLock { pinnedCache.containsKey(key) }
            if (have) continue
            if (!speakMutex.tryLock()) continue
            try {
                val pcm = eng.synthesise(piece, style, "en", speechRate)
                if (path != null) writePhrase(path, pcm) else cacheLock.withLock { pinnedCache[key] = pcm }
                rendered++
            } finally {
                speakMutex.unlock()
            }
        }
        return rendered
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

        val pcm = eng.synthesise(cleanText, voiceStyle, language, request.speechRate)
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

        val pcm = eng.synthesise(cleanText, voiceStyle, language, request.speechRate)
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

        return eng.synthesise(cleanText, voiceStyle, language, request.speechRate)
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

/** FNV-1a, 64-bit, as hex: stable across launches and platforms (String.hashCode is 32-bit). */
private fun fnv64(s: String): String {
    var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    for (b in s.encodeToByteArray()) {
        h = h xor (b.toLong() and 0xFF)
        h *= 0x100000001b3L
    }
    return h.toULong().toString(16)
}

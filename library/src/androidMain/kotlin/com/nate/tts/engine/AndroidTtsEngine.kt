package com.nate.tts.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.nate.tts.config.ModelConfig
import com.nate.tts.config.SupportedLanguages
import com.nate.tts.error.TtsError
import com.nate.tts.model.TtsVoice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "AndroidTtsEngine"

/**
 * Android implementation of [TtsEngine] using ONNX Runtime and AudioTrack.
 *
 * Loads the supertonic-3 multi-model pipeline:
 * - duration_predictor.onnx — text + style → scalar duration (seconds)
 * - text_encoder.onnx       — text + style → text embeddings
 * - vector_estimator.onnx   — iterative flow-matching denoiser (N steps)
 * - vocoder.onnx            — latent → audio waveform
 *
 * Based on the official supertonic Python/JS reference implementation at:
 * https://github.com/supertone-inc/supertonic
 */
class AndroidTtsEngine : TtsEngine {

    private var ortEnvironment: OrtEnvironment? = null
    private var durationPredictorSession: OrtSession? = null
    private var textEncoderSession: OrtSession? = null
    private var vectorEstimatorSession: OrtSession? = null
    private var vocoderSession: OrtSession? = null

    // Playback design — a SINGLE persistent MODE_STREAM track for the engine's whole life.
    //
    // Earlier designs created a fresh AudioTrack per cue (MODE_STATIC). On some devices
    // (e.g. Samsung SM-S928B) the per-cue create → play → stop → release cycle intermittently
    // corrupts a chunk of the clip: the start/stop transient of a short track drops or garbles
    // either the onset ("Nice bucket" -> "bucket") or the tail ("Nice bucket" -> "Nice buck"),
    // ~15-40% of clips at random. No buffer sizing or silence lead-in fixes it, because the
    // glitch is the track lifecycle itself, not the buffer contents.
    //
    // Instead, ONE stream track is created once and never torn down between cues. A feeder
    // thread writes it continuously with blocking writes: 20 ms of silence when idle (which
    // keeps the output hot and the buffer from underrunning) and the clip PCM when a cue is
    // submitted. No per-cue track start/stop → no transient → no garble. [framesWritten] tracks
    // total frames handed to the track so playAudio can block until the clip has drained.
    private val trackLock = Any()
    private val clipLock = Object()
    private var streamTrack: AudioTrack? = null
    private var streamThread: Thread? = null
    private var streamRate: Int = 0
    @Volatile private var streamRunning: Boolean = false
    @Volatile private var pendingClip: ShortArray? = null
    @Volatile private var framesWritten: Long = 0L
    @Volatile private var clipEndFrame: Long = -1L

    private var modelConfig: ModelConfig? = null
    private var modelDir: String? = null

    // Unicode indexer: 65536-element array indexed by code point → token ID
    private var unicodeIndexer: IntArray? = null

    // Voice styles: each has style_ttl and style_dp components
    private val voiceStylesTtl = mutableMapOf<String, StyleTensor>()
    private val voiceStylesDp = mutableMapOf<String, StyleTensor>()

    // Config values from tts.json
    private var sampleRate: Int = 44100
    private var baseChunkSize: Int = 512
    private var chunkCompressFactor: Int = 6
    private var latentDim: Int = 24

    /**
     * How many candidates to render per utterance; the best (see [selectBestCandidate]) is kept.
     * Flow matching from random noise garbles a word on ~15-20% of draws; rendering a few and
     * keeping the most-complete one removes it. 1 disables best-of-N (single render). The
     * candidates render concurrently, so N>1 costs far less than N× a single render.
     */
    var synthBestOfN: Int = debugProp("debug.tts.bestof")?.toIntOrNull()?.coerceIn(1, 5) ?: 1

    /** latentDim * chunkCompressFactor — the actual channel dimension for latent tensors */
    private val latentDimVal: Int get() = latentDim * chunkCompressFactor
    /** baseChunkSize * chunkCompressFactor — samples per latent frame */
    private val chunkSize: Int get() = baseChunkSize * chunkCompressFactor

    @Volatile
    override var isPlaying: Boolean = false
        private set

    // ── Model Loading ────────────────────────────────────────────────────

    /** Flow-matching steps per render: [flowSteps], unless debug.tts.steps overrides it. */
    private var debugSteps: Int? = null
    override var flowSteps: Int = 5
    private val synthSteps get() = debugSteps ?: flowSteps

    override fun loadModel(modelDir: String): ModelConfig {
        release()

        // debug.tts.modeldir points at another copy of the model (e.g. a quantised one) for
        // speed/quality comparisons; ignored unless it exists.
        val dir = debugProp("debug.tts.modeldir")?.let(::File)?.takeIf { it.isDirectory } ?: File(modelDir)
        if (!dir.exists() || !dir.isDirectory) {
            throw TtsError.ModelNotLoaded("Model directory not found: $modelDir")
        }

        val requiredFiles = listOf(
            "duration_predictor.onnx", "text_encoder.onnx",
            "vector_estimator.onnx", "vocoder.onnx",
            "tts.json", "unicode_indexer.json",
        )
        val missing = requiredFiles.filter { !File(dir, it).exists() }
        if (missing.isNotEmpty()) {
            throw TtsError.ModelNotLoaded("Missing: ${missing.joinToString()}")
        }

        // Validate ONNX files aren't LFS pointers
        for (name in requiredFiles.filter { it.endsWith(".onnx") }) {
            val size = File(dir, name).length()
            if (size < 1000) {
                throw TtsError.ModelNotLoaded("$name is only $size bytes — likely a Git LFS pointer.")
            }
        }

        try {
            val env = OrtEnvironment.getEnvironment()
            // Use more cores per inference — the models were only getting 2 threads on an 8-core
            // SoC, leaving most of the CPU idle and inflating synth latency (which best-of-N
            // multiplies). Cap so we favour the big cores and don't thrash the little ones.
            val cores = Runtime.getRuntime().availableProcessors()
            // Debug overrides (adb shell setprop …), read at load: debug.tts.intra = intra-op
            // threads per model; debug.tts.spin = 0 stops idle ONNX threads busy-waiting.
            // Tried and dropped (Oct 2026, A36 rig, detection fps while a line renders): nice 10
            // halved synthesis speed with no detection gain; ONNX ignores intra-op thread
            // affinities on Android, so pinning to the little cores did nothing; 1, 3 or 4
            // threads traded detection-during-speech against detection-otherwise, net worse.
            val intraOp = debugProp("debug.tts.intra")?.toIntOrNull()?.coerceIn(1, 8) ?: cores.coerceIn(2, 6)
            val spin = debugProp("debug.tts.spin")
            debugSteps = debugProp("debug.tts.steps")?.toIntOrNull()?.coerceIn(1, 16)
            Log.i(TAG, "ONNX intra-op threads: $intraOp (of $cores cores), spinning=${spin ?: "default"}, bestOf=$synthBestOfN, steps=$synthSteps, ep=${debugProp("debug.tts.ep") ?: "cpu"}, model=${dir.absolutePath}")
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(intraOp)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                if (spin != null) addConfigEntry("session.intra_op.allow_spinning", if (spin == "0") "0" else "1")
                // debug.tts.ep = xnnpack | nnapi: try another execution provider (the CPU one
                // takes whatever it can't run).
                when (debugProp("debug.tts.ep")?.lowercase()) {
                    "xnnpack" -> addXnnpack(mapOf("intra_op_num_threads" to intraOp.toString()))
                    "nnapi" -> addNnapi()
                }
            }

            Log.i(TAG, "Loading ONNX models...")
            durationPredictorSession = env.createSession(File(dir, "duration_predictor.onnx").absolutePath, opts)
            textEncoderSession = env.createSession(File(dir, "text_encoder.onnx").absolutePath, opts)
            vectorEstimatorSession = env.createSession(File(dir, "vector_estimator.onnx").absolutePath, opts)
            vocoderSession = env.createSession(File(dir, "vocoder.onnx").absolutePath, opts)
            ortEnvironment = env
            this.modelDir = modelDir

            // Log I/O names
            Log.i(TAG, "DP inputs: ${durationPredictorSession!!.inputNames} outputs: ${durationPredictorSession!!.outputNames}")
            Log.i(TAG, "TE inputs: ${textEncoderSession!!.inputNames} outputs: ${textEncoderSession!!.outputNames}")
            Log.i(TAG, "VE inputs: ${vectorEstimatorSession!!.inputNames} outputs: ${vectorEstimatorSession!!.outputNames}")
            Log.i(TAG, "VOC inputs: ${vocoderSession!!.inputNames} outputs: ${vocoderSession!!.outputNames}")

            // Parse tts.json
            val ttsJson = JSONObject(File(dir, "tts.json").readText())
            val aeConfig = ttsJson.optJSONObject("ae")
            val ttlConfig = ttsJson.optJSONObject("ttl")
            sampleRate = aeConfig?.optInt("sample_rate", 44100) ?: 44100
            baseChunkSize = aeConfig?.optInt("base_chunk_size", 512) ?: 512
            chunkCompressFactor = ttlConfig?.optInt("chunk_compress_factor", 6) ?: 6
            latentDim = ttlConfig?.optInt("latent_dim", 24) ?: 24
            Log.i(TAG, "Config: sr=$sampleRate bcs=$baseChunkSize ccf=$chunkCompressFactor ld=$latentDim ldv=$latentDimVal cs=$chunkSize")

            // Parse unicode_indexer.json — array of 65536 ints
            val indexerArray = JSONArray(File(dir, "unicode_indexer.json").readText())
            unicodeIndexer = IntArray(indexerArray.length()) { indexerArray.getInt(it) }
            Log.i(TAG, "Unicode indexer: ${unicodeIndexer!!.size} entries")

            // Discover voice styles
            val voices = mutableListOf<TtsVoice>()
            dir.listFiles()
                ?.filter { it.extension == "json" && it.name.matches(Regex("[FM]\\d+\\.json")) }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    val styleId = file.nameWithoutExtension
                    val gender = if (styleId.startsWith("M")) "MALE" else "FEMALE"
                    try {
                        val json = JSONObject(file.readText())
                        voiceStylesTtl[styleId] = parseStyleTensor(json.getJSONObject("style_ttl"))
                        voiceStylesDp[styleId] = parseStyleTensor(json.getJSONObject("style_dp"))
                        voices.add(TtsVoice(id = styleId, name = styleId, gender = gender))
                        Log.i(TAG, "Voice: $styleId ttl=${voiceStylesTtl[styleId]!!.dims.toList()} dp=${voiceStylesDp[styleId]!!.dims.toList()}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load voice $styleId: ${e.message}")
                    }
                }

            if (voices.isEmpty()) {
                throw TtsError.ModelNotLoaded("No voice styles found (expected M1.json, F1.json etc.)")
            }

            val config = ModelConfig(
                sampleRate = sampleRate,
                voices = voices,
                supportedLanguages = SupportedLanguages.codes,
            )
            modelConfig = config
            Log.i(TAG, "Model loaded. ${voices.size} voices: ${voices.map { it.id }}")
            return config
        } catch (e: TtsError) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Model loading failed", e)
            throw TtsError.ModelNotLoaded("Failed to load models: ${e.message}")
        }
    }

    // ── Synthesis ─────────────────────────────────────────────────────────

    override fun registerVoice(id: String, styleTtl: FloatArray, styleDp: FloatArray) {
        voiceStylesTtl[id] = StyleTensor(styleTtl, intArrayOf(1, 50, 256))
        voiceStylesDp[id] = StyleTensor(styleDp, intArrayOf(1, 8, 16))
    }

    override fun synthesise(
        text: String, voiceStyleId: String, language: String, speechRate: Float,
    ): FloatArray {
        require(speechRate > 0f) { "speechRate must be positive, was $speechRate" }
        val env = ortEnvironment ?: throw TtsError.ModelNotLoaded()
        val dpSession = durationPredictorSession ?: throw TtsError.ModelNotLoaded()
        val teSession = textEncoderSession ?: throw TtsError.ModelNotLoaded()
        val veSession = vectorEstimatorSession ?: throw TtsError.ModelNotLoaded()
        val vocSession = vocoderSession ?: throw TtsError.ModelNotLoaded()
        val indexer = unicodeIndexer ?: throw TtsError.ModelNotLoaded("Unicode indexer not loaded")

        val styleTtl = voiceStylesTtl[voiceStyleId] ?: voiceStylesTtl.values.firstOrNull()
            ?: throw TtsError.InferenceFailed("No voice style for '$voiceStyleId'")
        val styleDp = voiceStylesDp[voiceStyleId] ?: voiceStylesDp.values.firstOrNull()
            ?: throw TtsError.InferenceFailed("No DP voice style for '$voiceStyleId'")

        val tensorsToClose = mutableListOf<OnnxTensor>()

        try {
            // ── 1. Wrap in the language tag the model expects: "<en>Hello world.</en>" ──
            if (!SupportedLanguages.isSupported(language.ifBlank { SupportedLanguages.DEFAULT })) {
                Log.w(TAG, "Unsupported language '$language', falling back to ${SupportedLanguages.DEFAULT}")
            }
            val processedText = TextPreprocessor.tagForModel(text, language)
            Log.d(TAG, "Processed text: '$processedText'")

            // ── 2. Tokenise ──
            val tokenIds = tokeniseText(processedText, indexer)
            if (tokenIds.isEmpty()) {
                throw TtsError.InvalidText("No tokens after tokenisation")
            }
            val seqLen = tokenIds.size
            Log.d(TAG, "Tokens: $seqLen ids, first10=${tokenIds.take(10)}")

            // ── 3. Create shared input tensors ──
            val textIdsTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(tokenIds.map { it.toLong() }.toLongArray()),
                longArrayOf(1, seqLen.toLong())
            ).also { tensorsToClose.add(it) }

            // text_mask: [1, 1, seqLen] filled with 1.0
            val textMaskTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(FloatArray(seqLen) { 1.0f }),
                longArrayOf(1, 1, seqLen.toLong())
            ).also { tensorsToClose.add(it) }

            val dpStyleTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(styleDp.data),
                styleDp.dims.map { it.toLong() }.toLongArray()
            ).also { tensorsToClose.add(it) }

            val ttlStyleTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(styleTtl.data),
                styleTtl.dims.map { it.toLong() }.toLongArray()
            ).also { tensorsToClose.add(it) }

            // ── Step 1: Duration Prediction ──
            val synthStartNs = System.nanoTime()
            Log.d(TAG, "Step 1: Duration prediction...")
            val dpResult = dpSession.run(mapOf(
                "text_ids" to textIdsTensor,
                "style_dp" to dpStyleTensor,
                "text_mask" to textMaskTensor,
            ))
            val durTensor = dpResult.get(dpSession.outputNames.first()).get() as OnnxTensor
            val durSeconds = extractFloats(durTensor)
            dpResult.close()

            // Duration is a scalar (total seconds), apply speed factor
            // 1.05 is the reference renderer's own base speed; speechRate scales
            // it, which is what SpeakRequest.speechRate always promised and never did
            val speed = 1.05f * speechRate
            val duration = durSeconds[0] / speed
            Log.i(TAG, "Duration: ${durSeconds[0]}s -> ${duration}s (speed=$speed)")

            // ── Step 2: Text Encoding ──
            Log.d(TAG, "Step 2: Text encoding...")
            val teResult = teSession.run(mapOf(
                "text_ids" to textIdsTensor,
                "style_ttl" to ttlStyleTensor,
                "text_mask" to textMaskTensor,
            ))
            val textEmbTensor = teResult.get(teSession.outputNames.first()).get() as OnnxTensor
            // Keep textEmb alive — don't close teResult until after VE loop
            val textEmbShape = textEmbTensor.info.shape
            Log.i(TAG, "TextEmb shape: ${textEmbShape.toList()}")

            // ── Step 3: Sample noisy latent + compute masks ──
            val wavLenMax = (duration * sampleRate).toLong()
            val wavLength = (duration * sampleRate).toLong()
            val latentLen = ((wavLenMax + chunkSize - 1) / chunkSize).toInt()
            Log.i(TAG, "wavLen=$wavLength latentLen=$latentLen latentDimVal=$latentDimVal")

            // Latent mask: [1, 1, latentLen] — 1.0 within the (deterministic) duration, else 0.
            val noiseSize = latentDimVal * latentLen
            val latentLength = ((wavLength + chunkSize - 1) / chunkSize).toInt()
            val latentMaskData = FloatArray(latentLen) { i -> if (i < latentLength) 1.0f else 0.0f }
            val latentMaskTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(latentMaskData),
                longArrayOf(1, 1, latentLen.toLong())
            ).also { tensorsToClose.add(it) }

            val totalSteps = synthSteps
            val trimLength = min((sampleRate * duration).toInt(), Int.MAX_VALUE)

            // ── Steps 4+5: best-of-N flow matching + vocoding ──
            // Flow matching from a random latent under-converges into a garbled word on ~15-20%
            // of noise draws. The coaching text is LLM-generated (unbounded), so we can't audit a
            // fixed cue list. Instead render N candidates from DIFFERENT seeds (the shared, noise-
            // free text encoding + duration are computed once above) and keep the MEDOID — the one
            // most acoustically similar to the others. Clean renders of the same text agree; a
            // garbled render is an idiosyncratic outlier, so the majority self-consistency wins
            // without any external recognizer. A text-derived base seed keeps the whole thing
            // reproducible for debugging.
            val baseSeed = (text + "|" + voiceStyleId).hashCode().toLong()

            // Render one candidate (noise → N VE steps → vocoder → trimmed audio). Pure w.r.t.
            // the shared read-only tensors, so candidates can run concurrently. A single render
            // only saturates ~2 threads, so running N at once fills the intra-op pool and makes
            // best-of-N cost close to a single render instead of N×.
            fun render(c: Int): FloatArray {
                var xt = randomGaussian(noiseSize, baseSeed + c * 7919L)
                for (d in 0 until latentDimVal) {
                    val base = d * latentLen
                    for (t in 0 until latentLen) xt[base + t] *= latentMaskData[t]
                }
                for (step in 0 until totalSteps) {
                    val xtTensor = OnnxTensor.createTensor(
                        env, FloatBuffer.wrap(xt), longArrayOf(1, latentDimVal.toLong(), latentLen.toLong())
                    )
                    val currentStepTensor = OnnxTensor.createTensor(
                        env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1)
                    )
                    val totalStepTensor = OnnxTensor.createTensor(
                        env, FloatBuffer.wrap(floatArrayOf(totalSteps.toFloat())), longArrayOf(1)
                    )
                    val veResult = veSession.run(mapOf(
                        "noisy_latent" to xtTensor,
                        "text_emb" to textEmbTensor,
                        "style_ttl" to ttlStyleTensor,
                        "text_mask" to textMaskTensor,
                        "latent_mask" to latentMaskTensor,
                        "current_step" to currentStepTensor,
                        "total_step" to totalStepTensor,
                    ))
                    xt = extractFloats(veResult.get(veSession.outputNames.first()).get() as OnnxTensor)
                    veResult.close(); xtTensor.close(); currentStepTensor.close(); totalStepTensor.close()
                }
                val latentTensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(xt), longArrayOf(1, latentDimVal.toLong(), latentLen.toLong())
                )
                val vocResult = vocSession.run(mapOf("latent" to latentTensor))
                val full = extractFloats(vocResult.get(vocSession.outputNames.first()).get() as OnnxTensor)
                vocResult.close(); latentTensor.close()
                return full.copyOfRange(0, min(full.size, trimLength))
            }

            val candidates: List<FloatArray>
            if (synthBestOfN <= 1) {
                candidates = listOf(render(0))
            } else {
                val out = arrayOfNulls<FloatArray>(synthBestOfN)
                val errors = arrayOfNulls<Throwable>(synthBestOfN)
                val threads = (0 until synthBestOfN).map { c ->
                    Thread { try { out[c] = render(c) } catch (t: Throwable) { errors[c] = t } }
                        .apply { name = "tts-cand-$c"; start() }
                }
                threads.forEach { it.join() }
                errors.firstOrNull { it != null }?.let { throw it }
                candidates = out.map { it!! }
            }

            // Done with text encoder output
            teResult.close()

            val audio = if (candidates.size <= 1) candidates[0] else candidates[selectBestCandidate(candidates)]

            Log.i(TAG, "SynthTiming: ${(System.nanoTime() - synthStartNs) / 1_000_000}ms for ${audio.size / sampleRate.toFloat()}s of audio, bestOf=$synthBestOfN, steps=$synthSteps")
            Log.i(TAG, "Synthesis done: ${audio.size} samples (${audio.size / sampleRate.toFloat()}s) " +
                "min=${audio.min()} max=${audio.max()}")

            // Debug WAV export — opt-in (setprop debug.tts.wav 1): it wrote ~250 KB to disk after
            // every line, before playback, on every install.
            if (debugProp("debug.tts.wav") == "1") try {
                val debugDir = File(File(modelDir ?: "").parentFile, "debug")
                debugDir.mkdirs()
                saveWav(File(debugDir, "tts_debug.wav"), audio, sampleRate)
                Log.i(TAG, "Debug WAV saved to ${debugDir.absolutePath}/tts_debug.wav")
            } catch (e: Exception) {
                Log.w(TAG, "Could not save debug WAV: ${e.message}")
            }

            tensorsToClose.forEach { it.close() }
            return audio
        } catch (e: TtsError) {
            tensorsToClose.forEach { runCatching { it.close() } }
            throw e
        } catch (e: Exception) {
            tensorsToClose.forEach { runCatching { it.close() } }
            Log.e(TAG, "Inference failed", e)
            throw TtsError.InferenceFailed("ONNX inference failed: ${e.message}")
        }
    }

    // ── Playback ──────────────────────────────────────────────────────────

    /**
     * Bring up the single persistent stream track and its feeder thread (idempotent while the
     * sample rate is unchanged). Holds [trackLock]. The feeder writes 20 ms silence chunks when
     * idle and clip PCM (via [pendingClip]) when a cue is submitted, all with blocking writes so
     * the track's buffer never underruns.
     */
    private fun ensureStreamLocked(sampleRate: Int) {
        if (streamTrack != null && streamRate == sampleRate && streamRunning) return
        stopStreamLocked()

        // Small buffer keeps the clip close to the head of the queue (low added latency) while
        // continuous blocking feeding keeps it from underrunning.
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        streamTrack = t
        streamRate = sampleRate
        streamRunning = true
        framesWritten = 0L
        clipEndFrame = -1L
        pendingClip = null
        t.play()

        val silence = ShortArray(sampleRate * 20 / 1000) // 20 ms keep-alive chunk
        val th = Thread {
            try {
                while (streamRunning) {
                    val clip = pendingClip
                    if (clip != null) {
                        pendingClip = null
                        var off = 0
                        while (off < clip.size && streamRunning) {
                            val w = t.write(clip, off, clip.size - off, AudioTrack.WRITE_BLOCKING)
                            if (w <= 0) break
                            off += w
                            framesWritten += w
                        }
                        synchronized(clipLock) {
                            clipEndFrame = framesWritten
                            clipLock.notifyAll()
                        }
                    } else {
                        val w = t.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                        if (w > 0) framesWritten += w else if (w < 0) break
                    }
                }
            } catch (_: Throwable) { /* released — exit */ }
        }.apply { isDaemon = true; name = "tts-stream" }
        streamThread = th
        th.start()
    }

    private fun stopStreamLocked() {
        streamRunning = false
        streamThread?.let { runCatching { it.interrupt() } }
        streamThread = null
        streamTrack?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        streamTrack = null
        streamRate = 0
        pendingClip = null
        clipEndFrame = -1L
        framesWritten = 0L
        synchronized(clipLock) { clipLock.notifyAll() }
    }

    /** Called before synthesis: bring up the stream so the output is hot when the cue plays. */
    override fun prepareStream(sampleRate: Int) {
        synchronized(trackLock) { ensureStreamLocked(sampleRate) }
    }

    override fun playAudio(pcmData: FloatArray, sampleRate: Int, volume: Float) {
        Log.i(TAG, "Playing ${pcmData.size} samples (${pcmData.size / sampleRate.toFloat()}s)")

        val pcm16 = ShortArray(pcmData.size) { i ->
            (pcmData[i].coerceIn(-1.0f, 1.0f) * Short.MAX_VALUE).toInt().toShort()
        }

        val track: AudioTrack
        synchronized(trackLock) {
            ensureStreamLocked(sampleRate)
            track = streamTrack ?: throw TtsError.AudioPlaybackFailed("No stream track")
            runCatching { track.setVolume(volume) }
            isPlaying = true
            // Hand the clip to the feeder. It writes it right after the silence already buffered.
            clipEndFrame = -1L
            pendingClip = pcm16
        }

        try {
            // Wait for the feeder to accept + write the whole clip (sets clipEndFrame).
            val acceptDeadline = System.currentTimeMillis() + 3000
            synchronized(clipLock) {
                while (clipEndFrame < 0 && streamRunning) {
                    val remain = acceptDeadline - System.currentTimeMillis()
                    if (remain <= 0) break
                    clipLock.wait(remain)
                }
            }
            val endFrame = clipEndFrame
            if (endFrame >= 0) {
                // Block until the clip has physically drained (playback head reaches its end).
                val durationMs = (pcm16.size.toLong() * 1000L) / sampleRate
                val drainDeadline = System.currentTimeMillis() + durationMs + 1000
                while (streamRunning && System.currentTimeMillis() < drainDeadline) {
                    val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (head >= endFrame) break
                    Thread.sleep(10)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            throw TtsError.AudioPlaybackFailed("Playback failed: ${e.message}")
        } finally {
            synchronized(trackLock) { isPlaying = false }
        }
    }

    override fun stopAudio() {
        // Soft barge-in: just drop the queued clip so the next cue supersedes it. The stream
        // track is NEVER paused/flushed/restarted here — that would add a per-cue start transient
        // (the very thing this design removes). Any already-buffered audio (≤ one small buffer)
        // simply drains as the feeder resumes silence.
        pendingClip = null
        synchronized(trackLock) { isPlaying = false }
        synchronized(clipLock) { clipLock.notifyAll() }
    }

    override fun release() {
        synchronized(trackLock) {
            isPlaying = false
            stopStreamLocked() // stopAudio keeps the stream warm; release tears it down
        }
        listOf(durationPredictorSession, textEncoderSession, vectorEstimatorSession, vocoderSession)
            .forEach { runCatching { it?.close() } }
        durationPredictorSession = null
        textEncoderSession = null
        vectorEstimatorSession = null
        vocoderSession = null
        ortEnvironment = null
        modelConfig = null
        unicodeIndexer = null
        voiceStylesTtl.clear()
        voiceStylesDp.clear()
        modelDir = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private data class StyleTensor(val data: FloatArray, val dims: IntArray)

    private fun parseStyleTensor(json: JSONObject): StyleTensor {
        val dimsArr = json.getJSONArray("dims")
        val dims = IntArray(dimsArr.length()) { dimsArr.getInt(it) }
        val data = flattenJson(json.getJSONArray("data"))
        return StyleTensor(data, dims)
    }

    private fun flattenJson(arr: JSONArray): FloatArray {
        val result = mutableListOf<Float>()
        for (i in 0 until arr.length()) {
            when (val item = arr.get(i)) {
                is JSONArray -> result.addAll(flattenJson(item).toList())
                is Number -> result.add(item.toFloat())
                else -> result.add(arr.getDouble(i).toFloat())
            }
        }
        return result.toFloatArray()
    }

    /**
     * Tokenise text using unicode_indexer. Matches the official reference
     * (`[indexer[ord(c)] for c in text]`) except that codepoints the indexer
     * marks unsupported ([UNSUPPORTED_TOKEN]) are dropped rather than passed
     * through — a negative id would be read by the embedding Gather as an
     * index from the end of the table, silently substituting an unrelated
     * character. Text should already be NFKD-normalised by [TextPreprocessor].
     */
    private fun tokeniseText(text: String, indexer: IntArray): IntArray {
        // Use codePoints to handle multi-byte chars correctly
        val codePoints = text.codePoints().toArray()
        val tokens = ArrayList<Int>(codePoints.size)
        var dropped = 0
        for (cp in codePoints) {
            val token = if (cp < indexer.size) indexer[cp] else UNSUPPORTED_TOKEN
            if (token == UNSUPPORTED_TOKEN) dropped++ else tokens.add(token)
        }
        if (dropped > 0) Log.w(TAG, "Dropped $dropped unsupported character(s)")
        return tokens.toIntArray()
    }

    /** Extract flat FloatArray from OnnxTensor via its raw FloatBuffer. */
    private fun extractFloats(tensor: OnnxTensor): FloatArray {
        val buf = tensor.floatBuffer
        val arr = FloatArray(buf.remaining())
        buf.get(arr)
        return arr
    }

    /**
     * Pick the medoid of N candidate waveforms — the one whose log-magnitude spectrogram is
     * closest (summed L2 over frames × bands) to all the others. Clean renders of the same text
     * are acoustically similar; a garbled render is an outlier, so the medoid is a clean render
     * as long as a majority are clean. No external recognizer needed.
     */
    private val specBands = 24

    /**
     * Choose the best of N candidate renders. The dominant garble mode is a DROPPED word
     * ("Good work" -> "Work"), and the duration predictor sizes every candidate to the SAME
     * length, so a dropped word leaves a silent gap → fewer voiced frames. A garble is often the
     * *majority* outcome, so consensus/medoid selection picks it; instead prefer the candidate
     * with the MOST voiced content (closest to filling the intended duration with speech), which
     * favours the complete render even when it's the minority. Ties (near-equal content, e.g. a
     * substitution rather than a drop) fall back to the medoid — the self-consistent one.
     */
    private fun selectBestCandidate(cands: List<FloatArray>): Int {
        val voiced = cands.map { voicedCount(it) }
        val maxV = voiced.max().coerceAtLeast(1)
        // Candidates within 6% of the fullest are "content-equivalent"; break that tie by medoid.
        val threshold = maxV * 0.94
        val full = cands.indices.filter { voiced[it] >= threshold }
        cands.indices.forEach { Log.d(TAG, "cand $it voiced=${voiced[it]} full=${it in full}") }
        val chosen = if (full.size == 1) full[0] else medoidAmong(cands, full)
        Log.i(TAG, "select: chose candidate $chosen of ${cands.size} (voiced=${voiced[chosen]}/$maxV)")
        return chosen
    }

    /** Number of speech (non-silent) samples — a proxy for how much of the utterance is present. */
    private fun voicedCount(audio: FloatArray): Int {
        var n = 0
        for (v in audio) if (v > 0.02f || v < -0.02f) n++
        return n
    }

    /** Medoid (min total DTW distance) restricted to a candidate subset. */
    private fun medoidAmong(cands: List<FloatArray>, subset: List<Int>): Int {
        if (subset.size == 1) return subset[0]
        val feats = subset.associateWith { specFeature(cands[it]) }
        var best = subset[0]; var bestTotal = Double.MAX_VALUE
        for (i in subset) {
            var total = 0.0
            for (j in subset) if (i != j) total += dtwCost(feats[i]!!, feats[j]!!, specBands)
            if (total < bestTotal) { bestTotal = total; best = i }
        }
        return best
    }

    /**
     * DTW distance between two band-spectrograms (frames × [bands]), length-normalized. Time
     * warping makes it robust to the prosody/timing jitter between two clean renders, so a
     * dropped or substituted word stands out as the real difference — which raw frame-aligned
     * L2 could not tell apart from natural variation.
     */
    private fun dtwCost(a: FloatArray, b: FloatArray, bands: Int): Double {
        val na = a.size / bands; val nb = b.size / bands
        if (na == 0 || nb == 0) return Double.MAX_VALUE / 4
        val INF = Double.MAX_VALUE / 4
        var prev = DoubleArray(nb + 1) { INF }
        var cur = DoubleArray(nb + 1) { INF }
        prev[0] = 0.0
        for (i in 1..na) {
            cur[0] = INF
            val ao = (i - 1) * bands
            for (j in 1..nb) {
                val bo = (j - 1) * bands
                var c = 0.0
                for (k in 0 until bands) { val e = (a[ao + k] - b[bo + k]).toDouble(); c += e * e }
                cur[j] = sqrt(c) + minOf(prev[j], cur[j - 1], prev[j - 1])
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[nb] / (na + nb)
    }

    /** Coarse log-magnitude spectrogram (24 bands/frame) used only to compare candidates. */
    private fun specFeature(audio: FloatArray): FloatArray {
        val frame = 1024; val hop = 512; val bands = 24
        val half = frame / 2
        val binsPerBand = half / bands
        val win = FloatArray(frame) { (0.5 - 0.5 * cos(2.0 * Math.PI * it / (frame - 1))).toFloat() }
        val nFrames = if (audio.size < frame) 1 else 1 + (audio.size - frame) / hop
        val re = DoubleArray(frame); val im = DoubleArray(frame)
        val out = FloatArray(nFrames * bands)
        for (f in 0 until nFrames) {
            val start = f * hop
            for (i in 0 until frame) {
                val s = start + i
                re[i] = if (s < audio.size) (audio[s] * win[i]).toDouble() else 0.0
                im[i] = 0.0
            }
            fftInPlace(re, im)
            for (b in 0 until bands) {
                var e = 0.0
                val lo = b * binsPerBand
                for (k in lo until lo + binsPerBand) e += re[k] * re[k] + im[k] * im[k]
                out[f * bands + b] = ln(e + 1e-6).toFloat()
            }
        }
        return out
    }

    /** In-place iterative radix-2 Cooley–Tukey FFT (size must be a power of two). */
    private fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang); val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var cwr = 1.0; var cwi = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k]; val ai = im[i + k]
                    val br = re[i + k + len / 2] * cwr - im[i + k + len / 2] * cwi
                    val bi = re[i + k + len / 2] * cwi + im[i + k + len / 2] * cwr
                    re[i + k] = ar + br; im[i + k] = ai + bi
                    re[i + k + len / 2] = ar - br; im[i + k + len / 2] = ai - bi
                    val ncwr = cwr * wr - cwi * wi; cwi = cwr * wi + cwi * wr; cwr = ncwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Random Gaussian noise via Box-Muller transform. */
    private fun randomGaussian(count: Int, seed: Long? = null): FloatArray {
        val rng = if (seed != null) java.util.Random(seed) else java.util.Random()
        val out = FloatArray(count)
        var i = 0
        while (i < count - 1) {
            val u1 = max(0.0001, min(0.9999, rng.nextDouble()))
            val u2 = rng.nextDouble()
            val r = sqrt(-2.0 * ln(u1))
            out[i] = (r * cos(2.0 * Math.PI * u2)).toFloat()
            out[i + 1] = (r * kotlin.math.sin(2.0 * Math.PI * u2)).toFloat()
            i += 2
        }
        if (count % 2 != 0) {
            val u1 = max(0.0001, min(0.9999, rng.nextDouble()))
            val u2 = rng.nextDouble()
            out[count - 1] = (sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)).toFloat()
        }
        return out
    }

    /** Write float PCM as 16-bit WAV for debugging. */
    private fun saveWav(file: File, samples: FloatArray, sr: Int) {
        val dataSize = samples.size * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.writeBytes("RIFF")
            raf.writeInt(Integer.reverseBytes(36 + dataSize))
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeInt(Integer.reverseBytes(16))
            raf.writeShort(java.lang.Short.reverseBytes(1).toInt())
            raf.writeShort(java.lang.Short.reverseBytes(1).toInt())
            raf.writeInt(Integer.reverseBytes(sr))
            raf.writeInt(Integer.reverseBytes(sr * 2))
            raf.writeShort(java.lang.Short.reverseBytes(2).toInt())
            raf.writeShort(java.lang.Short.reverseBytes(16).toInt())
            raf.writeBytes("data")
            raf.writeInt(Integer.reverseBytes(dataSize))
            val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                buf.clear()
                buf.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                raf.write(buf.array(), 0, 2)
            }
        }
    }
}

/** A debug system property (adb shell setprop), or null if unset/empty. */
private fun debugProp(name: String): String? = runCatching {
    val cls = Class.forName("android.os.SystemProperties")
    (cls.getMethod("get", String::class.java, String::class.java).invoke(null, name, "") as String)
}.getOrNull()?.takeIf { it.isNotBlank() }

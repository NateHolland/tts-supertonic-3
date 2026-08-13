package com.nate.tts.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.nate.tts.config.ModelConfig
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * Loads the supertonic-2 multi-model pipeline:
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

    // All access to [audioTrack] + [playbackLatch] is guarded by [trackLock] so a new
    // utterance's stopAudio() can't race the previous utterance's playback teardown (a source
    // of overlapping/garbled output). [playbackLatch] lets stopAudio() promptly wake a
    // playAudio() that's blocked waiting for the clip to finish.
    private val trackLock = Any()
    private var audioTrack: AudioTrack? = null
    private var playbackLatch: CountDownLatch? = null

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

    /** latentDim * chunkCompressFactor — the actual channel dimension for latent tensors */
    private val latentDimVal: Int get() = latentDim * chunkCompressFactor
    /** baseChunkSize * chunkCompressFactor — samples per latent frame */
    private val chunkSize: Int get() = baseChunkSize * chunkCompressFactor

    @Volatile
    override var isPlaying: Boolean = false
        private set

    // ── Model Loading ────────────────────────────────────────────────────

    override fun loadModel(modelDir: String): ModelConfig {
        release()

        val dir = File(modelDir)
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
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
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
                supportedLanguages = listOf("en", "ko", "es", "pt", "fr"),
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

    override fun synthesise(text: String, voiceStyleId: String, language: String): FloatArray {
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
            // ── 1. Preprocess text with language tags ──
            // Critical: the model expects text wrapped like "<en>Hello world.</en>"
            val lang = language.ifBlank { "en" }
            var processedText = text.trim()
            // Add trailing punctuation if missing (matching official preprocessor)
            if (!processedText.matches(Regex(".*[.!?;:,'\"')\\]}…]$"))) {
                processedText += "."
            }
            processedText = "<$lang>$processedText</$lang>"
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
            val speed = 1.05f
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

            // Generate random Gaussian noise [1, latentDimVal, latentLen]
            val noiseSize = latentDimVal * latentLen
            val noise = randomGaussian(noiseSize)

            // Compute latent mask: [1, 1, latentLen]
            // For single-sample case, all positions within the duration are 1.0
            val latentLength = ((wavLength + chunkSize - 1) / chunkSize).toInt()
            val latentMaskData = FloatArray(latentLen) { i ->
                if (i < latentLength) 1.0f else 0.0f
            }

            // Apply mask to noise: zero out positions beyond the actual duration
            for (d in 0 until latentDimVal) {
                for (t in 0 until latentLen) {
                    noise[d * latentLen + t] *= latentMaskData[t]
                }
            }

            val latentMaskTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(latentMaskData),
                longArrayOf(1, 1, latentLen.toLong())
            ).also { tensorsToClose.add(it) }

            // ── Step 4: Iterative denoising with vector estimator ──
            val totalSteps = 5
            var xt = noise  // mutable — replaced each iteration
            Log.d(TAG, "Step 4: VE denoising ($totalSteps steps)...")

            for (step in 0 until totalSteps) {
                val xtTensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(xt),
                    longArrayOf(1, latentDimVal.toLong(), latentLen.toLong())
                )
                val currentStepTensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(floatArrayOf(step.toFloat())),
                    longArrayOf(1)
                )
                val totalStepTensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(floatArrayOf(totalSteps.toFloat())),
                    longArrayOf(1)
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
                veResult.close()
                xtTensor.close()
                currentStepTensor.close()
                totalStepTensor.close()

                Log.d(TAG, "VE step $step: size=${xt.size} min=${xt.min()} max=${xt.max()}")
            }

            // Done with text encoder output
            teResult.close()

            // ── Step 5: Vocoder ──
            Log.d(TAG, "Step 5: Vocoder...")
            val latentTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(xt),
                longArrayOf(1, latentDimVal.toLong(), latentLen.toLong())
            )
            val vocResult = vocSession.run(mapOf("latent" to latentTensor))
            val fullAudio = extractFloats(vocResult.get(vocSession.outputNames.first()).get() as OnnxTensor)
            vocResult.close()
            latentTensor.close()

            // Trim audio to actual duration
            val trimLength = min(fullAudio.size, (sampleRate * duration).toInt())
            val audio = fullAudio.copyOfRange(0, trimLength)

            Log.i(TAG, "Synthesis done: ${audio.size} samples (${audio.size / sampleRate.toFloat()}s) " +
                "min=${audio.min()} max=${audio.max()}")

            // Debug WAV export
            try {
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

    override fun playAudio(pcmData: FloatArray, sampleRate: Int, volume: Float) {
        stopAudio()

        val latch = CountDownLatch(1)
        var track: AudioTrack? = null
        try {
            Log.i(TAG, "Playing ${pcmData.size} samples (${pcmData.size / sampleRate.toFloat()}s)")

            val pcm16 = ShortArray(pcmData.size) { i ->
                (pcmData[i].coerceIn(-1.0f, 1.0f) * Short.MAX_VALUE).toInt().toShort()
            }

            val bufferSize = pcm16.size * 2
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )

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
                .setBufferSizeInBytes(maxOf(bufferSize, minBuf))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track = t

            t.write(pcm16, 0, pcm16.size)
            t.setVolume(volume)
            t.notificationMarkerPosition = pcm16.size
            t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(x: AudioTrack?) { latch.countDown() }
                override fun onPeriodicNotification(x: AudioTrack?) {}
            })

            // Publish as the current track BEFORE play() so a concurrent stopAudio() can find
            // and stop it (and wake this latch) rather than leaving an orphan playing.
            synchronized(trackLock) {
                audioTrack = t
                playbackLatch = latch
                isPlaying = true
            }

            t.play()
            val durationMs = (pcm16.size.toLong() * 1000L) / sampleRate
            // Returns when the clip finishes OR stopAudio() counts the latch down early.
            latch.await(durationMs + 500, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            throw TtsError.AudioPlaybackFailed("Playback failed: ${e.message}")
        } finally {
            synchronized(trackLock) {
                if (playbackLatch === latch) playbackLatch = null
                // Only tear down / clear state if stopAudio() hasn't already replaced us.
                if (audioTrack === track && track != null) {
                    runCatching { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() }
                    runCatching { track.release() }
                    audioTrack = null
                    isPlaying = false
                }
            }
        }
    }

    override fun stopAudio() {
        synchronized(trackLock) {
            // Wake any playAudio() blocked on the clip so its coroutine can unwind promptly.
            playbackLatch?.countDown()
            playbackLatch = null
            audioTrack?.let {
                runCatching { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop() }
                runCatching { it.release() }
            }
            audioTrack = null
            isPlaying = false
        }
    }

    override fun release() {
        stopAudio()
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
     * Tokenise text using unicode_indexer.
     * Matches official: `[indexer[ord(c)] for c in text]`
     */
    private fun tokeniseText(text: String, indexer: IntArray): IntArray {
        // Use codePoints to handle multi-byte chars correctly
        val codePoints = text.codePoints().toArray()
        return IntArray(codePoints.size) { i ->
            val cp = codePoints[i]
            if (cp < indexer.size) indexer[cp] else 0
        }
    }

    /** Extract flat FloatArray from OnnxTensor via its raw FloatBuffer. */
    private fun extractFloats(tensor: OnnxTensor): FloatArray {
        val buf = tensor.floatBuffer
        val arr = FloatArray(buf.remaining())
        buf.get(arr)
        return arr
    }

    /** Random Gaussian noise via Box-Muller transform. */
    private fun randomGaussian(count: Int): FloatArray {
        val rng = java.util.Random()
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

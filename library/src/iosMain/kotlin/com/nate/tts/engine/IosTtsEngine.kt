package com.nate.tts.engine

import cocoapods.onnxruntime_objc.*
import com.nate.tts.config.ModelConfig
import com.nate.tts.error.TtsError
import com.nate.tts.model.TtsVoice
import kotlinx.cinterop.*
import platform.AVFAudio.*
import platform.Foundation.*
import platform.posix.memcpy
import kotlin.math.*
import kotlin.random.Random

/**
 * iOS implementation of [TtsEngine] using ONNX Runtime Obj-C API via cinterop.
 *
 * Loads the supertonic-2 multi-model pipeline and runs inference entirely in Kotlin.
 */
@OptIn(ExperimentalForeignApi::class)
class IosTtsEngine : TtsEngine {

    private var dpSession: ORTSession? = null
    private var teSession: ORTSession? = null
    private var veSession: ORTSession? = null
    private var vocSession: ORTSession? = null
    private var ortEnv: ORTEnv? = null

    private var audioPlayer: AVAudioPlayer? = null
    private var modelDir: String? = null
    private var _isPlaying: Boolean = false

    private var unicodeIndexer: IntArray = IntArray(0)
    private val voiceStylesTtl = mutableMapOf<String, StyleTensor>()
    private val voiceStylesDp = mutableMapOf<String, StyleTensor>()

    private var sampleRate: Int = 44100
    private var baseChunkSize: Int = 512
    private var chunkCompressFactor: Int = 6
    private var latentDim: Int = 24

    private val latentDimVal: Int get() = latentDim * chunkCompressFactor
    private val chunkSize: Int get() = baseChunkSize * chunkCompressFactor

    override var isPlaying: Boolean
        get() = _isPlaying
        private set(value) { _isPlaying = value }

    override fun loadModel(modelDir: String): ModelConfig {
        release()

        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(modelDir)) {
            throw TtsError.ModelNotLoaded("Model directory not found: $modelDir")
        }

        this.modelDir = modelDir

        val requiredFiles = listOf(
            "duration_predictor.onnx", "text_encoder.onnx",
            "vector_estimator.onnx", "vocoder.onnx",
            "tts.json", "unicode_indexer.json"
        )
        for (file in requiredFiles) {
            val path = "$modelDir/$file"
            if (!fm.fileExistsAtPath(path)) {
                throw TtsError.ModelNotLoaded("Missing required file: $file")
            }
        }

        // Parse tts.json
        val ttsJsonData = fm.contentsAtPath("$modelDir/tts.json")
        if (ttsJsonData != null) {
            val json = NSJSONSerialization.JSONObjectWithData(ttsJsonData, 0u, null) as? Map<*, *>
            if (json != null) {
                val ae = json["ae"] as? Map<*, *>
                val ttl = json["ttl"] as? Map<*, *>
                sampleRate = toIntSafe(ae?.get("sample_rate")) ?: 44100
                baseChunkSize = toIntSafe(ae?.get("base_chunk_size")) ?: 512
                chunkCompressFactor = toIntSafe(ttl?.get("chunk_compress_factor")) ?: 6
                latentDim = toIntSafe(ttl?.get("latent_dim")) ?: 24
            }
        }
        println("[TTS] Config: sr=$sampleRate bcs=$baseChunkSize ccf=$chunkCompressFactor ld=$latentDim ldv=$latentDimVal cs=$chunkSize")

        // Parse unicode_indexer.json
        val indexerData = fm.contentsAtPath("$modelDir/unicode_indexer.json")
        if (indexerData != null) {
            val arr = NSJSONSerialization.JSONObjectWithData(indexerData, 0u, null) as? List<*>
            if (arr != null) {
                unicodeIndexer = IntArray(arr.size) { toIntSafe(arr[it]) ?: 0 }
            }
        }
        println("[TTS] Unicode indexer: ${unicodeIndexer.size} entries")

        // Initialise ONNX Runtime sessions
        try {
            val env = ortCall("ORTEnv") { e -> ORTEnv(loggingLevel = ORTLoggingLevel.ORTLoggingLevelWarning, error = e) }
            val opts = ortCall("ORTSessionOptions") { e -> ORTSessionOptions(error = e) }
            ortCall("setIntraOpNumThreads") { e -> opts.setIntraOpNumThreads(2, error = e); true }
            ortCall("setGraphOptimizationLevel") { e -> opts.setGraphOptimizationLevel(ORTGraphOptimizationLevel.ORTGraphOptimizationLevelAll, error = e); true }

            println("[TTS] Loading ONNX models...")
            dpSession = ortCall("DP session") { e -> ORTSession(env = env, modelPath = "$modelDir/duration_predictor.onnx", sessionOptions = opts, error = e) }
            teSession = ortCall("TE session") { e -> ORTSession(env = env, modelPath = "$modelDir/text_encoder.onnx", sessionOptions = opts, error = e) }
            veSession = ortCall("VE session") { e -> ORTSession(env = env, modelPath = "$modelDir/vector_estimator.onnx", sessionOptions = opts, error = e) }
            vocSession = ortCall("VOC session") { e -> ORTSession(env = env, modelPath = "$modelDir/vocoder.onnx", sessionOptions = opts, error = e) }
            ortEnv = env
        } catch (e: Exception) {
            throw TtsError.ModelNotLoaded("Failed to create ONNX sessions: ${e.message}")
        }

        // Discover voice styles
        val voices = mutableListOf<TtsVoice>()
        val contents = fm.contentsOfDirectoryAtPath(modelDir, null) as? List<*> ?: emptyList<String>()
        val styleFiles = contents
            .filterIsInstance<String>()
            .filter { it.matches(Regex("[FM]\\d+\\.json")) }
            .sorted()

        for (file in styleFiles) {
            val styleId = file.removeSuffix(".json")
            val gender = if (styleId.startsWith("M")) "MALE" else "FEMALE"
            val styleData = fm.contentsAtPath("$modelDir/$file") ?: continue
            val json = NSJSONSerialization.JSONObjectWithData(styleData, 0u, null) as? Map<*, *> ?: continue
            val ttlJson = json["style_ttl"] as? Map<*, *> ?: continue
            val dpJson = json["style_dp"] as? Map<*, *> ?: continue

            voiceStylesTtl[styleId] = parseStyleTensor(ttlJson)
            voiceStylesDp[styleId] = parseStyleTensor(dpJson)
            voices.add(TtsVoice(id = styleId, name = styleId, gender = gender))
            println("[TTS] Voice: $styleId")
        }

        if (voices.isEmpty()) {
            throw TtsError.ModelNotLoaded("No voice styles found")
        }

        println("[TTS] Model loaded. ${voices.size} voices")
        return ModelConfig(sampleRate = sampleRate, voices = voices, supportedLanguages = listOf("en", "ko", "es", "pt", "fr"))
    }

    override fun synthesise(text: String, voiceStyleId: String, language: String): FloatArray {
        val dpSess = dpSession ?: throw TtsError.ModelNotLoaded()
        val teSess = teSession ?: throw TtsError.ModelNotLoaded()
        val veSess = veSession ?: throw TtsError.ModelNotLoaded()
        val vocSess = vocSession ?: throw TtsError.ModelNotLoaded()

        val styleTtl = voiceStylesTtl[voiceStyleId] ?: voiceStylesTtl.values.firstOrNull()
            ?: throw TtsError.InferenceFailed("No voice style for '$voiceStyleId'")
        val styleDp = voiceStylesDp[voiceStyleId] ?: voiceStylesDp.values.firstOrNull()
            ?: throw TtsError.InferenceFailed("No DP voice style for '$voiceStyleId'")

        try {
            // 1. Preprocess text with language tags
            val lang = language.ifBlank { "en" }
            var processedText = text.trim()
            if (!processedText.matches(Regex(".*[.!?;:,'\"')\\]}…]$"))) {
                processedText += "."
            }
            processedText = "<$lang>$processedText</$lang>"
            println("[TTS] Processed text: '$processedText'")

            // 2. Tokenise
            val tokenIds = tokeniseText(processedText)
            if (tokenIds.isEmpty()) throw TtsError.InvalidText("No tokens")
            val seqLen = tokenIds.size
            println("[TTS] Tokens: $seqLen")

            // 3. Create tensors
            println("[TTS] Creating textIds tensor: shape=[1, $seqLen]")
            val textIdsTensor = createInt64Tensor(tokenIds.map { it.toLong() }.toLongArray(), longArrayOf(1, seqLen.toLong()))
            println("[TTS] Creating textMask tensor: shape=[1, 1, $seqLen]")
            val textMaskTensor = createFloatTensor(FloatArray(seqLen) { 1.0f }, longArrayOf(1, 1, seqLen.toLong()))
            println("[TTS] Creating dpStyle tensor: dims=${styleDp.dims.toList()}, data.size=${styleDp.data.size}")
            val dpStyleTensor = createFloatTensor(styleDp.data, styleDp.dims.map { it.toLong() }.toLongArray())
            println("[TTS] Creating ttlStyle tensor: dims=${styleTtl.dims.toList()}, data.size=${styleTtl.data.size}")
            val ttlStyleTensor = createFloatTensor(styleTtl.data, styleTtl.dims.map { it.toLong() }.toLongArray())

            // Step 1: Duration Prediction
            println("[TTS] Step 1: Duration prediction...")
            val dpOutputNamesList = ortCall("DP outputNames") { e -> dpSess.outputNamesWithError(e) }
            val dpOutputNames = NSSet.setWithArray(dpOutputNamesList)
            println("[TTS] DP inputs: text_ids, style_dp, text_mask; outputs: $dpOutputNamesList")
            val dpOutputs = ortCall("DP run") { e ->
                dpSess.runWithInputs(
                    mapOf("text_ids" to textIdsTensor, "style_dp" to dpStyleTensor, "text_mask" to textMaskTensor) as Map<Any?, *>,
                    outputNames = dpOutputNames,
                    runOptions = null,
                    error = e
                )
            } as Map<*, *>
            val dpOutName = (dpOutputNamesList as List<*>).first() as String
            val durFloats = extractFloats(dpOutputs[dpOutName] as ORTValue)

            val speed = 1.05f
            val duration = durFloats[0] / speed
            println("[TTS] Duration: ${durFloats[0]}s -> ${duration}s")

            // Step 2: Text Encoding
            println("[TTS] Step 2: Text encoding...")
            val teOutputNamesList = ortCall("TE outputNames") { e -> teSess.outputNamesWithError(e) }
            val teOutputNames = NSSet.setWithArray(teOutputNamesList)
            val teOutputs = ortCall("TE run") { e ->
                teSess.runWithInputs(
                    mapOf("text_ids" to textIdsTensor, "style_ttl" to ttlStyleTensor, "text_mask" to textMaskTensor) as Map<Any?, *>,
                    outputNames = teOutputNames,
                    runOptions = null,
                    error = e
                )
            } as Map<*, *>
            val teOutName = (teOutputNamesList as List<*>).first() as String
            val textEmbValue = teOutputs[teOutName] as ORTValue

            // Step 3: Latent noise + masks
            val wavLenMax = (duration * sampleRate).toLong()
            val latentLen = ((wavLenMax + chunkSize - 1) / chunkSize).toInt()
            println("[TTS] latentLen=$latentLen latentDimVal=$latentDimVal")

            val noiseSize = latentDimVal * latentLen
            val noise = randomGaussian(noiseSize)

            val latentLength = ((wavLenMax + chunkSize - 1) / chunkSize).toInt()
            val latentMaskArray = FloatArray(latentLen) { i -> if (i < latentLength) 1.0f else 0.0f }

            for (d in 0 until latentDimVal) {
                for (t in 0 until latentLen) {
                    noise[d * latentLen + t] *= latentMaskArray[t]
                }
            }

            val latentMaskTensor = createFloatTensor(latentMaskArray, longArrayOf(1, 1, latentLen.toLong()))

            // Step 4: VE denoising
            val totalSteps = 5
            var xt = noise
            println("[TTS] Step 4: VE denoising ($totalSteps steps)...")

            val veOutputNamesList = ortCall("VE outputNames") { e -> veSess.outputNamesWithError(e) }
            val veOutputNames = NSSet.setWithArray(veOutputNamesList)
            val veOutName = (veOutputNamesList as List<*>).first() as String

            for (step in 0 until totalSteps) {
                val xtTensor = createFloatTensor(xt, longArrayOf(1, latentDimVal.toLong(), latentLen.toLong()))
                val currentStepTensor = createFloatTensor(floatArrayOf(step.toFloat()), longArrayOf(1))
                val totalStepTensor = createFloatTensor(floatArrayOf(totalSteps.toFloat()), longArrayOf(1))

                val veOutputs = ortCall("VE run step $step") { e ->
                    veSess.runWithInputs(
                        mapOf(
                            "noisy_latent" to xtTensor, "text_emb" to textEmbValue,
                            "style_ttl" to ttlStyleTensor, "text_mask" to textMaskTensor,
                            "latent_mask" to latentMaskTensor, "current_step" to currentStepTensor,
                            "total_step" to totalStepTensor
                        ) as Map<Any?, *>,
                        outputNames = veOutputNames, runOptions = null, error = e
                    )
                } as Map<*, *>

                xt = extractFloats(veOutputs[veOutName] as ORTValue)
                println("[TTS] VE step $step: size=${xt.size}")
            }

            // Step 5: Vocoder
            println("[TTS] Step 5: Vocoder...")
            val latentTensor = createFloatTensor(xt, longArrayOf(1, latentDimVal.toLong(), latentLen.toLong()))
            val vocOutputNamesList = ortCall("VOC outputNames") { e -> vocSess.outputNamesWithError(e) }
            val vocOutputNames = NSSet.setWithArray(vocOutputNamesList)
            val vocOutName = (vocOutputNamesList as List<*>).first() as String
            val vocOutputs = ortCall("VOC run") { e ->
                vocSess.runWithInputs(
                    mapOf("latent" to latentTensor) as Map<Any?, *>,
                    outputNames = vocOutputNames, runOptions = null, error = e
                )
            } as Map<*, *>

            val fullAudio = extractFloats(vocOutputs[vocOutName] as ORTValue)
            val trimLength = minOf(fullAudio.size, (sampleRate * duration).toInt())
            val audio = fullAudio.copyOfRange(0, trimLength)

            println("[TTS] Synthesis done: ${audio.size} samples (${audio.size / sampleRate.toFloat()}s)")
            return audio

        } catch (e: TtsError) {
            throw e
        } catch (e: Exception) {
            println("[TTS] ERROR: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            throw TtsError.InferenceFailed("ONNX inference failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    override fun playAudio(pcmData: FloatArray, sampleRate: Int, volume: Float) {
        stopAudio()

        if (pcmData.isEmpty()) {
            println("[TTS] WARNING: playAudio called with 0 samples")
            return
        }

        val wavData = createWavData(pcmData, sampleRate)

        try {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)

            audioPlayer = AVAudioPlayer(data = wavData, error = null)
            audioPlayer?.volume = volume
            _isPlaying = true
            audioPlayer?.play()
            println("[TTS] Playing ${pcmData.size} samples")

            // Block until playback completes (matches Android behavior)
            val durationMs = (pcmData.size.toLong() * 1000L) / sampleRate
            NSThread.sleepForTimeInterval(durationMs.toDouble() / 1000.0 + 0.5)
            _isPlaying = false
        } catch (e: Exception) {
            _isPlaying = false
            println("[TTS] AVAudioPlayer error: ${e.message}")
        }
    }

    override fun stopAudio() {
        audioPlayer?.stop()
        audioPlayer = null
        _isPlaying = false
    }

    override fun release() {
        stopAudio()
        dpSession = null
        teSession = null
        veSession = null
        vocSession = null
        ortEnv = null
        modelDir = null
        unicodeIndexer = IntArray(0)
        voiceStylesTtl.clear()
        voiceStylesDp.clear()
    }

    // -- Helpers --

    /** Allocate an NSError pointer, run the block, and throw if ORT set an error. */
    private inline fun <T> ortCall(label: String, block: (CPointer<ObjCObjectVar<NSError?>>) -> T?): T {
        return memScoped {
            val errVar = alloc<ObjCObjectVar<NSError?>>()
            val result = block(errVar.ptr)
            val err = errVar.value
            if (err != null) {
                throw TtsError.InferenceFailed("$label: ${err.localizedDescription}")
            }
            result ?: throw TtsError.InferenceFailed("$label returned null without error")
        }
    }

    /** Safely convert ObjC/K/N number types to Int (handles NSNumber bridging inconsistency). */
    private fun toIntSafe(value: Any?): Int? = when (value) {
        null -> null
        is Number -> value.toInt()
        is NSNumber -> value.intValue
        else -> value.toString().toIntOrNull()
    }

    private data class StyleTensor(val data: FloatArray, val dims: IntArray)

    private fun parseStyleTensor(json: Map<*, *>): StyleTensor {
        val dimsList = json["dims"] as? List<*> ?: emptyList<Any>()
        val dims = IntArray(dimsList.size) { i ->
            val v = dimsList[i]
            when (v) {
                is Number -> v.toInt()
                is NSNumber -> v.intValue
                else -> v.toString().toIntOrNull() ?: 0
            }
        }
        val data = flattenJsonArray(json["data"])
        val expectedSize = dims.fold(1) { acc, d -> acc * d }
        if (data.size != expectedSize) {
            println("[TTS] WARNING: Style tensor data.size=${data.size} != expected $expectedSize (dims=${dims.toList()})")
        }
        println("[TTS] Parsed style tensor: dims=${dims.toList()}, data.size=${data.size}")
        return StyleTensor(data, dims)
    }

    private fun flattenJsonArray(value: Any?): FloatArray {
        val result = mutableListOf<Float>()
        flattenInto(value, result)
        return result.toFloatArray()
    }

    private fun flattenInto(value: Any?, out: MutableList<Float>) {
        if (value == null) return
        if (value is List<*>) {
            for (item in value) flattenInto(item, out)
            return
        }
        // K/N bridging: NSNumber may or may not auto-bridge to kotlin.Number
        // Check both to handle all cases
        if (value is Number) {
            out.add(value.toFloat())
            return
        }
        if (value is NSNumber) {
            out.add(value.floatValue)
            return
        }
        // Last resort: string parse (matches Android's getDouble fallback)
        value.toString().toFloatOrNull()?.let { out.add(it) }
    }

    private fun tokeniseText(text: String): IntArray {
        val tokens = mutableListOf<Int>()
        for (char in text) {
            val cp = char.code
            if (cp < unicodeIndexer.size) {
                tokens.add(unicodeIndexer[cp])
            } else {
                tokens.add(0)
            }
        }
        return tokens.toIntArray()
    }

    private fun createFloatTensor(data: FloatArray, shape: LongArray): ORTValue {
        val expectedSize = shape.fold(1L) { acc, d -> acc * d }.toInt()
        if (data.size != expectedSize) {
            throw TtsError.InferenceFailed(
                "Tensor size mismatch: data.size=${data.size} but shape=${shape.toList()} expects $expectedSize elements"
            )
        }
        val byteCount = data.size * 4
        val nsData = NSMutableData.dataWithLength(byteCount.toULong())!!
        data.usePinned { pinned ->
            memcpy(nsData.mutableBytes, pinned.addressOf(0), byteCount.toULong())
        }
        val nsShape = shape.map { NSNumber(long = it) }
        return ortCall("FloatTensor(shape=${shape.toList()})") { e ->
            ORTValue(tensorData = nsData, elementType = ORTTensorElementDataType.ORTTensorElementDataTypeFloat, shape = nsShape, error = e)
        }
    }

    private fun createInt64Tensor(data: LongArray, shape: LongArray): ORTValue {
        val byteCount = data.size * 8
        val nsData = NSMutableData.dataWithLength(byteCount.toULong())!!
        data.usePinned { pinned ->
            memcpy(nsData.mutableBytes, pinned.addressOf(0), byteCount.toULong())
        }
        val nsShape = shape.map { NSNumber(long = it) }
        return ortCall("Int64Tensor(shape=${shape.toList()})") { e ->
            ORTValue(tensorData = nsData, elementType = ORTTensorElementDataType.ORTTensorElementDataTypeInt64, shape = nsShape, error = e)
        }
    }

    private fun extractFloats(value: ORTValue): FloatArray {
        val tensorData = value.tensorDataWithError(null)!!
        val count = tensorData.length.toInt() / 4
        val result = FloatArray(count)
        result.usePinned { pinned ->
            memcpy(pinned.addressOf(0), tensorData.bytes, tensorData.length)
        }
        return result
    }

    private fun randomGaussian(count: Int): FloatArray {
        val out = FloatArray(count)
        var i = 0
        while (i < count - 1) {
            val u1 = maxOf(0.0001, minOf(0.9999, Random.nextDouble()))
            val u2 = Random.nextDouble()
            val r = sqrt(-2.0 * ln(u1))
            out[i] = (r * cos(2.0 * PI * u2)).toFloat()
            out[i + 1] = (r * sin(2.0 * PI * u2)).toFloat()
            i += 2
        }
        if (count % 2 != 0) {
            val u1 = maxOf(0.0001, minOf(0.9999, Random.nextDouble()))
            val u2 = Random.nextDouble()
            out[count - 1] = (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
        }
        return out
    }

    private fun createWavData(pcmFloat: FloatArray, sampleRate: Int): NSData {
        val numSamples = pcmFloat.size
        val dataSize = numSamples * 2
        val fileSize = 44 + dataSize
        val nsData = NSMutableData.dataWithCapacity(fileSize.toULong())!!

        fun appendBytes(bytes: ByteArray) {
            bytes.usePinned { pinned ->
                nsData.appendBytes(pinned.addressOf(0), bytes.size.toULong())
            }
        }

        fun appendInt32LE(value: Int) {
            val bytes = byteArrayOf(
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 24) and 0xFF).toByte()
            )
            appendBytes(bytes)
        }

        fun appendInt16LE(value: Int) {
            val bytes = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
            appendBytes(bytes)
        }

        // RIFF header
        appendBytes("RIFF".encodeToByteArray())
        appendInt32LE(fileSize - 8)
        appendBytes("WAVE".encodeToByteArray())
        // fmt chunk
        appendBytes("fmt ".encodeToByteArray())
        appendInt32LE(16)
        appendInt16LE(1) // PCM
        appendInt16LE(1) // mono
        appendInt32LE(sampleRate)
        appendInt32LE(sampleRate * 2)
        appendInt16LE(2) // block align
        appendInt16LE(16) // bits per sample
        // data chunk
        appendBytes("data".encodeToByteArray())
        appendInt32LE(dataSize)

        for (sample in pcmFloat) {
            val clamped = maxOf(-1.0f, minOf(1.0f, sample))
            val int16Val = (clamped * Short.MAX_VALUE).toInt().toShort()
            appendInt16LE(int16Val.toInt() and 0xFFFF)
        }

        return nsData
    }
}

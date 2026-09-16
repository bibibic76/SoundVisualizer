package com.example.soundvisualizer.ai

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * YAMNet ONNX path (yamnet.onnx + yamnet.data external weights).
 * Independent of AudioCaptureService / Overlay.
 */
class YamnetInference private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val outputName: String
) : AutoCloseable {

    data class Result(
        val logits: FloatArray,
        val probabilities: FloatArray,
        val inferenceTimeMs: Double
    )

    companion object {
        const val INPUT_NAME = "audio"
        const val OUTPUT_NAME = "class_scores"
        const val NUM_CLASSES = 521
        const val TIME_FRAMES = 96
        const val MEL_BINS = 64
        const val LOG_MEL_SIZE = TIME_FRAMES * MEL_BINS

        private const val ASSET_DIR = "ai"
        private const val FILES_SUBDIR = "ai_models"
        private const val OBSOLETE_BUNDLE_MANIFEST = "model_bundle.manifest"
        private const val TAG = "YamnetInference"

        /**
         * Verifies and, when needed, copies assets/ai/yamnet.onnx + yamnet.data into app filesDir
         * so ORT can resolve external data by filesystem relative path (assets are not a real FS).
         */
        fun create(context: Context): YamnetInference {
            val modelDir = ensureModelFiles(context)
            val onnxPath = File(modelDir, YamnetModelFiles.onnx.name).absolutePath

            val env = OrtEnvironment.getEnvironment()
            val session = OrtSession.SessionOptions().use { opts ->
                opts.setIntraOpNumThreads(1)
                opts.setInterOpNumThreads(1)
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                env.createSession(onnxPath, opts)
            }
            return session.closeOnFailure { ownedSession ->
                val inName = ownedSession.inputNames.firstOrNull()
                    ?: throw IllegalStateException("YAMNet session has no inputs")
                val outName = ownedSession.outputNames.firstOrNull()
                    ?: throw IllegalStateException("YAMNet session has no outputs")

                // Validate metadata against the expected contract
                val inInfo = ownedSession.inputInfo[inName]?.info as? TensorInfo
                    ?: throw IllegalStateException("Missing TensorInfo for input $inName")
                val shape = inInfo.shape
                require(inName == INPUT_NAME) { "Expected input '$INPUT_NAME', got '$inName'" }
                require(outName == OUTPUT_NAME) { "Expected output '$OUTPUT_NAME', got '$outName'" }
                require(shape.contentEquals(longArrayOf(1, 1, 96, 64))) {
                    "Expected input shape [1,1,96,64], got ${shape.contentToString()}"
                }

                YamnetInference(env, ownedSession, inName, outName)
            }
        }

        @Synchronized
        fun ensureModelFiles(context: Context): File {
            val destDir = File(context.filesDir, FILES_SUBDIR)
            removeObsoleteManifest(destDir)
            val am = context.assets
            val copied = ModelCacheIntegrity.ensureFiles(destDir, YamnetModelFiles.files) { name ->
                am.open("$ASSET_DIR/$name")
            }
            if (copied.isNotEmpty()) {
                Log.i(TAG, "Model cache written: $copied")
            }
            return destDir
        }

        private fun removeObsoleteManifest(directory: File) {
            for (name in listOf(OBSOLETE_BUNDLE_MANIFEST, ".$OBSOLETE_BUNDLE_MANIFEST.partial")) {
                val obsolete = File(directory, name)
                if (obsolete.exists() && !obsolete.delete()) {
                    Log.w(TAG, "Unable to remove obsolete model cache metadata: $obsolete")
                }
            }
        }

        /** Softmax — float32 buffer semantics. */
        fun softmax(logits: FloatArray): FloatArray {
            if (logits.isEmpty()) return floatArrayOf()
            var max = logits[0]
            for (i in 1 until logits.size) if (logits[i] > max) max = logits[i]
            val out = FloatArray(logits.size)
            var sum = 0.0
            for (i in logits.indices) {
                val e = exp((logits[i] - max).toDouble())
                out[i] = e.toFloat()
                sum += e
            }
            if (sum <= 0.0 || sum.isNaN()) {
                val inv = 1f / logits.size
                for (i in out.indices) out[i] = inv
                return out
            }
            for (i in out.indices) out[i] = (out[i].toDouble() / sum).toFloat()
            return out
        }
    }

    /**
     * ORT 입력 버퍼. 추론마다 새로 잡으면 힙 밖 메모리가 Cleaner 가 돌 때까지 남으므로
     * 한 번만 잡아 재사용한다. 호출은 RealtimeAiPipeline 의 inferLock 으로 직렬화된다.
     */
    private val inputBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(LOG_MEL_SIZE * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    /**
     * @param logMelFlat length 6144, layout time*64+mel (same as AudioPreprocessor output)
     */
    fun inferFromLogMelFlat(logMelFlat: FloatArray): Result {
        require(logMelFlat.size == LOG_MEL_SIZE) {
            "Expected log-mel size $LOG_MEL_SIZE, got ${logMelFlat.size}"
        }

        val shape = longArrayOf(1, 1, TIME_FRAMES.toLong(), MEL_BINS.toLong())
        val buffer = inputBuffer
        buffer.clear()
        buffer.put(logMelFlat)
        buffer.rewind()

        val t0 = System.nanoTime()
        OnnxTensor.createTensor(env, buffer, shape).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { outputs ->
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
                val value = outputs.get(0).value
                val logits = when (value) {
                    is Array<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val row = value as Array<FloatArray>
                        row[0].clone()
                    }
                    is FloatArray -> value.clone()
                    else -> throw IllegalStateException("Unexpected ORT output type: ${value?.javaClass}")
                }
                require(logits.size == NUM_CLASSES) {
                    "Expected $NUM_CLASSES logits, got ${logits.size}"
                }
                val probs = softmax(logits)
                return Result(logits = logits, probabilities = probs, inferenceTimeMs = elapsedMs)
            }
        }
    }

    fun inputOutputSummary(): String {
        val inInfo = session.inputInfo[inputName]?.info as TensorInfo
        val outInfo = session.outputInfo[outputName]?.info as TensorInfo
        return "in=$inputName${inInfo.shape.contentToString()} out=$outputName${outInfo.shape.contentToString()}"
    }

    override fun close() {
        session.close()
        // OrtEnvironment is process-wide singleton; do not close globally here.
    }
}

/** Expected bytes for the YAMNet bundle shipped in assets/ai. Update with every bundle update. */
internal object YamnetModelFiles {
    val onnx = ModelCacheIntegrity.ExpectedFile(
        name = "yamnet.onnx",
        byteCount = 25_592L,
        sha256 = "290369c40886a4ae948f77671fff901023eec81484ac393465dfb1b342b1ca85"
    )
    val data = ModelCacheIntegrity.ExpectedFile(
        name = "yamnet.data",
        byteCount = 14_915_108L,
        sha256 = "aa05b5b196bdfd74fb59ae4cbba22578c5ab25b9e792e52867678844bcecc839"
    )
    val files = listOf(onnx, data)
}

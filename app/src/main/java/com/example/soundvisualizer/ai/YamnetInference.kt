package com.example.soundvisualizer.ai

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        private const val ONNX_FILE = "yamnet.onnx"
        private const val DATA_FILE = "yamnet.data"
        private const val FILES_SUBDIR = "ai_models"

        /**
         * Copies assets/ai/yamnet.onnx + yamnet.data into app filesDir so ORT can resolve
         * external data by filesystem relative path (assets alone are not a real FS for .data).
         */
        fun create(context: Context): YamnetInference {
            val modelDir = ensureModelFiles(context)
            val onnxPath = File(modelDir, ONNX_FILE).absolutePath

            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(onnxPath, opts)

            val inName = session.inputNames.firstOrNull()
                ?: throw IllegalStateException("YAMNet session has no inputs")
            val outName = session.outputNames.firstOrNull()
                ?: throw IllegalStateException("YAMNet session has no outputs")

            // Validate metadata against the expected contract
            val inInfo = session.inputInfo[inName]?.info as? TensorInfo
                ?: throw IllegalStateException("Missing TensorInfo for input $inName")
            val shape = inInfo.shape
            require(inName == INPUT_NAME) { "Expected input '$INPUT_NAME', got '$inName'" }
            require(outName == OUTPUT_NAME) { "Expected output '$OUTPUT_NAME', got '$outName'" }
            require(shape.contentEquals(longArrayOf(1, 1, 96, 64))) {
                "Expected input shape [1,1,96,64], got ${shape.contentToString()}"
            }

            return YamnetInference(env, session, inName, outName)
        }

        fun ensureModelFiles(context: Context): File {
            val destDir = File(context.filesDir, FILES_SUBDIR)
            if (!destDir.exists()) destDir.mkdirs()

            val am = context.assets
            copyAssetIfNeeded(am, "$ASSET_DIR/$ONNX_FILE", File(destDir, ONNX_FILE))
            copyAssetIfNeeded(am, "$ASSET_DIR/$DATA_FILE", File(destDir, DATA_FILE))

            val onnx = File(destDir, ONNX_FILE)
            val data = File(destDir, DATA_FILE)
            require(onnx.isFile && onnx.length() > 0) { "Missing $ONNX_FILE after asset copy" }
            require(data.isFile && data.length() > 0) {
                "Missing $DATA_FILE after asset copy (external weights required)"
            }
            return destDir
        }

        private fun copyAssetIfNeeded(
            am: android.content.res.AssetManager,
            assetPath: String,
            dest: File
        ) {
            if (dest.isFile && dest.length() > 0) {
                val assetLen = try {
                    am.openFd(assetPath).use { it.length }
                } catch (_: Exception) {
                    -1L
                }
                if (assetLen > 0 && assetLen == dest.length()) return
            }
            am.open(assetPath).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
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
     * @param logMelFlat length 6144, layout time*64+mel (same as AudioPreprocessor output)
     */
    fun inferFromLogMelFlat(logMelFlat: FloatArray): Result {
        require(logMelFlat.size == LOG_MEL_SIZE) {
            "Expected log-mel size $LOG_MEL_SIZE, got ${logMelFlat.size}"
        }

        val shape = longArrayOf(1, 1, TIME_FRAMES.toLong(), MEL_BINS.toLong())
        val buffer = ByteBuffer
            .allocateDirect(LOG_MEL_SIZE * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
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

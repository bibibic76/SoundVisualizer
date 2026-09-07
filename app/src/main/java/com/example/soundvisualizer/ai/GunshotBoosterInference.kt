package com.example.soundvisualizer.ai

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * gunshot_booster.onnx — input Softmax probs [1,521] → gunshot_score [1,1].
 * Decision/adopt rules live in [GunshotBoosterDecision], not here.
 */
class GunshotBoosterInference private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val outputName: String
) : AutoCloseable {

    companion object {
        const val INPUT_NAME = "yamnet_scores"
        const val OUTPUT_NAME = "gunshot_score"
        const val NUM_CLASSES = 521
        private const val ASSET_PATH = "ai/gunshot_booster.onnx"

        fun create(context: Context): GunshotBoosterInference {
            val bytes = context.assets.open(ASSET_PATH).use { it.readBytes() }
            require(bytes.isNotEmpty()) { "Empty $ASSET_PATH" }

            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(bytes, opts)

            val inName = session.inputNames.firstOrNull()
                ?: throw IllegalStateException("Booster session has no inputs")
            val outName = session.outputNames.firstOrNull()
                ?: throw IllegalStateException("Booster session has no outputs")
            require(inName == INPUT_NAME) { "Expected input '$INPUT_NAME', got '$inName'" }
            require(outName == OUTPUT_NAME) { "Expected output '$OUTPUT_NAME', got '$outName'" }

            val inInfo = session.inputInfo[inName]?.info as? TensorInfo
                ?: throw IllegalStateException("Missing TensorInfo for $inName")
            // Dynamic batch dim OK; last dim must be 521
            val shape = inInfo.shape
            require(shape.isNotEmpty() && shape.last() == 521L) {
                "Expected last dim 521, got ${shape.contentToString()}"
            }

            return GunshotBoosterInference(env, session, inName, outName)
        }
    }

    fun inputOutputSummary(): String {
        val inInfo = session.inputInfo[inputName]?.info as TensorInfo
        val outInfo = session.outputInfo[outputName]?.info as TensorInfo
        return "in=$inputName${inInfo.shape.contentToString()} out=$outputName${outInfo.shape.contentToString()}"
    }

    /** @param yamnetSoftmaxProbs length 521 Softmax probabilities (not logits). */
    fun score(yamnetSoftmaxProbs: FloatArray): Float {
        require(yamnetSoftmaxProbs.size == NUM_CLASSES) {
            "Expected $NUM_CLASSES probs, got ${yamnetSoftmaxProbs.size}"
        }
        val shape = longArrayOf(1, NUM_CLASSES.toLong())
        val buffer = ByteBuffer
            .allocateDirect(NUM_CLASSES * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(yamnetSoftmaxProbs)
        buffer.rewind()

        OnnxTensor.createTensor(env, buffer, shape).use { input ->
            session.run(mapOf(inputName to input)).use { outputs ->
                val value = outputs.get(0).value
                return when (value) {
                    is Array<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val row = value as Array<FloatArray>
                        row[0][0]
                    }
                    is FloatArray -> value[0]
                    else -> throw IllegalStateException("Unexpected booster output: ${value?.javaClass}")
                }
            }
        }
    }

    override fun close() {
        session.close()
    }
}

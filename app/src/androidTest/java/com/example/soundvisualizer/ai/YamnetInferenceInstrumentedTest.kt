package com.example.soundvisualizer.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Runs ONNX Runtime Android against yamnet.onnx + yamnet.data,
 * comparing Softmax probs to Python reference CP3 golden.
 *
 * Requires device/emulator (onnxruntime-android is not a desktop JVM artifact).
 */
@RunWith(AndroidJUnit4::class)
class YamnetInferenceInstrumentedTest {

    @Test
    fun yamnet_matchesPythonCp3Golden() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context

        val logMel = loadFloat32Le(testContext.assets.open("ai_reference/logmel_golden.bin"))
        val expectedLogits = loadFloat32Le(testContext.assets.open("ai_reference/yamnet_logits_golden.bin"))
        val expectedProbs = loadFloat32Le(testContext.assets.open("ai_reference/yamnet_probs_golden.bin"))

        assertEquals(YamnetInference.LOG_MEL_SIZE, logMel.size)
        assertEquals(YamnetInference.NUM_CLASSES, expectedLogits.size)
        assertEquals(YamnetInference.NUM_CLASSES, expectedProbs.size)

        YamnetInference.create(context).use { yamnet ->
            println("ORT session: ${yamnet.inputOutputSummary()}")

            val r1 = yamnet.inferFromLogMelFlat(logMel)
            val r2 = yamnet.inferFromLogMelFlat(logMel)

            assertEquals(521, r1.logits.size)
            assertEquals(521, r1.probabilities.size)
            val sum = r1.probabilities.sum()
            assertTrue("softmax sum≈1 (got $sum)", abs(sum - 1.0f) < 1e-5f)

            // Deterministic on-device
            assertTrue("logits deterministic", r1.logits.contentEquals(r2.logits))
            assertTrue("probs deterministic", r1.probabilities.contentEquals(r2.probabilities))

            val logitErr = errors(r1.logits, expectedLogits)
            val probErr = errors(r1.probabilities, expectedProbs)

            val argmaxA = r1.probabilities.indices.maxByOrNull { r1.probabilities[it] }!!
            val argmaxE = expectedProbs.indices.maxByOrNull { expectedProbs[it] }!!
            val top5A = topK(r1.probabilities, 5)
            val top5E = topK(expectedProbs, 5)

            println("=== YAMNet ORT Android vs Python CP3 ===")
            println("inferMs=${r1.inferenceTimeMs}")
            println("softmaxSum=$sum")
            println("logits maxAbs=${logitErr.maxAbs} MAE=${logitErr.mae} RMSE=${logitErr.rmse}")
            println("probs  maxAbs=${probErr.maxAbs} MAE=${probErr.mae} RMSE=${probErr.rmse}")
            println("argmax actual=$argmaxA expected=$argmaxE match=${argmaxA == argmaxE}")
            val classNames = loadClassNames(context)
            println("top5 actual=$top5A names=${top5A.map { classNames[it.first] }}")
            println("top5 expect=$top5E names=${top5E.map { classNames[it.first] }}")
            println("top5 indices match=${top5A.map { it.first } == top5E.map { it.first }}")

            // Same ONNX model: logits should be extremely close across ORT versions.
            assertTrue("logits maxAbs too large: ${logitErr.maxAbs}", logitErr.maxAbs < 1e-3)
            assertTrue("probs maxAbs too large: ${probErr.maxAbs}", probErr.maxAbs < 1e-4)
            assertEquals("argmax must match", argmaxE, argmaxA)
            assertEquals("top5 indices must match", top5E.map { it.first }, top5A.map { it.first })
        }
    }

    private fun loadClassNames(context: android.content.Context): Map<Int, String> {
        val map = HashMap<Int, String>()
        context.assets.open("ai/yamnet_class_map.csv").bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val first = line.indexOf(',')
                val second = if (first >= 0) line.indexOf(',', first + 1) else -1
                if (first > 0 && second > first) {
                    val idx = line.substring(0, first).toIntOrNull() ?: return@forEach
                    map[idx] = line.substring(second + 1).trim()
                }
            }
        }
        return map
    }

    private data class Err(val maxAbs: Double, val mae: Double, val rmse: Double)

    private fun errors(a: FloatArray, b: FloatArray): Err {
        var maxAbs = 0.0
        var sumAbs = 0.0
        var sumSq = 0.0
        for (i in a.indices) {
            val d = abs(a[i].toDouble() - b[i].toDouble())
            maxAbs = max(maxAbs, d)
            sumAbs += d
            sumSq += d * d
        }
        val n = a.size.toDouble()
        return Err(maxAbs, sumAbs / n, sqrt(sumSq / n))
    }

    private fun topK(probs: FloatArray, k: Int): List<Pair<Int, Float>> {
        return probs.indices.sortedByDescending { probs[it] }.take(k).map { it to probs[it] }
    }

    private fun loadFloat32Le(input: java.io.InputStream): FloatArray {
        val bytes = input.use { it.readBytes() }
        require(bytes.size % 4 == 0)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        bb.asFloatBuffer().get(out)
        return out
    }
}

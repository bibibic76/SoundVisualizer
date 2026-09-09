package com.example.soundvisualizer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Python tools/ai_reference golden fixtures vs Android AudioPreprocessor.
 */
class AudioPreprocessorGoldenTest {

    @Test
    fun logMel_matchesPythonReferenceGolden() {
        val mono = loadFloat32LeResource("ai_reference/mono16k_golden.bin")
        val expected = loadFloat32LeResource("ai_reference/logmel_golden.bin")

        assertEquals("mono length", AudioPreprocessor.REQUIRED_MONO_16K_SAMPLES, mono.size)
        assertEquals("golden logmel length", AudioPreprocessor.LOG_MEL_SIZE, expected.size)

        val preprocessor = AudioPreprocessor()
        val actual = preprocessor.computeLogMelSpectrogram(mono)

        assertEquals(AudioPreprocessor.LOG_MEL_SIZE, actual.size)
        assertTrue("all finite", actual.all { it.isFinite() })

        // logical 96x64
        val reshaped = preprocessor.reshapeToNchw(actual)
        assertEquals(1, reshaped.size)
        assertEquals(1, reshaped[0].size)
        assertEquals(96, reshaped[0][0].size)
        assertEquals(64, reshaped[0][0][0].size)

        var maxAbs = 0.0
        var sumAbs = 0.0
        var sumSq = 0.0
        for (i in actual.indices) {
            val d = abs(actual[i].toDouble() - expected[i].toDouble())
            maxAbs = max(maxAbs, d)
            sumAbs += d
            sumSq += d * d
        }
        val mae = sumAbs / actual.size
        val rmse = sqrt(sumSq / actual.size)

        val actualStats = stats(actual)
        val expectedStats = stats(expected)

        println("=== AudioPreprocessor vs Python golden ===")
        println("maxAbsError=$maxAbs")
        println("MAE=$mae")
        println("RMSE=$rmse")
        println("actual  min=${actualStats.min} max=${actualStats.max} mean=${actualStats.mean}")
        println("expect  min=${expectedStats.min} max=${expectedStats.max} mean=${expectedStats.mean}")
        println("actualSha16=${sha256Hex(actual).take(16)}")
        println("expectSha16=${sha256Hex(expected).take(16)}")

        // Determinism: second run identical bit-for-bit on JVM float path
        val actual2 = preprocessor.computeLogMelSpectrogram(mono)
        assertTrue("deterministic", actual.contentEquals(actual2))

        // Measured on this golden (numpy FFT vs our radix-2): maxAbs≈4.8e-7, MAE≈2.9e-8.
        // Proposed production gate after measurement (not bit-identical):
        assertTrue(
            "maxAbsError too large (got $maxAbs); expected < 1e-5 for matching the Python path",
            maxAbs < 1e-5
        )
        assertTrue("MAE too large (got $mae); expected < 1e-6", mae < 1e-6)
        assertTrue("RMSE too large (got $rmse); expected < 1e-6", rmse < 1e-6)

        // Store metrics on companion for optional inspection
        lastMetrics = Metrics(maxAbs, mae, rmse, actualStats, expectedStats)
    }

    data class Stats(val min: Float, val max: Float, val mean: Float)
    data class Metrics(
        val maxAbs: Double,
        val mae: Double,
        val rmse: Double,
        val actual: Stats,
        val expected: Stats
    )

    companion object {
        @JvmStatic
        var lastMetrics: Metrics? = null
    }

    private fun stats(a: FloatArray): Stats {
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        var sum = 0.0
        for (v in a) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            sum += v
        }
        return Stats(minV, maxV, (sum / a.size).toFloat())
    }

    private fun sha256Hex(a: FloatArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in a) {
            buf.clear()
            buf.putFloat(v)
            md.update(buf.array())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun loadFloat32LeResource(path: String): FloatArray {
        val stream = requireNotNull(javaClass.classLoader!!.getResourceAsStream(path)) {
            "Missing test resource: $path"
        }
        return stream.use { readFloat32Le(it) }
    }

    private fun readFloat32Le(input: InputStream): FloatArray {
        val bytes = input.readBytes()
        require(bytes.size % 4 == 0) { "float32 blob size must be multiple of 4, got ${bytes.size}" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        bb.asFloatBuffer().get(out)
        return out
    }
}

package com.example.soundvisualizer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Pinned Qualcomm/torch_audioset source frontend vs its independent Python fixtures. */
class QualcommSourceAudioPreprocessorGoldenTest {

    @Test
    fun logMel_matchesPinnedSourceFrontendFixtures() {
        val cases = listOf(
            "golden" to "mono16k_golden.bin",
            "silence" to "e2e_silence_mono16k.bin",
            "gunshot" to "e2e_gunshot_mono16k.bin",
            "alarm" to "e2e_alarm_mono16k.bin",
        )
        val preprocessor = QualcommSourceAudioPreprocessor()

        for ((name, inputFile) in cases) {
            val input = loadFloat32("ai_reference/$inputFile")
            val expected = loadFloat32("ai_reference/qualcomm_source_${name}_logmel.bin")
            val actual = preprocessor.computeLogMelSpectrogram(input)

            assertEquals("$name output size", AudioPreprocessor.LOG_MEL_SIZE, actual.size)
            assertEquals("$name fixture size", expected.size, actual.size)
            assertTrue("$name output must be finite", actual.all { it.isFinite() })

            var maximumAbsoluteError = 0.0
            var absoluteErrorSum = 0.0
            for (index in actual.indices) {
                val error = abs(actual[index].toDouble() - expected[index].toDouble())
                maximumAbsoluteError = max(maximumAbsoluteError, error)
                absoluteErrorSum += error
            }
            val meanAbsoluteError = absoluteErrorSum / actual.size
            println(
                "$name Qualcomm source parity: " +
                    "maxAbs=$maximumAbsoluteError meanAbs=$meanAbsoluteError",
            )

            assertTrue(
                "$name maxAbs=$maximumAbsoluteError must stay below 1e-5",
                maximumAbsoluteError < 1e-5,
            )
            assertTrue(
                "$name meanAbs=$meanAbsoluteError must stay below 1e-7",
                meanAbsoluteError < 1e-7,
            )
        }
    }

    private fun loadFloat32(path: String): FloatArray {
        val bytes = requireNotNull(javaClass.classLoader!!.getResourceAsStream(path)) {
            "Missing test resource: $path"
        }.use { it.readBytes() }
        require(bytes.size % Float.SIZE_BYTES == 0)
        return FloatArray(bytes.size / Float.SIZE_BYTES).also {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
        }
    }
}

package com.example.soundvisualizer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * JVM: capture downmix + linear resample + ring snapshot (no ORT).
 */
class CaptureAudioPathTest {

    @Test
    fun captureSamplesForWindow_44100() {
        assertEquals(42998, CaptureAudioMath.captureSamplesForOneYamnetWindow(44100))
    }

    @Test
    fun stereoDownmix_averagesLR() {
        val interleaved = floatArrayOf(1f, 3f, 2f, 4f, 0.5f) // odd trailing dropped
        val dest = FloatArray(4)
        val n = CaptureAudioMath.downmixInterleavedToMono(interleaved, interleaved.size, 2, dest)
        assertEquals(2, n)
        assertEquals(2f, dest[0], 1e-6f)
        assertEquals(3f, dest[1], 1e-6f)
    }

    @Test
    fun ring_ingestStereo_and_rightPadSnapshot() {
        val buf = AiAudioBuffer(44100, 2)
        // 3 frames stereo
        buf.ingestInterleaved(floatArrayOf(1f, 1f, 2f, 2f, 3f, 3f), 6)
        assertEquals(3, buf.availableSamples)
        val dest = FloatArray(5)
        buf.copyTailRightPadded(dest, 5)
        assertEquals(0f, dest[0], 0f)
        assertEquals(0f, dest[1], 0f)
        assertEquals(1f, dest[2], 0f)
        assertEquals(2f, dest[3], 0f)
        assertEquals(3f, dest[4], 0f)
    }

    @Test
    fun e2eFixtures_resampleAndLogMel_matchPython() {
        val names = listOf("silence", "gunshot", "alarm")
        for (name in names) {
            val stereo = loadResource("ai_reference/e2e_${name}_stereo44100.bin")
            val expectedMono16 = loadResource("ai_reference/e2e_${name}_mono16k.bin")
            val expectedLogMel = loadResource("ai_reference/e2e_${name}_logmel.bin")

            val buf = AiAudioBuffer(44100, 2)
            // Simulate AudioRecord chunked reads of 1024 floats
            var offset = 0
            while (offset < stereo.size) {
                val n = minOf(1024, stereo.size - offset)
                val chunk = stereo.copyOfRange(offset, offset + n)
                buf.ingestInterleaved(chunk, n)
                offset += n
            }
            assertTrue("$name ring filled", buf.hasEnoughForYamnetWindow())

            val need = CaptureAudioMath.captureSamplesForOneYamnetWindow(44100)
            val capture = FloatArray(need)
            buf.copyTailRightPadded(capture, need)

            val mono16 = FloatArray(CaptureAudioMath.REQUIRED_MONO_16K_SAMPLES)
            CaptureAudioMath.resampleMonoFloatTo16kCustom(capture, need, 44100, mono16)

            assertEquals(expectedMono16.size, mono16.size)
            assertTrue("$name mono16k maxAbs", maxAbs(mono16, expectedMono16) < 1e-5)

            val logMel = AudioPreprocessor().computeLogMelSpectrogram(mono16)
            assertEquals(expectedLogMel.size, logMel.size)
            assertTrue("$name logmel maxAbs", maxAbs(logMel, expectedLogMel) < 1e-4)
        }
    }

    private fun loadResource(path: String): FloatArray {
        val bytes = javaClass.classLoader!!.getResourceAsStream(path)!!.use { it.readBytes() }
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    private fun maxAbs(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        for (i in a.indices) m = max(m, abs(a[i] - b[i]))
        return m
    }
}

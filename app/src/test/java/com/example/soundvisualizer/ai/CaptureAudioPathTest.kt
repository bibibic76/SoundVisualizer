package com.example.soundvisualizer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * JVM: capture downmix + anti-aliased resample + ring snapshot (no ORT).
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
    fun e2eFixtures_resampleAndLogMel_haveFixedFiniteShape() {
        val names = listOf("silence", "gunshot", "alarm")
        for (name in names) {
            val stereo = loadResource("ai_reference/e2e_${name}_stereo44100.bin")

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

            assertEquals(CaptureAudioMath.REQUIRED_MONO_16K_SAMPLES, mono16.size)
            assertTrue("$name mono16k finite", mono16.all { it.isFinite() })

            val logMel = AudioPreprocessor().computeLogMelSpectrogram(mono16)
            assertEquals(AudioPreprocessor.LOG_MEL_SIZE, logMel.size)
            assertTrue("$name logmel finite", logMel.all { it.isFinite() })
        }
    }

    @Test
    fun antiAliasedResample_meetsFrequencyAcceptanceAt44100And48000() {
        for (sampleRate in listOf(44100, 48000)) {
            assertGainAtLeast(sampleRate, 7000.0, -1.5)
            assertGainAtLeast(sampleRate, 7500.0, -3.0)
            assertGainAtMost(sampleRate, 8500.0, -20.0)
            assertGainAtMost(sampleRate, 9000.0, -45.0)
            assertGainAtMost(sampleRate, 10000.0, -45.0)
            assertGainAtMost(sampleRate, 15000.0, -60.0)
        }
    }

    @Test
    fun resample16k_passthroughIsBitExact() {
        val source = FloatArray(CaptureAudioMath.REQUIRED_MONO_16K_SAMPLES) { index ->
            Float.fromBits(index * 7919)
        }
        val destination = FloatArray(CaptureAudioMath.REQUIRED_MONO_16K_SAMPLES)

        CaptureAudioMath.resampleMonoFloatTo16kCustom(
            source,
            source.size,
            CaptureAudioMath.TARGET_SAMPLE_RATE,
            destination
        )

        assertTrue(source.contentEquals(destination))
    }

    @Test
    fun antiAliasedResample_keepsYamnetWindowLengthAt44100And48000() {
        for (sampleRate in listOf(44100, 48000)) {
            val source = FloatArray(CaptureAudioMath.captureSamplesForOneYamnetWindow(sampleRate))
            val destination = FloatArray(CaptureAudioMath.REQUIRED_MONO_16K_SAMPLES)

            CaptureAudioMath.resampleMonoFloatTo16kCustom(source, source.size, sampleRate, destination)

            assertEquals(CaptureAudioMath.REQUIRED_MONO_16K_SAMPLES, destination.size)
            assertTrue(destination.all { it == 0f })
        }
    }

    @Test
    fun antiAliasedResample_zeroExtendsAtSourceWindowEdges() {
        val source = floatArrayOf(1f)
        val destination = FloatArray(64)

        CaptureAudioMath.resampleMonoFloatTo16kCustom(source, source.size, 48000, destination, 64)

        assertTrue(destination.all { it.isFinite() })
        assertTrue("source support must end instead of repeating its final sample", destination[32] == 0f)
    }

    private fun loadResource(path: String): FloatArray {
        val bytes = javaClass.classLoader!!.getResourceAsStream(path)!!.use { it.readBytes() }
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    private fun assertGainAtLeast(sampleRate: Int, frequencyHz: Double, minimumDb: Double) {
        val gain = toneGainDb(sampleRate, frequencyHz)
        assertTrue(
            "$sampleRate Hz, $frequencyHz Hz gain $gain dB must be >= $minimumDb dB",
            gain >= minimumDb
        )
    }

    private fun assertGainAtMost(sampleRate: Int, frequencyHz: Double, maximumDb: Double) {
        val gain = toneGainDb(sampleRate, frequencyHz)
        assertTrue(
            "$sampleRate Hz, $frequencyHz Hz gain $gain dB must be <= $maximumDb dB",
            gain <= maximumDb
        )
    }

    private fun toneGainDb(sampleRate: Int, inputFrequencyHz: Double): Double {
        val destinationLength = 4096
        val sourceLength = ceil(
            destinationLength * sampleRate.toDouble() / CaptureAudioMath.TARGET_SAMPLE_RATE
        ).toInt() + 96
        val source = FloatArray(sourceLength) { index ->
            cos(2.0 * Math.PI * inputFrequencyHz * index / sampleRate).toFloat()
        }
        val destination = FloatArray(destinationLength)
        CaptureAudioMath.resampleMonoFloatTo16kCustom(
            source,
            source.size,
            sampleRate,
            destination,
            destinationLength
        )

        var aliasFrequency = inputFrequencyHz % CaptureAudioMath.TARGET_SAMPLE_RATE
        if (aliasFrequency > CaptureAudioMath.TARGET_SAMPLE_RATE / 2.0) {
            aliasFrequency = CaptureAudioMath.TARGET_SAMPLE_RATE - aliasFrequency
        }
        var yc = 0.0
        var ys = 0.0
        var cc = 0.0
        var ss = 0.0
        for (index in 128 until destinationLength - 128) {
            val phase = 2.0 * Math.PI * aliasFrequency * index / CaptureAudioMath.TARGET_SAMPLE_RATE
            val cosine = cos(phase)
            val sine = sin(phase)
            val value = destination[index].toDouble()
            yc += value * cosine
            ys += value * sine
            cc += cosine * cosine
            ss += sine * sine
        }
        val cosineCoefficient = yc / cc
        val sineCoefficient = if (ss < 1e-12) 0.0 else ys / ss
        val amplitude = sqrt(cosineCoefficient * cosineCoefficient + sineCoefficient * sineCoefficient)
        return 20.0 * kotlin.math.log10(max(amplitude, 1e-300))
    }
}

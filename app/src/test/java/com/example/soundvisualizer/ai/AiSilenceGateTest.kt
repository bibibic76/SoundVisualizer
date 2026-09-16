package com.example.soundvisualizer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSilenceGateTest {

    @Test
    fun `long silence and threshold-level noise keep gate closed`() {
        val gate = AiSilenceGate()
        val quiet = floatArrayOf(0.01f, -0.01f, 0.005f, -0.005f)

        gate.onInterleavedPcm(quiet, quiet.size, nowMs = 0)

        assertFalse(gate.isOpen(0))
        assertFalse(gate.isOpen(10_000))
    }

    @Test
    fun `slow inference ticks stay open until two fully silent completions`() {
        val gate = AiSilenceGate()
        gate.onInterleavedPcm(floatArrayOf(0.0101f, 0f), 2, nowMs = 100)

        assertTrue(gate.isOpen(100))
        val firstSilentSnapshot = 100 + AiSilenceGate.YAMNET_WINDOW_MS
        assertTrue(gate.isOpen(firstSilentSnapshot + 10_000))

        gate.onInferenceCompleted(firstSilentSnapshot)
        assertTrue(gate.isOpen(firstSilentSnapshot + 350))

        gate.onInferenceCompleted(firstSilentSnapshot + 350)
        assertFalse(gate.isOpen(firstSilentSnapshot + 350))
    }

    @Test
    fun `inferences completed before active input leaves window do not close gate`() {
        val gate = AiSilenceGate()
        gate.onInterleavedPcm(floatArrayOf(0.02f, 0f), 2, nowMs = 0)

        repeat(AiSilenceGate.REQUIRED_SILENT_INFERENCE_COMPLETIONS) {
            gate.onInferenceCompleted(AiSilenceGate.YAMNET_WINDOW_MS - 1)
        }

        assertTrue(gate.isOpen(AiSilenceGate.YAMNET_WINDOW_MS + 10_000))
    }

    @Test
    fun `opposite-phase short transient still opens from original channels`() {
        val gate = AiSilenceGate()
        // Downmix would be zero, but either channel's peak must reopen inference.
        gate.onInterleavedPcm(floatArrayOf(0.02f, -0.02f), 2, nowMs = 200)

        assertTrue(gate.isOpen(200))
    }

    @Test
    fun `new active input reopens a closed gate immediately`() {
        val gate = AiSilenceGate()

        gate.onInterleavedPcm(floatArrayOf(0.02f, 0f), 2, nowMs = 0)
        repeat(AiSilenceGate.REQUIRED_SILENT_INFERENCE_COMPLETIONS) { index ->
            gate.onInferenceCompleted(AiSilenceGate.YAMNET_WINDOW_MS + index * 350L)
        }
        assertFalse(gate.isOpen(AiSilenceGate.YAMNET_WINDOW_MS + 350))

        val newActiveMs = AiSilenceGate.YAMNET_WINDOW_MS + 351
        gate.onInterleavedPcm(floatArrayOf(0.02f, 0f), 2, nowMs = newActiveMs)

        assertTrue(gate.isOpen(newActiveMs))
    }

    @Test
    fun `cleanup contract uses YAMNet window and coarse hysteresis constants`() {
        val yamnetWindowMs =
            AudioPreprocessor.REQUIRED_MONO_16K_SAMPLES * 1000L / AudioPreprocessor.SAMPLE_RATE

        assertEquals(yamnetWindowMs, AiSilenceGate.YAMNET_WINDOW_MS)
        assertEquals(
            AiPostProcessor.COARSE_HYSTERESIS_THRESHOLD,
            AiSilenceGate.REQUIRED_SILENT_INFERENCE_COMPLETIONS
        )
    }
}

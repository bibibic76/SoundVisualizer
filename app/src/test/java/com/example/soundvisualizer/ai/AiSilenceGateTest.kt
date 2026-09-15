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
    fun `above-threshold input stays open through AI release then closes`() {
        val gate = AiSilenceGate()
        gate.onInterleavedPcm(floatArrayOf(0.0101f, 0f), 2, nowMs = 100)

        assertTrue(gate.isOpen(100))
        assertTrue(gate.isOpen(100 + AiSilenceGate.RELEASE_MS))
        assertFalse(gate.isOpen(101 + AiSilenceGate.RELEASE_MS))
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
        assertFalse(gate.isOpen(AiSilenceGate.RELEASE_MS + 1))
        gate.onInterleavedPcm(floatArrayOf(0.02f, 0f), 2, nowMs = AiSilenceGate.RELEASE_MS + 1)

        assertTrue(gate.isOpen(AiSilenceGate.RELEASE_MS + 1))
    }

    @Test
    fun `AI release covers YAMNet window and coarse hysteresis`() {
        val yamnetWindowMs =
            AudioPreprocessor.REQUIRED_MONO_16K_SAMPLES * 1000L / AudioPreprocessor.SAMPLE_RATE
        val hysteresisMs =
            AiPostProcessor.COARSE_HYSTERESIS_THRESHOLD * RealtimeAiPipeline.AI_PREDICT_INTERVAL_MS

        assertTrue(AiSilenceGate.RELEASE_MS >= yamnetWindowMs + hysteresisMs)
        assertEquals(1600L, AiSilenceGate.RELEASE_MS)
    }
}

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
    fun `above-threshold input opens immediately and release holds for 400ms`() {
        val gate = AiSilenceGate()
        gate.onInterleavedPcm(floatArrayOf(0.0101f, 0f), 2, nowMs = 100)

        assertTrue(gate.isOpen(100))
        assertTrue(gate.isOpen(500))
        assertFalse(gate.isOpen(501))
    }

    @Test
    fun `opposite-phase short transient still opens from original channels`() {
        val gate = AiSilenceGate()
        // Downmix would be zero, but either channel's peak must reopen inference.
        gate.onInterleavedPcm(floatArrayOf(0.02f, -0.02f), 2, nowMs = 200)

        assertTrue(gate.isOpen(200))
    }

    @Test
    fun `ring keeps receiving silence while gate is closed`() {
        val buffer = AiAudioBuffer(captureSampleRate = 16000, channels = 2)
        val gate = AiSilenceGate()
        val quiet = floatArrayOf(0.001f, -0.001f, 0.002f, -0.002f)

        gate.onInterleavedPcm(quiet, quiet.size, nowMs = 0)
        buffer.ingestInterleaved(quiet, quiet.size)

        assertFalse(gate.isOpen(0))
        assertEquals(2, buffer.availableSamples)
    }
}

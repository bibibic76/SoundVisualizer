package com.example.soundvisualizer.ai

import org.junit.Assert.*
import org.junit.Test

class AiSpeechActivityTest {
    private val active = floatArrayOf(.1f, -.1f)

    @Test
    fun shortImpulseCannotQualifyFromLingeringModelContext() {
        val gate = AiSilenceGate(trackContinuousActivity = true)
        gate.onInterleavedPcm(active, 2, 0)
        gate.onInterleavedPcm(floatArrayOf(0f, 0f), 2, 30)
        assertTrue(gate.isOpen(200))
        assertFalse(gate.isContinuouslyActive(200))
    }

    @Test
    fun sustainedOriginalChannelActivityQualifiesEvenIfDownmixWouldCancel() {
        val gate = AiSilenceGate(trackContinuousActivity = true)
        for (now in 0L..100L step 10L) gate.onInterleavedPcm(active, 2, now)
        assertTrue(gate.isContinuouslyActive(100))
        assertFalse(gate.isContinuouslyActive(201))
        gate.onInterleavedPcm(active, 2, 250)
        assertFalse(gate.isContinuouslyActive(250))
    }

    @Test
    fun resetAndDefaultModeNeverCarryExperimentalActivity() {
        val gate = AiSilenceGate(trackContinuousActivity = true)
        for (now in 0L..100L step 10L) gate.onInterleavedPcm(active, 2, now)
        gate.reset()
        assertFalse(gate.isContinuouslyActive(100))
        val baseline = AiSilenceGate()
        for (now in 0L..100L step 10L) baseline.onInterleavedPcm(active, 2, now)
        assertFalse(baseline.isContinuouslyActive(100))
        assertTrue(baseline.isOpen(100))
    }
}

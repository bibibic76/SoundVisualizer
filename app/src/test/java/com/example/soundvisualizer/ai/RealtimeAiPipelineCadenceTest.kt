package com.example.soundvisualizer.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeAiPipelineCadenceTest {
    @Test
    fun inferenceTimeIsSubtractedFromTickInterval() {
        assertEquals(
            220L,
            remainingTickDelayMs(
                intervalMs = 250L,
                tickStartedAtMs = 1_000L,
                tickFinishedAtMs = 1_030L
            )
        )
    }

    @Test
    fun overrunDoesNotAddAnotherIntervalOrReturnNegativeDelay() {
        assertEquals(
            0L,
            remainingTickDelayMs(
                intervalMs = 250L,
                tickStartedAtMs = 1_000L,
                tickFinishedAtMs = 1_300L
            )
        )
    }

    @Test
    fun monotonicClockAnomalyDoesNotLengthenTheConfiguredInterval() {
        assertEquals(
            250L,
            remainingTickDelayMs(
                intervalMs = 250L,
                tickStartedAtMs = 1_001L,
                tickFinishedAtMs = 1_000L
            )
        )
    }
}

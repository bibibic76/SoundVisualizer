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
                tickFinishedAtMs = 1_030L,
                minimumRestMs = RealtimeAiPipeline.MIN_TICK_REST_MS
            )
        )
    }

    @Test
    fun overrunKeepsMinimumRestInsteadOfStartingContinuousInference() {
        assertEquals(
            100L,
            remainingTickDelayMs(
                intervalMs = 250L,
                tickStartedAtMs = 1_000L,
                tickFinishedAtMs = 1_300L,
                minimumRestMs = RealtimeAiPipeline.MIN_TICK_REST_MS
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
                tickFinishedAtMs = 1_000L,
                minimumRestMs = RealtimeAiPipeline.MIN_TICK_REST_MS
            )
        )
    }
}

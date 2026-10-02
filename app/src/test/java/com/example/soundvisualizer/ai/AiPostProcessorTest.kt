package com.example.soundvisualizer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPostProcessorTest {
    @Test fun `speech requires two frames`() {
        val pp = AiPostProcessor()
        pp.process(AiPostProcessor.FrameInput("ambient", "Wind", .4f))
        assertEquals("ambient", pp.process(AiPostProcessor.FrameInput("speech", "Speech", .4f)).confirmedCoarse)
        assertEquals("speech", pp.process(AiPostProcessor.FrameInput("speech", "Speech", .4f)).confirmedCoarse)
    }

    @Test fun `danger switches immediately at configured confidence`() {
        val result = AiPostProcessor().process(AiPostProcessor.FrameInput("danger", "Siren", .28f))
        assertEquals("danger", result.uiCoarse)
    }

    @Test fun `promoted strong cue uses documented threshold`() {
        val pp = AiPostProcessor()
        assertFalse(pp.process(AiPostProcessor.FrameInput("danger", "Smoke detector, smoke alarm", .119f,
            dangerCuePromoted = true, hasStrongDangerCue = true)).meetsThreshold)
        val confirmed = pp.process(AiPostProcessor.FrameInput("danger", "Smoke detector, smoke alarm", .12f,
            dangerCuePromoted = true, hasStrongDangerCue = true))
        assertTrue(confirmed.meetsThreshold)
        assertEquals("danger", confirmed.uiCoarse)
    }

    @Test fun `thresholds have no Booster-specific path`() {
        assertEquals(.25f, AiPostProcessor.computeEffectiveThreshold("danger", .25f, false, false), 0f)
        assertEquals(.20f, AiPostProcessor.computeEffectiveThreshold("danger", .25f, true, false), 0f)
        assertEquals(.12f, AiPostProcessor.computeEffectiveThreshold("danger", .25f, true, false, true), 0f)
    }

    @Test fun `water remains ambient`() {
        val result = AiPostProcessor().process(AiPostProcessor.FrameInput("ambient", "Rain", 1f))
        assertEquals("ambient", result.uiCoarse)
    }
}

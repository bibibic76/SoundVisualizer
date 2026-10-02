package com.example.soundvisualizer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YamnetSafetyCueDecisionTest {
    private val names = List(YamnetCoarseClassifier.NUM_CLASSES) { "Ambient $it" }.toMutableList().also {
        it[0] = "Music"
        it[1] = "Siren"
        it[2] = "Gunshot, gunfire"
        it[3] = "Speech"
    }
    private val classifier = YamnetCoarseClassifier(names)

    @Test fun `strong non-gunshot cue is retained without an auxiliary model`() {
        val probabilities = FloatArray(YamnetCoarseClassifier.NUM_CLASSES)
        probabilities[0] = .6f
        probabilities[1] = .2f
        // Real softmax output has no exact-zero tail. Fill five non-gunshot top-k
        // entries so the sparse fixture does not fabricate a zero-score gunshot cue.
        probabilities[4] = .001f
        probabilities[5] = .001f
        probabilities[6] = .001f
        val decision = YamnetSafetyCueDecision.decide(names, classifier.classify(probabilities))
        assertEquals("danger", decision.postCoarse)
        assertEquals("Siren", decision.postDisplay)
        assertTrue(decision.dangerCuePromoted)
    }

    @Test fun `gunshot cue is not score-promoted by the removed Booster path`() {
        val probabilities = FloatArray(YamnetCoarseClassifier.NUM_CLASSES)
        probabilities[0] = .6f
        probabilities[2] = .1f
        val decision = YamnetSafetyCueDecision.decide(names, classifier.classify(probabilities))
        assertEquals("ambient", decision.postCoarse)
        assertTrue(decision.hasGunshotCue)
        assertFalse(decision.dangerCuePromoted)
    }

    @Test fun `speech result is not safety-promoted`() {
        val probabilities = FloatArray(YamnetCoarseClassifier.NUM_CLASSES)
        probabilities[3] = .6f
        probabilities[1] = .1f
        val decision = YamnetSafetyCueDecision.decide(names, classifier.classify(probabilities))
        assertEquals("speech", decision.postCoarse)
        assertFalse(decision.dangerCuePromoted)
    }

    @Test fun `approved attention cues are safety-promoted`() {
        val attentionCues = listOf(
            "Vehicle horn, car horn, honking",
            "Air horn, truck horn",
            "Burst, pop",
            "Boom",
            "Bang",
            "Smash, crash",
            "Breaking",
            "Shatter"
        )

        attentionCues.forEach { cue ->
            val cueNames = names.toMutableList().also { it[1] = cue }
            val probabilities = FloatArray(YamnetCoarseClassifier.NUM_CLASSES)
            probabilities[0] = .6f
            probabilities[1] = .2f
            probabilities[4] = .001f
            probabilities[5] = .001f
            probabilities[6] = .001f

            val decision = YamnetSafetyCueDecision.decide(
                cueNames,
                YamnetCoarseClassifier(cueNames).classify(probabilities)
            )

            assertEquals(cue, "danger", decision.postCoarse)
            assertEquals(cue, cue, decision.postDisplay)
            assertTrue(cue, decision.dangerCuePromoted)
        }
    }

    @Test fun `temporary proxies and partial label matches are not safety-promoted`() {
        listOf("Plop", "Gargling", "Breaking news").forEach { proxy ->
            val proxyNames = names.toMutableList().also { it[1] = proxy }
            val probabilities = FloatArray(YamnetCoarseClassifier.NUM_CLASSES)
            probabilities[0] = .6f
            probabilities[1] = .2f
            probabilities[4] = .001f
            probabilities[5] = .001f
            probabilities[6] = .001f

            val decision = YamnetSafetyCueDecision.decide(
                proxyNames,
                YamnetCoarseClassifier(proxyNames).classify(probabilities)
            )

            assertEquals(proxy, "ambient", decision.postCoarse)
            assertFalse(proxy, decision.dangerCuePromoted)
        }
    }
}

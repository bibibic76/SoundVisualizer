package com.example.soundvisualizer.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class YamnetMappingPolicyTest {
    private val names = List(521) { i ->
        listOf("Music", "Siren", "Explosion", "Gunshot, gunfire", "Speech", "Footsteps", "Wind")
            .getOrNull(i) ?: "class_$i"
    }
    private val classifier = YamnetCoarseClassifier(names)
    private fun probabilities(vararg values: Pair<Int, Float>) = FloatArray(521).also { p ->
        values.forEach { (i, value) -> p[i] = value }
    }
    private fun policy(vararg values: Pair<String, String>) = YamnetMappingPolicy.from(mapOf(*values), names)

    @Test fun defaultsPreserveEverySingleClassDecision() {
        val csv = listOf(File("src/main/assets/ai/yamnet_class_map.csv"), File("app/src/main/assets/ai/yamnet_class_map.csv"))
            .first { it.isFile }
        val actualNames = csv.inputStream().use { YamnetCoarseClassifier.loadClassNames(it) }
        val actualClassifier = YamnetCoarseClassifier(actualNames)
        val empty = YamnetMappingPolicy.from(emptyMap(), actualNames)
        for (i in actualNames.indices) {
            assertEquals(YamnetThreeClassMapper.mapDisplayNameToCoarse(actualNames[i]), empty.coarse(actualNames[i]))
            val p = probabilities(i to 1f)
            assertEquals(actualClassifier.classify(p), actualClassifier.classify(p, empty))
            val pre = actualClassifier.classify(p)
            assertEquals(YamnetSafetyCueDecision.decide(actualNames, pre), YamnetSafetyCueDecision.decide(actualNames, pre, empty))
        }
    }

    @Test fun sirenAndExplosionDemotionsAffectVotesAndSafetyCues() {
        for (index in listOf(1, 2)) {
            val mapping = policy(names[index] to "ambient")
            val pre = classifier.classify(probabilities(index to .8f, 6 to .2f), mapping)
            val decision = YamnetSafetyCueDecision.decide(names, pre, mapping)
            assertEquals("ambient", pre.coarse)
            assertEquals("ambient", decision.postCoarse)
            assertFalse(decision.hasStrongDangerCue)
            assertFalse(decision.dangerCuePromoted)
        }
    }

    @Test fun anotherIndependentCueStillPromotesDanger() {
        val mapping = policy("Siren" to "ambient")
        // Five actual candidates; a zero-score gunshot must not accidentally fill a tied slot.
        val pre = classifier.classify(probabilities(1 to .6f, 2 to .2f, 6 to .1f, 0 to .06f, 5 to .04f), mapping)
        assertEquals("ambient", pre.coarse)
        val decision = YamnetSafetyCueDecision.decide(names, pre, mapping)
        assertEquals("danger", decision.postCoarse)
        assertEquals("Explosion", decision.postDisplay)
    }

    @Test fun speechOverrideDoesNotBlockIndependentDangerCue() {
        val p = probabilities(1 to .6f, 2 to .2f, 6 to .1f, 0 to .06f, 5 to .04f)
        val mapping = policy("Siren" to "speech")
        val pre = classifier.classify(p, mapping)
        assertEquals("speech", pre.coarse)
        assertEquals("danger", pre.defaultCoarse)
        val decision = YamnetSafetyCueDecision.decide(names, pre, mapping)
        assertEquals("danger", decision.postCoarse)
        assertEquals("Explosion", decision.postDisplay)
        assertTrue(decision.dangerCuePromoted)
    }

    @Test fun ambientToSpeechOverrideDoesNotBlockIndependentDangerCue() {
        val mapping = policy("Wind" to "speech")
        val pre = classifier.classify(probabilities(6 to .5f, 1 to .2f, 0 to .08f, 5 to .05f, 2 to .01f), mapping)
        assertEquals("speech", pre.coarse)
        assertEquals("ambient", pre.defaultCoarse)
        assertEquals("danger", YamnetSafetyCueDecision.decide(names, pre, mapping).postCoarse)
    }

    @Test fun demotedSoleCueStaysSpeechWithoutShortcutReentry() {
        val mapping = policy("Siren" to "speech")
        val pre = classifier.classify(probabilities(1 to .6f, 6 to .2f, 0 to .1f, 5 to .06f, 4 to .04f), mapping)
        val decision = YamnetSafetyCueDecision.decide(names, pre, mapping)
        assertEquals("speech", decision.postCoarse)
        assertFalse(decision.dangerCuePromoted)
    }

    @Test fun defaultSpeechVoteStillBlocksAfterUserDemotion() {
        // Wind is the display: protection here comes from the combined default vote,
        // not from a speech-like display name.
        val localNames = names.toMutableList().also { it[7] = "Conversation" }
        val mapping = YamnetMappingPolicy.from(mapOf("Speech" to "ambient", "Conversation" to "ambient"), localNames)
        val pre = YamnetCoarseClassifier(localNames).classify(
            probabilities(6 to .3f, 4 to .25f, 7 to .24f, 1 to .15f, 5 to .06f), mapping)
        assertEquals("ambient", pre.coarse)
        assertEquals("speech", pre.defaultCoarse)
        assertFalse(YamnetSafetyCueDecision.decide(localNames, pre, mapping).dangerCuePromoted)
    }

    @Test fun demotedGunshotDoesNotSuppressIndependentSirenCue() {
        val mapping = policy("Gunshot, gunfire" to "ambient")
        val pre = classifier.classify(probabilities(3 to .7f, 1 to .2f, 6 to .1f), mapping)
        val decision = YamnetSafetyCueDecision.decide(names, pre, mapping)
        assertFalse(decision.hasGunshotCue)
        assertEquals("Siren", decision.postDisplay)
        assertTrue(decision.dangerCuePromoted)
    }

    @Test fun newlyPromotedFootstepsVoteButDoNotBecomeSafetyCue() {
        val mapping = policy("Footsteps" to "danger")
        val strong = classifier.classify(probabilities(5 to .8f, 6 to .2f), mapping)
        assertEquals("danger", strong.coarse)
        assertFalse(YamnetSafetyCueDecision.decide(names, strong, mapping).hasStrongDangerCue)
        val weak = classifier.classify(probabilities(0 to .5f, 6 to .3f, 5 to .2f), mapping)
        assertEquals("ambient", weak.coarse)
        assertEquals("Music", weak.displayName)
        assertFalse(YamnetSafetyCueDecision.decide(names, weak, mapping).dangerCuePromoted)
    }

    @Test fun demotedDangerCannotWinGameMixDisplayPreference() {
        val mapping = policy("Siren" to "ambient")
        val pre = classifier.classify(probabilities(0 to .7f, 1 to .3f), mapping)
        assertEquals("Music", pre.displayName)
        assertFalse(pre.gameMixPreferenceApplied)
    }

    @Test fun policyCopiesValidExactLabelsAndHasCanonicalIdentity() {
        val input = mutableMapOf("Siren" to "ambient", "Footsteps" to "danger")
        val snapshot = YamnetMappingPolicy.from(input, names)
        input.clear()
        assertEquals("ambient", snapshot.coarse("Siren"))
        assertEquals(2, snapshot.overrideCount)
        assertEquals(policy("Footsteps" to "danger", "Siren" to "ambient").signature, snapshot.signature)
        assertEquals("default", policy("siren" to "ambient", "Music" to "invalid", "unknown" to "danger").signature)
    }

    @Test fun explicitCriticalDecisionPreventsKeywordReentryInPostprocessor() {
        val mapping = policy("Gunshot, gunfire" to "ambient", "Explosion" to "ambient")
        val hits = listOf(YamnetCoarseClassifier.TopClassHit(2, "Explosion", .1f))
        val critical = mapping.hasCriticalDangerCue("Gunshot, gunfire", hits)
        assertFalse(critical)
        assertTrue(policy("Gunshot, gunfire" to "ambient").hasCriticalDangerCue("Gunshot, gunfire", hits))
        val post = AiPostProcessor()
        val result = post.process(AiPostProcessor.FrameInput(
            coarse = "danger", display = "Gunshot, gunfire", confidence = .21f,
            topKSummary = "Explosion", criticalDangerEvent = critical
        ))
        assertEquals(.25f, result.effectiveThreshold, 0f)
        assertFalse(result.meetsThreshold)
        assertEquals("ambient", result.uiCoarse)
    }
}

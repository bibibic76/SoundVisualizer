package com.example.soundvisualizer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Product policy #343, not sample-specific detection rules. */
class AttentionMappingPolicyTest {
    private val attention = listOf(
        "Reversing beeps", "Train horn", "Train whistle", "Foghorn", "Bicycle bell",
        "Emergency vehicle", "Skidding", "Tire squeal", "Slam"
    )

    @Test fun approvedLabelsVoteDangerWithoutBecomingSafetyCues() {
        for (name in attention) {
            assertEquals(name, "danger", YamnetThreeClassMapper.mapDisplayNameToCoarse(name))
            assertFalse(name, YamnetSafetyCueDecision.isStrongDangerKeyword(name))
            assertFalse(name, AiPostProcessor.isCriticalDangerKeyword(name))
        }
    }

    @Test fun instrumentsAndUnapprovedNeighborsStayAmbient() {
        for (name in listOf(
            "Singing bowl", "Truck", "Car", "Bus", "Motorcycle", "Train", "Vehicle",
            "Air brake", "Doorbell", "Alarm clock", "Honk", "French horn", "Whistle",
            "Plop", "Gargling", "Yell", "Baby cry, infant cry"
        ) + attention.map { "$it-like sound" }) {
            assertEquals(name, "ambient", YamnetThreeClassMapper.mapDisplayNameToCoarse(name))
        }
        for (name in listOf("Singing", "Choir", "Screaming", "Shout", "Laughter", "Crying, sobbing")) {
            assertEquals(name, "speech", YamnetThreeClassMapper.mapDisplayNameToCoarse(name))
        }
    }

    @Test fun weakAttentionCandidateDoesNotPromoteAmbientVote() {
        val names = List(521) { i -> listOf("Wind", "Rain", "Water", "Slam", "Vehicle").getOrNull(i) ?: "class_$i" }
        val probabilities = FloatArray(521).apply {
            this[0] = .55f; this[1] = .2f; this[2] = .15f; this[3] = .06f; this[4] = .04f
        }
        val pre = YamnetCoarseClassifier(names).classify(probabilities)
        val result = YamnetSafetyCueDecision.decide(names, pre)
        assertEquals("ambient", result.postCoarse)
        assertFalse(result.dangerCuePromoted)
    }
}

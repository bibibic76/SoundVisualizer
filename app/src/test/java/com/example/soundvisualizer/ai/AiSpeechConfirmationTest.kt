package com.example.soundvisualizer.ai

import org.junit.Assert.*
import org.junit.Test

class AiSpeechConfirmationTest {
    private val speech = AiPostProcessor.FrameInput("speech", "Speech", .7f)
    private val request = AiSpeechConfirmation.Request("Speech", .7f, "default")

    @Test
    fun consistentSecondObservationConfirmsWithoutLoweringThresholdOrCount() {
        val processor = AiPostProcessor()
        val first = processor.process(speech)
        assertEquals("ambient", first.uiCoarse)
        assertEquals(1, first.candidateStreak)
        assertEquals(request, AiSpeechConfirmation.request(speech, first, "default"))
        assertEquals("speech", processor.confirmSpeechIfConsistent(speech.copy(confidence = .8f), request)?.uiCoarse)
    }

    @Test
    fun rejectedEarlyFramePreservesCandidateAndNormalConfirmation() {
        for (rejected in listOf(
            speech.copy(coarse = "ambient"), speech.copy(coarse = "danger", display = "Gunshot, gunfire"),
            speech.copy(display = "Conversation"), speech.copy(confidence = .6f), speech.copy(confidence = .1f)
        )) {
            val processor = AiPostProcessor()
            processor.process(speech)
            assertNull(processor.confirmSpeechIfConsistent(rejected, request))
            assertTrue(processor.hasPendingSpeechConfirmation())
            assertEquals("speech", processor.process(speech).uiCoarse)
        }
    }

    @Test
    fun dangerEntryAndExitNeverRequestEarlySpeechConfirmation() {
        val processor = AiPostProcessor()
        val danger = AiPostProcessor.FrameInput("danger", "Explosion", .8f)
        assertNull(AiSpeechConfirmation.request(danger, processor.process(danger), "default"))
        val firstSpeech = processor.process(speech)
        assertEquals("speech", firstSpeech.uiCoarse) // Existing confident Danger exit remains immediate.
        assertNull(AiSpeechConfirmation.request(speech, firstSpeech, "default"))
        assertNull(processor.confirmSpeechIfConsistent(speech, request))
    }

    @Test
    fun noEarlyObservationCanConfirmWithoutFirstCandidate() {
        val processor = AiPostProcessor()
        assertNull(processor.confirmSpeechIfConsistent(speech, request))
        assertEquals("ambient", processor.process(speech).uiCoarse)
    }

    @Test
    fun deadlinePreserves100msRestAndSkipsSlowInference() {
        assertEquals(125L, AiSpeechConfirmation.deadlineMs(0, 25, 250))
        assertEquals(110L, AiSpeechConfirmation.deadlineMs(0, 10, 250))
        assertNull(AiSpeechConfirmation.deadlineMs(0, 26, 250))
        assertNull(AiSpeechConfirmation.deadlineMs(0, 100, 250))
        assertNull(AiSpeechConfirmation.deadlineMs(0, 25, 249))
    }
}

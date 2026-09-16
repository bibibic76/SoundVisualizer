package com.example.soundvisualizer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCaptureInferenceGateTest {

    companion object {
        private const val SLOW_TICK_MS = RealtimeAiPipeline.AI_PREDICT_INTERVAL_MS + 100L
    }

    @Test
    fun activeWindowIsRetainedBeforeOpenGateCanBeObserved() {
        val buffer = AiAudioBuffer(captureSampleRate = 16000, channels = 2)
        val ingress = AiCaptureInferenceGate(buffer)
        val activeWindow = FloatArray(AudioPreprocessor.REQUIRED_MONO_16K_SAMPLES * 2) { index ->
            if (index % 2 == 0) 0.02f else 0f
        }

        ingress.ingestInterleaved(activeWindow, activeWindow.size, nowMs = 0)

        assertTrue(buffer.hasEnoughForYamnetWindow())
        assertTrue(ingress.isInferenceOpen(0))
    }

    @Test
    fun activeSilenceThenNewSound_keepsRingAndReopensAfterCleanup() {
        val buffer = AiAudioBuffer(captureSampleRate = 16000, channels = 2)
        val ingress = AiCaptureInferenceGate(buffer)
        val active = floatArrayOf(0.02f, 0f)
        val silence = FloatArray(AudioPreprocessor.REQUIRED_MONO_16K_SAMPLES * 2)

        ingress.ingestInterleaved(active, active.size, nowMs = 0)
        ingress.ingestInterleaved(silence, silence.size, nowMs = 1)

        assertTrue(buffer.hasEnoughForYamnetWindow())
        val firstSilentSnapshot = AiSilenceGate.YAMNET_WINDOW_MS
        ingress.onInferenceCompleted(firstSilentSnapshot)
        assertTrue(ingress.isInferenceOpen(firstSilentSnapshot + SLOW_TICK_MS))
        ingress.onInferenceCompleted(firstSilentSnapshot + SLOW_TICK_MS)
        assertFalse(ingress.isInferenceOpen(firstSilentSnapshot + SLOW_TICK_MS))
        val samplesAfterLongSilence = buffer.availableSamples

        val newActiveMs = firstSilentSnapshot + SLOW_TICK_MS + 1
        ingress.ingestInterleaved(active, active.size, nowMs = newActiveMs)

        assertTrue(ingress.isInferenceOpen(newActiveMs))
        assertEquals(samplesAfterLongSilence + 1, buffer.availableSamples)
    }

    @Test
    fun continuousInferenceFailure_closesThroughPipelineGateFallback() {
        val buffer = AiAudioBuffer(captureSampleRate = 16000, channels = 2)
        val ingress = AiCaptureInferenceGate(buffer)
        val active = floatArrayOf(0.02f, 0f)
        ingress.ingestInterleaved(active, active.size, nowMs = 0)
        val firstFailedSnapshot = AiSilenceGate.YAMNET_WINDOW_MS

        ingress.onInferenceFailed(firstFailedSnapshot)

        assertTrue(
            ingress.isInferenceOpen(
                firstFailedSnapshot + AiSilenceGate.FAILURE_FALLBACK_MS - 1
            )
        )
        assertFalse(
            ingress.isInferenceOpen(firstFailedSnapshot + AiSilenceGate.FAILURE_FALLBACK_MS)
        )
    }

    @Test
    fun completedSilentInferences_clearConfirmedSpeechBeforeGateCloses() {
        val buffer = AiAudioBuffer(captureSampleRate = 16000, channels = 2)
        val ingress = AiCaptureInferenceGate(buffer)
        val postProcessor = AiPostProcessor()
        val active = floatArrayOf(0.02f, 0f)

        ingress.ingestInterleaved(active, active.size, nowMs = 0)
        postProcessor.process(AiPostProcessor.FrameInput("speech", "Speech", 0.4f))
        val confirmedSpeech =
            postProcessor.process(AiPostProcessor.FrameInput("speech", "Speech", 0.4f))
        assertEquals("speech", confirmedSpeech.confirmedCoarse)

        val firstSilentSnapshot = AiSilenceGate.YAMNET_WINDOW_MS
        val firstCleanup =
            postProcessor.process(AiPostProcessor.FrameInput("ambient", "Silence", 0.4f))
        ingress.onInferenceCompleted(firstSilentSnapshot)
        assertEquals("speech", firstCleanup.confirmedCoarse)
        assertTrue(ingress.isInferenceOpen(firstSilentSnapshot + SLOW_TICK_MS))

        val completedCleanup =
            postProcessor.process(AiPostProcessor.FrameInput("ambient", "Silence", 0.4f))
        ingress.onInferenceCompleted(firstSilentSnapshot + SLOW_TICK_MS)
        assertEquals("ambient", completedCleanup.confirmedCoarse)
        assertFalse(ingress.isInferenceOpen(firstSilentSnapshot + SLOW_TICK_MS))

        val newActiveMs = firstSilentSnapshot + SLOW_TICK_MS + 1
        ingress.ingestInterleaved(active, active.size, nowMs = newActiveMs)
        assertTrue(ingress.isInferenceOpen(newActiveMs))
        assertEquals("ambient", completedCleanup.uiCoarse)
    }

    @Test
    fun speechCandidatesSeparatedBySilentCleanup_doNotCombineAcrossClosedGate() {
        val buffer = AiAudioBuffer(captureSampleRate = 16000, channels = 2)
        val ingress = AiCaptureInferenceGate(buffer)
        val postProcessor = AiPostProcessor()
        val active = floatArrayOf(0.02f, 0f)

        ingress.ingestInterleaved(active, active.size, nowMs = 0)
        val firstSpeech =
            postProcessor.process(AiPostProcessor.FrameInput("speech", "Speech", 0.4f))
        assertEquals(1, firstSpeech.candidateStreak)

        val firstSilentSnapshot = AiSilenceGate.YAMNET_WINDOW_MS
        repeat(AiSilenceGate.REQUIRED_SILENT_INFERENCE_COMPLETIONS) { index ->
            postProcessor.process(AiPostProcessor.FrameInput("ambient", "Silence", 0.4f))
            ingress.onInferenceCompleted(firstSilentSnapshot + index * SLOW_TICK_MS)
        }
        assertFalse(ingress.isInferenceOpen(firstSilentSnapshot + SLOW_TICK_MS))

        val newActiveMs = firstSilentSnapshot + SLOW_TICK_MS + 1
        ingress.ingestInterleaved(active, active.size, nowMs = newActiveMs)
        val firstSpeechAfterSilence =
            postProcessor.process(AiPostProcessor.FrameInput("speech", "Speech", 0.4f))

        assertEquals("ambient", firstSpeechAfterSilence.confirmedCoarse)
        assertEquals("speech", firstSpeechAfterSilence.candidateCoarse)
        assertEquals(1, firstSpeechAfterSilence.candidateStreak)
    }
}

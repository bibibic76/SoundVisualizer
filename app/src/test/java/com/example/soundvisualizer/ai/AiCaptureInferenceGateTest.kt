package com.example.soundvisualizer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCaptureInferenceGateTest {

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
    fun dangerSilenceThenNewSound_keepsRingAndClearsGateBeforeReopen() {
        val buffer = AiAudioBuffer(captureSampleRate = 16000, channels = 2)
        val ingress = AiCaptureInferenceGate(buffer)
        val active = floatArrayOf(0.02f, 0f)
        val silence = FloatArray(AudioPreprocessor.REQUIRED_MONO_16K_SAMPLES * 2)

        ingress.ingestInterleaved(active, active.size, nowMs = 0)
        ingress.ingestInterleaved(silence, silence.size, nowMs = 1)

        assertTrue(buffer.hasEnoughForYamnetWindow())
        assertTrue(ingress.isInferenceOpen(AiSilenceGate.RELEASE_MS))
        assertFalse(ingress.isInferenceOpen(AiSilenceGate.RELEASE_MS + 1))
        val samplesAfterLongSilence = buffer.availableSamples

        ingress.ingestInterleaved(active, active.size, nowMs = AiSilenceGate.RELEASE_MS + 2)

        assertTrue(ingress.isInferenceOpen(AiSilenceGate.RELEASE_MS + 2))
        assertEquals(samplesAfterLongSilence + 1, buffer.availableSamples)
    }
}

package com.example.soundvisualizer.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCaptureSampleRatePolicyTest {

    @Test
    fun reportedHighRates_preferAcceptedAiRate() {
        for (reported in intArrayOf(88200, 96000, 176400, 192000)) {
            assertArrayEquals(
                intArrayOf(48000, 44100, reported),
                AiCaptureSampleRatePolicy.orderedCaptureCandidates(reported)
            )
            val selected = AiCaptureSampleRatePolicy.selectCaptureRate(reported) { rate ->
                rate == reported || rate == 48000
            }

            assertEquals(48000, selected)
            assertTrue(AiCaptureSampleRatePolicy.isSupportedForAi(selected))
        }
    }

    @Test
    fun reportedHighRates_withoutAcceptedAiRate_remainCaptureOnlyFallback() {
        for (reported in intArrayOf(88200, 96000, 176400, 192000)) {
            val selected = AiCaptureSampleRatePolicy.selectCaptureRate(reported) { rate ->
                rate == reported
            }

            assertEquals(reported, selected)
            assertFalse(AiCaptureSampleRatePolicy.isSupportedForAi(selected))
        }
    }

    @Test
    fun reportedSupportedRate_keepsNativeRateFirst() {
        assertArrayEquals(
            intArrayOf(44100, 48000),
            AiCaptureSampleRatePolicy.orderedCaptureCandidates(44100)
        )
        assertArrayEquals(
            intArrayOf(48000, 44100),
            AiCaptureSampleRatePolicy.orderedCaptureCandidates(48000)
        )
    }

    @Test
    fun supportedAiRates_fitTheFixedRing() {
        for (rate in intArrayOf(16000, 44100, 48000)) {
            assertTrue(AiCaptureSampleRatePolicy.isSupportedForAi(rate))
            assertTrue(
                CaptureAudioMath.captureSamplesForOneYamnetWindow(rate) <=
                    AiAudioBuffer.MAX_RING_SIZE
            )
            AiAudioBuffer(captureSampleRate = rate, channels = 2)
        }
    }

    @Test
    fun unvalidatedHighRatesAreRejectedBeforeTheyCanEnterTheAiRing() {
        assertTrue(
            CaptureAudioMath.captureSamplesForOneYamnetWindow(176400) >
                AiAudioBuffer.MAX_RING_SIZE
        )
        assertTrue(
            CaptureAudioMath.captureSamplesForOneYamnetWindow(192000) >
                AiAudioBuffer.MAX_RING_SIZE
        )
        for (rate in intArrayOf(88200, 96000, 176400, 192000)) {
            assertFalse(AiCaptureSampleRatePolicy.isSupportedForAi(rate))
            assertThrows(IllegalArgumentException::class.java) {
                AiAudioBuffer(captureSampleRate = rate, channels = 2)
            }
        }
    }
}

package com.example.soundvisualizer.ai

import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps realtime inference enabled while recent capture PCM contains sound.
 *
 * The threshold matches the existing audio-level policy, but the AI release is
 * intentionally longer: it lets the 0.975s YAMNet window become silent and lets
 * coarse hysteresis settle before long silence starts skipping inference. PCM
 * ingestion is the producer and the inference coroutine is the consumer, so the
 * active timestamp is atomic.
 */
class AiSilenceGate(
    private val activeThreshold: Float = ACTIVE_THRESHOLD,
    private val releaseMs: Long = RELEASE_MS
) {
    companion object {
        /** Matches HapticPolicy.LEVEL_THRESHOLD and OverlayService.WAKE_THRESHOLD. */
        const val ACTIVE_THRESHOLD = 0.01f

        private const val YAMNET_WINDOW_MS =
            AudioPreprocessor.REQUIRED_MONO_16K_SAMPLES * 1000L / AudioPreprocessor.SAMPLE_RATE
        private const val HYSTERESIS_SETTLE_MS =
            AiPostProcessor.COARSE_HYSTERESIS_THRESHOLD * RealtimeAiPipeline.AI_PREDICT_INTERVAL_MS
        private const val SCHEDULER_ALIGNMENT_MARGIN_MS = RealtimeAiPipeline.AI_PREDICT_INTERVAL_MS / 2

        /** 975ms YAMNet window + 500ms coarse hysteresis + 125ms tick alignment = 1600ms. */
        const val RELEASE_MS =
            YAMNET_WINDOW_MS + HYSTERESIS_SETTLE_MS + SCHEDULER_ALIGNMENT_MARGIN_MS

        private const val NO_ACTIVE_INPUT = Long.MIN_VALUE
    }

    private val lastActiveInputMs = AtomicLong(NO_ACTIVE_INPUT)

    fun onInterleavedPcm(interleaved: FloatArray, floatCount: Int, nowMs: Long) {
        if (CaptureAudioMath.maxAbsoluteInterleavedPeak(interleaved, floatCount) > activeThreshold) {
            lastActiveInputMs.set(nowMs)
        }
    }

    fun isOpen(nowMs: Long): Boolean {
        val lastActiveMs = lastActiveInputMs.get()
        return lastActiveMs != NO_ACTIVE_INPUT && nowMs - lastActiveMs <= releaseMs
    }

    fun reset() {
        lastActiveInputMs.set(NO_ACTIVE_INPUT)
    }
}

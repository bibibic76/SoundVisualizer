package com.example.soundvisualizer.ai

import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps realtime inference enabled while recent capture PCM contains sound.
 *
 * The threshold and release duration intentionally match the existing audio-level
 * policy used by the overlay and haptics. PCM ingestion is the producer and the
 * inference coroutine is the consumer, so the active timestamp is atomic.
 */
class AiSilenceGate(
    private val activeThreshold: Float = ACTIVE_THRESHOLD,
    private val releaseMs: Long = RELEASE_MS
) {
    companion object {
        /** Matches HapticPolicy.LEVEL_THRESHOLD and OverlayService.WAKE_THRESHOLD. */
        const val ACTIVE_THRESHOLD = 0.01f

        /** Matches HapticPolicy.RELEASE_MS. */
        const val RELEASE_MS = 400L

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

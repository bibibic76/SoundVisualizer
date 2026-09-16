package com.example.soundvisualizer.ai

/**
 * Keeps realtime inference enabled while recent capture PCM contains sound.
 *
 * Once the last active input has left the YAMNet window, inference remains open
 * until enough fully silent inferences have completed to settle coarse hysteresis.
 * PCM ingestion is the producer and the inference coroutine is the consumer, so
 * the small state transitions are synchronized as one unit.
 */
class AiSilenceGate(
    private val activeThreshold: Float = ACTIVE_THRESHOLD
) {
    companion object {
        /** Matches HapticPolicy.LEVEL_THRESHOLD and VisualizerEngine.WAKE_THRESHOLD. */
        const val ACTIVE_THRESHOLD = 0.01f

        /**
         * Wall-clock drain duration for capture PCM that is continuously ingested in real time.
         */
        const val YAMNET_WINDOW_MS =
            AudioPreprocessor.REQUIRED_MONO_16K_SAMPLES * 1000L / AudioPreprocessor.SAMPLE_RATE

        /** Fully silent results needed to clear the longest coarse hysteresis streak. */
        const val REQUIRED_SILENT_INFERENCE_COMPLETIONS =
            AiPostProcessor.COARSE_HYSTERESIS_THRESHOLD

        /**
         * Stops retrying a continuously failing inference after four full windows.
         * Successful inference clears this fallback and still requires the normal
         * completion count, so a transient failure cannot shorten state cleanup.
         */
        const val FAILURE_FALLBACK_MS = YAMNET_WINDOW_MS * 4

        private const val NO_TIMESTAMP = Long.MIN_VALUE
    }

    private val stateLock = Any()
    private var lastActiveInputMs = NO_TIMESTAMP
    private var silentInferenceCompletions = 0
    private var firstConsecutiveFailureMs = NO_TIMESTAMP

    fun onInterleavedPcm(interleaved: FloatArray, floatCount: Int, nowMs: Long) {
        if (CaptureAudioMath.maxAbsoluteInterleavedPeak(interleaved, floatCount) > activeThreshold) {
            synchronized(stateLock) {
                lastActiveInputMs = nowMs
                silentInferenceCompletions = 0
                firstConsecutiveFailureMs = NO_TIMESTAMP
            }
        }
    }

    fun isOpen(nowMs: Long): Boolean = synchronized(stateLock) {
        if (lastActiveInputMs == NO_TIMESTAMP) return@synchronized false
        if (nowMs - lastActiveInputMs < YAMNET_WINDOW_MS) return@synchronized true
        if (silentInferenceCompletions >= REQUIRED_SILENT_INFERENCE_COMPLETIONS) {
            return@synchronized false
        }
        firstConsecutiveFailureMs == NO_TIMESTAMP ||
            nowMs - firstConsecutiveFailureMs < FAILURE_FALLBACK_MS
    }

    /** Records a successfully completed inference for the window captured at [snapshotTimeMs]. */
    fun onInferenceCompleted(snapshotTimeMs: Long) {
        synchronized(stateLock) {
            if (lastActiveInputMs == NO_TIMESTAMP) return
            if (snapshotTimeMs - lastActiveInputMs < YAMNET_WINDOW_MS) return
            firstConsecutiveFailureMs = NO_TIMESTAMP
            if (silentInferenceCompletions < REQUIRED_SILENT_INFERENCE_COMPLETIONS) {
                silentInferenceCompletions++
            }
        }
    }

    /** Starts a bounded fallback only for consecutive failures on fully drained windows. */
    fun onInferenceFailed(snapshotTimeMs: Long) {
        synchronized(stateLock) {
            if (lastActiveInputMs == NO_TIMESTAMP) return
            if (snapshotTimeMs - lastActiveInputMs < YAMNET_WINDOW_MS) return
            if (firstConsecutiveFailureMs == NO_TIMESTAMP) {
                firstConsecutiveFailureMs = snapshotTimeMs
            }
        }
    }

    fun reset() {
        synchronized(stateLock) {
            lastActiveInputMs = NO_TIMESTAMP
            silentInferenceCompletions = 0
            firstConsecutiveFailureMs = NO_TIMESTAMP
        }
    }
}

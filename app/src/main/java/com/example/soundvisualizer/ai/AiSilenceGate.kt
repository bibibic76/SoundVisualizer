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

        /** Duration after which the last active input has left a full YAMNet window. */
        const val YAMNET_WINDOW_MS =
            AudioPreprocessor.REQUIRED_MONO_16K_SAMPLES * 1000L / AudioPreprocessor.SAMPLE_RATE

        /** Fully silent results needed to clear the longest coarse hysteresis streak. */
        const val REQUIRED_SILENT_INFERENCE_COMPLETIONS =
            AiPostProcessor.COARSE_HYSTERESIS_THRESHOLD

        private const val NO_ACTIVE_INPUT = Long.MIN_VALUE
    }

    private val stateLock = Any()
    private var lastActiveInputMs = NO_ACTIVE_INPUT
    private var silentInferenceCompletions = 0

    fun onInterleavedPcm(interleaved: FloatArray, floatCount: Int, nowMs: Long) {
        if (CaptureAudioMath.maxAbsoluteInterleavedPeak(interleaved, floatCount) > activeThreshold) {
            synchronized(stateLock) {
                lastActiveInputMs = nowMs
                silentInferenceCompletions = 0
            }
        }
    }

    fun isOpen(nowMs: Long): Boolean = synchronized(stateLock) {
        if (lastActiveInputMs == NO_ACTIVE_INPUT) return@synchronized false
        if (nowMs - lastActiveInputMs < YAMNET_WINDOW_MS) return@synchronized true
        silentInferenceCompletions < REQUIRED_SILENT_INFERENCE_COMPLETIONS
    }

    /** Records a successfully completed inference for the window captured at [snapshotTimeMs]. */
    fun onInferenceCompleted(snapshotTimeMs: Long) {
        synchronized(stateLock) {
            if (lastActiveInputMs == NO_ACTIVE_INPUT) return
            if (snapshotTimeMs - lastActiveInputMs < YAMNET_WINDOW_MS) return
            if (silentInferenceCompletions < REQUIRED_SILENT_INFERENCE_COMPLETIONS) {
                silentInferenceCompletions++
            }
        }
    }

    fun reset() {
        synchronized(stateLock) {
            lastActiveInputMs = NO_ACTIVE_INPUT
            silentInferenceCompletions = 0
        }
    }
}

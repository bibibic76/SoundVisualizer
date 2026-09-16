package com.example.soundvisualizer.ai

/**
 * The realtime capture ingress used by [RealtimeAiPipeline].
 *
 * PCM is always retained in the AI ring, including while inference is gated off.
 * The gate only decides whether a ready ring window may start inference.
 */
internal class AiCaptureInferenceGate(
    private val audioBuffer: AiAudioBuffer,
    private val silenceGate: AiSilenceGate = AiSilenceGate()
) {
    fun ingestInterleaved(pcm: FloatArray, floatCount: Int, nowMs: Long) {
        // Publish PCM before opening inference so a newly observed open gate always
        // has the triggering capture data available in the ring.
        audioBuffer.ingestInterleaved(pcm, floatCount)
        silenceGate.onInterleavedPcm(pcm, floatCount, nowMs)
    }

    fun isInferenceOpen(nowMs: Long): Boolean = silenceGate.isOpen(nowMs)

    fun onInferenceCompleted(snapshotTimeMs: Long) {
        silenceGate.onInferenceCompleted(snapshotTimeMs)
    }

    fun onInferenceFailed(snapshotTimeMs: Long) {
        silenceGate.onInferenceFailed(snapshotTimeMs)
    }

    fun reset() {
        silenceGate.reset()
    }
}

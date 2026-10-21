package com.example.soundvisualizer.ai

/** Conservative experiment: accelerate Ambient -> Speech's second observation only. */
object AiSpeechConfirmation {
    const val MIN_REST_MS = 100L
    const val MAX_PRECEDING_TICK_MS = 25L

    data class Request(val display: String, val confidence: Float, val mappingSignature: String)

    fun request(
        frame: AiPostProcessor.FrameInput,
        post: AiPostProcessor.FrameResult,
        mappingSignature: String
    ): Request? = if (
        frame.coarse == "speech" && post.meetsThreshold &&
        post.confirmedCoarse == "ambient" && post.candidateCoarse == "speech" &&
        post.candidateStreak == 1
    ) Request(frame.display, frame.confidence, mappingSignature) else null

    /** Leave room to fall back to the regular tick if the estimate holds. Never shorten the rest. */
    fun deadlineMs(startedMs: Long, finishedMs: Long, regularDeadlineMs: Long): Long? {
        val elapsed = (finishedMs - startedMs).coerceAtLeast(0L)
        val early = finishedMs + MIN_REST_MS
        return early.takeIf {
            elapsed <= MAX_PRECEDING_TICK_MS && early + elapsed + MIN_REST_MS <= regularDeadlineMs
        }
    }

    fun matches(request: Request, frame: AiPostProcessor.FrameInput): Boolean =
        frame.coarse == "speech" && frame.display == request.display &&
            frame.confidence >= request.confidence
}

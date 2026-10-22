package com.example.soundvisualizer.ai

/** Tiny synchronized control state; no model, PCM, I/O or waits under this lock. */
internal class SileroPreviewGate<T : Any> {
    data class Candidate<T>(val parent: T, val freshAfterFrame: Long,
        val acceptBeforeNs: Long, val expiresNs: Long, val generation: Long)
    data class Accepted<T>(val parent: T, val expiresNs: Long, val atNs: Long, val acceptBeforeNs: Long)
    private var candidate: Candidate<T>? = null
    private var accepted: Accepted<T>? = null
    private var streak = 0
    private var previousEnd = -1L

    @Synchronized fun regular(candidate: Candidate<T>?) {
        this.candidate = candidate
        accepted = null
        streak = 0
        previousEnd = -1
    }

    /** Stop new acceptance when regular work starts; hold an accepted view until its expiry. */
    @Synchronized fun regularStarted() { candidate = null; streak = 0; previousEnd = -1 }

    @Synchronized fun block(generation: Long, start: Long, end: Long, probability: Float, nowNs: Long): Accepted<T>? {
        val c = candidate ?: return null
        if (accepted != null || c.generation != generation || start < c.freshAfterFrame) return null
        if (nowNs >= c.acceptBeforeNs) { candidate = null; return null }
        if (previousEnd != -1L && previousEnd != start) streak = 0
        previousEnd = end
        streak = if (probability.isFinite() && probability >= .9f) streak + 1 else 0
        if (streak < 3) return null
        return Accepted(c.parent, c.expiresNs, nowNs, c.acceptBeforeNs).also { accepted = it }
    }

    @Synchronized fun view(nowNs: Long): Accepted<T>? = accepted?.takeIf { nowNs < it.expiresNs }
}

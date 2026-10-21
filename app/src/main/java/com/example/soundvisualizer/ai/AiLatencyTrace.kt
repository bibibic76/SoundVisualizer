package com.example.soundvisualizer.ai

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Optional diagnostics only. All times are elapsedRealtimeNanos (BOOTTIME), never wall time. */
object AiLatencyTrace {
    private val ids = AtomicLong()
    fun nextId(): Long = ids.incrementAndGet()

    class Recording internal constructor(val id: Long, capacity: Int) {
        val active = AtomicBoolean(true)
        val queue = LinkedBlockingQueue<Event>(capacity)
        val dropped = AtomicLong()
        @Synchronized fun offer(event: Event) {
            if (!active.get()) return
            // Drop newest: never block capture/inference/render waiting for a writer.
            if (!queue.offer(event)) dropped.incrementAndGet()
        }
        @Synchronized fun finish() { active.set(false) }
    }

    data class Event(val stage: String, val atNs: Long, val stamp: AiLatencyStamp? = null,
                     val sessionId: Long = 0, val detail: String = "")

    @Volatile private var current: Recording? = null
    val recordingId: Long get() = current?.id ?: 0L

    @Synchronized fun begin(capacity: Int = 512): Recording {
        current?.finish()
        return Recording(nextId(), capacity).also { current = it }
    }

    @Synchronized fun end(recording: Recording) {
        recording.finish()
        if (current === recording) current = null
    }

    fun mark(stage: String, atNs: Long, sessionId: Long = 0, detail: String = "") {
        current?.offer(Event(stage, atNs, sessionId = sessionId, detail = detail))
    }

    fun result(stamp: AiLatencyStamp, publishedNs: Long) {
        val mine = current ?: return
        if (stamp.recordingId == mine.id) mine.offer(Event("result", publishedNs, stamp))
    }

    /** One event per result and consumer, even across repeated frames/polls. */
    fun observed(stamp: AiLatencyStamp?, stage: Stage, atNs: Long, detail: String = "") {
        val mine = current ?: return
        if (stamp == null || stamp.recordingId != mine.id) return
        val bit = 1 shl stage.ordinal
        while (true) {
            val old = stamp.observed.get()
            if (old and bit != 0) return
            if (stamp.observed.compareAndSet(old, old or bit)) break
        }
        mine.offer(Event(stage.name.lowercase(), atNs, stamp, detail = detail))
    }

    enum class Stage { COLOR_READ, COLOR_DRAW, HUD_POLL, HUD_DRAW }
}

/** Reused under the audio ring lock: no capture-buffer allocation. Unknown metadata stays zero. */
class AiPcmTrace {
    var recordingId = 0L
    var captureSessionId = 0L
    var endFrameExclusive = 0L
    var readCompletedNs = 0L
    var inputMayBeFiltered = false
    fun clear() {
        recordingId = 0; captureSessionId = 0; endFrameExclusive = 0; readCompletedNs = 0
        inputMayBeFiltered = false
    }
}

/** Metadata of the exact PCM tail copied for this result. No audio is stored. */
data class AiLatencyStamp(
    val recordingId: Long,
    val pipelineSessionId: Long,
    val resultId: Long,
    val pipelineStartedNs: Long,
    val captureSessionId: Long,
    val firstFrame: Long,
    val endFrameExclusive: Long,
    val sampleRate: Int,
    val readCompletedNs: Long,
    val inputMayBeFiltered: Boolean,
    val snapshotBeginNs: Long,
    val snapshotEndNs: Long,
    val preprocessedNs: Long,
    val inferredNs: Long,
    val confirmedNs: Long,
    val rawCoarse: String,
    val meetsThreshold: Boolean,
    val candidateStreak: Int,
    val confirmedCoarse: String,
    val mappingSignature: String,
    val rawConfidence: Float = 0f,
    val effectiveThreshold: Float = 0f,
    val candidateCoarse: String = ""
) {
    internal val observed = AtomicInteger()
}

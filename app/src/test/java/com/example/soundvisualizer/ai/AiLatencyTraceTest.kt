package com.example.soundvisualizer.ai

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AiLatencyTraceTest {
    private var recording = AiLatencyTrace.begin()
    @After fun cleanup() = AiLatencyTrace.end(recording)

    private fun stamp() = AiLatencyStamp(recording.id, 10, 20, 1, 30, 0, 48000,
        48000, 100, false, 200, 210, 220, 230, 240, "speech", true, 1, "ambient", "default")

    @Test fun observersKeepIndependentFirstTimesAndIgnoreRepeatedFrames() {
        val s = stamp()
        AiLatencyTrace.result(s, 250)
        AiLatencyTrace.observed(s, AiLatencyTrace.Stage.COLOR_READ, 260)
        AiLatencyTrace.observed(s, AiLatencyTrace.Stage.COLOR_READ, 270)
        AiLatencyTrace.observed(s, AiLatencyTrace.Stage.COLOR_DRAW, 280)
        AiLatencyTrace.observed(s, AiLatencyTrace.Stage.HUD_DRAW, 350)
        assertEquals(listOf(250L, 260L, 280L, 350L), recording.queue.map { it.atNs })
        assertEquals("ambient", recording.queue.first().stamp!!.confirmedCoarse)
    }

    @Test fun restartedRecordingRejectsOldResultsAndOldWorkerCannotEndNewRecording() {
        val old = recording
        val s = stamp()
        recording = AiLatencyTrace.begin()
        AiLatencyTrace.end(old)
        AiLatencyTrace.result(s, 250)
        AiLatencyTrace.observed(s, AiLatencyTrace.Stage.COLOR_DRAW, 280)
        assertTrue(recording.queue.isEmpty())
        assertEquals(recording.id, AiLatencyTrace.recordingId)
        AiLatencyTrace.result(stamp(), 300)
        assertEquals(1, recording.queue.size)
    }

    @Test fun queueIsBoundedAndReportsDroppedEvents() {
        recording = AiLatencyTrace.begin(2)
        repeat(5) { AiLatencyTrace.mark("test", it.toLong()) }
        assertEquals(2, recording.queue.size)
        assertEquals(3L, recording.dropped.get())
    }

    @Test fun simultaneousObservationsEmitExactlyOnce() {
        val s = stamp()
        val pool = Executors.newFixedThreadPool(4)
        repeat(100) { pool.execute { AiLatencyTrace.observed(s, AiLatencyTrace.Stage.COLOR_DRAW, 280) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(1, recording.queue.size)
    }

    @Test fun pcmMetadataMatchesCopiedTailAndUnknownInputCannotInheritOldFrames() {
        val buffer = AiAudioBuffer(48000, 2)
        val pcm = floatArrayOf(.2f, .4f, .4f, .6f)
        buffer.ingestInterleaved(pcm, pcm.size, 100, 2, 10, true)
        buffer.ingestInterleaved(pcm, pcm.size, 100, 4, 20, true)
        val copied = FloatArray(4)
        val trace = AiPcmTrace()
        buffer.copyTailRightPadded(copied, 4, trace)
        assertArrayEquals(floatArrayOf(.3f, .5f, .3f, .5f), copied, .0001f)
        assertEquals(100L, trace.captureSessionId)
        assertEquals(4L, trace.endFrameExclusive)
        assertEquals(20L, trace.readCompletedNs)
        assertTrue(trace.inputMayBeFiltered)
        // New capture source has not filled a whole requested window yet.
        buffer.ingestInterleaved(pcm, pcm.size, 101, 2, 30, false)
        buffer.copyTailRightPadded(copied, 4, trace)
        assertEquals(0L, trace.captureSessionId)
        buffer.reset()
        buffer.copyTailRightPadded(copied, 4, trace)
        assertEquals(0L, trace.readCompletedNs)
        assertArrayEquals(FloatArray(4), copied, 0f)
    }

    @Test fun stoppedTracingEmitsNothing() {
        val s = stamp()
        AiLatencyTrace.end(recording)
        AiLatencyTrace.result(s, 250)
        AiLatencyTrace.mark("off", 300)
        AiLatencyTrace.observed(s, AiLatencyTrace.Stage.COLOR_DRAW, 300)
        assertTrue(recording.queue.isEmpty())
    }
}

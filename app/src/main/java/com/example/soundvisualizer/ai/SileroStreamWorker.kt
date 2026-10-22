package com.example.soundvisualizer.ai

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One background owner, conflated wakeups, no extra capture PCM copy or unbounded queue. */
internal class SileroStreamWorker(context: Context, private val buffer: AiAudioBuffer,
    private val discontinuity: () -> Unit,
    private val block: (Long, Long, Long, Float, Long) -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val queued = AtomicBoolean(false)
    private val wake = Semaphore(0)
    val calls = AtomicLong()
    val resets = AtomicLong()
    val failed = AtomicBoolean(false)
    val computeNs = AtomicLong()
    @Volatile var beforeScoreForTest: (() -> Unit)? = null
    private val owner = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            SileroInference(context.applicationContext).use { model ->
                var epoch = -1L
                var next = 0L
                val pcm = FloatArray(1700) // 512 at48k +96 FIR context; supported max48k.
                val output = FloatArray(512)
                while (!closed.get()) {
                    wake.acquire(); queued.set(false)
                    if (closed.get()) break
                    var cursor = buffer.cursor()
                    if (epoch != cursor.generation) {
                        epoch = cursor.generation; next = 0; model.reset(); resets.incrementAndGet()
                    }
                    val rate = buffer.sampleRate
                    // If CPU cannot keep up, discard backlog and reset recurrent evidence.
                    val newest = (((cursor.frames - if (rate > 16_000) 48 else 0).coerceAtLeast(0) * 16_000 / rate / 512) - 1).coerceAtLeast(0) * 512
                    if (newest - next > 2560) { next = newest; model.reset(); resets.incrementAndGet(); discontinuity() }
                    var processed = 0
                    while (!closed.get() && processed++ < 5) {
                        val first = next * rate / 16_000 - if (rate > 16_000) 47 else 0
                        val end = (next + 511) * rate / 16_000 + if (rate > 16_000) 49 else 1
                        val size = (end - first).toInt()
                        if (!buffer.copyRange(epoch, first, size, pcm)) break
                        val began = SystemClock.elapsedRealtimeNanos()
                        CaptureAudioMath.resampleStreamBlock(pcm, first, next, rate, output)
                        beforeScoreForTest?.invoke()
                        val probability = model.score(output)
                        computeNs.addAndGet(SystemClock.elapsedRealtimeNanos() - began)
                        calls.incrementAndGet()
                        cursor = buffer.cursor()
                        if (cursor.generation == epoch && !closed.get())
                            block(epoch, next * rate / 16_000, (next + 512) * rate / 16_000,
                                probability, SystemClock.elapsedRealtimeNanos())
                        next += 512
                    }
                }
            }
        } catch (error: Throwable) {
            failed.set(true)
            Log.w("SileroPreview", "Optional verifier disabled; regular AI continues", error)
            if (AiLatencyTrace.recordingId != 0L)
                AiLatencyTrace.mark("silero_error", SystemClock.elapsedRealtimeNanos(), detail = error.javaClass.simpleName)
        }
    }, "silero-speech-preview").apply { isDaemon = true; start() }

    fun signal() { if (!closed.get() && !failed.get() && queued.compareAndSet(false, true)) wake.release() }
    override fun close() { if (closed.compareAndSet(false, true)) wake.release() }
}

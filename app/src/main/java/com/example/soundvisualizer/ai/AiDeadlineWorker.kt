package com.example.soundvisualizer.ai

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional, single-flight lane. No await/join, shared inference lock, or queue of
 * predictions. Its resource is created, used and closed on its own thread.
 * This removes application-level blocking, NOT CPU/memory contention on Android.
 */
internal class AiDeadlineWorker<R : AutoCloseable, I : Any, O : Any>(
    private val clockMs: () -> Long,
    createResource: () -> R,
    private val compute: (R, I) -> O?,
    private val onAccepted: () -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    threadFactory: ThreadFactory = ThreadFactory { Thread(it, "ai-preview").apply { isDaemon = true } }
) : AutoCloseable {
    data class Completed<I, O>(val input: I, val output: O, val generation: Long, val expiresAtMs: Long)

    private val closed = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val completed = AtomicReference<Completed<I, O>?>(null)
    private val executor = ScheduledThreadPoolExecutor(1, threadFactory).apply {
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        removeOnCancelPolicy = true
    }
    // Accessed only by executor, including cleanup; never closed under native run().
    private var resource: R? = null

    init {
        executor.execute {
            try {
                resource = createResource()
                if (!closed.get()) ready.set(true)
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    fun isReady(): Boolean = ready.get() && !closed.get()
    fun isBusy(): Boolean = busy.get()

    fun submit(input: I, notBeforeMs: Long, expiresAtMs: Long): Boolean {
        if (!isReady() || notBeforeMs >= expiresAtMs || clockMs() >= expiresAtMs ||
            !busy.compareAndSet(false, true)) return false
        val epoch = generation.get()
        try {
            executor.schedule({
                try {
                    if (closed.get() || generation.get() != epoch || clockMs() >= expiresAtMs) return@schedule
                    val output = resource?.let { compute(it, input) } ?: return@schedule
                    // A slow native call may return AFTER expiry/stop/mapping/regular tick.
                    // Never publish it, never move the regular deadline to accommodate it.
                    if (!closed.get() && generation.get() == epoch && clockMs() < expiresAtMs) {
                        completed.set(Completed(input, output, epoch, expiresAtMs))
                        onAccepted()
                    }
                } catch (t: Throwable) {
                    onError(t)
                } finally {
                    busy.set(false)
                }
            }, (notBeforeMs - clockMs()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            busy.set(false)
            return false
        }
        return true
    }

    fun latest(): Completed<I, O>? = completed.get()?.takeIf {
        !closed.get() && it.generation == generation.get() && clockMs() < it.expiresAtMs
    }

    /** Does not cancel/interrupt a native call or wait for it to acknowledge cancellation. */
    fun invalidate() {
        generation.incrementAndGet()
        completed.set(null)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready.set(false)
        invalidate()
        // Enqueued after any active computation. shutdown never waits for native work.
        executor.execute {
            try {
                resource?.close()
            } catch (t: Throwable) {
                onError(t)
            } finally {
                resource = null
            }
        }
        executor.shutdown()
    }
}

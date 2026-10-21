package com.example.soundvisualizer.ai

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AiDeadlineWorkerTest {
    private fun await(latch: CountDownLatch) = assertTrue("worker timed out", latch.await(5, TimeUnit.SECONDS))
    private fun ready(worker: AiDeadlineWorker<*, *, *>) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!worker.isReady() && System.nanoTime() < end) Thread.yield()
        assertTrue(worker.isReady())
    }
    private fun idle(worker: AiDeadlineWorker<*, *, *>) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (worker.isBusy() && System.nanoTime() < end) Thread.yield()
        assertFalse(worker.isBusy())
    }

    @Test
    fun lateNativeWorkDoesNotBlockRegularWorkOrPublishAfterDeadline() {
        val clock = AtomicLong(1000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val accepted = AtomicLong(0)
        AiDeadlineWorker(
            clock::get, { AutoCloseable {} },
            { _: AutoCloseable, _: String -> entered.countDown(); await(release); "speech" },
            { accepted.incrementAndGet(); Unit }
        ).use { worker ->
            ready(worker)
            assertTrue(worker.submit("candidate", 1000, 1100))
            await(entered)
            assertFalse(worker.submit("must-not-queue", 1000, 1100))
            clock.set(1100)
            // This regular-state update completes while the optional call is still blocked.
            var regular = "ambient"
            regular = "danger"
            worker.invalidate()
            assertEquals("danger", regular)
            assertEquals(1L, release.count)
            assertNull(worker.latest())
            release.countDown()
            idle(worker)
            assertNull(worker.latest())
            assertEquals(0, accepted.get())
        }
    }

    @Test
    fun acceptedPreviewExpiresAtOriginalSlotAndDoesNotSurviveNewGeneration() {
        val clock = AtomicLong(1000)
        AiDeadlineWorker(clock::get, { AutoCloseable {} }, { _: AutoCloseable, input: String -> input }).use { worker ->
            ready(worker)
            assertTrue(worker.submit("speech", 1000, 1100))
            idle(worker)
            assertEquals("speech", worker.latest()?.output)
            clock.set(1100)
            assertNull(worker.latest())
            clock.set(1200)
            assertTrue(worker.submit("new speech", 1200, 1300))
            idle(worker)
            assertNotNull(worker.latest())
            worker.invalidate()
            assertNull(worker.latest())
        }
    }

    @Test
    fun closeDoesNotWaitAndResourceIsClosedOnlyAfterNativeWorkReturns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val closedDuringCompute = AtomicBoolean(false)
        val worker = AiDeadlineWorker(
            { 1000L }, { AutoCloseable { disposed.countDown() } },
            { _: AutoCloseable, _: String ->
                entered.countDown(); await(release)
                closedDuringCompute.set(disposed.count == 0L)
                "speech"
            }
        )
        ready(worker)
        assertTrue(worker.submit("candidate", 1000, 2000))
        await(entered)
        worker.close()
        assertEquals(1L, disposed.count)
        assertFalse(worker.submit("closed", 1000, 2000))
        release.countDown()
        await(disposed)
        assertFalse(closedDuringCompute.get())
        assertNull(worker.latest())
    }

    @Test
    fun optionalFailureDoesNotPublishAndLaneCanRecover() {
        val failures = AtomicLong(0)
        AiDeadlineWorker(
            { 1000L }, { AutoCloseable {} },
            { _: AutoCloseable, input: String -> if (input == "fail") error("optional failure") else input },
            onError = { failures.incrementAndGet() }
        ).use { worker ->
            ready(worker)
            assertTrue(worker.submit("fail", 1000, 2000))
            idle(worker)
            assertNull(worker.latest())
            assertEquals(1, failures.get())
            assertTrue(worker.submit("speech", 1000, 2000))
            idle(worker)
            assertEquals("speech", worker.latest()?.output)
        }
    }
}

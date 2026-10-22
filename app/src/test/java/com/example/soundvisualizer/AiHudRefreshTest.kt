package com.example.soundvisualizer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AiHudRefreshTest {
    private fun changes() = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Test fun notificationDoesNotWaitForHeartbeat() = runBlocking {
        val changed = changes()
        val received = Channel<Unit>(Channel.UNLIMITED)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            aiHudRefreshes(changed, MutableStateFlow(false), 60_000).collect { received.send(Unit) }
        }
        try {
            withTimeout(2_000) {
                changed.subscriptionCount.first { it == 1 }
                received.receive() // initial read
                changed.emit(Unit)
                received.receive() // event arrives without advancing the 60-second heartbeat
            }
        } finally { job.cancelAndJoin() }
    }

    @Test fun pauseUnsubscribesAndResumeReadsCurrentSource() = runBlocking {
        val changed = changes()
        val paused = MutableStateFlow(false)
        val seen = Channel<Int>(Channel.UNLIMITED)
        var latest = 1
        val job = launch {
            aiHudRefreshes(changed, paused, 60_000).collect { seen.send(latest) }
        }
        try {
            withTimeout(2_000) {
                changed.subscriptionCount.first { it == 1 }
                assertEquals(1, seen.receive())
                paused.value = true
                changed.subscriptionCount.first { it == 0 }
                latest = 2
                changed.emit(Unit)
                yield()
                assertTrue(seen.tryReceive().isFailure)
                latest = 3
                paused.value = false
                assertEquals(3, seen.receive())
            }
        } finally { job.cancelAndJoin() }
        assertEquals(0, changed.subscriptionCount.value)
    }

    @Test fun slowHudCoalescesBurstsAndReadsNewestValue() = runBlocking {
        val changed = changes()
        val entered = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val seen = Channel<Int>(Channel.UNLIMITED)
        var latest = 0
        val job = launch {
            aiHudRefreshes(changed, MutableStateFlow(false), 60_000).collect {
                if (!entered.isCompleted) { entered.complete(Unit); unblock.await() }
                seen.send(latest)
            }
        }
        try {
            withTimeout(2_000) {
                changed.subscriptionCount.first { it == 1 }
                entered.await()
                repeat(1_000) { latest = it + 1; changed.tryEmit(Unit) }
                yield()
                unblock.complete(Unit)
                assertEquals(1_000, seen.receive())
                // Drain any already-running update; a thousand stale payloads must never queue.
                yield()
                var count = 1
                while (seen.tryReceive().isSuccess) count++
                assertTrue("burst queued $count updates", count <= 3)
            }
        } finally { job.cancelAndJoin() }
    }

    @Test fun heartbeatRemainsForLevelAndAgeWithoutResults() = runBlocking {
        val received = Channel<Unit>(Channel.UNLIMITED)
        val changed = changes()
        val job = launch { aiHudRefreshes(changed, MutableStateFlow(false), 10).collect { received.send(Unit) } }
        try {
            withTimeout(2_000) { received.receive(); received.receive() }
        } finally { job.cancelAndJoin() }
        assertEquals(0, changed.subscriptionCount.value)
    }
}

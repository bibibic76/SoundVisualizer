package com.example.soundvisualizer

import com.example.soundvisualizer.feedback.HapticTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private const val RATE = 48_000
private const val NS_PER_FRAME = 1e9 / RATE

/** 프레임 [frame] 이 실제로 마이크에 들어온 시각(ns). 0 번 프레임이 5초에 들어왔다. */
private fun trueNs(frame: Long): Double = 5e9 + frame * NS_PER_FRAME

/**
 * 버퍼를 차례로 읽는다. 읽기는 버퍼의 마지막 프레임이 들어온 뒤 [next] 의 지연만큼 늦게 돌아온다. 늦은 읽기 뒤의 읽기는
 * 데이터가 이미 있어 곧바로 돌아오므로, 돌아온 시각은 늘 늘어난다.
 */
private class Reads(private val clock: CaptureClock, private val frames: Int = 512) {
    var framesRead = 0L
        private set
    var lastReadNs = 0L
        private set

    /** 이번 읽기가 실제로 늦은 만큼(ns). */
    var lastDelayNs = 0L
        private set

    /** 오버런으로 버려진 프레임 수. 그 뒤로 읽는 프레임은 우리가 센 번호보다 그만큼 늦게 들어왔다. */
    var droppedFrames = 0L

    /** 우리가 센 [frame] 번 프레임이 실제로 마이크에 들어온 시각(ns). 버려진 뒤의 프레임에만 쓴다. */
    fun captureNs(frame: Long): Double = trueNs(frame + droppedFrames)

    /** @return 이 버퍼의 첫 프레임 시각(ms) */
    fun next(delayNs: Long, poll: (LongArray) -> Boolean): Double {
        val end = framesRead + frames
        lastReadNs = maxOf((captureNs(end) + delayNs).toLong(), lastReadNs + 1_000)
        lastDelayNs = (lastReadNs - captureNs(end)).toLong()
        val start = clock.onRead(frames, lastReadNs, poll)
        framesRead = end
        return start
    }
}

/**
 * 외부 사운드 모드에서 캡처 버퍼의 프레임마다 마이크에 들어온 시각을 세는 규칙(#352). 오디오 서버의 시각 표시
 * (`AudioRecord.getTimestamp`)는 함수로 흉내 낸다.
 */
class CaptureClockTest {

    @Test
    fun `기준이 48000 번 프레임을 1초로 주면 48512 번 프레임은 1_010667초다`() {
        val clock = CaptureClock(RATE)
        val anchor: (LongArray) -> Boolean = { out ->
            out[0] = 48_000
            out[1] = 1_000_000_000
            true
        }
        // 0 번 프레임이 0초에 들어왔다. 첫 읽기는 1초 분량(48000 프레임)이고 20ms 늦게 돌아온다.
        val first = clock.onRead(48_000, 1_020_000_000, anchor)
        assertEquals("기준을 받았다", CaptureClock.Mode.TIMESTAMP, clock.mode)
        assertEquals(0.0, first, 1e-6)
        assertEquals(20.0, clock.latencyMs, 1e-6)
        assertEquals(1000.0, clock.onRead(512, 1_030_666_667, anchor), 1e-6)
        assertEquals(1010.666667, clock.onRead(512, 1_041_333_333, anchor), 1e-6)
        assertEquals("시각 표시가 있으면 끝을 늘리지 않는다", 0, clock.endExtraMs)
    }

    @Test
    fun `지연 없는 어림보다 2ms 넘게 늦거나 300ms 넘게 이른 시각 표시는 버린다`() {
        for ((offsetMs, accepted) in listOf(3.0 to false, 1.0 to true, -299.0 to true, -301.0 to false)) {
            val clock = CaptureClock(RATE)
            // 읽기가 지연 없이 돌아오므로 어림이 곧 실제 시각이다. 시각 표시는 실제보다 offsetMs 만큼 어긋난다.
            Reads(clock).next(0) { out ->
                val p = 400L
                out[0] = p
                out[1] = (trueNs(p) + offsetMs * 1e6).toLong()
                true
            }
            assertEquals("$offsetMs ms", if (accepted) 1 else 0, clock.anchors)
            assertEquals("$offsetMs ms", if (accepted) 0 else 1, clock.rejects)
            assertEquals(
                "$offsetMs ms",
                if (accepted) CaptureClock.Mode.TIMESTAMP else CaptureClock.Mode.FALLBACK,
                clock.mode
            )
        }
    }

    @Test
    fun `연달아 세 번 버리면 어림으로 돌아가 끝을 늘리고 맞는 기준이 오면 돌아온다`() {
        val clock = CaptureClock(RATE)
        val reads = Reads(clock)
        var offsetNs = 30e6 // 실제보다 30ms 이르다. 지연 없이 읽으므로 입력 지연이 30ms 인 것과 같다.
        val poll: (LongArray) -> Boolean = { out ->
            val p = reads.framesRead
            out[0] = p
            out[1] = (trueNs(p) - offsetNs).toLong()
            true
        }
        val modes = ArrayList<CaptureClock.Mode>()
        val extras = ArrayList<Int>()
        for (k in 0 until 600) { // 6.4초
            when (clock.polls) {
                1 -> offsetNs = 400e6 // 300ms 넘게 이르다
                4 -> offsetNs = 30e6
            }
            val before = clock.polls
            reads.next(0, poll)
            if (clock.polls != before) {
                modes.add(clock.mode)
                extras.add(clock.endExtraMs)
            }
        }
        // 물은 차례: 1 맞음, 2·3 버림(아직 기준), 4 버림(세 번째) → 어림, 5 맞음 → 기준.
        assertEquals(CaptureClock.Mode.TIMESTAMP, modes[0])
        assertEquals("한두 번 버려도 기준을 쓴다", CaptureClock.Mode.TIMESTAMP, modes[1])
        assertEquals(CaptureClock.Mode.TIMESTAMP, modes[2])
        assertEquals("세 번째로 버리면 어림이다", CaptureClock.Mode.FALLBACK, modes[3])
        assertEquals("어림으로 도는 동안은 끝을 60ms 늘린다", HapticTuning.AI_FEED_FALLBACK_EXTRA_MS, extras[3])
        assertEquals(CaptureClock.Mode.TIMESTAMP, modes[4])
        assertEquals(0, extras[4])
        assertEquals(3, clock.rejects)
    }

    @Test
    fun `시각 표시를 연달아 세 번 받지 못하면 어림으로 돌아간다`() {
        val clock = CaptureClock(RATE)
        val reads = Reads(clock)
        var ok = true
        val poll: (LongArray) -> Boolean = { out ->
            out[0] = reads.framesRead
            out[1] = (trueNs(reads.framesRead) - 20e6).toLong()
            ok
        }
        reads.next(0, poll)
        assertEquals(CaptureClock.Mode.TIMESTAMP, clock.mode)
        ok = false
        val start = reads.lastReadNs
        while (reads.lastReadNs - start < 1_400_000_000L) {
            reads.next(0, poll)
            assertEquals("두 번까지는 실패해도 기준을 쓴다", CaptureClock.Mode.TIMESTAMP, clock.mode)
        }
        while (reads.lastReadNs - start < 1_600_000_000L) reads.next(0, poll)
        assertEquals("세 번째 실패", CaptureClock.Mode.FALLBACK, clock.mode)
        assertEquals(3, clock.failures)

        // 기기가 시각 표시를 아예 주지 않으면 처음부터 어림이다.
        val never = CaptureClock(RATE)
        Reads(never).next(0) { false }
        assertEquals(CaptureClock.Mode.FALLBACK, never.mode)
        assertEquals(HapticTuning.AI_FEED_FALLBACK_EXTRA_MS, never.endExtraMs)
    }

    @Test
    fun `어림은 최근 2초 동안 가장 덜 늦은 읽기를 따르고 실제보다 이르지 않다`() {
        // 시각 표시가 없을 때와, 늘 읽기가 돌아온 뒤의 시각을 줄 때 모두 그렇다. 뒤의 것은 틀린 값이라 버리고, 오버런 뒤처럼
        // 어림의 최솟값을 다시 세지도 않는다.
        for (afterRead in listOf(false, true)) {
            val clock = CaptureClock(RATE)
            val reads = Reads(clock)
            val poll: (LongArray) -> Boolean = { out ->
                out[0] = reads.framesRead
                out[1] = (trueNs(reads.framesRead) + 1e9).toLong()
                afterRead
            }
            val random = Random(7)
            val history = ArrayList<Pair<Long, Long>>() // (돌아온 시각, 늦은 만큼)
            for (k in 0 until 1500) { // 16초. 입력 지연 20ms 에 0~15ms 가 더 늦는다.
                val first = reads.framesRead
                val startMs = reads.next(20_000_000L + random.nextLong(0, 15_000_001), poll)
                history.add(reads.lastReadNs to reads.lastDelayNs)
                val now = reads.lastReadNs
                val within2s = history.filter { now - it.first <= 2_000_000_000L }.minOf { it.second }
                val within1_5s = history.filter { now - it.first <= 1_500_000_000L }.minOf { it.second }
                val lateNs = startMs * 1e6 - trueNs(first)
                assertTrue("$afterRead k=$k: 실제보다 ${-lateNs / 1e6}ms 이르다", lateNs >= -NS_PER_FRAME)
                // 0.5초 칸 네 개로 보므로 보는 범위는 최근 1.5~2초다. 그 안의 가장 덜 늦은 읽기와 프레임 하나 안에서 같다.
                assertTrue(
                    "$afterRead k=$k: ${lateNs / 1e6}ms 가 2초 안의 최소 ${within2s / 1e6}ms 와 1.5초 안의 최소 " +
                        "${within1_5s / 1e6}ms 사이가 아니다",
                    lateNs >= within2s - NS_PER_FRAME && lateNs <= within1_5s + NS_PER_FRAME
                )
            }
            assertEquals(CaptureClock.Mode.FALLBACK, clock.mode)
            assertEquals(0, clock.anchors)
            assertEquals(if (afterRead) clock.polls else 0, clock.rejects)
        }
    }

    @Test
    fun `오버런으로 프레임이 버려져도 다음 시각 표시에서 곧바로 바로잡는다`() {
        val clock = CaptureClock(RATE)
        val reads = Reads(clock)
        // 오디오 서버는 우리가 센 프레임 번호와 그 프레임이 실제로 들어온 시각을 준다. 버려진 프레임은 시각에 들어 있다.
        val poll: (LongArray) -> Boolean = { out ->
            out[0] = reads.framesRead
            out[1] = reads.captureNs(reads.framesRead).toLong()
            true
        }
        val random = Random(5)
        fun delay() = 20_000_000L + random.nextLong(0, 15_000_001) // 입력 지연 20ms 에 0~15ms
        while (reads.lastReadNs < 9_000_000_000L) reads.next(delay(), poll) // 4초
        assertEquals(CaptureClock.Mode.TIMESTAMP, clock.mode)

        // 캡처 스레드가 밀려 오디오 서버가 80ms 분량을 버렸다. 그 뒤로 읽는 프레임은 우리가 센 번호보다 80ms 늦게 들어왔다.
        // 어림의 최솟값과 지난 기준은 80ms 이르게 남는다. 예전에는 그것과 견주어 맞는 시각 표시를 늦다고 버려 2초쯤 이르게 셌다.
        reads.droppedFrames = 80L * RATE / 1000
        val droppedAt = reads.lastReadNs
        val pollsAtDrop = clock.polls
        var beforePoll = 0
        while (reads.lastReadNs - droppedAt < 3_000_000_000L) {
            val first = reads.framesRead
            val startMs = reads.next(delay(), poll)
            val errorMs = startMs - reads.captureNs(first) / 1e6
            val sinceMs = (reads.lastReadNs - droppedAt) / 1e6
            if (clock.polls == pollsAtDrop) {
                // 묻기 전에는 알 길이 없어 지난 기준으로 80ms 이르다.
                beforePoll++
                assertEquals("버려진 뒤 ${sinceMs}ms", -80.0, errorMs, 0.01)
            } else {
                assertEquals("버려진 뒤 ${sinceMs}ms: 실제 캡처 시각과 다르다", 0.0, errorMs, 0.01)
                assertEquals("버려진 뒤 ${sinceMs}ms", CaptureClock.Mode.TIMESTAMP, clock.mode)
            }
        }
        assertTrue("버려진 뒤 처음 묻기까지 ${beforePoll}번 읽었다(0.5초 안이어야 한다)", beforePoll <= 47)
        assertEquals("맞는 시각 표시를 버렸다", 0, clock.rejects)
    }

    @Test
    fun `오디오 서버에는 많아야 0_5초에 한 번 묻는다`() {
        val clock = CaptureClock(RATE)
        val reads = Reads(clock)
        val asked = ArrayList<Long>()
        val poll: (LongArray) -> Boolean = { out ->
            asked.add(reads.lastReadNs)
            out[0] = reads.framesRead
            out[1] = (trueNs(reads.framesRead) - 20e6).toLong()
            true
        }
        val random = Random(3)
        while (reads.framesRead < 10L * RATE) reads.next(20_000_000L + random.nextLong(0, 15_000_001), poll)
        assertEquals(asked.size, clock.polls)
        asked.zipWithNext { a, b -> assertTrue("${(b - a) / 1e6}ms 만에 다시 물었다", b - a >= 500_000_000L) }
        assertTrue("10초에 ${asked.size}번 물었다", asked.size in 19..21)
    }
}

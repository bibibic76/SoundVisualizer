package com.example.soundvisualizer.feedback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 외부 사운드 모드에서 AI 입력이 빼는 구간의 목록(#352). 캡처 스레드가 잠그지 않고 읽는다. */
class SelfVibrationTimelineTest {

    private fun windowsOf(timeline: SelfVibrationTimeline): List<LongArray> =
        timeline.snapshot().toList().chunked(SelfVibrationTimeline.FIELDS).map { it.toLongArray() }

    @Test
    fun `연 구간을 읽고 끝을 고치고 지울 수 있다`() {
        val timeline = SelfVibrationTimeline()
        val a = timeline.open(995, 1580, 1400)
        val b = timeline.open(1995, 2580, 2400)
        assertNotEquals(0L, a)
        assertArrayEquals(longArrayOf(a, 995, 1580, 1400, b, 1995, 2580, 2400), timeline.snapshot())

        timeline.setEnd(a, 1590, 1410)
        assertArrayEquals("고친 구간만 바뀐다", longArrayOf(a, 995, 1590, 1410, b, 1995, 2580, 2400), timeline.snapshot())

        timeline.retract(a)
        assertArrayEquals(longArrayOf(b, 1995, 2580, 2400), timeline.snapshot())
        timeline.retract(a)
        timeline.setEnd(a, 9, 9)
        assertArrayEquals("없는 번호는 아무것도 바꾸지 않는다", longArrayOf(b, 1995, 2580, 2400), timeline.snapshot())
    }

    @Test
    fun `한 번 읽은 목록은 그 뒤에 써도 바뀌지 않는다`() {
        // 캡처 스레드는 잠그지 않고 버퍼마다 한 번 읽는다. 읽은 배열을 쓰는 쪽이 고치면 버퍼 중간에 구간이 바뀐다.
        val timeline = SelfVibrationTimeline()
        val a = timeline.open(0, 585, 405)
        val before = timeline.snapshot()
        val copy = before.copyOf()
        timeline.setEnd(a, 600, 420)
        timeline.cut(a, 100)
        timeline.open(1000, 1585, 1405)
        timeline.retract(a)
        assertArrayEquals(copy, before)
        val now = timeline.snapshot()
        assertSame("쓰지 않으면 같은 배열이다(읽을 때 할당하지 않는다)", now, timeline.snapshot())
    }

    @Test
    fun `아홉 번째 구간을 열면 가장 오래된 것을 버린다`() {
        val timeline = SelfVibrationTimeline()
        val handles = (0 until 9).map { i -> timeline.open(i * 100L, i * 100L + 50, i * 100L + 40) }
        val windows = windowsOf(timeline)
        assertEquals(HapticTuning.AI_FEED_TIMELINE_CAPACITY, windows.size)
        assertEquals(handles.drop(1), windows.map { it[SelfVibrationTimeline.HANDLE] })
    }

    @Test
    fun `새로 열 때 2초 넘게 전에 끝난 구간은 버린다`() {
        val timeline = SelfVibrationTimeline()
        val old = timeline.open(0, 500, 400)
        val recent = timeline.open(1000, 1500, 1400)
        // 3000ms 에 연다. 500ms 에 끝난 구간은 2.5초 전에 끝났고, 1500ms 에 끝난 구간은 1.5초 전에 끝났다.
        val newest = timeline.open(3000, 3585, 3405)
        assertEquals(listOf(recent, newest), windowsOf(timeline).map { it[SelfVibrationTimeline.HANDLE] })
        assertTrue(old !in windowsOf(timeline).map { it[SelfVibrationTimeline.HANDLE] })
        // 꼭 2초 전에 끝난 구간은 남긴다.
        val edge = SelfVibrationTimeline()
        edge.open(0, 1000, 900)
        edge.open(3000, 3585, 3405)
        assertEquals(2, windowsOf(edge).size)
    }

    @Test
    fun `끊으면 꼬리 여유를 그대로 두고 앞당기며 늦추지는 않는다`() {
        val timeline = SelfVibrationTimeline()
        // 보낸 시각 1000, 울림 400ms, 꼬리 여유 180ms.
        val a = timeline.open(995, 1580, 1400)
        timeline.cutLatest(1200)
        assertArrayEquals("멈춘 시각 + 180", longArrayOf(a, 995, 1380, 1200), timeline.snapshot())
        timeline.cutLatest(1300)
        assertArrayEquals("이미 앞당긴 끝을 늦추지 않는다", longArrayOf(a, 995, 1380, 1200), timeline.snapshot())

        val b = SelfVibrationTimeline()
        val h = b.open(995, 1580, 1400)
        b.cutLatest(1500)
        assertArrayEquals("저절로 끝난 뒤에 끊어도 그대로다", longArrayOf(h, 995, 1580, 1400), b.snapshot())

        val c = SelfVibrationTimeline()
        val first = c.open(995, 1580, 1400)
        val second = c.open(1995, 2580, 2400)
        c.cutLatest(2100)
        assertArrayEquals(
            "가장 최근 구간만 끊는다",
            longArrayOf(first, 995, 1580, 1400, second, 1995, 2280, 2100),
            c.snapshot()
        )
        c.cut(first, 1100)
        assertArrayEquals(
            "번호로 고른 구간도 같은 규칙이다(미리보기)",
            longArrayOf(first, 995, 1280, 1100, second, 1995, 2280, 2100),
            c.snapshot()
        )
    }

    @Test
    fun `빈 목록을 끊어도 아무 일도 없다`() {
        val timeline = SelfVibrationTimeline()
        timeline.cutLatest(100)
        timeline.cut(1, 100)
        assertEquals(0, timeline.snapshot().size)
    }

    @Test
    fun `앱이 쓰는 공용 목록도 빈 목록으로 시작하고 구간을 열고 지운다`() {
        // #383: companion 이 shared 를 먼저 만들 때 처음 값의 상수가 아직 null 이라 shared 만 null 배열을 들고 시작했고,
        // 진동 미리 듣기와 외부 사운드 모드 진동 때 앱이 죽었다. 다른 테스트는 저마다 새 목록을 만들어 이 경로를 보지 못한다.
        val shared = SelfVibrationTimeline.shared
        val before: LongArray? = shared.snapshot()
        assertNotNull("공용 목록이 null 로 시작했다", before)
        val handle = shared.open(1_000_000, 1_000_585, 1_000_405)
        assertTrue(windowsOf(shared).any { it[SelfVibrationTimeline.HANDLE] == handle })
        shared.retract(handle)
        assertTrue(windowsOf(shared).none { it[SelfVibrationTimeline.HANDLE] == handle })
    }
}

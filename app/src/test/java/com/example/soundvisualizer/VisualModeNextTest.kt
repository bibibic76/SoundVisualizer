package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Test

/** 실행 중 알림의 [모드 바꾸기] 가 도는 차례를 고정한다. */
class VisualModeNextTest {

    @Test
    fun `설정 화면 차례대로 넘어간다`() {
        assertEquals(VisualMode.Pad, VisualMode.Wave.next())
        assertEquals(VisualMode.CircleRipple, VisualMode.Pad.next())
        assertEquals(VisualMode.Outline, VisualMode.CircleRipple.next())
    }

    @Test
    fun `마지막 다음은 처음으로 돌아온다`() {
        assertEquals(VisualMode.Wave, VisualMode.Outline.next())
    }

    @Test
    fun `모드 수만큼 넘기면 제자리로 온다`() {
        VisualMode.values().forEach { start ->
            var mode = start
            repeat(VisualMode.values().size) { mode = mode.next() }
            assertEquals(start, mode)
        }
    }

    @Test
    fun `한 바퀴 도는 동안 모든 모드를 한 번씩 지난다`() {
        val seen = mutableListOf(VisualMode.Wave)
        repeat(VisualMode.values().size - 1) { seen += seen.last().next() }

        assertEquals(VisualMode.values().toList(), seen)
    }
}

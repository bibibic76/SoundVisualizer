package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 실행 중 알림의 모드 칩이 누른 모드를 번호로 넘긴다. 그 번호를 되돌리는 규칙을 고정한다. */
class VisualModeOrdinalTest {

    @Test
    fun `번호로 같은 모드를 되찾는다`() {
        VisualMode.values().forEach { mode ->
            assertEquals(mode, VisualMode.fromOrdinal(mode.ordinal))
        }
    }

    @Test
    fun `설정 화면 차례가 번호 차례와 같다`() {
        assertEquals(VisualMode.Wave, VisualMode.fromOrdinal(0))
        assertEquals(VisualMode.Pad, VisualMode.fromOrdinal(1))
        assertEquals(VisualMode.CircleRipple, VisualMode.fromOrdinal(2))
        assertEquals(VisualMode.Outline, VisualMode.fromOrdinal(3))
    }

    @Test
    fun `없는 번호는 아무것도 내지 않는다`() {
        // 옛 버전 알림이 알림창에 남아 있다가 지금은 없는 모드를 넘길 수 있다. 그때 튕기지 않아야 한다.
        assertNull(VisualMode.fromOrdinal(-1))
        assertNull(VisualMode.fromOrdinal(VisualMode.values().size))
        assertNull(VisualMode.fromOrdinal(Int.MAX_VALUE))
        assertNull(VisualMode.fromOrdinal(Int.MIN_VALUE))
    }

    @Test
    fun `알림 칩 자리가 모드 수와 같다`() {
        // 모드를 더하면서 notification_modes.xml 에 칩을 더하지 않으면 마지막 모드를 고를 수 없다.
        assertEquals(VisualMode.values().size, AudioCaptureService.MODE_CHIP_IDS.size)
    }

    @Test
    fun `칩마다 다른 자리를 쓴다`() {
        assertEquals(
            AudioCaptureService.MODE_CHIP_IDS.size,
            AudioCaptureService.MODE_CHIP_IDS.toSet().size
        )
    }
}

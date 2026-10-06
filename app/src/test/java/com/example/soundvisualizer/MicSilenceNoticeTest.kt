package com.example.soundvisualizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 마이크로 아무것도 들어오지 않는다는 안내를 띄우는 규칙(#226).
 *
 * 조용한 방을 막힌 마이크로 잘못 알리면 사용자는 멀쩡한 폰 설정을 헤맨다. 반대로 막힌 마이크를 알리지 않으면
 * 앱이 고장 난 줄 안다. 둘을 가르는 것은 "잡음이라도 들어오는가" 다.
 */
class MicSilenceNoticeTest {

    private val tick = 500L

    /** [fromMs] 부터 [toMs] 까지 [tick] 간격으로 같은 값을 흘리고, 마지막 상태를 돌려준다. */
    private fun MicSilenceNotice.feed(fromMs: Long, toMs: Long, peak: Float, buffers: Int = 20, silenced: Boolean = false): Boolean {
        var t = fromMs
        while (t <= toMs) {
            onTick(t, silenced, peak, buffers)
            t += tick
        }
        return isSilenced
    }

    @Test
    fun `조용한 방의 잡음은 막힌 것으로 보지 않는다`() {
        // S25+ 사무실에서 잰 잡음 피크는 -40dBFS 안팎이다. 조용한 방은 그보다 작지만 0 은 아니다.
        assertFalse(MicSilenceNotice().feed(0, 60_000, peak = 0.0001f))
    }

    @Test
    fun `0 이 이어지면 잠시 뒤에 알린다`() {
        val notice = MicSilenceNotice()
        assertFalse("마이크를 여는 순간의 0 은 기다린다", notice.feed(0, MicSilenceNotice.HOLD_MS - tick, peak = 0f))
        assertTrue(notice.feed(MicSilenceNotice.HOLD_MS, MicSilenceNotice.HOLD_MS, peak = 0f))
    }

    @Test
    fun `시스템이 막았다고 알리면 기다리지 않는다`() {
        val notice = MicSilenceNotice()
        assertTrue(notice.onTick(0, silencedBySystem = true, peak = 0f, buffers = 20))
        assertTrue(notice.isSilenced)
    }

    @Test
    fun `소리가 다시 들어오면 바로 내린다`() {
        val notice = MicSilenceNotice()
        notice.feed(0, 5_000, peak = 0f)
        assertTrue(notice.isSilenced)

        assertTrue("바뀌었다고 알려야 화면과 알림을 고친다", notice.onTick(5_500, false, 0.0001f, 20))
        assertFalse(notice.isSilenced)
    }

    @Test
    fun `버퍼가 오지 않으면 마이크 탓으로 알리지 않는다`() {
        // 캡처가 멈춘 것이다. 이 안내가 아니라 다시 켜기가 필요하다.
        assertFalse(MicSilenceNotice().feed(0, 10_000, peak = 0f, buffers = 0))
        assertFalse(MicSilenceNotice().feed(0, 10_000, peak = 0f, buffers = 0, silenced = true))
    }

    @Test
    fun `중간에 잡음이 한 번이라도 들어오면 처음부터 다시 센다`() {
        val notice = MicSilenceNotice()
        notice.feed(0, 2_500, peak = 0f)
        notice.onTick(3_000, false, 0.0001f, 20)
        assertFalse(notice.feed(3_500, 3_500 + MicSilenceNotice.HOLD_MS - tick, peak = 0f))
    }

    @Test
    fun `같은 상태가 이어지면 바뀌었다고 하지 않는다`() {
        val notice = MicSilenceNotice()
        assertTrue(notice.onTick(0, true, 0f, 20))
        assertFalse(notice.onTick(500, true, 0f, 20))
        assertFalse(notice.onTick(1_000, false, 0f, 20))
    }
}

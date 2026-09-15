package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시각화가 멈췄을 때 알릴지와 무엇으로 알릴지.
 *
 * 청각장애 사용자는 조용한 장면과 꺼진 상태를 구분하지 못하므로, 사용자가 끈 경우만 빼고 모두 알려야 한다.
 * 반대로 직접 끌 때마다 진동과 알림이 오면 성가셔서 알림 자체를 꺼 버리게 된다.
 */
class StopReasonTest {

    private val unexpected = listOf(StopReason.ProjectionStopped, StopReason.CaptureError, StopReason.StartFailed)

    @Test
    fun `사용자가 끈 경우에는 진동도 알림도 토스트도 없다`() {
        val plan = StopAlertPlan.decide(StopReason.UserRequested, hasVibrator = true)
        assertFalse("진동", plan.vibrate)
        assertFalse("알림", plan.notify)
        assertFalse("알림을 올리지 않았어도 토스트를 띄우지 않는다", plan.toastAfter(posted = false))
    }

    @Test
    fun `사용자가 끈 게 아니면 모든 이유에서 진동과 알림으로 알린다`() {
        for (reason in unexpected) {
            val plan = StopAlertPlan.decide(reason, hasVibrator = true)
            assertTrue("$reason 진동", plan.vibrate)
            assertTrue("$reason 알림", plan.notify)
        }
    }

    @Test
    fun `알림이 올라갔으면 토스트를 겹쳐 띄우지 않는다`() {
        for (reason in unexpected) {
            assertFalse("$reason", StopAlertPlan.decide(reason, hasVibrator = true).toastAfter(posted = true))
        }
    }

    @Test
    fun `알림을 올리지 못하면 토스트로 대신 알린다`() {
        // 알림 권한 거부, 앱·채널 알림 끄기. 알림 권한은 거부해도 앱이 돌기 때문에 흔한 경우다.
        for (reason in unexpected) {
            assertTrue("$reason", StopAlertPlan.decide(reason, hasVibrator = true).toastAfter(posted = false))
        }
    }

    @Test
    fun `진동 모터가 없으면 진동만 빼고 알림은 그대로 한다`() {
        val plan = StopAlertPlan.decide(StopReason.ProjectionStopped, hasVibrator = false)
        assertFalse("진동", plan.vibrate)
        assertTrue("알림", plan.notify)
        assertTrue("토스트 대체", plan.toastAfter(posted = false))
    }

    @Test
    fun `서비스가 스스로 남긴 이유가 밖에서 받은 이유보다 먼저다`() {
        // 캡처가 끊겨 내리는 중에 오버레이도 실패를 알려 온 경우. 먼저 난 원인을 알린다.
        assertEquals(
            StopReason.CaptureError,
            StopReason.resolve(recorded = StopReason.CaptureError, external = StopReason.StartFailed)
        )
    }

    @Test
    fun `스스로 남긴 이유가 없으면 오버레이가 남긴 이유를 쓴다`() {
        assertEquals(StopReason.StartFailed, StopReason.resolve(recorded = null, external = StopReason.StartFailed))
    }

    @Test
    fun `이유 없이 밖에서 멈추면 앱 버튼이나 타일로 사용자가 끈 것으로 본다`() {
        assertEquals(StopReason.UserRequested, StopReason.resolve(recorded = null, external = null))
    }

    @Test
    fun `알림의 중지 버튼으로 끈 경우는 밖에서 받은 실패 이유보다 먼저다`() {
        assertEquals(
            StopReason.UserRequested,
            StopReason.resolve(recorded = StopReason.UserRequested, external = StopReason.StartFailed)
        )
    }
}

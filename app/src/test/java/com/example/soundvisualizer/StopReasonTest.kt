package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시각화가 멈췄을 때 알릴지와 무엇으로 알릴지.
 *
 * 청각장애 사용자는 조용한 장면과 꺼진 상태를 구분하지 못하므로, 사용자가 끈 경우만 빼고 모두 알려야 한다.
 * 반대로 직접 끌 때마다 진동과 알림이 오면 성가셔서 알림 자체를 꺼 버리게 된다.
 */
class StopReasonTest {

    private val unexpected = StopReason.values().filter { it != StopReason.UserRequested }

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
    fun `이유마다 다른 문구로 알린다`() {
        // 문구를 함께 쓰면 엉뚱한 원인을 가리킨다. 오버레이를 못 띄운 것을 "소리 받기 실패"로 알리는 식이다.
        val texts = unexpected.associateWith { StopAlert.textFor(it) }
        assertFalse("문구가 없는 이유: $texts", texts.values.any { it == 0 })
        assertEquals("이유 수만큼 문구가 있어야 한다: $texts", unexpected.size, texts.values.toSet().size)
    }
}

/**
 * 멈추는 중인지와 처음 남긴 이유를 지키는 래치.
 *
 * 프로젝션이 끊기면 뒤따라 읽기 오류가 나므로, 먼저 난 원인 하나만 알려야 한다.
 * 두 번 알리면 진동과 헤드업이 겹치고, 내려간 뒤에 알리면 다음 실행의 홈 안내가 된다.
 */
class StopLatchTest {

    @Test
    fun `처음에는 멈추는 중이 아니다`() {
        val latch = StopLatch()
        assertNull(latch.reason)
        assertFalse(latch.isDestroyed)
        assertFalse(latch.isStopping)
    }

    @Test
    fun `처음 이유로 한 번만 알린다`() {
        val latch = StopLatch()
        assertTrue(latch.claimAlert(StopReason.ProjectionStopped))
        assertFalse("뒤따라 온 읽기 오류로 또 알리지 않는다", latch.claimAlert(StopReason.CaptureError))
        assertEquals("먼저 난 원인이 정확하다", StopReason.ProjectionStopped, latch.reason)
        assertTrue(latch.isStopping)
    }

    @Test
    fun `내려간 뒤에는 알리지 않는다`() {
        val latch = StopLatch()
        latch.onDestroy()
        assertFalse("늦게 도착한 콜백", latch.claimAlert(StopReason.ProjectionStopped))
        assertNull(latch.reason)
        assertTrue(latch.isStopping)
    }

    @Test
    fun `알린 뒤 내려가도 이유는 그대로다`() {
        val latch = StopLatch()
        latch.claimAlert(StopReason.CaptureError)
        latch.onDestroy()
        assertEquals(StopReason.CaptureError, latch.reason)
        assertTrue(latch.isDestroyed)
    }
}

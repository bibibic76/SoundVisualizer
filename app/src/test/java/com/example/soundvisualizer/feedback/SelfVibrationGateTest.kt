package com.example.soundvisualizer.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GUARD = 110L
private const val ON = 100L
private const val LOUD = 0.3f
private const val QUIET = 0.002f

/** 외부 사운드 모드에서 앱의 진동이 섞인 값을 거르는 문(#290). 진동 루프 전체는 [SelfHearingLoopTest] 가 본다. */
class SelfVibrationGateTest {

    @Test
    fun `울린 적이 없으면 값을 그대로 넘기고 기다리지도 깨어나지도 않는다`() {
        val gate = SelfVibrationGate()
        for (t in 0L..1000L step 100) {
            assertEquals(LOUD, gate.read(t, LOUD), 0f)
            assertFalse(gate.holding)
            assertTrue(gate.lastReadClean)
            assertNull(gate.reopenAtMs(t))
        }
    }

    @Test
    fun `울림과 꼬리가 끝나기 전에 시작한 구간은 섞였고 그 뒤에 시작한 구간은 깨끗하다`() {
        val gate = SelfVibrationGate()
        gate.read(0, QUIET)
        gate.onPlayed(0, ON, GUARD) // 0~210ms 는 듣지 않는다
        gate.read(100, LOUD)
        assertFalse("울리는 중에 읽은 값이 깨끗하다", gate.lastReadClean)
        gate.read(212, LOUD) // (100, 212] 도 210 전에 시작했다
        assertFalse(gate.lastReadClean)
        assertEquals("꼬리 뒤에 시작한 구간은 그대로다", LOUD, gate.read(250, LOUD), 0f)
        assertTrue(gate.lastReadClean)
        assertFalse(gate.holding)
    }

    @Test
    fun `꼬리가 끝날 때 깨어나되 두 시계의 반올림만큼 늦게 깨어난다`() {
        val gate = SelfVibrationGate()
        gate.onPlayed(1000, ON, GUARD)
        assertEquals(1000 + ON + GUARD + HapticTuning.SELF_HEARING_REOPEN_SLACK_MS, gate.reopenAtMs(1000))
        assertEquals(1000 + ON + GUARD + HapticTuning.SELF_HEARING_REOPEN_SLACK_MS, gate.reopenAtMs(1200))
        assertNull("꼬리가 끝났으면 따로 깨어나지 않는다", gate.reopenAtMs(1000 + ON + GUARD))
    }

    @Test
    fun `묶음 전 쉼에서 소리가 들렸으면 섞인 값 자리에 그 크기를 돌려주고 판단을 기다린다`() {
        val gate = SelfVibrationGate()
        gate.read(0, LOUD)
        gate.onPlayed(0, ON, GUARD)
        assertEquals("이어 주기", LOUD, gate.read(100, 0.9f), 0f)
        assertTrue(gate.holding)
        assertEquals("이어 준 크기는 읽은 구간이 시작된 시각의 것이다", 0L, gate.levelAtMs)
        assertEquals(LOUD, gate.read(212, 0.9f), 0f)
        assertTrue(gate.holding)
        assertEquals(100L, gate.levelAtMs)
        gate.read(250, QUIET)
        assertEquals("깨끗한 값은 읽은 시각의 것이다", 250L, gate.levelAtMs)
    }

    @Test
    fun `이어 준 크기는 첫 울림의 꼬리가 끝나기 조금 전보다 늦은 소리로 치지 않는다`() {
        // 꼬리 끝(210) 바로 앞에서 시작한 구간도 0.08초 전(130)의 소리로 친다. 늦게 치면 느림이 소리가 끝난 뒤 한 번 더 울린다.
        val gate = SelfVibrationGate()
        gate.read(0, LOUD)
        gate.onPlayed(0, ON, GUARD)
        gate.read(100, 0.9f)
        assertEquals(0L, gate.levelAtMs)
        gate.read(205, 0.9f)
        assertEquals(100L, gate.levelAtMs)
        assertEquals("이어 주기", LOUD, gate.read(212, 0.9f), 0f)
        assertTrue(gate.holding)
        assertEquals(ON + GUARD - HapticTuning.SELF_HEARING_CREDIT_LEAD_MS, gate.levelAtMs)
    }

    @Test
    fun `묶음 전 쉼이 조용했으면 섞인 값은 0 이다`() {
        val gate = SelfVibrationGate()
        gate.read(0, QUIET)
        gate.onPlayed(0, ON, GUARD)
        assertEquals("자기 진동 소리를 소리로 보면 안 된다", 0f, gate.read(100, 0.9f), 0f)
        assertTrue("판단은 기다린다", gate.holding)
    }

    @Test
    fun `이어 주기는 바로 전 쉼에서 들은 것만 쓴다`() {
        val gate = SelfVibrationGate()
        gate.read(0, LOUD)
        gate.onPlayed(0, ON, GUARD)
        gate.read(100, 0.9f)
        gate.read(212, 0.9f)
        gate.read(250, QUIET) // 깨끗하고 조용한 쉼
        gate.onPlayed(250, ON, GUARD)
        assertEquals("앞 묶음 전의 큰 소리로 이 묶음을 이어 주면 안 된다", 0f, gate.read(350, 0.9f), 0f)
    }

    @Test
    fun `묶음 안에서 다시 보낸 울림은 듣지 않는 끝만 늘리고 이어 주기와 기다리기는 늘리지 않는다`() {
        val gate = SelfVibrationGate()
        gate.read(0, LOUD)
        gate.onPlayed(0, ON, GUARD) // 첫 울림의 끝 210
        gate.read(100, 0.9f)
        gate.onPlayed(150, 200, GUARD) // 방식이 바뀌어 새로 울렸다. 듣지 않는 끝은 460
        assertEquals("첫 울림 끝 전에 시작한 구간은 이어 준다", LOUD, gate.read(200, 0.9f), 0f)
        assertTrue(gate.holding)
        gate.read(220, 0.9f) // (200, 220] 도 첫 울림 끝(210) 전에 시작했다
        assertTrue(gate.holding)
        assertEquals("첫 울림 끝 뒤에 시작한 구간은 이어 주지 않는다", 0f, gate.read(300, 0.9f), 0f)
        assertFalse("기다리기도 첫 울림 끝까지다", gate.holding)
        assertFalse(gate.lastReadClean)
        assertEquals(460 + HapticTuning.SELF_HEARING_REOPEN_SLACK_MS, gate.reopenAtMs(300))
    }

    @Test
    fun `울림을 끊으면 꼬리만큼만 더 듣지 않는다`() {
        val gate = SelfVibrationGate()
        gate.read(0, LOUD)
        gate.onPlayed(0, 400, 180) // 느림: 끝 580
        gate.onCancelled(50)
        assertEquals(50 + 180 + HapticTuning.SELF_HEARING_REOPEN_SLACK_MS, gate.reopenAtMs(50))
        gate.read(100, 0.9f)
        assertTrue("끊은 뒤의 꼬리는 아직 섞였다", gate.holding)
        gate.read(232, 0.9f)
        assertEquals(QUIET, gate.read(300, QUIET), 0f)
        assertTrue(gate.lastReadClean)
    }

    @Test
    fun `깨어나는 시각이 늦어도 구간의 시작으로 기다린다`() {
        val gate = SelfVibrationGate()
        gate.read(0, LOUD)
        gate.onPlayed(0, ON, GUARD) // 끝 210
        gate.read(200, 0.9f)
        // 꼬리 끝(212)에 깨어나야 했는데 300 에 깨어났다. (200, 300] 은 210 전에 시작했으므로 증거가 없다.
        gate.read(300, QUIET)
        assertTrue("늦게 깨어났다고 소리가 끝난 것으로 보면 안 된다", gate.holding)
        assertEquals(QUIET, gate.read(350, QUIET), 0f)
        assertFalse(gate.holding)
    }

    @Test
    fun `미리보기 진동도 그 꼬리까지 듣지 않지만 판단은 기다리지 않는다`() {
        val gate = SelfVibrationGate()
        gate.read(0, QUIET)
        gate.onOtherVibration(2000)
        assertEquals(2000 + HapticTuning.SELF_HEARING_GUARD_MAX_MS + HapticTuning.SELF_HEARING_REOPEN_SLACK_MS, gate.reopenAtMs(100))
        assertEquals(0f, gate.read(1000, 0.9f), 0f)
        assertFalse(gate.lastReadClean)
        assertFalse("앱이 울린 묶음이 아니면 기다리지 않는다", gate.holding)
    }

    @Test
    fun `미리보기를 일찍 멈추면 듣지 않는 구간도 준다`() {
        val gate = SelfVibrationGate()
        gate.read(0, QUIET)
        gate.onOtherVibration(2000) // 2초 미리보기
        assertEquals(2000 + HapticTuning.SELF_HEARING_GUARD_MAX_MS + HapticTuning.SELF_HEARING_REOPEN_SLACK_MS, gate.reopenAtMs(100))
        gate.onOtherVibration(300) // 300ms 에 멈췄다(HapticPreviewGate 는 멈춘 시각을 남긴다)
        val end = 300 + HapticTuning.SELF_HEARING_GUARD_MAX_MS
        assertEquals(end + HapticTuning.SELF_HEARING_REOPEN_SLACK_MS, gate.reopenAtMs(400))
        gate.read(end + 2, 0.9f)
        assertEquals("멈춘 미리보기의 꼬리 뒤에는 다시 듣는다", QUIET, gate.read(end + 50, QUIET), 0f)
        assertTrue(gate.lastReadClean)
    }

    @Test
    fun `미리보기 시각을 알려도 앱이 울린 진동의 듣지 않는 구간은 줄지 않는다`() {
        val gate = SelfVibrationGate()
        gate.read(0, QUIET)
        gate.onPlayed(0, 400, 180) // 느림: 580 까지
        gate.onOtherVibration(0) // 미리보기는 없다(HapticPreviewGate.busyUntilMs 의 처음 값)
        assertEquals(580 + HapticTuning.SELF_HEARING_REOPEN_SLACK_MS, gate.reopenAtMs(100))
    }
}

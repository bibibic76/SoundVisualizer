package com.example.soundvisualizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 보호된 소리 안내를 띄울지 정하는 규칙.
 *
 * 이 판단이 틀리면 둘 중 하나가 일어난다. 띄우지 않으면 청각장애 사용자는 앱이 고장 난 줄 알고,
 * 잘못 띄우면 멀쩡한 앱을 탓하게 된다. 그래서 "정말 확실할 때만 띄운다" 를 여러 각도로 확인한다.
 */
class BlockedCaptureNoticeTest {

    private val hold = BlockedCaptureNotice.HOLD_MS
    private val clear = BlockedCaptureNotice.CLEAR_MS

    /** 무음(0)만 받으며 [ms] 만큼 재생이 이어진 것으로 친다. 마지막으로 넣은 시각을 돌려준다. */
    private fun BlockedCaptureNotice.playSilently(from: Long, ms: Long): Long {
        var now = from
        val until = from + ms
        while (now <= until) {
            onTick(nowMs = now, canJudge = true, mediaPlaying = true, level = 0f)
            now += TICK
        }
        return now - TICK
    }

    @Test
    fun `처음에는 안내가 없다`() {
        assertFalse(BlockedCaptureNotice().isBlocked)
    }

    @Test
    fun `재생 중인데 무음만 들어오면 버티는 시간 뒤에 안내한다`() {
        val notice = BlockedCaptureNotice()
        val start = 10_000L
        notice.playSilently(from = start, ms = hold - TICK)
        assertFalse("아직 버티는 시간을 채우지 못했다", notice.isBlocked)
        assertTrue(
            "버티는 시간을 채운 틱에서 바뀐다",
            notice.onTick(start + hold, canJudge = true, mediaPlaying = true, level = 0f)
        )
        assertTrue(notice.isBlocked)
    }

    @Test
    fun `안내가 바뀐 틱에서만 true 를 돌려준다`() {
        // 틱마다 알림을 다시 올리면 상태 표시줄이 계속 깜빡인다.
        val notice = BlockedCaptureNotice()
        notice.playSilently(from = 0, ms = hold)
        assertTrue(notice.isBlocked)
        assertFalse("이미 띄운 뒤에는 그대로", notice.onTick(hold + TICK, canJudge = true, mediaPlaying = true, level = 0f))
        assertFalse("계속 그대로", notice.onTick(hold + 60_000, canJudge = true, mediaPlaying = true, level = 0f))
    }

    @Test
    fun `진짜 조용할 때는 안내하지 않는다`() {
        // 폰이 아무것도 재생하지 않으면 받을 소리 자체가 없다. 아무리 오래 기다려도 뜨면 안 된다.
        val notice = BlockedCaptureNotice()
        var now = 0L
        repeat(100) {
            assertFalse(notice.onTick(now, canJudge = true, mediaPlaying = false, level = 0f))
            now += TICK
        }
        assertFalse(notice.isBlocked)
    }

    @Test
    fun `소리를 받고 있으면 안내하지 않는다`() {
        val notice = BlockedCaptureNotice()
        var now = 0L
        repeat(100) {
            assertFalse(notice.onTick(now, canJudge = true, mediaPlaying = true, level = 0.5f))
            now += TICK
        }
        assertFalse(notice.isBlocked)
    }

    @Test
    fun `소리가 다시 들어오면 안내를 바로 내린다`() {
        val notice = BlockedCaptureNotice()
        val end = notice.playSilently(from = 0, ms = hold)
        assertTrue(notice.isBlocked)
        assertTrue(
            "받은 첫 소리에서 바로 내린다",
            notice.onTick(end + TICK, canJudge = true, mediaPlaying = true, level = 0.3f)
        )
        assertFalse(notice.isBlocked)
    }

    @Test
    fun `아주 작은 소리 한 번에도 세던 것을 버린다`() {
        // 눈에 보이지도 않을 만큼 작아도 받은 것은 받은 것이다. 그 앱을 탓할 수 없다.
        val level = BlockedCaptureNotice.SILENCE_LEVEL * 2
        val notice = BlockedCaptureNotice()
        assertTrue(notice.hasSound(level))
        notice.playSilently(from = 0, ms = hold - TICK)
        notice.onTick(hold, canJudge = true, mediaPlaying = true, level = level)
        notice.playSilently(from = hold + TICK, ms = hold - TICK * 2)
        assertFalse("작은 소리로 끊겼으니 처음부터 다시 버텨야 한다", notice.isBlocked)
    }

    @Test
    fun `무음 기준 이하는 못 받은 것으로 본다`() {
        // 막힌 앱은 정확히 0 을 주지만, 0 하고만 비교하면 떠도는 한 샘플에도 안내가 영영 뜨지 않는다.
        val notice = BlockedCaptureNotice()
        assertFalse(notice.hasSound(0f))
        assertFalse(notice.hasSound(BlockedCaptureNotice.SILENCE_LEVEL))
        notice.playSilently(from = 0, ms = hold)
        assertTrue(notice.isBlocked)
    }

    @Test
    fun `판단할 수 없는 상황이면 세던 것을 버린다`() {
        // 볼륨 0, 통화 중, 헤드셋 연결 등. 무음의 이유가 따로 있으면 그 앱 탓을 할 수 없다.
        val notice = BlockedCaptureNotice()
        notice.playSilently(from = 0, ms = hold - TICK)
        notice.onTick(hold, canJudge = false, mediaPlaying = true, level = 0f)
        notice.playSilently(from = hold + TICK, ms = hold - TICK * 2)
        assertFalse("끊겼으니 처음부터 다시 센다", notice.isBlocked)
        // 다시 세기 시작한 시각(hold + TICK)에서 hold 를 채우면 그때 뜬다.
        assertTrue("다시 버티면 뜬다", notice.onTick(hold * 2 + TICK, canJudge = true, mediaPlaying = true, level = 0f))
    }

    @Test
    fun `판단할 수 없게 되면 떠 있던 안내도 바로 내린다`() {
        // 안내 중에 전화가 오거나 헤드셋을 꽂으면 그때부터는 아무 말도 할 수 없다.
        val notice = BlockedCaptureNotice()
        val end = notice.playSilently(from = 0, ms = hold)
        assertTrue(notice.isBlocked)
        assertTrue(notice.onTick(end + TICK, canJudge = false, mediaPlaying = true, level = 0f))
        assertFalse(notice.isBlocked)
    }

    @Test
    fun `재생이 잠깐 끊겨도 안내가 깜빡이지 않는다`() {
        // 다음 화로 넘어가는 1초 남짓한 틈마다 사라지면 읽을 수 없다.
        val notice = BlockedCaptureNotice()
        val end = notice.playSilently(from = 0, ms = hold)
        var now = end + TICK
        val until = end + clear
        while (now < until) {
            assertFalse("아직 내릴 때가 아니다", notice.onTick(now, canJudge = true, mediaPlaying = false, level = 0f))
            now += TICK
        }
        assertTrue(notice.isBlocked)
    }

    @Test
    fun `재생이 끝나면 안내를 내린다`() {
        val notice = BlockedCaptureNotice()
        val end = notice.playSilently(from = 0, ms = hold)
        var now = end + TICK
        var changed = false
        repeat((clear / TICK).toInt() + 2) {
            changed = changed || notice.onTick(now, canJudge = true, mediaPlaying = false, level = 0f)
            now += TICK
        }
        assertTrue("근거가 사라졌으므로 내린다", changed)
        assertFalse(notice.isBlocked)
    }

    @Test
    fun `재생이 다시 이어지면 안내를 그대로 둔다`() {
        // 잠깐 멈췄다 이어지는 경우다. 내렸다가 다시 버티게 하면 안내가 늦는다.
        val notice = BlockedCaptureNotice()
        val end = notice.playSilently(from = 0, ms = hold)
        notice.onTick(end + TICK, canJudge = true, mediaPlaying = false, level = 0f)
        notice.onTick(end + TICK * 2, canJudge = true, mediaPlaying = true, level = 0f)
        assertTrue(notice.isBlocked)
        // 다시 끊겨도 내리는 시간을 처음부터 센다.
        notice.onTick(end + TICK * 3, canJudge = true, mediaPlaying = false, level = 0f)
        assertTrue(notice.isBlocked)
    }

    @Test
    fun `내린 뒤 다시 막힌 앱으로 돌아가면 또 안내한다`() {
        val notice = BlockedCaptureNotice()
        var now = 0L
        repeat(3) { round ->
            now = notice.playSilently(from = now, ms = hold) + TICK
            assertTrue("${round + 1}번째 안내", notice.isBlocked)
            assertTrue(notice.onTick(now, canJudge = true, mediaPlaying = true, level = 0.4f))
            now += TICK
        }
    }

    @Test
    fun `reset 은 안내와 세던 것을 모두 버린다`() {
        val notice = BlockedCaptureNotice()
        notice.playSilently(from = 0, ms = hold)
        assertTrue("떠 있던 안내를 내렸으면 true", notice.reset())
        assertFalse(notice.isBlocked)
        assertFalse("두 번 불러도 바뀐 것이 없다", notice.reset())
        // 세던 것도 버려야, 화면을 켜 다시 시작한 캡처가 옛 시각 때문에 곧바로 안내하지 않는다.
        notice.playSilently(from = hold * 10, ms = hold - TICK)
        assertFalse(notice.isBlocked)
    }

    @Test
    fun `틱 간격이 달라도 시각으로만 판단한다`() {
        // 메인 스레드가 밀려 틱이 늦게 와도 6초는 6초다.
        val notice = BlockedCaptureNotice()
        assertFalse(notice.onTick(0, canJudge = true, mediaPlaying = true, level = 0f))
        assertFalse("한 틱만으로는 뜨지 않는다", notice.onTick(hold - 1, canJudge = true, mediaPlaying = true, level = 0f))
        assertTrue(notice.onTick(hold, canJudge = true, mediaPlaying = true, level = 0f))
    }

    @Test
    fun `기준값은 보수적으로 잡혀 있다`() {
        // 값을 바꿀 때 왜 그렇게 잡았는지 한 번 더 보게 남겨 둔다.
        assertTrue("무음 기준은 그리기·진동 기준(0.01)보다 낮다", BlockedCaptureNotice.SILENCE_LEVEL < 0.01f)
        assertTrue("0 하고만 비교하지는 않는다", BlockedCaptureNotice.SILENCE_LEVEL > 0f)
        assertTrue("잠깐 조용한 구간을 지나 보낼 만큼 길다", BlockedCaptureNotice.HOLD_MS >= 5_000L)
        assertTrue("사용자가 고장으로 여기기 전에는 뜬다", BlockedCaptureNotice.HOLD_MS <= 10_000L)
        assertTrue("내리는 시간은 버티는 시간보다 짧다", BlockedCaptureNotice.CLEAR_MS < BlockedCaptureNotice.HOLD_MS)
    }

    @Test
    fun `생성자로 기준을 바꿔 쓸 수 있다`() {
        val notice = BlockedCaptureNotice(silenceLevel = 0.1f, holdMs = 1_000L, clearMs = 500L)
        assertFalse("바꾼 기준 아래는 무음으로 본다", notice.hasSound(0.05f))
        assertFalse(notice.onTick(0, canJudge = true, mediaPlaying = true, level = 0.05f))
        assertTrue(notice.onTick(1_000, canJudge = true, mediaPlaying = true, level = 0.05f))
        assertTrue(notice.isBlocked)
    }

    private companion object {
        /** 캡처 서비스가 도는 확인 간격과 같게 둔다. */
        const val TICK = 500L
    }
}

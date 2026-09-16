package com.example.soundvisualizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 받지 못하고 있다는 안내를 띄울지 정하는 규칙.
 *
 * 이 판단이 틀리면 둘 중 하나가 일어난다. 띄우지 않으면 청각장애 사용자는 앱이 고장 난 줄 알고,
 * 잘못 띄우면 멀쩡한 앱을 탓하게 된다. 그래서 "정말 확실할 때만 띄운다" 를 여러 각도로 확인한다.
 */
class BlockedCaptureNoticeTest {

    private val hold = BlockedCaptureNotice.HOLD_MS
    private val clear = BlockedCaptureNotice.CLEAR_MS

    /** 아무것도 받지 못한 채 [ms] 만큼 재생이 이어진 것으로 친다. 마지막으로 넣은 시각을 돌려준다. */
    private fun BlockedCaptureNotice.playSilently(from: Long, ms: Long): Long {
        var now = from
        val until = from + ms
        while (now <= until) {
            onTick(nowMs = now, canJudge = true, mediaPlaying = true, peak = 0f, buffers = BUFFERS)
            now += TICK
        }
        return now - TICK
    }

    @Test
    fun `처음에는 안내가 없다`() {
        assertFalse(BlockedCaptureNotice().isBlocked)
    }

    @Test
    fun `재생 중인데 아무것도 못 받으면 버티는 시간 뒤에 안내한다`() {
        val notice = BlockedCaptureNotice()
        val start = 10_000L
        notice.playSilently(from = start, ms = hold - TICK)
        assertFalse("아직 버티는 시간을 채우지 못했다", notice.isBlocked)
        assertTrue(
            "버티는 시간을 채운 틱에서 바뀐다",
            notice.onTick(start + hold, canJudge = true, mediaPlaying = true, peak = 0f, buffers = BUFFERS)
        )
        assertTrue(notice.isBlocked)
    }

    @Test
    fun `안내가 바뀐 틱에서만 true 를 돌려준다`() {
        // 틱마다 알림을 다시 올리면 상태 표시줄이 계속 깜빡인다.
        val notice = BlockedCaptureNotice()
        notice.playSilently(from = 0, ms = hold)
        assertTrue(notice.isBlocked)
        assertFalse(
            "이미 띄운 뒤에는 그대로",
            notice.onTick(hold + TICK, canJudge = true, mediaPlaying = true, peak = 0f, buffers = BUFFERS)
        )
        assertFalse(
            "계속 그대로",
            notice.onTick(hold + 60_000, canJudge = true, mediaPlaying = true, peak = 0f, buffers = BUFFERS)
        )
    }

    @Test
    fun `재생이 잠깐 끊기면 버티던 시간을 처음부터 다시 센다`() {
        // 안내의 근거는 "끊기지 않고" 이어진 어긋남이다. 재생이 멈춘 구간은 받을 소리가 없었던 구간이라
        // 근거가 되지 못한다. 이 규칙이 빠지면 짧은 무음이 여러 번 쌓여, 소리를 잘 넘겨주던 앱에도
        // 언젠가는 안내가 뜬다. 피드를 넘기며 자동 재생 영상을 스치는 흔한 사용에서 바로 드러난다.
        val notice = BlockedCaptureNotice()
        val firstEnd = notice.playSilently(from = 0, ms = hold - TICK)
        assertFalse("아직 버티는 시간을 채우지 못했다", notice.isBlocked)

        // 재생이 한 틱 멈춘다 (다음 화로 넘어가기, 장면 전환).
        assertFalse(
            notice.onTick(firstEnd + TICK, canJudge = true, mediaPlaying = false, peak = 0f, buffers = BUFFERS)
        )

        // 다시 재생. 앞 구간을 그대로 세고 있었다면 여기서 곧바로 안내가 뜬다.
        val resume = firstEnd + 2 * TICK
        notice.playSilently(from = resume, ms = hold - TICK)
        assertFalse("끊긴 앞 구간은 세지 않는다", notice.isBlocked)

        assertTrue(
            "다시 시작한 시점부터 버티는 시간을 채우면 그때 안내한다",
            notice.onTick(resume + hold, canJudge = true, mediaPlaying = true, peak = 0f, buffers = BUFFERS)
        )
    }

    @Test
    fun `진짜 조용할 때는 안내하지 않는다`() {
        // 폰이 아무것도 재생하지 않으면 받을 소리 자체가 없다. 아무리 오래 기다려도 뜨면 안 된다.
        val notice = BlockedCaptureNotice()
        var now = 0L
        repeat(100) {
            assertFalse(notice.onTick(now, canJudge = true, mediaPlaying = false, peak = 0f, buffers = BUFFERS))
            now += TICK
        }
        assertFalse(notice.isBlocked)
    }

    @Test
    fun `소리를 받고 있으면 안내하지 않는다`() {
        val notice = BlockedCaptureNotice()
        var now = 0L
        repeat(100) {
            assertFalse(notice.onTick(now, canJudge = true, mediaPlaying = true, peak = 0.5f, buffers = BUFFERS))
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
            notice.onTick(end + TICK, canJudge = true, mediaPlaying = true, peak = 0.3f, buffers = BUFFERS)
        )
        assertFalse(notice.isBlocked)
    }

    @Test
    fun `아주 작은 소리 한 번에도 세던 것을 버린다`() {
        // 눈에 보이지도 않을 만큼 작아도 받은 것은 받은 것이다. 그 앱을 탓할 수 없다.
        val peak = BlockedCaptureNotice.SILENCE_LEVEL * 2
        val notice = BlockedCaptureNotice()
        assertTrue(notice.hasSound(peak))
        notice.playSilently(from = 0, ms = hold - TICK)
        notice.onTick(hold, canJudge = true, mediaPlaying = true, peak = peak, buffers = BUFFERS)
        notice.playSilently(from = hold + TICK, ms = hold - TICK * 2)
        assertFalse("작은 소리로 끊겼으니 처음부터 다시 버텨야 한다", notice.isBlocked)
    }

    @Test
    fun `볼륨을 낮춰 작게 들어온 소리는 받은 것으로 본다`() {
        // 우리가 받는 소리에는 미디어 볼륨이 곱해져 있다. 15단계 중 1~2단계에서는 보통의 영상도
        // 0.002 언저리로 들어오는데, 화면을 보려고 볼륨을 낮춰 두는 것은 이 앱 사용자에게 아주 흔하다.
        // 그 정도를 무음으로 치면 멀쩡한 앱이 누명을 쓴다.
        val notice = BlockedCaptureNotice()
        val quiet = 0.002f
        assertTrue("작은 볼륨으로 들어온 소리", notice.hasSound(quiet))
        var now = 0L
        repeat(((hold / TICK) * 3).toInt()) {
            assertFalse(notice.onTick(now, canJudge = true, mediaPlaying = true, peak = quiet, buffers = BUFFERS))
            now += TICK
        }
        assertFalse(notice.isBlocked)
    }

    @Test
    fun `아무것도 들어오지 않은 것만 못 받은 것으로 본다`() {
        // 막힌 앱의 소리는 볼륨과 상관없이 믹스에 섞이지 않아 정확히 0 으로 들어온다.
        // 0 하고만 비교하지 않는 것은 부동소수점 찌꺼기를 소리로 세지 않기 위해서다.
        val notice = BlockedCaptureNotice()
        assertFalse(notice.hasSound(0f))
        assertFalse(notice.hasSound(BlockedCaptureNotice.SILENCE_LEVEL))
        notice.playSilently(from = 0, ms = hold)
        assertTrue(notice.isBlocked)
    }

    @Test
    fun `버퍼가 오지 않으면 안내하지 않는다`() {
        // 우리 쪽 캡처가 멈춘 경우다(화면을 켠 뒤 오디오 서버가 버퍼를 다시 보내지 않는 등).
        // 조용한 원인이 우리에게 있는데 앱을 탓하면, 사용자는 엉뚱한 곳을 고치러 간다.
        val notice = BlockedCaptureNotice()
        var now = 0L
        repeat(((hold / TICK) * 3).toInt()) {
            assertFalse(notice.onTick(now, canJudge = true, mediaPlaying = true, peak = 0f, buffers = 0))
            now += TICK
        }
        assertFalse(notice.isBlocked)
    }

    @Test
    fun `버퍼가 끊기면 떠 있던 안내도 바로 내린다`() {
        // 안내를 뒷받침하던 근거(우리는 받고 있는데 소리가 없다)가 사라졌다.
        val notice = BlockedCaptureNotice()
        val end = notice.playSilently(from = 0, ms = hold)
        assertTrue(notice.isBlocked)
        assertTrue(notice.onTick(end + TICK, canJudge = true, mediaPlaying = true, peak = 0f, buffers = 0))
        assertFalse(notice.isBlocked)
    }

    @Test
    fun `판단할 수 없는 상황이면 세던 것을 버린다`() {
        // 볼륨 0, 통화 중 등. 무음의 이유가 따로 있으면 그 앱 탓을 할 수 없다.
        val notice = BlockedCaptureNotice()
        notice.playSilently(from = 0, ms = hold - TICK)
        notice.onTick(hold, canJudge = false, mediaPlaying = true, peak = 0f, buffers = BUFFERS)
        notice.playSilently(from = hold + TICK, ms = hold - TICK * 2)
        assertFalse("끊겼으니 처음부터 다시 센다", notice.isBlocked)
        // 다시 세기 시작한 시각(hold + TICK)에서 hold 를 채우면 그때 뜬다.
        assertTrue(
            "다시 버티면 뜬다",
            notice.onTick(hold * 2 + TICK, canJudge = true, mediaPlaying = true, peak = 0f, buffers = BUFFERS)
        )
    }

    @Test
    fun `판단할 수 없게 되면 떠 있던 안내도 바로 내린다`() {
        // 안내 중에 전화가 오거나 사용자가 볼륨을 0 으로 내리면 그때부터는 아무 말도 할 수 없다.
        val notice = BlockedCaptureNotice()
        val end = notice.playSilently(from = 0, ms = hold)
        assertTrue(notice.isBlocked)
        assertTrue(notice.onTick(end + TICK, canJudge = false, mediaPlaying = true, peak = 0f, buffers = BUFFERS))
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
            assertFalse(
                "아직 내릴 때가 아니다",
                notice.onTick(now, canJudge = true, mediaPlaying = false, peak = 0f, buffers = BUFFERS)
            )
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
            changed = changed ||
                notice.onTick(now, canJudge = true, mediaPlaying = false, peak = 0f, buffers = BUFFERS)
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
        notice.onTick(end + TICK, canJudge = true, mediaPlaying = false, peak = 0f, buffers = BUFFERS)
        notice.onTick(end + TICK * 2, canJudge = true, mediaPlaying = true, peak = 0f, buffers = BUFFERS)
        assertTrue(notice.isBlocked)
        // 다시 끊겨도 내리는 시간을 처음부터 센다.
        notice.onTick(end + TICK * 3, canJudge = true, mediaPlaying = false, peak = 0f, buffers = BUFFERS)
        assertTrue(notice.isBlocked)
    }

    @Test
    fun `내린 뒤 다시 막힌 앱으로 돌아가면 또 안내한다`() {
        val notice = BlockedCaptureNotice()
        var now = 0L
        repeat(3) { round ->
            now = notice.playSilently(from = now, ms = hold) + TICK
            assertTrue("${round + 1}번째 안내", notice.isBlocked)
            assertTrue(notice.onTick(now, canJudge = true, mediaPlaying = true, peak = 0.4f, buffers = BUFFERS))
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
        // 메인 스레드가 밀려 틱이 늦게 와도 15초는 15초다.
        val notice = BlockedCaptureNotice()
        assertFalse(notice.onTick(0, canJudge = true, mediaPlaying = true, peak = 0f, buffers = BUFFERS))
        assertFalse(
            "한 틱만으로는 뜨지 않는다",
            notice.onTick(hold - 1, canJudge = true, mediaPlaying = true, peak = 0f, buffers = BUFFERS)
        )
        assertTrue(notice.onTick(hold, canJudge = true, mediaPlaying = true, peak = 0f, buffers = BUFFERS))
    }

    @Test
    fun `기준값은 보수적으로 잡혀 있다`() {
        // 값을 바꿀 때 왜 그렇게 잡았는지 한 번 더 보게 남겨 둔다.
        assertTrue(
            "볼륨을 낮춰 작게 들어온 소리를 무음으로 치면 안 된다",
            BlockedCaptureNotice.SILENCE_LEVEL < 0.0001f
        )
        assertTrue("0 하고만 비교하지는 않는다", BlockedCaptureNotice.SILENCE_LEVEL > 0f)
        assertTrue(
            "피드의 자동 재생 영상을 몇 초 훑고 지나가는 것만으로는 뜨지 않는다",
            BlockedCaptureNotice.HOLD_MS >= 10_000L
        )
        assertTrue("사용자가 고장으로 여기기 전에는 뜬다", BlockedCaptureNotice.HOLD_MS <= 20_000L)
        assertTrue("내리는 시간은 버티는 시간보다 짧다", BlockedCaptureNotice.CLEAR_MS < BlockedCaptureNotice.HOLD_MS)
    }

    @Test
    fun `생성자로 기준을 바꿔 쓸 수 있다`() {
        val notice = BlockedCaptureNotice(silenceLevel = 0.1f, holdMs = 1_000L, clearMs = 500L)
        assertFalse("바꾼 기준 아래는 무음으로 본다", notice.hasSound(0.05f))
        assertFalse(notice.onTick(0, canJudge = true, mediaPlaying = true, peak = 0.05f, buffers = BUFFERS))
        assertTrue(notice.onTick(1_000, canJudge = true, mediaPlaying = true, peak = 0.05f, buffers = BUFFERS))
        assertTrue(notice.isBlocked)
    }

    private companion object {
        /** 캡처 서비스가 도는 확인 간격과 같게 둔다. */
        const val TICK = 500L

        /** 그 0.5초 동안 도착하는 버퍼 수 (11.6ms 짜리 버퍼 기준). */
        const val BUFFERS = 43
    }
}

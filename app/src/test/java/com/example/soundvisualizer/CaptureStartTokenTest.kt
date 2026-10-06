package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 사용자가 켜지 않은 캡처 서비스 시작을 거르는 규칙과, 켜기를 누른 쪽이 고른 소스를 서비스까지 전하는 규칙.
 *
 * 통과시키면 안 되는 시작(옛 알림 버튼)은 Android 14 이상에서 프로세스를 죽인다. 거르면 안 되는 시작(실행 버튼)을
 * 거르면 startForegroundService 뒤에 포그라운드를 시작하지 않아 역시 시스템이 앱을 죽인다. 둘 다 여기서 고정한다.
 */
class CaptureStartTokenTest {

    private val internal = CaptureSource.InternalPlayback
    private val mic = CaptureSource.Microphone

    @Test
    fun `켜기를 누르지 않았으면 거른다`() {
        // 프로세스가 새로 떠서 서비스를 만든 경우. 표를 발급한 적이 없다.
        assertNull(CaptureStartToken().consume(nowMs = 1_000L))
    }

    @Test
    fun `방금 켜기를 눌렀으면 고른 소스로 통과시킨다`() {
        val token = CaptureStartToken()
        token.issue(nowMs = 1_000L, internal)
        assertEquals(internal, token.consume(nowMs = 1_050L))

        token.issue(nowMs = 2_000L, mic)
        assertEquals("외부 사운드 모드로 켰으면 마이크로 받는다", mic, token.consume(nowMs = 2_050L))
    }

    @Test
    fun `같은 표로 두 번 통과시키지 않는다`() {
        val token = CaptureStartToken()
        token.issue(nowMs = 1_000L, mic)
        assertEquals(mic, token.consume(nowMs = 1_050L))

        assertNull("내려간 뒤 다른 시작이 지난 표로 뜨면 안 된다", token.consume(nowMs = 1_100L))
    }

    @Test
    fun `받아 주는 시간이 끝날 때까지는 통과시킨다`() {
        val token = CaptureStartToken()

        token.issue(nowMs = 1_000L, internal)

        assertEquals(internal, token.consume(nowMs = 1_000L + CaptureStartToken.MAX_AGE_MS))
    }

    @Test
    fun `오래된 표는 거른다`() {
        // 띄우다 실패해 남은 표. 한참 뒤의 다른 시작을 통과시키면 안 된다.
        val token = CaptureStartToken()

        token.issue(nowMs = 1_000L, mic)

        assertNull(token.consume(nowMs = 1_000L + CaptureStartToken.MAX_AGE_MS + 1))
    }

    @Test
    fun `시계가 거꾸로 가면 거른다`() {
        val token = CaptureStartToken()

        token.issue(nowMs = 5_000L, internal)

        assertNull(token.consume(nowMs = 4_000L))
    }

    @Test
    fun `다시 누르면 새 표로 바뀐다`() {
        val token = CaptureStartToken()
        token.issue(nowMs = 1_000L, internal)

        token.issue(nowMs = 30_000L, mic)

        assertEquals("처음 누른 것은 오래됐지만 방금 다시 눌렀다", mic, token.consume(nowMs = 30_050L))
    }
}

package com.example.soundvisualizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 꺼짐 안내를 홈 탭으로 옮기는 규칙.
 *
 * 알림을 꺼 둔 사용자에게는 홈의 안내가 무엇이 꺼졌는지 아는 유일한 통로다. 한 번은 반드시 보여줘야 하고,
 * 그 뒤에는 보던 탭에 남을 수 있어야 한다. 두 요구가 부딪히므로 "안내 하나에 한 번"을 여기서 고정한다.
 */
class StopNoticeRoutingTest {

    @Test
    fun `안내가 없으면 옮기지 않는다`() {
        assertFalse(StopNoticeRouting().shouldShowOnHome(null))
    }

    @Test
    fun `안내 하나에 한 번만 옮긴다`() {
        val routing = StopNoticeRouting()

        assertTrue("첫 안내", routing.shouldShowOnHome(1))
        assertFalse("같은 안내로 다시 돌아옴", routing.shouldShowOnHome(1))
        assertFalse(routing.shouldShowOnHome(1))
    }

    @Test
    fun `안내를 닫은 뒤 또 꺼지면 다시 옮긴다`() {
        val routing = StopNoticeRouting()
        assertTrue(routing.shouldShowOnHome(1))

        // 닫거나 다시 켜면 안내가 사라진다. 그동안은 옮길 일이 없다.
        assertFalse(routing.shouldShowOnHome(null))
        // 또 꺼지면 새 번호가 붙는다. 같은 이유로 꺼져도 마찬가지다.
        assertTrue("다음 안내", routing.shouldShowOnHome(2))
    }

    @Test
    fun `타일로 설정을 열면 그 안내로는 옮기지 않는다`() {
        val routing = StopNoticeRouting()

        routing.skipCurrent(1)

        assertFalse("설정을 열러 온 사용자를 홈으로 끌어내지 않는다", routing.shouldShowOnHome(1))
        assertTrue("그다음 안내는 옮긴다", routing.shouldShowOnHome(2))
    }

    @Test
    fun `안내가 없을 때 타일로 들어와도 다음 안내는 옮긴다`() {
        val routing = StopNoticeRouting()

        routing.skipCurrent(0) // 안내가 없으면 번호는 아직 0 이다.

        assertTrue(routing.shouldShowOnHome(1))
    }

    @Test
    fun `화면을 다시 만들어도 이미 옮긴 안내는 다시 옮기지 않는다`() {
        val routing = StopNoticeRouting()
        assertTrue(routing.shouldShowOnHome(3))

        // 화면 회전·언어 변경으로 다시 만들어질 때는 저장한 번호로 되살린다.
        val restored = StopNoticeRouting(routing.routedSeq)

        assertFalse(restored.shouldShowOnHome(3))
        assertTrue(restored.shouldShowOnHome(4))
    }
}

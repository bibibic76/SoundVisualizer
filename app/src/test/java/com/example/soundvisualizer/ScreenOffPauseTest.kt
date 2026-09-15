package com.example.soundvisualizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 화면이 꺼진 동안 캡처·AI·진동을 쉬는 상태.
 *
 * 쉬기와 다시 켜기는 AudioRecord 를 멈추고 다시 시작하는 일이라 짝이 맞아야 한다.
 * 두 번 쉬면 이미 멈춘 캡처를 또 멈추고, 쉬지 않았는데 다시 켜면 돌고 있는 캡처 스레드 옆에 하나를 더 띄운다.
 */
class ScreenOffPauseTest {

    @Test
    fun `처음에는 쉬지 않는다`() {
        assertFalse(ScreenOffPause().isPaused)
    }

    @Test
    fun `설정이 켜져 있으면 화면이 꺼질 때 쉬고 켜질 때 다시 켠다`() {
        val pause = ScreenOffPause()
        assertTrue("꺼짐 → 쉬기", pause.onScreenOff(pauseEnabled = true))
        assertTrue(pause.isPaused)
        assertTrue("켜짐 → 다시 켜기", pause.onScreenOn())
        assertFalse(pause.isPaused)
    }

    @Test
    fun `설정이 꺼져 있으면 화면이 꺼져도 계속 돈다`() {
        val pause = ScreenOffPause()
        assertFalse(pause.onScreenOff(pauseEnabled = false))
        assertFalse(pause.isPaused)
        assertFalse("쉬지 않았으니 켜질 때 할 일이 없다", pause.onScreenOn())
    }

    @Test
    fun `쉬지 않았는데 화면이 켜지면 아무것도 하지 않는다`() {
        // 서비스가 켜진 뒤 첫 방송이 켜짐일 수 있다.
        assertFalse(ScreenOffPause().onScreenOn())
    }

    @Test
    fun `꺼짐이 두 번 와도 한 번만 쉰다`() {
        val pause = ScreenOffPause()
        assertTrue(pause.onScreenOff(pauseEnabled = true))
        assertFalse("이미 쉬는 중", pause.onScreenOff(pauseEnabled = true))
        assertTrue(pause.onScreenOn())
        assertFalse("켜짐이 두 번 와도 한 번만 다시 켠다", pause.onScreenOn())
    }

    @Test
    fun `쉬는 중에 설정이 꺼져도 화면이 켜지면 다시 켠다`() {
        // 다시 켜지 않으면 캡처가 멈춘 채 "실행 중"으로 남는다.
        val pause = ScreenOffPause()
        assertTrue(pause.onScreenOff(pauseEnabled = true))
        assertFalse("설정이 꺼진 채 꺼짐이 또 와도 상태는 그대로", pause.onScreenOff(pauseEnabled = false))
        assertTrue(pause.isPaused)
        assertTrue(pause.onScreenOn())
    }

    @Test
    fun `다시 켠 뒤 화면이 또 꺼지면 다시 쉰다`() {
        val pause = ScreenOffPause()
        repeat(3) { round ->
            assertTrue("${round + 1}번째 꺼짐", pause.onScreenOff(pauseEnabled = true))
            assertTrue("${round + 1}번째 켜짐", pause.onScreenOn())
        }
    }

    @Test
    fun `꺼지는 순간의 설정을 따른다`() {
        val pause = ScreenOffPause()
        assertFalse("설정 꺼짐 상태로 꺼짐", pause.onScreenOff(pauseEnabled = false))
        assertFalse(pause.onScreenOn())
        assertTrue("설정을 켠 뒤 다음 꺼짐부터 쉰다", pause.onScreenOff(pauseEnabled = true))
    }
}

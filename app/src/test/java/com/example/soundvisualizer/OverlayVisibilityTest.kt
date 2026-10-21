package com.example.soundvisualizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayVisibilityTest {

    private fun visible() = OverlayVisibility.initial(interactive = true, keyguardLocked = false)

    /** 화면이 꺼졌다가 켜져, 잠금이 풀리기를 기다리는 상태. */
    private fun waiting() = visible().apply {
        onScreenOff()
        onScreenOn()
    }

    @Test
    fun `화면이 꺼지면 가려진다`() {
        val v = visible()
        v.onScreenOff()
        assertTrue(v.covered)
        assertFalse("화면이 꺼진 동안은 잠금을 확인할 필요가 없다", v.waitingForUnlock)
    }

    @Test
    fun `화면이 켜져도 잠금 풀림 방송이 올 때까지 가려진 채 기다린다`() {
        // #401: 잠금 화면이 창을 숨긴 동안, 또는 잠금이 풀리는 도중에 새 프레임을 요청하면 잠금을 풀어도 그리지 않았다.
        // 화면이 켜지는 순간에는 잠금이 이미 풀려 보여도 다시 보이게 하지 않는다.
        val v = waiting()
        assertTrue(v.covered)
        assertTrue(v.waitingForUnlock)

        assertTrue(v.onUnlocked())
        assertFalse(v.covered)
        assertFalse(v.waitingForUnlock)
    }

    @Test
    fun `잠금 확인은 두 번 연달아 풀려 있어야 다시 보이게 한다`() {
        val v = waiting()
        assertFalse("잠금 화면인데 다시 보이게 했다", v.onLockCheck(keyguardLocked = true))
        assertFalse("풀리는 도중일 수 있는데 바로 다시 보이게 했다", v.onLockCheck(keyguardLocked = false))
        assertTrue(v.covered)
        assertTrue("잠금 풀림 방송을 놓쳐도 다시 보여야 한다", v.onLockCheck(keyguardLocked = false))
        assertFalse(v.covered)
        assertFalse(v.waitingForUnlock)
    }

    @Test
    fun `잠금 확인 중에 다시 잠기면 처음부터 센다`() {
        val v = waiting()
        v.onLockCheck(keyguardLocked = false)
        v.onLockCheck(keyguardLocked = true)
        assertFalse(v.onLockCheck(keyguardLocked = false))
        assertTrue(v.covered)
    }

    @Test
    fun `잠금이 풀리기 전에 다시 꺼지면 잠금 확인을 멈춘다`() {
        val v = waiting()
        v.onLockCheck(keyguardLocked = false)
        v.onScreenOff()
        assertTrue(v.covered)
        assertFalse(v.waitingForUnlock)
        assertFalse("기다리지 않는데 잠금 확인으로 다시 보이게 했다", v.onLockCheck(keyguardLocked = false))
    }

    @Test
    fun `이미 보이는 중이어도 잠금 풀림이 오면 다시 보이게 한다`() {
        // 잠금이 풀려도 창이 숨김으로 남는 기기가 있어(#401), 잠금 풀림마다 창 배치를 다시 요청해야 한다.
        assertTrue(visible().onUnlocked())
    }

    @Test
    fun `띄우는 순간의 화면과 잠금 상태를 따른다`() {
        val off = OverlayVisibility.initial(interactive = false, keyguardLocked = true)
        assertTrue(off.covered)
        assertFalse(off.waitingForUnlock)

        val locked = OverlayVisibility.initial(interactive = true, keyguardLocked = true)
        assertTrue(locked.covered)
        assertTrue(locked.waitingForUnlock)

        val open = OverlayVisibility.initial(interactive = true, keyguardLocked = false)
        assertFalse(open.covered)
        assertFalse(open.waitingForUnlock)
    }
}

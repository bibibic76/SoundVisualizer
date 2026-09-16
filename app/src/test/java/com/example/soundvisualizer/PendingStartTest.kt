package com.example.soundvisualizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 오버레이 권한을 켜고 돌아왔을 때 실행을 이어가는 규칙.
 *
 * 이어가지 않으면 허용하고 돌아온 사용자가 실행을 한 번 더 눌러야 한다. 반대로 아무 때나 이어가면
 * 부탁하지도 않은 화면 녹화 동의 창이 뜬다. 두 요구가 부딪히므로 "사용자가 누른 실행 한 번만"을 여기서 고정한다.
 */
class PendingStartTest {

    @Test
    fun `실행을 누르지 않았으면 이어가지 않는다`() {
        // 앱을 새로 열거나, 화면을 돌리거나, 타일에서 돌아온 경우.
        assertFalse(PendingStart().consumeOnResume(granted = true))
    }

    @Test
    fun `허용하고 돌아오면 이어간다`() {
        val pending = PendingStart()

        pending.awaitPermission()

        assertTrue(pending.consumeOnResume(granted = true))
    }

    @Test
    fun `이어간 실행은 다시 이어가지 않는다`() {
        val pending = PendingStart()
        pending.awaitPermission()
        assertTrue(pending.consumeOnResume(granted = true))

        assertFalse("다른 앱에 갔다 돌아올 때마다 켜지면 안 된다", pending.consumeOnResume(granted = true))
    }

    @Test
    fun `허용하지 않고 돌아오면 이어가지 않고 기다림도 끝낸다`() {
        val pending = PendingStart()
        pending.awaitPermission()

        assertFalse(pending.consumeOnResume(granted = false))
        assertFalse("나중에 허용해도 그때 뜬금없이 켜지지 않는다", pending.consumeOnResume(granted = true))
    }

    @Test
    fun `실행 종료를 누르면 기다리던 실행을 버린다`() {
        val pending = PendingStart()
        pending.awaitPermission()

        pending.cancel()

        assertFalse(pending.consumeOnResume(granted = true))
    }

    @Test
    fun `화면을 돌려 다시 만들어져도 기다리던 실행은 이어간다`() {
        val pending = PendingStart()
        pending.awaitPermission()

        // 권한 화면에 있는 동안 액티비티가 다시 만들어지면 저장한 값으로 되살린다.
        val restored = PendingStart(pending.isPending)

        assertTrue(restored.consumeOnResume(granted = true))
    }
}

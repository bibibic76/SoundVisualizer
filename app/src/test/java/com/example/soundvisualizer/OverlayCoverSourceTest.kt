package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 오버레이가 가려진 동안 쉬고, 잠금이 풀리면 창을 다시 보이게 하는 연결(#401, #433). 창과 시스템 방송이 있어야 해서 JVM 에서
 * 돌릴 수 없으므로 소스에서 확인한다([UnlockReceiverSourceTest] 와 같은 방식). 언제 가려졌다고 볼지는 OverlayVisibilityTest,
 * 가려졌을 때 엔진이 하는 일은 VisualizerEngineTest 가 본다.
 */
class OverlayCoverSourceTest {

    private fun code(name: String): List<String> =
        File("src/main/java/com/example/soundvisualizer/$name").readText(Charsets.UTF_8)
            .split('\n')
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.contains("/**") }
            .filterNot { it.startsWith("import ") }

    private val service = code("OverlayService.kt")
    private val overlay = code("VisualizerOverlay.kt")

    private fun registrationOf(receiver: String): String {
        val lines = service.filter { it.contains("registerReceiver(") && !it.contains("unregisterReceiver(") && it.contains(receiver) }
        assertEquals("$receiver 를 등록하는 줄", 1, lines.size)
        return lines.single()
    }

    /** [start] 가 든 줄부터 다음 빈 줄 전까지. */
    private fun blockAfter(lines: List<String>, start: String): List<String> {
        val from = lines.indexOfFirst { it.contains(start) }
        assertTrue("'$start' 가 없다", from >= 0)
        return lines.drop(from).takeWhile { it.isNotBlank() }
    }

    @Test
    fun `잠금 풀림은 내보낸 수신기로 받고 화면 켜짐 꺼짐은 내보내지 않은 수신기로 받는다`() {
        val unlock = registrationOf("unlockReceiver")
        assertTrue(unlock.contains("ACTION_USER_PRESENT"))
        assertTrue(unlock.contains("RECEIVER_EXPORTED"))
        assertFalse("시스템 UI 가 보내는 방송은 내보내지 않은 수신기에 오지 않는다(#385)", unlock.contains("RECEIVER_NOT_EXPORTED"))
        assertTrue(registrationOf("screenReceiver").contains("RECEIVER_NOT_EXPORTED"))
        assertTrue(service.any { it.contains("unregisterReceiver(screenReceiver)") })
        assertTrue(service.any { it.contains("unregisterReceiver(unlockReceiver)") })
    }

    @Test
    fun `잠금이 풀리면 창 배치를 다시 요청한다`() {
        // 잠금 풀림 방송과, 방송을 놓쳤을 때의 잠금 확인 둘 다다. S25+ 는 이것이 없으면 잠금을 풀어도 창을 숨긴 채 둔다.
        assertTrue(blockAfter(service, "val unlockReceiver").any { it.contains("visibility.onUnlocked()") && it.contains("showAgain()") })
        assertTrue(blockAfter(service, "val unlockCheck").any { it.contains("visibility.onLockCheck(") && it.contains("showAgain()") })
        assertTrue(blockAfter(service, "private fun showAgain()").any { it.contains("updateViewLayout(") })
    }

    @Test
    fun `가려지면 프레임을 요청하기 전에 쉬고 다시 보이면 쌓인 소리 크기를 버린다`() {
        // 그리는 중에 가려지면 다음 프레임을 요청하기 전에 쉰다. 숨겨진 창의 요청은 S25+ 에서 잠금을 풀어도 끝나지 않았다.
        val sleep = overlay.indexOfFirst { it.contains("covered.value") && it.contains("engine.sleepNow()") }
        val frame = overlay.indexOfFirst { it.contains("withFrameNanos {") }
        assertTrue("가려졌는지 보기 전에 프레임을 요청한다", sleep in 0 until frame)

        // 쉬는 동안 가려지면 다시 보일 때까지 확인하지 않고, 다시 보이면 가려진 동안 쌓인 소리 크기부터 버린다.
        val rest = blockAfter(overlay, "if (covered.value) {")
        val wait = rest.indexOfFirst { it.contains("covered.first { !it }") }
        val uncovered = rest.indexOfFirst { it.contains("engine.onUncovered()") }
        assertTrue("가려진 동안 기다리지 않는다", wait >= 0)
        assertTrue("다시 보인 뒤 가려진 동안 쌓인 소리 크기를 버리지 않는다(#433)", uncovered > wait)
    }
}

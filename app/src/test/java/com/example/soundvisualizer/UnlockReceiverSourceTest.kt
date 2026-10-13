package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 잠금을 풀면 잠금 화면의 위협음 알림을 치우는 방송(USER_PRESENT)을 실제로 받는지(#385). 서비스와 시스템 방송이 있어야 해서
 * JVM 에서 돌릴 수 없으므로 소스에서 확인한다([com.example.soundvisualizer.feedback.HapticSourceContractTest] 와 같은 방식).
 *
 * 이 방송은 시스템 UI 앱이 보내서 RECEIVER_NOT_EXPORTED 수신기에는 오지 않는다. v1.7.0 까지는 화면 켜짐·꺼짐과 한 수신기에
 * 묶여 있어, 잠금을 풀어도 알림이 남았다.
 */
class UnlockReceiverSourceTest {

    private val service = File("src/main/java/com/example/soundvisualizer/AudioCaptureService.kt").readText(Charsets.UTF_8)
        .split('\n')
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.contains("/**") }

    private fun registrationOf(receiver: String): String {
        val lines = service.filter { it.contains("registerReceiver(") && !it.contains("unregisterReceiver(") && it.contains(receiver) }
        assertEquals("$receiver 를 등록하는 줄", 1, lines.size)
        return lines.single()
    }

    @Test
    fun `잠금 풀림은 화면 켜짐 꺼짐과 따로 내보낸 수신기로 받는다`() {
        val unlock = registrationOf("unlockReceiver")
        assertTrue(unlock.contains("ACTION_USER_PRESENT"))
        assertTrue(unlock.contains("RECEIVER_EXPORTED"))
        assertFalse("시스템 UI 가 보내는 방송은 내보내지 않은 수신기에 오지 않는다", unlock.contains("RECEIVER_NOT_EXPORTED"))

        assertTrue(registrationOf("screenReceiver").contains("RECEIVER_NOT_EXPORTED"))
        assertFalse(
            "화면 켜짐·꺼짐 수신기에 넣으면 잠금 풀림이 오지 않는다",
            service.any { it.contains("addAction(Intent.ACTION_USER_PRESENT)") }
        )
    }

    @Test
    fun `내려갈 때 두 수신기를 모두 푼다`() {
        assertTrue(service.any { it.contains("unregisterReceiver(screenReceiver)") })
        assertTrue(service.any { it.contains("unregisterReceiver(unlockReceiver)") })
    }
}

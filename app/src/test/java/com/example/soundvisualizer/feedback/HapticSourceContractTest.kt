package com.example.soundvisualizer.feedback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 진동 알림과 플레이어가 지켜야 할 순서와 규칙. Context 와 진동기가 있어야 만들 수 있어 JVM 에서 돌릴 수 없으므로
 * 소스에서 확인한다([HapticLevelSourceTest] 와 같은 방식).
 */
class HapticSourceContractTest {

    private fun code(path: String): String =
        File("src/main/java/com/example/soundvisualizer/$path").readText(Charsets.UTF_8)
            .split('\n')
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.contains("/**") }
            .joinToString("\n")

    private val notifier = code("feedback/HapticNotifier.kt")
    private val player = code("feedback/HapticPlayer.kt")

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature 가 없다", start >= 0)
        var depth = 0
        var i = source.indexOf('{', start)
        val open = i
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        return source.substring(open)
    }

    @Test
    fun `진동 스레드는 배경 우선순위가 아니다`() {
        assertTrue(notifier.contains("THREAD_PRIORITY_DISPLAY"))
        assertFalse("배경 우선순위는 렌더·AI 가 바쁠 때 틱이 밀린다", notifier.contains("THREAD_PRIORITY_BACKGROUND"))
    }

    @Test
    fun `틱은 절대 시각으로 잡는다`() {
        assertTrue(notifier.contains("postAtTime("))
        assertFalse("postDelayed 는 틱마다 조금씩 밀린다", notifier.contains("postDelayed("))
    }

    @Test
    fun `멈출 때는 표시를 내리고 스레드를 기다린 뒤 플레이어를 닫는다`() {
        val stop = body(notifier, "fun stop()")
        val order = listOf("running = false", "quitSafely", ".join(", "player.close()").map { stop.indexOf(it) }
        assertTrue("stop() 순서가 어긋났다: $order", order.all { it >= 0 } && order == order.sorted())
    }

    @Test
    fun `계획은 한 곳에서만 보내고 멈춘 뒤와 미리보기 중에는 보내지 않는다`() {
        assertTrue("playPlan 을 여러 곳에서 부른다", notifier.split("player.playPlan(").size == 2)
        val issue = body(notifier, "private fun issue(")
        assertTrue(issue.contains("player.playPlan("))
        assertTrue("issue 가 멈춘 뒤를 먼저 거르지 않는다", issue.trimStart('{').trimStart().startsWith("if (!running) return"))
        assertTrue(issue.contains("HapticPreviewGate.busyUntilMs"))
    }

    @Test
    fun `라벨 공급자는 마지막 인자다`() {
        val ctor = notifier.substring(notifier.indexOf("class HapticNotifier("), notifier.indexOf(") {", notifier.indexOf("class HapticNotifier(")))
        assertTrue("labelSource 가 마지막 인자가 아니다", ctor.trimEnd().endsWith("private val labelSource: () -> String?"))
    }

    @Test
    fun `진동은 잠금 안에서 닫혔는지 본 뒤에만 울린다`() {
        val vibrate = body(player, "private fun vibrate(")
        val sync = vibrate.indexOf("synchronized(lock)")
        val closed = vibrate.indexOf("if (closed)")
        val calls = Regex("\\.vibrate\\(").findAll(vibrate).map { it.range.first }.toList()
        assertTrue(sync >= 0 && closed > sync)
        assertTrue("vibrate 가 잠금 밖에 있다", calls.isNotEmpty() && calls.all { it > closed })
        assertTrue("vibrate() 는 한 함수 안에서만 부른다", Regex("\\.vibrate\\(").findAll(player).count() == calls.size)
        assertTrue(body(player, "fun close()").contains("synchronized(lock)"))
    }

    @Test
    fun `알람 용도는 꺼짐 알림에만 쓰고 반복 효과는 쓰지 않는다`() {
        assertTrue(player.contains("USAGE_ACCESSIBILITY"))
        assertTrue(player.contains("USAGE_ASSISTANCE_ACCESSIBILITY"))
        assertTrue(Regex("USAGE_ALARM").findAll(player).count() == 2) // 새 API 와 옛 API 한 번씩
        assertFalse(player.contains("createRepeatingEffect"))
        for (line in player.lines().filter { it.contains("createWaveform(") }) {
            assertTrue("반복하는 파형: $line", line.contains("NO_REPEAT)"))
        }
    }

    @Test
    fun `꺼짐 알림 앞에서 미리보기를 멈춘다`() {
        val alert = code("StopAlert.kt")
        val stop = alert.indexOf("HapticPreviewGate.stopPreview()")
        val play = alert.indexOf("playStoppedAlert()")
        assertTrue("미리보기를 멈추지 않거나 알림 뒤에 멈춘다", stop in 0 until play)
    }
}

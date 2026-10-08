package com.example.soundvisualizer.feedback

import org.junit.Assert.assertEquals
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
    fun `첫 틱은 handler 를 둔 뒤에 보낸다`() {
        // 틱은 handler 로만 다음 틱을 잡는다. 첫 틱을 먼저 보내면 진동 스레드가 그 틈에 먼저 돌 때 틱이 끊겨 진동이 멈춘다(#232).
        val start = body(notifier, "fun start()")
        val assign = start.indexOf("handler = ")
        val post = start.indexOf(".post(tick)")
        assertTrue("start() 가 handler 를 두기 전에 첫 틱을 보낸다", assign >= 0 && post > assign)
        assertFalse("also 블록 안에서 보내면 handler 를 두기 전에 보낸다", start.contains(".also"))
    }

    @Test
    fun `미리보기가 진동기를 잡은 동안에는 보내지 않고 보낸 연속 울림을 잊는다`() {
        // 미리보기가 끊은 연속 울림을 아직 울리는 중으로 알면, 미리보기가 끝난 뒤에도 그 울림이 끝날 때까지 조용하다(#232).
        val tick = body(notifier, "override fun run()")
        val gate = tick.indexOf("HapticPreviewGate.busyUntilMs")
        assertTrue("틱이 미리보기가 진동기를 잡았는지 보지 않는다", gate >= 0)
        val forget = tick.indexOf("driver.onPreempted(", gate)
        val send = tick.indexOf("issue(", gate)
        assertTrue("미리보기 중에 보낸 울림을 잊지 않는다", forget > gate)
        assertTrue("미리보기를 확인하기 전에 보낸다", send > forget && tick.indexOf("issue(") == send)
    }

    @Test
    fun `진동기에는 한 곳에서만 보내고 멈춘 뒤에는 보내지 않는다`() {
        assertTrue("playPlan 을 여러 곳에서 부른다", notifier.split("player.playPlan(").size == 2)
        assertTrue("cancel 을 여러 곳에서 부른다", notifier.split("player.cancel(").size == 2)
        val issue = body(notifier, "private fun issue(")
        assertTrue(issue.contains("player.playPlan("))
        assertTrue(issue.contains("player.cancel("))
        assertTrue("issue 가 멈춘 뒤를 먼저 거르지 않는다", issue.trimStart('{').trimStart().startsWith("if (!running) return"))
    }

    @Test
    fun `진동은 AI 가 아니라 캡처와 함께 켜진다`() {
        // AI 모델을 못 불러온 실행에서도 큰 소리에는 울려야 한다(#225). 캡처 서비스는 Context 가 있어야 만들 수 있다.
        val service = code("AudioCaptureService.kt")
        assertFalse("AI 를 시작할 때 진동 알림을 만든다", body(service, "private fun startAiLocked(").contains("HapticNotifier("))
        assertTrue("캡처를 시작할 때 진동 알림을 켜지 않는다", body(service, "private fun startCaptureLoop(").contains("startHaptics()"))
        val start = body(service, "private fun startHaptics(")
        assertTrue("AI 를 쓸 수 없는 실행을 진동 알림에 알리지 않는다", start.contains("unlabeledAlerts = { !SettingsManager.aiAvailable.value }"))
        assertTrue("오버레이와 다른 곳에서 라벨을 읽는다", start.contains("AiClassification.latest()"))
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
        val cancel = body(player, "fun cancel()")
        assertTrue("cancel 이 닫힌 뒤에도 끊는다", cancel.indexOf("if (closed)") in 0 until cancel.indexOf(".cancel()"))
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
    fun `화면이 꺼져도 계속 들으면 끊긴 연속 진동을 다시 보낸다`() {
        // 사용자가 화면을 끄면 안드로이드가 울리던 진동을 끊고 앱에는 알리지 않는다. 연속 울림이 30초라
        // 그대로 두면 최대 30초 조용하다(#288).
        val screenOff = body(notifier, "fun onScreenOff()")
        assertTrue("진동 스레드에서 보낸 울림을 잊지 않는다", screenOff.contains("handler?.post") && screenOff.contains("driver.onPreempted()"))
        assertTrue("멈춘 뒤에도 driver 를 건드린다", screenOff.contains("if (running)"))

        val service = body(code("AudioCaptureService.kt"), "private fun onScreenOff()")
        val pause = service.indexOf("pauseForScreenOff()")
        val notify = service.indexOf("hapticNotifier }?.onScreenOff()")
        assertTrue("쉬지 않는 갈래에서 진동 알림에 알리지 않는다", pause >= 0 && notify > pause && service.contains("} else {"))
    }

    @Test
    fun `꺼짐 알림 앞에서 미리보기를 멈춘다`() {
        val alert = code("StopAlert.kt")
        val stop = alert.indexOf("HapticPreviewGate.stopPreview()")
        val play = alert.indexOf("playStoppedAlert()")
        assertTrue("미리보기를 멈추지 않거나 알림 뒤에 멈춘다", stop in 0 until play)
    }

    /** [source] 에서 [start] 로 시작하는 호출의 괄호 안 전체. */
    private fun call(source: String, start: String): String {
        val from = source.indexOf(start)
        assertTrue("$start 가 없다", from >= 0)
        var depth = 0
        var i = source.indexOf('(', from)
        while (i < source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return source.substring(from, i + 1)
            }
            i++
        }
        return source.substring(from)
    }

    @Test
    fun `외부 사운드 모드에서는 섞인 값을 걸러 판단하고 꼬리 끝에 깨어난다`() {
        // 크기는 틱마다 한 번만 읽는다(#174). 문이 그 값을 거른 뒤 판단에 넘긴다(#290).
        // 한 단어만 바뀌어도(peak 를 넘기거나 configFor 를 쓰면) 고리가 되살아나는데, 루프 시뮬레이션은 이 배선을 따로
        // 흉내 내므로 잡지 못한다. 그래서 여기서 고정한다.
        val tick = body(notifier, "override fun run()")
        assertEquals(1, tick.split("AudioEngine.takeHapticPeak()").size - 1)
        val take = tick.indexOf("AudioEngine.takeHapticPeak()")
        val other = tick.indexOf("gate?.onOtherVibration(HapticPreviewGate.busyUntilMs)")
        val read = tick.indexOf("gate?.read(now, peak) ?: peak")
        val decide = tick.indexOf("policy.onTick(")
        assertTrue("미리보기 진동을 크기를 거르기 전에 문에 알리지 않는다", other in 0 until read)
        assertTrue("문이 크기를 읽은 뒤, 판단 전에 거르지 않는다", take in 0 until read && read < decide)
        val onTick = call(tick, "policy.onTick(")
        assertTrue("판단에 거른 크기를 넘기지 않는다: $onTick", onTick.contains("label, level, tickConfig,"))
        assertTrue("판단에 기다리기를 넘기지 않는다", onTick.contains("holding = gate?.holding == true"))
        assertTrue("판단에 크기가 들린 시각을 넘기지 않는다", onTick.contains("levelAtMs = gate?.levelAtMs ?: now"))
        assertTrue(
            "꼬리가 끝날 때 깨어나지 않는다",
            tick.contains("minOf(driver.nextWakeMs(now), gate?.reopenAtMs(now) ?: Long.MAX_VALUE)")
        )
    }

    @Test
    fun `외부 사운드 모드에서만 문을 두고 종류마다 상한을 건다`() {
        assertTrue(notifier.contains("private val selfGate: SelfVibrationGate? = if (hearsOwnVibration) SelfVibrationGate() else null"))
        // 틱은 라벨마다 그 라벨의 상한을 건 설정을 읽는다(#354). 종류를 모르는 큰 소리도 위협음 라벨로 설정을 읽으므로
        // 위협음의 상한을 따른다. 폰 안의 소리는 저장된 설정 그대로다. 판단이 이 설정을 읽는지는 위 테스트가 고정한다.
        val tickConfig = notifier.substring(notifier.indexOf("private val tickConfig"))
        assertTrue(
            "외부 사운드 모드에서만 라벨마다 상한을 걸지 않는다",
            tickConfig.lineSequence().take(2).joinToString(" ")
                .contains("if (hearsOwnVibration) { label -> configFor(label).inExternalSound(label) } else configFor")
        )
        val issue = body(notifier, "private fun issue(")
        assertTrue("울림의 꼬리 여유를 그 방식대로 주지 않는다", call(issue, "selfGate?.onPlayed(").contains("HapticTuning.selfHearingGuardMs("))
    }

    @Test
    fun `보낸 울림과 끊기를 문에 알린다`() {
        val issue = body(notifier, "private fun issue(")
        val play = issue.indexOf("player.playPlan(")
        val played = issue.indexOf("onPlayed(")
        val failed = issue.indexOf("driver.onPreempted()")
        assertTrue("보낸 울림을 문에 알리지 않거나 보내기 전에 알린다", played > play)
        assertTrue("보내지 못한 울림까지 문에 알린다", played < failed)
        val cancel = issue.indexOf("player.cancel(")
        assertTrue("끊은 것을 문에 알리지 않는다", issue.indexOf("onCancelled(", cancel) > cancel)
    }

    @Test
    fun `외부 사운드 모드인지는 그 실행의 소스가 정한다`() {
        val start = body(code("AudioCaptureService.kt"), "private fun startHaptics(")
        assertTrue(start.contains("hearsOwnVibration = captureSource.hearsOwnVibration"))
    }
}

package com.example.soundvisualizer.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 깨끗한 AI 입력(#352)의 배선. 캡처 서비스·진동 알림·미리보기는 Context 와 오디오·진동기가 있어야 만들 수 있어 JVM 에서 돌릴 수
 * 없으므로 소스에서 확인한다([HapticSourceContractTest] 와 같은 방식). 필터와 규칙 자체는 [AiFeedFilterTest]·[AiLabelFreshnessTest]·
 * [SelfVibrationTimelineTest]·[com.example.soundvisualizer.CaptureClockTest] 가, 고리 전체는 [AiFeedLoopTest] 가 본다.
 *
 * 폰 안의 소리(외부 사운드 모드 꺼짐)는 아무것도 바뀌지 않아야 한다. 그 갈래가 지금까지의 코드 그대로인지도 여기서 고정한다.
 */
class AiFeedSourceContractTest {

    private fun code(path: String): String =
        File("src/main/java/com/example/soundvisualizer/$path").readText(Charsets.UTF_8)
            .split('\n')
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.contains("/**") }
            .joinToString("\n")

    private val service = code("AudioCaptureService.kt")
    private val notifier = code("feedback/HapticNotifier.kt")
    private val preview = code("feedback/HapticPreviewGate.kt")

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

    /** [parts] 가 [source] 에 이 차례로 한 번씩 나오는지. */
    private fun assertInOrder(message: String, source: String, vararg parts: String) {
        var at = -1
        for (part in parts) {
            val next = source.indexOf(part, at + 1)
            assertTrue("$message: '$part' 가 없거나 앞의 것보다 먼저 나온다", next > at)
            at = next
        }
    }

    @Test
    fun `캡처 루프는 외부 사운드 모드에서만 시각을 세고 AI 입력을 거른다`() {
        val loop = body(service, "private fun captureLoop(")
        assertTrue(loop.contains("val clock = if (hearsOwnVibration) CaptureClock(record.sampleRate) else null"))
        assertTrue(loop.contains("val aiFeed = if (hearsOwnVibration) {"))
        assertTrue(call(loop, "AiFeedFilter(").contains("SelfVibrationTimeline.shared::snapshot"))
        assertEquals("필터를 한 곳에서만 만든다", 1, loop.split("AiFeedFilter(").size - 1)
        val start = body(service, "private fun startCaptureLoop(")
        assertTrue("외부 사운드 모드인지를 그 실행의 소스가 정하지 않는다", start.contains("val hearsOwnVibration = captureSource.hearsOwnVibration"))
        assertTrue(start.contains("captureLoop(record, dualMono, hearsOwnVibration, debug)"))
    }

    @Test
    fun `AI 에 넣기 직전에 거르고 오버레이와 진동은 원래 버퍼를 읽는다`() {
        val loop = body(service, "private fun captureLoop(")
        assertInOrder(
            "캡처 루프의 차례",
            loop,
            "record.read(",
            "SystemClock.elapsedRealtimeNanos()",
            "AudioEngine.pushAudioBuffer(buffer, floats, levelGain)",
            "OverlayWake.onBuffer()",
            "clock?.onRead(floats / 2, readNs, pollTimestamp)",
            "aiPipeline?.let",
            "floatView.get(aiScratch, 0, floats)",
            "aiFeed.process(aiScratch, floats, startMs, clock.endExtraMs)",
            "pipeline.ingestInterleavedPcm(aiScratch, floats,"
        )
        // 프레임 번호가 맞으려면 AI 가 아직 없어도 버퍼마다 센다.
        assertTrue("시각을 AI 가 있을 때만 센다", loop.indexOf("clock?.onRead(") < loop.indexOf("aiPipeline?.let"))
        assertTrue(loop.contains("record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME)"))
        assertEquals("AI 에 넣는 곳이 하나가 아니다", 1, loop.split("ingestInterleavedPcm(").size - 1)
        assertEquals("필터를 부르는 곳이 하나가 아니다", 1, loop.split("aiFeed.process(").size - 1)
    }

    @Test
    fun `거르다 예외가 나면 캡처를 멈추지 않고 그 캡처가 끝날 때까지 AI 에 원래 소리를 넘긴다`() {
        val loop = body(service, "private fun captureLoop(")
        // 실패 표시는 캡처 루프의 지역 변수다. 다음 캡처(화면이 다시 켜질 때 등)는 새 필터로 다시 거른다.
        assertInOrder("실패 표시는 루프 앞에서 한 번 세운다", loop, "var aiFeedFailed = false", "while (isRecording) {")
        assertEquals("실패 표시를 도중에 되돌린다", 1, loop.split("aiFeedFailed = false").size - 1)
        assertInOrder(
            "거르는 차례",
            loop,
            "floatView.get(aiScratch, 0, floats)",
            "if (aiFeed != null && clock != null && !aiFeedFailed) {",
            "try {",
            "aiFeed.process(aiScratch, floats, startMs, clock.endExtraMs)",
            "} catch (e: Exception) {",
            "pipeline.ingestInterleavedPcm(aiScratch, floats,"
        )
        val handler = body(loop, "} catch (e: Exception) {")
        assertInOrder(
            "실패하면 한 번 남기고, 반쯤 고친 버퍼 대신 원래 소리를 다시 옮긴다",
            handler,
            "aiFeedFailed = true",
            "Log.e(TAG, ",
            "floatView.position(0)",
            "floatView.get(aiScratch, 0, floats)"
        )
        assertTrue("예외를 함께 남기지 않는다", call(handler, "Log.e(").endsWith(", e)"))
        assertFalse("실패해도 AI 에 넣지 않거나 캡처를 멈춘다", handler.contains("return") || handler.contains("break") || handler.contains("throw"))
    }

    @Test
    fun `진동은 외부 사운드 모드에서만 쉬는 AI 의 라벨을 버리고 폰 안의 소리는 그대로 읽는다`() {
        val start = body(service, "private fun startHaptics(")
        assertTrue(start.contains("val freshness = if (captureSource.hearsOwnVibration) AiLabelFreshness() else null"))
        val external = start.substring(start.indexOf("freshness.label("))
        assertTrue(external.startsWith("freshness.label(SystemClock.elapsedRealtime(), AiClassification.latest())"))
        assertFalse(
            "쉬는 AI 의 null 을 지난 라벨로 메운다",
            external.substringBefore('\n').contains("?:")
        )
        assertTrue("폰 안의 소리의 라벨이 바뀌었다", start.contains("{ AiClassification.latest()?.coarse }"))
        assertTrue(
            "외부 사운드 모드에서만 구간을 알리지 않는다",
            start.contains("selfVibrationTimeline = if (captureSource.hearsOwnVibration) SelfVibrationTimeline.shared else null")
        )
    }

    @Test
    fun `잠금 화면 알림도 외부 사운드 모드에서만 쉬는 AI 의 라벨을 버린다`() {
        val alert = body(service, "private fun updateDangerAlert(")
        assertEquals("결과를 한 번만 읽지 않는다(#361)", 1, alert.split("AiClassification.latest()").size - 1)
        assertTrue(alert.contains("val label = if (captureSource.hearsOwnVibration) {"))
        assertTrue(alert.contains("alertFreshness.label(now, result) ?: AiClassification.AMBIENT"))
        assertTrue("폰 안의 소리의 라벨이 바뀌었다", alert.contains("result?.coarse ?: AiClassification.AMBIENT"))
        val tick = call(alert, "dangerAlertPolicy.onTick(")
        assertTrue("알릴지를 고른 라벨로 정하지 않는다: $tick", tick.contains("label,") && !tick.contains("coarse"))
        assertTrue("알림 이름을 같은 결과에서 고르지 않는다", alert.contains("DangerAlert.show(uiContext, result?.let { dangerSoundName(it) })"))
        assertInOrder("새 캡처는 지난 결과를 잊는다", body(service, "private fun startBlockedCheck("), "dangerAlertPolicy.reset()", "alertFreshness.reset()")
    }

    @Test
    fun `진동 알림은 보내기 전에 구간을 열고 보낸 시각으로 고치며 실패하면 지운다`() {
        val issue = body(notifier, "private fun issue(")
        assertInOrder(
            "울림을 보내는 차례",
            issue,
            "HapticTuning.aiFeedGuardMs(mode)",
            "selfVibrationTimeline?.open(",
            "player.playPlan(command.plan)",
            "val sent = SystemClock.elapsedRealtime()",
            "selfVibrationTimeline?.setEnd(feedWindow, sent + duration + feedGuard, sent + duration)",
            "selfGate?.onPlayed(sent, duration, HapticTuning.selfHearingGuardMs(mode))",
            "} else {",
            "selfVibrationTimeline?.retract(feedWindow)",
            "driver.onPreempted()"
        )
        val open = call(issue, "selfVibrationTimeline?.open(")
        assertTrue("AI 입력의 꼬리 여유가 아니다: $open", open.contains("feedGuard") && !open.contains("selfHearingGuardMs"))
        assertTrue(open.contains("beforeSend - HapticTuning.AI_FEED_PRE_MS"))
        // 구간은 모터를 멈춘 뒤의 시각으로 끊는다. 틱이 시작된 시각(now)으로 끊으면 그 사이에 돈 모터 소리를 덜 덮는다.
        assertInOrder(
            "끊으면 구간도 끊는다",
            issue,
            "player.cancel()",
            "selfGate?.onCancelled(now)",
            "selfVibrationTimeline?.cutLatest(SystemClock.elapsedRealtime())"
        )
        assertTrue(notifier.contains("private val selfVibrationTimeline: SelfVibrationTimeline? = null"))
        // 틱(라벨·크기·판단)은 그대로다. 쉬는 AI 의 라벨은 라벨 공급자가 거른다.
        assertFalse(body(notifier, "override fun run()").contains("selfVibrationTimeline"))
    }

    @Test
    fun `미리보기도 보내기 전에 구간을 열고 멈추면 끊는다`() {
        val play = body(preview, "fun play(")
        assertInOrder(
            "미리보기의 차례",
            play,
            "timeline.open(",
            "if (!p.playPlan(plan)) {",
            "timeline.retract(window)",
            "return",
            "feedWindow = window"
        )
        val open = call(play, "timeline.open(")
        assertTrue(open.contains("plan.durationMs + MARGIN_MS + HapticTuning.SELF_HEARING_GUARD_MAX_MS"))
        assertInOrder(
            "놓을 때 구간을 끊는다",
            body(preview, "private fun release("),
            "busyUntilMs = SystemClock.elapsedRealtime()",
            "SelfVibrationTimeline.shared.cut(feedWindow, busyUntilMs)",
            "feedWindow = 0L"
        )
    }
}

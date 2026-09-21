package com.example.soundvisualizer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.soundvisualizer.ai.AiClassificationResult
import com.example.soundvisualizer.ai.AiFrontendMode
import com.example.soundvisualizer.ai.YamnetCoarseClassifier
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 개발자 모드 기록이 **실제로 파일에 닿는지** (#193, #195).
 *
 * 줄을 만드는 규칙은 `AiDebugCsvTest`·`AiDebugLogWriterTest` 가 기기 없이 지킨다. 여기서 보는 것은
 * 안드로이드에 붙는 부분이다 — 파일이 생기는지, 쓰는 스레드가 실제로 쓰는지, 끄면 닫히고 다시 켜면
 * 새 파일이 열리는지.
 *
 * 이 부분이 깨지면 조용히 깨진다. 파일이 안 열려도 앱은 멀쩡히 돌고 기록만 비어 있어서, 채점할 때
 * "이 구간에는 결과가 없었다" 로 읽힌다.
 */
@RunWith(AndroidJUnit4::class)
class AiDebugRecorderInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val logDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, AiDebugRecorder.DIR_NAME)

    private fun result(timestampMs: Long, coarse: String = "danger") = AiClassificationResult(
        coarse = coarse,
        display = "Gunshot, gunfire",
        confidence = 0.5f,
        gunshotScore = 0.8f,
        top5 = listOf(YamnetCoarseClassifier.TopClassHit(1, "Gunshot, gunfire", 0.5f)),
        gunshotEvidence = 0.2f,
        boosterReason = "booster_accepted",
        dangerCuePromoted = false,
        boosterAvailable = true,
        preBoosterCoarse = "ambient",
        boosterAccepted = true,
        meetsThreshold = true,
        useBoosterDangerPreview = false,
        timestampMs = timestampMs,
        preprocessMs = 1.0,
        yamnetMs = 2.0,
        boosterMs = 0.5,
        totalMs = 3.5,
        frontendMode = AiFrontendMode.CURRENT,
        boosterEnabled = true
    )

    /**
     * 조건이 될 때까지 짧게 기다린다. 쓰는 스레드가 따로 돌기 때문에 필요하다.
     * 고정 sleep 은 느린 기기에서 깜빡이고 빠른 기기에서는 시간을 버린다.
     */
    private fun waitFor(what: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("$what: ${timeoutMs}ms 안에 일어나지 않았다")
    }

    private fun files(): List<File> = logDir.listFiles()?.sortedBy { it.name } ?: emptyList()

    @Before
    fun clean() {
        AiDebugRecorder.stop()
        logDir.deleteRecursively()
    }

    @Test
    fun 켜지_않으면_폴더도_만들지_않는다() {
        // 기본이 꺼짐이라, 기록을 쓰지 않는 사용자의 기기에는 아무 흔적도 남지 않아야 한다.
        AiDebugRecorder.offer(result(1_000L), nowMs = 1_000L, level = 0.5f, shown = true)
        Thread.sleep(200)
        assertTrue("켜지 않았는데 폴더가 생겼다", !logDir.exists())
        assertNull(AiDebugRecorder.currentFile)
    }

    @Test
    fun 켜면_파일이_생기고_넘긴_줄이_바로_들어간다() {
        AiDebugRecorder.start(context)
        val file = AiDebugRecorder.currentFile
        assertNotNull("파일을 열지 못했다", file)
        assertTrue("이름이 ai-<날짜-시각>.csv 가 아니다: ${file!!.name}",
            Regex("""ai-\d{8}-\d{6}\.csv""").matches(file.name))

        AiDebugRecorder.offer(result(2_000L), nowMs = 2_050L, level = 0.42f, shown = true)
        // 줄마다 밀어 넣으므로, 끄지 않고도 파일에서 보여야 한다. 모아 두면 돌아가는 중에 받은 파일에
        // 최근 몇 초가 비어 그 구간을 "결과가 없었다" 로 읽게 된다.
        waitFor("넘긴 줄이 파일에 나타남") { file.readText().lines().size >= 2 }

        val lines = file.readText().trim().lines()
        assertEquals(AiDebugCsv.HEADER, lines[0])
        assertEquals(AiDebugCsv.row(result(2_000L), 2_050L, 0.42f, true), lines[1])
    }

    @Test
    fun 같은_결과를_두_번_넘겨도_한_줄만_남는다() {
        AiDebugRecorder.start(context)
        val file = AiDebugRecorder.currentFile!!
        val same = result(3_000L)
        AiDebugRecorder.offer(same, nowMs = 3_000L, level = 0.1f, shown = true)
        AiDebugRecorder.offer(same, nowMs = 3_100L, level = 0.1f, shown = true)
        AiDebugRecorder.offer(result(3_250L), nowMs = 3_250L, level = 0.1f, shown = true)
        waitFor("세 번 넘긴 것이 두 줄로 남음") { file.readText().trim().lines().size == 3 }

        Thread.sleep(300)   // 혹시 늦게 한 줄 더 들어오는지 본다
        assertEquals(3, file.readText().trim().lines().size)
    }

    @Test
    fun 끄면_닫히고_다시_켜면_새_파일에_쓴다() {
        AiDebugRecorder.start(context)
        val first = AiDebugRecorder.currentFile!!
        AiDebugRecorder.offer(result(4_000L), nowMs = 4_000L, level = 0.2f, shown = true)
        waitFor("첫 파일에 줄이 들어감") { first.readText().contains(AiDebugCsv.HEADER) }
        AiDebugRecorder.stop()

        // 파일 이름은 초 단위라, 같은 초에 다시 켜면 같은 이름이 된다. 실제 사용에서는 사람이 스위치를
        // 누르는 간격이라 겹치지 않지만, 테스트에서는 초가 넘어가기를 기다려 두 파일을 구분한다.
        Thread.sleep(1_100)
        AiDebugRecorder.start(context)
        val second = AiDebugRecorder.currentFile!!
        assertTrue("다시 켰는데 같은 파일에 이어 쓴다", first.name != second.name)

        AiDebugRecorder.offer(result(5_000L), nowMs = 5_000L, level = 0.3f, shown = false)
        waitFor("새 파일에 줄이 들어감") { second.readText().trim().lines().size >= 2 }

        // 앞 파일은 그대로 남아 있어야 한다. 회차마다 파일이 갈려야 채점이 섞이지 않는다.
        assertEquals(2, files().size)
        assertEquals(2, first.readText().trim().lines().size)
    }

    @Test
    fun 껐다_켜기를_되풀이해도_파일이_새지_않는다() {
        // 설정 스위치를 빠르게 누르는 경우. 스레드나 열린 파일이 쌓이면 여기서 드러난다.
        val opened = mutableListOf<File>()
        repeat(5) {
            AiDebugRecorder.start(context)
            // 끄면 currentFile 이 비므로, 끄기 전에 붙잡아 둔다.
            AiDebugRecorder.currentFile?.let(opened::add)
            AiDebugRecorder.offer(result(6_000L + it), nowMs = 6_000L + it, level = 0.1f, shown = true)
            AiDebugRecorder.stop()
        }
        assertEquals("켤 때마다 파일이 열리지 않았다", 5, opened.size)
        assertNull("끈 뒤에도 쓰고 있는 파일이 남았다", AiDebugRecorder.currentFile)
        // 같은 초 안에 다섯 번이면 파일 이름이 겹쳐 한 파일에 덮어써진다. 개수가 아니라 "쓰다 터지지
        // 않았는지" 와 "마지막 파일이 제대로 닫혔는지" 를 본다.
        val last = opened.last()
        waitFor("마지막 파일이 쓰이고 닫힘") { last.exists() && last.readText().contains(AiDebugCsv.HEADER) }
        // 스레드는 큐를 비우고 나가므로 곧바로는 아직 살아 있을 수 있다(큐 대기 시간 200ms).
        // 쌓이지 않는지를 보는 것이라, 잠깐 기다려 모두 사라지는지로 본다.
        waitFor("기록 스레드가 모두 끝남") {
            Thread.getAllStackTraces().keys.none { it.name == "ai-debug-record" && it.isAlive }
        }
    }

    @After
    fun stopAndClean() {
        AiDebugRecorder.stop()
        // 다음 테스트와 실제 사용에 남기지 않는다.
        Thread.sleep(300)
        logDir.deleteRecursively()
    }
}

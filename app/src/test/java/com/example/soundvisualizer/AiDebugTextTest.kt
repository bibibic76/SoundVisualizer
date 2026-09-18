package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiClassificationResult
import com.example.soundvisualizer.ai.AiFrontendMode
import com.example.soundvisualizer.ai.YamnetCoarseClassifier.TopClassHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 개발자 모드 오버레이의 글자 만들기.
 *
 * 여기서 고정하는 것은 전부 **실제로 일어나는 경우**다. NaN 점수, 빈 이름, 뒤로 간 시계,
 * 그리고 앱 언어 때문에 숫자가 아랍 숫자로 나오는 것까지.
 */
class AiDebugTextTest {

    private fun result(
        coarse: String = AiClassification.DANGER,
        display: String = "Gunshot, gunfire",
        confidence: Float = 0.41f,
        gunshotScore: Float = 0.83f,
        boosterAvailable: Boolean = true,
        preBoosterCoarse: String = AiClassification.AMBIENT,
        boosterAccepted: Boolean = true,
        meetsThreshold: Boolean = true,
        useBoosterDangerPreview: Boolean = false,
        timestampMs: Long = 1_000L,
        preprocessMs: Double = 12.0,
        yamnetMs: Double = 48.0,
        boosterMs: Double = 3.0,
        totalMs: Double = 65.0,
        top5: List<TopClassHit> = emptyList(),
        gunshotEvidence: Float = 0f,
        boosterReason: String = "",
        dangerCuePromoted: Boolean = false,
        frontendMode: AiFrontendMode = AiFrontendMode.CURRENT,
        boosterEnabled: Boolean = true
    ) = AiClassificationResult(
        coarse = coarse,
        display = display,
        confidence = confidence,
        gunshotScore = gunshotScore,
        boosterAvailable = boosterAvailable,
        preBoosterCoarse = preBoosterCoarse,
        boosterAccepted = boosterAccepted,
        meetsThreshold = meetsThreshold,
        useBoosterDangerPreview = useBoosterDangerPreview,
        timestampMs = timestampMs,
        preprocessMs = preprocessMs,
        yamnetMs = yamnetMs,
        boosterMs = boosterMs,
        totalMs = totalMs,
        top5 = top5,
        gunshotEvidence = gunshotEvidence,
        boosterReason = boosterReason,
        dangerCuePromoted = dangerCuePromoted,
        frontendMode = frontendMode,
        boosterEnabled = boosterEnabled
    )

    private fun hit(name: String, probability: Float) = TopClassHit(index = 0, name = name, probability = probability)

    private fun format(
        result: AiClassificationResult?,
        nowMs: Long = 1_200L,
        level: Float = 0.14f,
        shown: Boolean = true,
        aiAvailable: Boolean = true
    ) = AiDebugText.format(result, nowMs, level, shown, aiAvailable)

    @Test
    fun `종류와 이름과 확신도를 보여준다`() {
        val lines = format(result())

        assertEquals("DANGER", lines.coarse)
        assertEquals("Gunshot, gunfire", lines.label)
        assertEquals("0.41", lines.confidence)
        // 점 색은 원래 라벨로 고른다. 대문자로 바꾼 표시용 문자열을 쓰면 색을 못 찾는다.
        assertEquals(AiClassification.DANGER, lines.colorLabel)
    }

    @Test
    fun `모델이 말한 이름을 손대지 않는다`() {
        // 번역하거나 다듬으면 모델이 실제로 뭐라고 했는지 채점할 수 없다.
        assertEquals("Carnatic music", format(result(display = "Carnatic music")).label)
        assertEquals("Shuffling cards", format(result(display = "Shuffling cards")).label)
    }

    @Test
    fun `임계값과 부스터와 프리뷰를 보여준다`() {
        val lines = format(result(meetsThreshold = false, boosterAccepted = false, useBoosterDangerPreview = true))

        assertTrue(lines.detail, lines.detail.contains("thr N"))
        assertTrue(lines.detail, lines.detail.contains("pre ambient"))
        assertTrue(lines.detail, lines.detail.contains("bst N 0.83"))
        assertTrue(lines.detail, lines.detail.contains("prev Y"))
        // 어느 전처리를 썼는지는 detail 이 아니라 그 전처리 시간이 있는 줄에 둔다(#165).
        assertTrue(lines.timing, lines.timing.contains("path current"))
        assertFalse(lines.detail, lines.detail.contains("path"))
    }

    @Test
    fun `부스터 모델이 없으면 점수 자리에 NaN 을 띄우지 않는다`() {
        // boosterAvailable 이 false 면 gunshotScore 는 NaN 이라고 문서화돼 있다.
        val lines = format(result(boosterAvailable = false, gunshotScore = Float.NaN))

        assertTrue(lines.detail, lines.detail.contains("bst unavailable"))
        assertTrue("NaN 이 화면에 뜨면 안 된다: ${lines.detail}", !lines.detail.contains("NaN"))
    }

    @Test
    fun `선택한 Qualcomm frontend와 비활성화한 Booster를 구분해 보여준다`() {
        val lines = format(
            result(
                frontendMode = AiFrontendMode.QUALCOMM_SOURCE,
                boosterEnabled = false,
                boosterAvailable = false,
                gunshotScore = Float.NaN,
                boosterReason = "booster_disabled"
            )
        )

        assertTrue(lines.timing, lines.timing.contains("path qualcomm"))
        assertTrue(lines.detail, lines.detail.contains("bst disabled"))
        assertEquals("why booster_disabled   ev 0.00   cue N", lines.verdict)
    }

    @Test
    fun `확신도가 NaN 이면 값이 없다고 보여준다`() {
        assertEquals(AiDebugText.NONE, format(result(confidence = Float.NaN)).confidence)
    }

    @Test
    fun `확정된 이름이 없으면 비어 있다고 보여준다`() {
        // 임계값을 한 번도 넘지 못하면 확정 이름이 빈 문자열로 남는다.
        assertEquals("(none)", format(result(display = "")).label)
    }

    @Test
    fun `결과가 나온 뒤 흐른 시간을 보여준다`() {
        assertTrue(format(result(timestampMs = 1_000L), nowMs = 1_200L).detail.contains("age 0.2s"))
        assertTrue(format(result(timestampMs = 1_000L), nowMs = 4_500L).detail.contains("age 3.5s"))
    }

    @Test
    fun `오래된 결과의 나이는 짧게 쓴다`() {
        // 100초가 넘으면 소수점은 뜻이 없고 줄만 길어진다(#165). 경계에서 반올림으로 자리가 늘지 않는지도 본다.
        fun age(elapsedMs: Long) = format(result(timestampMs = 0L), nowMs = elapsedMs).detail.substringAfter("age ")
        assertEquals("99.9s", age(99_949L))
        assertEquals("100s", age(99_950L))
        assertEquals("999s", age(999_499L))
        assertEquals("17m", age(999_500L))
        assertEquals("60m", age(3_600_000L))
    }

    @Test
    fun `모든 줄이 HUD 한 줄에 들어간다`() {
        // 가장 긴 경우를 골라 만든다. 58자를 넘으면 폭 411dp 폰에서 두 줄로 꺾인다(#165).
        val longestName = "Livestock, farm animals, working animals"   // YAMNet 클래스 이름 중 가장 긴 것(40자)
        val worst = listOf(
            // 부스터 모델이 없고, 한참 조용해 결과가 오래된 경우. detail 이 가장 길다.
            result(
                coarse = AiClassification.AMBIENT, display = longestName, confidence = 0.41f,
                preBoosterCoarse = AiClassification.AMBIENT, boosterAvailable = false, gunshotScore = Float.NaN,
                meetsThreshold = false, timestampMs = 0L,
                frontendMode = AiFrontendMode.QUALCOMM_SOURCE,
                boosterReason = "blocked_speech_silence_or_low_conf score=0.1",
                preprocessMs = 400.0, yamnetMs = 800.0, boosterMs = 34.0, totalMs = 1234.0,
                top5 = List(5) { hit(longestName, 0.12f) }
            ),
            // 부스터를 일부러 끈 경우.
            result(display = longestName, boosterEnabled = false, boosterAvailable = false, gunshotScore = Float.NaN)
        )
        for (nowMs in listOf(200L, 99_949L, 999_499L, 59_999_999L)) {
            for (r in worst) {
                val lines = format(r, nowMs = nowMs, level = 1f)
                for (line in listOf(lines.detail, lines.verdict, lines.timing) + lines.top5) {
                    assertTrue("${line.length}자: $line", line.length <= AiDebugText.HUD_MAX_COLUMNS)
                }
                // 첫 줄은 종류·이름·확신도를 따로 그리고 사이에 간격(6dp·점 8dp·6dp·8dp = 28dp, 약 4.2자)이 있다.
                val firstRow = lines.coarse.length + lines.label.length + lines.confidence.length + 5
                assertTrue("첫 줄 약 ${firstRow}자", firstRow <= AiDebugText.HUD_MAX_COLUMNS)
            }
        }
    }

    @Test
    fun `시계가 뒤로 가도 나이가 음수로 보이지 않는다`() {
        // 벽시계라 NTP 보정으로 뒤로 갈 수 있다.
        assertTrue(format(result(timestampMs = 5_000L), nowMs = 1_000L).detail.contains("age 0.0s"))
    }

    @Test
    fun `판정 줄에는 이유 이름과 근거와 승격 여부를 보여준다`() {
        val lines = format(
            result(
                boosterReason = "game_mix_or_strong_danger score=0.5050 evidence=0.0000 adopt=False",
                gunshotEvidence = 0.07f,
                dangerCuePromoted = true
            )
        )

        // 이유 문자열 뒤에 붙어 오는 점수·근거는 bst·ev 와 겹치므로 이름만 쓴다.
        assertEquals("why game_mix_or_strong_danger   ev 0.07   cue Y", lines.verdict)
        assertFalse(lines.detail, lines.detail.contains("why"))
        assertFalse(lines.detail, lines.detail.contains("score="))
    }

    @Test
    fun `판정 이유가 없으면 값이 없다고 보여준다`() {
        assertEquals("why -   ev 0.00   cue N", format(result(boosterReason = "")).verdict)
    }

    @Test
    fun `top-5 를 한 줄에 하나씩 순위와 함께 보여준다`() {
        val lines = format(result(top5 = listOf(hit("Music", 0.6f), hit("Siren", 0.08f))))

        assertEquals(
            listOf(
                "1 " + "Music".padEnd(AiDebugText.TOP5_NAME_WIDTH) + " 0.60",
                "2 " + "Siren".padEnd(AiDebugText.TOP5_NAME_WIDTH) + " 0.08"
            ),
            lines.top5
        )
    }

    @Test
    fun `긴 이름은 칸에 맞춰 줄여 확률 자리가 흔들리지 않는다`() {
        val lines = format(
            result(top5 = listOf(hit("Vehicle horn, car horn, honking", 0.10f), hit("Air horn, truck horn", 0.14f)))
        )

        val long = lines.top5[0]
        assertTrue(long, long.contains("Vehicle horn, car horn, h" + AiDebugText.ELLIPSIS))
        // 두 줄의 길이가 같아야 확률이 같은 열에 선다.
        assertEquals(lines.top5[1].length, long.length)
    }

    @Test
    fun `top-5 는 다섯 줄까지만 만든다`() {
        val hits = (1..6).map { hit("Class $it", 0.1f) }
        assertEquals(5, format(result(top5 = hits)).top5.size)
    }

    @Test
    fun `top-5 가 없으면 줄을 만들지 않는다`() {
        assertTrue(format(result()).top5.isEmpty())

        val waiting = format(null)
        assertTrue(waiting.top5.isEmpty())
        assertEquals(AiDebugText.NONE, waiting.verdict)
    }

    @Test
    fun `소리 크기와 표시 여부와 소요시간을 보여준다`() {
        val lines = format(result(), level = 0.14f, shown = false)

        assertTrue(lines.timing, lines.timing.contains("lvl 0.14"))
        assertTrue(lines.timing, lines.timing.contains("shown N"))
        assertTrue(lines.timing, lines.timing.contains("65ms (12/48/3)"))
    }

    @Test
    fun `결과가 없으면 왜 없는지 구분해서 보여준다`() {
        val waiting = format(null, aiAvailable = true)
        assertEquals(AiDebugText.COARSE_WAITING, waiting.coarse)
        assertNull("색을 고를 라벨이 없다", waiting.colorLabel)
        assertEquals(AiDebugText.NONE, waiting.confidence)

        val off = format(null, aiAvailable = false)
        assertEquals(AiDebugText.COARSE_AI_OFF, off.coarse)
        assertNull(off.colorLabel)
    }

    @Test
    fun `결과가 없어도 소리 크기는 보여준다`() {
        // 분류가 죽었을 때 캡처까지 죽은 것인지 가르는 데 쓴다.
        assertTrue(format(null).timing.contains("lvl 0.14"))
    }

    @Test
    fun `앱 언어와 상관없이 숫자를 아라비아 숫자로 쓴다`() {
        // 앱이 기본 로캘을 고른 언어로 바꾼다. 서식을 못박지 않으면 아랍어에서 아랍 숫자가 나온다.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            val lines = format(result(top5 = listOf(hit("Music", 0.6f)), gunshotEvidence = 0.07f))

            assertEquals("0.41", lines.confidence)
            assertTrue(lines.verdict, lines.verdict.contains("ev 0.07"))
            assertTrue(lines.top5[0], lines.top5[0].endsWith(" 0.60"))
            assertTrue(lines.detail, lines.detail.contains("age 0.2s"))
            assertTrue(lines.timing, lines.timing.contains("65ms (12/48/3)"))
        } finally {
            Locale.setDefault(original)
        }
    }
}

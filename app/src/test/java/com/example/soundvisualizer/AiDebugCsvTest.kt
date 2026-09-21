package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiClassificationResult
import com.example.soundvisualizer.ai.AiFrontendMode
import com.example.soundvisualizer.ai.YamnetCoarseClassifier
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 개발자 모드 기록의 CSV 한 줄 (#193).
 *
 * 고정하는 것은 표가 어긋나는 경우들이다. 이름에 쉼표가 들어가는 것(YAMNet 은 "Gunshot, gunfire" 처럼
 * 쉼표가 든 이름을 쓴다), 로캘이 바뀌어 숫자가 달라지는 것, 값이 없는데 NaN 이 찍히는 것.
 */
class AiDebugCsvTest {

    private val defaultLocale = Locale.getDefault()

    private fun result(
        coarse: String = "danger",
        display: String = "Gunshot, gunfire",
        confidence: Float = 0.54321f,
        gunshotScore: Float = 0.8f,
        boosterAvailable: Boolean = true,
        top5: List<YamnetCoarseClassifier.TopClassHit> = listOf(
            YamnetCoarseClassifier.TopClassHit(1, "Gunshot, gunfire", 0.54321f),
            YamnetCoarseClassifier.TopClassHit(2, "Speech", 0.1f)
        ),
        timestampMs: Long = 1_000L
    ) = AiClassificationResult(
        coarse = coarse,
        display = display,
        confidence = confidence,
        gunshotScore = gunshotScore,
        top5 = top5,
        gunshotEvidence = 0.25f,
        boosterReason = "booster_accepted",
        dangerCuePromoted = false,
        boosterAvailable = boosterAvailable,
        preBoosterCoarse = "ambient",
        boosterAccepted = true,
        meetsThreshold = true,
        useBoosterDangerPreview = false,
        timestampMs = timestampMs,
        preprocessMs = 3.21,
        yamnetMs = 41.0,
        boosterMs = 2.0,
        totalMs = 46.21,
        frontendMode = AiFrontendMode.CURRENT,
        boosterEnabled = true
    )

    /** 칸 수를 맞춰 세려면 따옴표 안의 쉼표를 빼고 세야 한다. 아주 작은 CSV 파서. */
    private fun cells(line: String): List<String> {
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    cell.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    out += cell.toString(); cell.setLength(0)
                }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    @Test
    fun `머리글과 줄의 칸 수가 같다`() {
        val header = cells(AiDebugCsv.HEADER)
        val row = cells(AiDebugCsv.row(result(), nowMs = 1_500L, level = 0.4f, shown = true))
        assertEquals(header.size, row.size)
        assertEquals(AiDebugCsv.COLUMNS, header)
    }

    @Test
    fun `쉼표가 든 이름이 칸을 밀지 않는다`() {
        val row = AiDebugCsv.row(result(), nowMs = 1_500L, level = 0.4f, shown = true)
        val parsed = cells(row)
        assertEquals("Gunshot, gunfire", parsed[AiDebugCsv.COLUMNS.indexOf("display")])
        assertEquals("Gunshot, gunfire", parsed[AiDebugCsv.COLUMNS.indexOf("top1_name")])
        assertEquals(cells(AiDebugCsv.HEADER).size, parsed.size)
    }

    @Test
    fun `이름에 든 따옴표도 살아남는다`() {
        val row = AiDebugCsv.row(
            result(display = "he said \"stop\""), nowMs = 1_500L, level = 0.4f, shown = true
        )
        val parsed = cells(row)
        assertEquals("he said \"stop\"", parsed[AiDebugCsv.COLUMNS.indexOf("display")])
        assertEquals(cells(AiDebugCsv.HEADER).size, parsed.size)
    }

    @Test
    fun `모델이 덜 내놓으면 남은 top 칸은 비운다`() {
        val parsed = cells(AiDebugCsv.row(result(), nowMs = 1_500L, level = 0f, shown = false))
        assertEquals("Speech", parsed[AiDebugCsv.COLUMNS.indexOf("top2_name")])
        assertEquals(AiDebugCsv.NONE, parsed[AiDebugCsv.COLUMNS.indexOf("top3_name")])
        assertEquals(AiDebugCsv.NONE, parsed[AiDebugCsv.COLUMNS.indexOf("top5_prob")])
    }

    @Test
    fun `부스터가 없으면 점수 자리에 NaN 을 적지 않는다`() {
        val parsed = cells(
            AiDebugCsv.row(
                result(boosterAvailable = false, gunshotScore = Float.NaN),
                nowMs = 1_500L, level = 0.1f, shown = true
            )
        )
        assertEquals(AiDebugCsv.NONE, parsed[AiDebugCsv.COLUMNS.indexOf("gunshot_score")])
        assertEquals(AiDebugCsv.NONE, parsed[AiDebugCsv.COLUMNS.indexOf("gunshot_evidence")])
        assertTrue("NaN 이 줄에 남았다", !parsed.any { it.contains("NaN") })
    }

    @Test
    fun `언어를 바꿔도 숫자 서식이 같다`() {
        // 앱은 기본 로캘을 사용자가 고른 언어로 바꾼다. 아랍어에서는 %f 가 아랍 숫자를 쓴다.
        val us = AiDebugCsv.row(result(), nowMs = 1_500L, level = 0.4f, shown = true)
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        val arabic = AiDebugCsv.row(result(), nowMs = 1_500L, level = 0.4f, shown = true)
        assertEquals(us, arabic)
        assertTrue("확신도가 0.54321 로 적히지 않았다", us.contains("0.54321"))
    }

    @Test
    fun `나이는 음수로 적지 않는다`() {
        // System.currentTimeMillis 는 뒤로 튈 수 있다.
        val parsed = cells(
            AiDebugCsv.row(result(timestampMs = 5_000L), nowMs = 1_000L, level = 0f, shown = false)
        )
        assertEquals("0", parsed[AiDebugCsv.COLUMNS.indexOf("age_ms")])
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }
}

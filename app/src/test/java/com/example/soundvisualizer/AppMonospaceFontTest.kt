package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiClassificationResult
import com.example.soundvisualizer.ai.AiFrontendMode
import com.example.soundvisualizer.ai.YamnetCoarseClassifier.TopClassHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Font
import java.awt.font.FontRenderContext
import java.io.File

/**
 * 앱에 넣은 고정폭 글꼴([AppMonospace], #162).
 *
 * 삼성 One UI 는 시스템 monospace 를 비례폭으로 그려서 글꼴을 앱에 넣었다. 그 글꼴이 정말 고정폭인지,
 * 개발자 모드 HUD 가 찍을 수 있는 글자가 빠짐없이 들어 있는지를 기기 없이 확인한다.
 * 글꼴에 없는 글자는 그 글자만 시스템 글꼴로 그려져서, 그 줄이 다시 비례폭이 된다.
 *
 * 유닛 테스트는 app 모듈 폴더에서 돈다.
 */
class AppMonospaceFontTest {

    private val font: Font by lazy {
        val file = File("src/main/res/font/droid_sans_mono.ttf")
        assertTrue("글꼴 파일이 없습니다: ${file.absolutePath}", file.isFile)
        Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(100f)
    }

    @Test
    fun `넣은 글꼴은 글자마다 폭이 같다`() {
        // 좁은 글자(l, i, .)와 넓은 글자(M, W, 0)가 같은 폭이어야 값이 바뀌어도 자리가 흔들리지 않는다.
        val sample = (0x20..0x7E).map { it.toChar() }.joinToString("") + HUD_EXTRA_CHARS
        val glyphs = font.createGlyphVector(FontRenderContext(null, false, false), sample)
        val advances = (0 until glyphs.numGlyphs).map { glyphs.getGlyphMetrics(it).advance }.toSet()
        assertEquals("글자마다 폭이 다릅니다: $advances", 1, advances.size)
    }

    @Test
    fun `YAMNet 클래스 이름은 모두 글꼴에 있다`() {
        // HUD 의 이름 칸과 top-5 에 그대로 찍힌다.
        val names = classNames()
        assertEquals("클래스 맵 개수", 521, names.size)
        assertAllDisplayable("YAMNet 클래스 이름", names.joinToString(""))
    }

    @Test
    fun `HUD 가 만드는 글자는 모두 글꼴에 있다`() {
        // 실제로 나올 수 있는 모양을 두루 만들어 본다. 결과 없음, 모델 없음, 부스터 꺼짐·없음,
        // 두 frontend, NaN 점수, 긴 이름이 잘려 말줄임표가 붙는 top-5.
        val longName = classNames().maxBy { it.length }
        val outputs = buildList {
            add(AiDebugText.format(null, 0L, 0f, shown = false, aiAvailable = true))
            add(AiDebugText.format(null, 0L, 0f, shown = false, aiAvailable = false))
            for (coarse in listOf(AiClassification.AMBIENT, AiClassification.SPEECH, AiClassification.DANGER)) {
                for (mode in AiFrontendMode.entries) {
                    for ((enabled, available) in listOf(true to true, true to false, false to false)) {
                        add(
                            AiDebugText.format(
                                result(coarse, longName, mode, enabled, available),
                                nowMs = 3_456L, level = 0.14f, shown = true, aiAvailable = true
                            )
                        )
                    }
                }
            }
        }
        val text = outputs.joinToString("") { lines ->
            with(lines) { coarse + label + confidence + detail + verdict + timing + top5.joinToString("") }
        }
        assertTrue("말줄임표가 나오는 경우를 만들지 못했습니다", text.contains(AiDebugText.ELLIPSIS))
        assertAllDisplayable("HUD 글자", text)
    }

    private fun assertAllDisplayable(what: String, text: String) {
        val missing = text.codePoints().toArray().distinct().filter { !font.canDisplay(it) }
        assertTrue(
            "$what 중 글꼴에 없는 글자: ${missing.joinToString { "U+%04X".format(it) }}",
            missing.isEmpty()
        )
    }

    private fun classNames(): List<String> =
        File("src/main/assets/ai/yamnet_class_map.csv").readLines(Charsets.UTF_8)
            .drop(1)
            .filter { it.isNotBlank() }
            // index,mid,display_name — 이름에 쉼표가 있으면 큰따옴표로 감싸져 있다.
            .map { line -> line.split(",", limit = 3)[2].trim().removeSurrounding("\"") }

    private fun result(
        coarse: String,
        display: String,
        frontendMode: AiFrontendMode,
        boosterEnabled: Boolean,
        boosterAvailable: Boolean
    ) = AiClassificationResult(
        coarse = coarse,
        display = display,
        confidence = 0.41f,
        gunshotScore = if (boosterAvailable) 0.83f else Float.NaN,
        boosterAvailable = boosterAvailable,
        preBoosterCoarse = AiClassification.AMBIENT,
        boosterAccepted = boosterAvailable,
        meetsThreshold = true,
        useBoosterDangerPreview = false,
        timestampMs = 1_000L,
        preprocessMs = 12.0,
        yamnetMs = 48.0,
        boosterMs = 3.0,
        totalMs = 65.0,
        top5 = List(5) { TopClassHit(index = it, name = display, probability = 0.5f - it * 0.1f) },
        gunshotEvidence = 0f,
        boosterReason = "",
        dangerCuePromoted = false,
        frontendMode = frontendMode,
        boosterEnabled = boosterEnabled
    )

    private companion object {
        /** ASCII 말고 HUD 가 쓰는 글자. 말줄임표는 top-5 이름을 자를 때 붙는다. */
        const val HUD_EXTRA_CHARS = "…"
    }
}

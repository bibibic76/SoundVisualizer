package com.example.soundvisualizer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 글자와 바탕의 대비가 WCAG 기준(일반 글자 4.5:1)을 넘는지(#312). 흰 글자를 얹는 바탕과 어두운 바탕 위의 글자가
 * 각각 다른 파랑을 쓰는 까닭이 이것이다. 색을 바꾸다 기준 아래로 내려가면 여기서 막힌다.
 */
class ColorContrastTest {

    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun assertReadable(what: String, text: Color, background: Color) {
        val ratio = contrast(text, background)
        assertTrue("$what: ${"%.2f".format(ratio)}:1 < 4.5:1", ratio >= 4.5f)
    }

    @Test
    fun `흰 글자를 얹는 바탕은 4_5대1 을 넘는다`() {
        assertReadable("실행 버튼·고른 칸", Color.White, AccentFillColor)
        assertReadable("실행 종료 버튼", Color.White, DangerColor)
    }

    @Test
    fun `어두운 바탕 위의 강조 글자는 4_5대1 을 넘는다`() {
        assertReadable("카드 위 파란 글자", AccentColor, CardColor)
        assertReadable("배경 위 파란 글자", AccentColor, BgColor)
        assertReadable("카드 위 회색 글자", SecondaryTextColor, CardColor)
        // 분류 탭의 설명과 작은 안내(13sp)는 카드가 아니라 배경 위에 있다(#349). 이 대비(4.6:1) 때문에 더 작게 하지 않는다.
        assertReadable("배경 위 회색 글자", SecondaryTextColor, BgColor)
        assertReadable("카드 위 경고 글자", WarningColor, CardColor)
    }

    @Test
    fun `스위치·슬라이더 막대는 카드 위에서 그림 요소 기준 3대1 을 넘는다`() {
        assertTrue(contrast(AccentFillColor, CardColor) >= 3f)
    }

    @Test
    fun `알림의 고른 모드 칸은 앱의 바탕용 파랑과 같다`() {
        val colors = File("src/main/res/values/colors.xml").readText(Charsets.UTF_8)
        val hex = Regex("""<color name="notification_chip_on">#([0-9A-Fa-f]{6})</color>""").find(colors)!!.groupValues[1]
        assertEquals(AccentFillColor.toArgb() and 0xFFFFFF, hex.toInt(16))
    }
}

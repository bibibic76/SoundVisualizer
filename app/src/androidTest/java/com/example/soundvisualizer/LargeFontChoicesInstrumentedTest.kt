package com.example.soundvisualizer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.soundvisualizer.feedback.HapticMode
import com.example.soundvisualizer.feedback.HapticSettingRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 진동 방식 다섯 칸은 보통 글꼴에서 한 줄이고, 글꼴을 키워 이름이 칸에 들어가지 않으면 여러 줄로 나뉜다(#304).
 * 나뉘지 않으면 "Medium" 이 "Med/ium" 처럼 글자 중간에서 끊긴다. 누르지 않으므로 진동은 울리지 않는다.
 *
 * 설정 카드 안의 너비(약 264dp)에서 그린다. 기대하는 글자는 리소스에서 만든다.
 */
@RunWith(AndroidJUnit4::class)
class LargeFontChoicesInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { SettingsManager.init(context) }
    }

    private fun showRow(fontScale: Float) {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                Box(modifier = Modifier.width(264.dp)) {
                    HapticSettingRow(AiClassification.DANGER, shown = true)
                }
            }
        }
        rule.waitForIdle()
    }

    /** 고를 수 있는 칸(선택 상태가 있는 노드)의 자리. */
    private fun chip(mode: HapticMode): DpRect =
        rule.onNode(hasText(context.getString(mode.labelRes)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .getBoundsInRoot()

    @Test
    fun normalFontKeepsOneRow() {
        showRow(1f)
        val tops = HapticMode.entries.map { chip(it).top }
        assertTrue("보통 글꼴에서는 다섯 칸이 한 줄이다: $tops", tops.all { it == tops.first() })
    }

    @Test
    fun largeFontSplitsIntoRowsOfEqualWidth() {
        showRow(2f)
        val boxes = HapticMode.entries.map { chip(it) }
        val rows = boxes.map { it.top }.distinct()
        assertTrue("글꼴 200% 에서는 여러 줄로 나뉜다: $rows", rows.size > 1)
        val widths = boxes.map { it.right - it.left }
        widths.forEach { assertEquals("칸 너비는 모두 같다: $widths", widths.first().value, it.value, 0.5f) }
        // 4+1 처럼 한 줄에 몰고 한 칸만 남기지 않는다. 다섯 칸은 3+2, 그래도 안 들어가면 2+2+1 이다.
        val firstRow = boxes.count { it.top == rows.first() }
        assertTrue("첫 줄 칸 수 $firstRow", firstRow in 2..3)
    }
}

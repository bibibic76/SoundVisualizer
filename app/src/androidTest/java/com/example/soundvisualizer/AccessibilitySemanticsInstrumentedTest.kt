package com.example.soundvisualizer

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.Locale

/**
 * 화면 읽어주기가 기대는 표시가 빠지지 않는지 본다(#300). 기기 언어와 상관없이 돌도록 글자는 모두 리소스에서 만든다.
 *
 * - 설정의 구역 제목과 펼치기 카드의 머리는 제목(heading)이고, 카드 머리는 버튼이다.
 * - 슬라이더는 화면에 보이는 숫자 그대로 읽힌다. 범위가 0부터가 아니면 기본 읽기(범위 안의 퍼센트)가 화면과 다르다.
 * - 홈의 상태 줄은 바뀌면 읽어 주는 영역(live region)이다.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilitySemanticsInstrumentedTest {

    /** 새로 설치한 테스트 기기에서는 튜토리얼이 탭 화면 대신 뜬다. 액티비티보다 먼저 정하고, 끝나면 되돌린다. */
    private val tutorialSeen = object : ExternalResource() {
        private var before = true

        override fun before() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                SettingsManager.init(InstrumentationRegistry.getInstrumentation().targetContext)
                before = SettingsManager.tutorialSeen.value
                SettingsManager.setTutorialSeen(true)
            }
        }

        override fun after() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { SettingsManager.setTutorialSeen(before) }
        }
    }

    private val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tutorialSeen).around(rule)

    private fun string(id: Int): String = rule.activity.getString(id)

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun openTab(id: Int) {
        rule.onNodeWithText(string(id)).performClick()
        rule.waitForIdle()
    }

    @Test
    fun homeStatusIsLiveRegion() {
        val status = hasText(string(R.string.home_status_idle)) or
            hasText(string(R.string.home_status_running)) or
            hasText(string(R.string.home_status_running_external))
        rule.onNode(status).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun homeLanguageButtonSaysWhatItIs() {
        // 홈 오른쪽 아래의 언어 버튼(#308)은 화면에 지구본과 언어 이름뿐이라, "언어, 한국어" 처럼 무엇의 버튼인지 함께 읽혀야 한다.
        val title = string(R.string.settings_language_title)
        rule.onNode(
            SemanticsMatcher("언어 버튼") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("$title, ") }
            }
        ).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun settingsSectionTitleIsHeading() {
        openTab(R.string.tab_settings)
        rule.onNodeWithText(string(R.string.settings_section_mode)).assert(isHeading)
    }

    @Test
    fun expanderHeaderIsHeadingButton() {
        openTab(R.string.tab_help)
        // 머리는 제목 글자와 화살표 설명을 한 덩어리로 묶은 노드다.
        rule.onNode(hasText(string(R.string.help_start_title)) and isHeading)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun sizeSliderReadsTheNumberOnScreen() {
        openTab(R.string.tab_settings)
        // 고른 모드의 카드는 펼쳐져 있다. 크기 고정을 켰는지에 따라 둘 중 하나가 보인다. 고정 크기는 범위가 10부터라
        // 기본 읽기와 화면 숫자가 다르고, 크기(0부터)도 같은 규칙으로 읽히는지 함께 본다.
        val slider = hasContentDescription(string(R.string.setting_size)) or
            hasContentDescription(string(R.string.setting_fixed_size))
        // 탭 화면(가로)과 옆 탭의 목록도 스크롤되므로, 설정 목록을 그 안의 구역 제목으로 고른다.
        val settingsList = hasScrollToNodeAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            hasAnyDescendant(hasText(string(R.string.settings_section_mode)))
        rule.onNode(settingsList).performScrollToNode(slider)
        val config = rule.onNode(slider).fetchSemanticsNode().config
        val range = config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertNotNull("슬라이더가 아니다", range)
        assertEquals(
            "화면에 보이는 숫자로 읽혀야 한다",
            String.format(Locale.US, "%.0f", range!!.current),
            config.getOrNull(SemanticsProperties.StateDescription)
        )
    }
}

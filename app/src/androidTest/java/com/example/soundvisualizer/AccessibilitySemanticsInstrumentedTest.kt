package com.example.soundvisualizer

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
 * - 분류 탭(#349)의 구역 머리와 고급 모드의 묶음 머리는 제목이고, 묶음 카드는 한 덩어리로 읽는 48dp 이상의 드롭다운
 *   목록이며, 고급 모드 스위치는 줄 전체가 스위치다. 확인 창의 단추는 하는 일을 이름으로 읽는다.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilitySemanticsInstrumentedTest {

    /**
     * 새로 설치한 테스트 기기에서는 튜토리얼이 탭 화면 대신 뜬다. 액티비티보다 먼저 정하고, 끝나면 되돌린다.
     * 분류 탭은 바꾼 소리 없이 기본 모드로 열어 카드가 늘 같은 글로 읽히게 하고, 끝나면 사용자의 값으로 되돌린다.
     */
    private val settings = object : ExternalResource() {
        private var before = true
        private var typesBefore: Map<String, String> = emptyMap()
        private var advancedBefore = false

        override fun before() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                SettingsManager.init(InstrumentationRegistry.getInstrumentation().targetContext)
                before = SettingsManager.tutorialSeen.value
                typesBefore = SettingsManager.soundTypes.value
                advancedBefore = SettingsManager.classifyAdvanced.value
                SettingsManager.setTutorialSeen(true)
                SettingsManager.resetSoundTypes()
                SettingsManager.setClassifyAdvanced(false)
            }
        }

        override fun after() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                SettingsManager.resetSoundTypes()
                typesBefore.forEach { (name, type) -> SettingsManager.setSoundType(name, type) }
                SettingsManager.setClassifyAdvanced(advancedBefore)
                SettingsManager.setTutorialSeen(before)
            }
        }
    }

    private val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(settings).around(rule)

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
    fun tabsAreAtLeast48dp() {
        // 글자와 밑줄만으로는 약 35dp 라 누르는 자리가 작았다(#312). 가장 짧은 이름("홈")도 48dp 이상이다.
        for (id in listOf(R.string.tab_home, R.string.tab_settings, R.string.tab_classify, R.string.tab_help)) {
            rule.onNodeWithText(string(id)).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
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

    private val isDropdown = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList)

    /** 분류 탭의 목록. 탭 화면(가로)과 옆 탭의 목록과 가리려고, 찾기 칸이 붙어 있는 세로 목록으로 고른다. */
    private val classifyList = hasScrollToNodeAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
        hasAnyDescendant(hasSetTextAction())

    /** 모델 이름으로 분류 탭에 보이는 소리 이름을 찾는다. */
    private fun soundLabel(name: String): String {
        val entry = SoundCatalog.load(rule.activity).first { it.name == name }
        return rule.activity.resources.getStringArray(R.array.sound_names)[entry.index]
    }

    @Test
    fun classifySectionsAreHeadingsAndGroupCardsAreDropdowns() {
        openTab(R.string.tab_classify)
        // 구역 머리는 제목이라 구역 단위로 건너뛸 수 있다.
        rule.onNode(hasText(string(R.string.sound_type_danger)) and isHeading).assertExists()

        // 보이는 묶음 카드는 모두 이름·종류·소리 수를 한 덩어리로 읽는 드롭다운 목록이고 48dp 이상이다.
        val cards = rule.onAllNodes(isDropdown)
        val count = cards.fetchSemanticsNodes().size
        assertTrue("묶음 카드가 보이지 않는다", count > 0)
        for (i in 0 until count) {
            cards[i].assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).assertHeightIsAtLeast(48.dp)
        }

        // 소리 하나뿐인 묶음(문 쾅 닫는 소리)은 소리 한 줄과 같은 글로 읽고, 글이 한 줄뿐인 가장 낮은 카드도 48dp 이상이다.
        val slam = rule.activity.getString(R.string.cd_classify_sound, soundLabel("Slam"), string(R.string.sound_type_danger))
        rule.onNode(classifyList).performScrollToNode(hasContentDescription(slam))
        rule.onNodeWithContentDescription(slam).assert(isDropdown).assertHeightIsAtLeast(48.dp)

        // 고급 모드 스위치는 줄 전체가 이름과 함께 스위치로 읽힌다.
        val advanced = hasText(string(R.string.classify_advanced)) and isToggleable()
        rule.onNode(classifyList).performScrollToNode(advanced)
        rule.onNode(advanced).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
    }

    @Test
    fun classifyAdvancedGroupHeadingsAreHeadings() {
        rule.runOnUiThread { SettingsManager.setClassifyAdvanced(true) }
        openTab(R.string.tab_classify)
        rule.onNode(hasText(string(R.string.sound_type_danger)) and isHeading).assertExists()
        // 묶음 머리는 "경보음 묶음, 소리 3개" 처럼 읽는 제목이라 묶음 단위로 건너뛸 수 있다. 위협음 구역의 첫 묶음이다.
        val alarms = SoundGroups.sections(SoundCatalog.load(rule.activity))
            .flatMap { it.cards }
            .first { it.group == SoundGroup.ALARMS }
        val header = rule.activity.getString(R.string.cd_classify_group_header, soundLabel("Alarm"), alarms.members.size)
        rule.onNode(classifyList).performScrollToNode(hasContentDescription(header))
        rule.onNodeWithContentDescription(header).assert(isHeading)
    }

    @Test
    fun classifyWarningButtonsSayWhatTheyDo() {
        // 사이렌 하나만 바꿔 둔 채로 고급 모드를 끄면 덮어쓰기를 알리는 창이 뜬다.
        rule.runOnUiThread {
            SettingsManager.setSoundType("Civil defense siren", AiClassification.AMBIENT)
            SettingsManager.setClassifyAdvanced(true)
        }
        openTab(R.string.tab_classify)
        rule.onNode(hasText(string(R.string.classify_advanced)) and isToggleable()).performClick()
        rule.waitForIdle()
        // 창의 단추는 "확인"·"취소" 가 아니라 하는 일("기본 모드로", "고급 모드 유지")로 읽힌다.
        val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        rule.onNode(hasText(string(R.string.classify_basic_warning_confirm)) and hasClickAction()).assert(isButton)
        rule.onNode(hasText(string(R.string.classify_basic_warning_cancel)) and hasClickAction()).assert(isButton).performClick()
        rule.waitForIdle()
    }
}

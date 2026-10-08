package com.example.soundvisualizer

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.soundvisualizer.ai.YamnetThreeClassMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * 분류 탭을 실제 화면에서 조작한다. 기기 언어와 상관없이 돌도록 기대하는 글자는 모두 리소스에서 만든다.
 *
 * 탭은 모든 사용자에게 보인다(#328). 그래서 개발자 모드를 꺼 둔 채로 연다. 앱의 실제 설정을 쓰므로, 시작할 때의
 * 튜토리얼 본 여부·개발자 모드·저장된 선택·고급 모드를 챙겨 두고 바꾼 뒤, 끝나면 그대로 되돌린다.
 *
 * 처음에는 묶음째 고르는 기본 모드로 연다(#349). 소리를 하나씩 고르는 테스트는 고급 모드 스위치를 켠 뒤에 한다.
 * 화면에 보이는 미리보기·소리 수는 묶음 표(assets/sound_groups.tsv)의 사이렌 묶음 순서를 따른다.
 */
@RunWith(AndroidJUnit4::class)
class ClassifyTabInstrumentedTest {

    /**
     * 액티비티가 뜨기 전에 정해야 하므로 액티비티를 여는 규칙보다 바깥에 둔다. 새로 설치한 테스트 기기에서는 튜토리얼이
     * 탭 화면 대신 뜬다. 개발자 모드는 꺼 둔다(누구에게나 보이는지 본다). 고급 모드도 꺼서 기본 모드로 연다.
     */
    private val settings = object : ExternalResource() {
        private var tutorialBefore = true
        private var developerBefore = false
        private var typesBefore: Map<String, String> = emptyMap()
        private var advancedBefore = false

        override fun before() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                SettingsManager.init(InstrumentationRegistry.getInstrumentation().targetContext)
                tutorialBefore = SettingsManager.tutorialSeen.value
                developerBefore = SettingsManager.developerMode.value
                typesBefore = SettingsManager.soundTypes.value
                advancedBefore = SettingsManager.classifyAdvanced.value
                SettingsManager.setTutorialSeen(true)
                SettingsManager.setDeveloperMode(false)
                SettingsManager.resetSoundTypes()
                SettingsManager.setClassifyAdvanced(false)
            }
        }

        override fun after() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                SettingsManager.resetSoundTypes()
                typesBefore.forEach { (name, type) -> SettingsManager.setSoundType(name, type) }
                SettingsManager.setClassifyAdvanced(advancedBefore)
                SettingsManager.setDeveloperMode(developerBefore)
                SettingsManager.setTutorialSeen(tutorialBefore)
            }
        }
    }

    private val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(settings).around(rule)

    private fun string(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    /** 모델 이름으로 소리 번호와 화면에 보이는 이름을 찾는다. */
    private fun sound(name: String): Pair<SoundEntry, String> {
        val entry = SoundCatalog.load(rule.activity).first { it.name == name }
        return entry to rule.activity.resources.getStringArray(R.array.sound_names)[entry.index]
    }

    private fun openTab() {
        rule.onNodeWithText(string(R.string.tab_classify)).performClick()
        rule.waitForIdle()
    }

    /**
     * 찾는 말을 넣고 자판의 찾기 키를 누른다(사용자처럼). 찾기 키는 자판만 내리고([SearchField]), 목록은 글자를 칠 때마다
     * 이미 걸러져 있다. 자판이 떠 있으면 화면 아래가 가려져 결과 줄이 그려지지 않을 수 있다.
     */
    private fun search(text: String) {
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput(text)
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitForIdle()
    }

    /** 찾는 말을 지우고 자판을 내린다. */
    private fun clearSearch() {
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitForIdle()
    }

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    /** 분류 탭의 목록. 옆 탭의 목록과 가리려고, 찾기 칸이 붙어 있는 세로 목록으로 고른다. */
    private val classifyList = hasScrollToNodeAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
        hasAnyDescendant(hasSetTextAction())

    /** 목록을 [matcher] 가 보일 때까지 민다. 화면 밖의 칸은 그려지지 않아 찾을 수 없다. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        rule.onNode(classifyList).performScrollToNode(matcher)
        rule.waitForIdle()
    }

    /** 고급 모드 스위치 줄. 이름·설명·상태가 한 덩어리다. */
    private val advancedSwitch get() = hasText(string(R.string.classify_advanced)) and isToggleable()

    /** 고급 모드 스위치를 누른다(사용자처럼). 맨 위의 설명 칸에 있어서 먼저 그쪽으로 민다. */
    private fun toggleAdvanced() {
        scrollTo(advancedSwitch)
        rule.onNode(advancedSwitch).performClick()
        rule.waitForIdle()
    }

    /** 사이렌 묶음의 소리. 묶음 표의 순서이고, 묶음 이름은 맨 앞 '사이렌' 소리의 이름이다. */
    private val sirens = listOf(
        "Siren", "Ambulance (siren)", "Fire engine, fire truck (siren)", "Police car (siren)", "Civil defense siren"
    )

    /** 사이렌 카드의 이름과, 카드가 읽어 주는 미리보기(이름인 소리를 뺀 앞의 세 개). 찾는 말이 없을 때다. */
    private fun sirenCard(): Pair<String, String> =
        sound("Siren").second to sirens.drop(1).take(3).joinToString(", ") { sound(it).second }

    /** 기본 모드의 카드에서 종류를 고른다. 카드 어디를 눌러도 목록이 창으로 뜬다. */
    private fun pickOnCard(description: String, option: String) {
        scrollTo(hasContentDescription(description))
        rule.onNodeWithContentDescription(description).performClick()
        rule.onNode(hasText(option) and hasAnyAncestor(isPopup())).performClick()
        rule.waitForIdle()
    }

    @Test
    fun tabOpensInBasicModeWithSectionsAndSiblingNote() {
        openTab()
        rule.onNodeWithText(string(R.string.classify_title)).assertExists()
        // 한 소리가 여러 이름으로 함께 들린다는 안내(#328, #349). 묶음 하나만 바꾸고 안심하지 않게 한다.
        rule.onNodeWithText(string(R.string.classify_sibling_note)).assertExists()
        // 처음에는 묶음째 고르는 기본 모드다. 구역은 기본 종류마다 하나씩, 제목으로 읽힌다.
        rule.onNode(advancedSwitch).assertIsOff()
        for (type in listOf(R.string.sound_type_danger, R.string.sound_type_speech, R.string.sound_type_ambient)) {
            scrollTo(hasText(string(type)) and isHeading)
        }
        // 묶음 카드는 이름·종류·소리 수·미리보기를 한 번에 읽는다.
        val (title, preview) = sirenCard()
        val danger = string(R.string.sound_type_danger)
        scrollTo(hasContentDescription(string(R.string.cd_classify_group, title, danger, sirens.size, preview)))
    }

    @Test
    fun pickingATypeOnAGroupChangesAllItsSounds() {
        val (title, preview) = sirenCard()
        val danger = string(R.string.sound_type_danger)
        val ambient = string(R.string.sound_type_ambient)
        openTab()

        // 모두 같은 종류인 묶음은 묻지 않고 바로 바꾼다. 다섯 개가 모두 바뀌고, 다른 묶음의 '경보음' 은 그대로다.
        pickOnCard(string(R.string.cd_classify_group, title, danger, sirens.size, preview), ambient)
        rule.onNodeWithText(string(R.string.classify_group_overwrite_title)).assertDoesNotExist()
        assertEquals(sirens.associateWith { AiClassification.AMBIENT }, SettingsManager.soundTypes.value)
        assertEquals(AiClassification.DANGER, SettingsManager.soundType("Alarm"))

        // 바꾼 묶음은 위협음 구역에 그대로 있고, 지금 종류를 기본 종류와 함께 보여 준다. "바꾼 소리" 는 소리를 센다.
        val changed = string(R.string.cd_classify_group_changed, title, ambient, danger, sirens.size, preview)
        rule.onNodeWithContentDescription(changed).assertExists()
        rule.onNodeWithText(string(R.string.classify_group_changed_line, ambient, danger), useUnmergedTree = true).assertExists()
        scrollTo(hasText(string(R.string.classify_changed_count, sirens.size)))

        // 기본 종류를 고르면 묶음의 바꾼 것이 모두 지워진다.
        pickOnCard(changed, string(R.string.classify_type_default, danger))
        assertTrue(SettingsManager.soundTypes.value.isEmpty())
        rule.onNodeWithContentDescription(string(R.string.cd_classify_group, title, danger, sirens.size, preview)).assertExists()
    }

    @Test
    fun pickTypeFromDropdownThenResetAll() {
        val (siren, label) = sound("Civil defense siren")
        val danger = string(R.string.sound_type_danger)
        val ambient = string(R.string.sound_type_ambient)
        openTab()
        // 소리를 하나씩 고르는 것은 고급 모드다(#349). 섞인 묶음이 없으니 켤 때 묻지 않는다.
        toggleAdvanced()
        search(label)

        // 줄 전체가 드롭다운이다. 누르면 종류 목록이 창으로 뜬다.
        rule.onNodeWithContentDescription(string(R.string.cd_classify_sound, label, danger)).performClick()
        rule.onNode(hasText(string(R.string.classify_type_default, danger)) and hasAnyAncestor(isPopup())).assertExists()
        rule.onNode(hasText(ambient) and hasAnyAncestor(isPopup())).performClick()
        rule.waitForIdle()

        assertEquals(mapOf(siren.name to AiClassification.AMBIENT), SettingsManager.soundTypes.value)
        assertEquals("AI 가 읽어 갈 창구에 바로 보인다", AiClassification.AMBIENT, SettingsManager.soundTypeOverride(siren.name))
        assertEquals(
            "#291 전까지 AI 의 매핑은 그대로다",
            AiClassification.DANGER,
            YamnetThreeClassMapper.mapDisplayNameToCoarse(siren.name)
        )
        rule.onNodeWithContentDescription(string(R.string.cd_classify_sound_changed, label, ambient, danger)).assertExists()
        // 줄 안의 글자는 화면 읽어주기용 설명으로 묶여 있어서, 화면에 그려졌는지는 묶기 전 트리에서 본다.
        rule.onNodeWithText(string(R.string.classify_changed_from, danger), useUnmergedTree = true).assertExists()
        rule.onNodeWithText(string(R.string.classify_changed_count, 1)).assertExists()

        rule.onNodeWithText(string(R.string.classify_reset_all)).performClick()
        rule.onNodeWithText(string(R.string.classify_reset_confirm)).performClick()
        rule.waitForIdle()

        assertTrue(SettingsManager.soundTypes.value.isEmpty())
        rule.onNodeWithContentDescription(string(R.string.cd_classify_sound, label, danger)).assertExists()
        rule.onNodeWithText(string(R.string.classify_changed_count, 0)).assertExists()
    }

    @Test
    fun typeFilterFollowsTheChosenType() {
        val (doorbell, doorbellLabel) = sound("Doorbell")
        val (rain, rainLabel) = sound("Rain")
        val (_, whiteNoiseLabel) = sound("White noise")
        rule.runOnUiThread { SettingsManager.setSoundType(doorbell.name, AiClassification.DANGER) }
        val danger = string(R.string.sound_type_danger)
        val ambient = string(R.string.sound_type_ambient)
        openTab()
        // 소리 줄로 보는 고급 모드에서 거른다(#349). 켤 때는 묻지 않는다.
        toggleAdvanced()

        // 거르기 칸의 "위협음" 을 누른다. 줄 안의 종류 글자는 줄의 설명으로 묶여 있어 여기 걸리지 않고, 같은 글자인
        // 구역 머리는 고를 수 있는 칸이 아니라서 걸리지 않는다.
        rule.onNode(hasText(danger) and isSelectable()).performClick()

        search(doorbellLabel)
        rule.onNodeWithContentDescription(string(R.string.cd_classify_sound_changed, doorbellLabel, danger, ambient)).assertExists()

        search(rainLabel)
        // "Rain" also matches "Train horn/whistle", now valid Danger results (#343).
        // The filter must exclude Rain itself, not every substring match.
        rule.onNodeWithContentDescription(string(R.string.cd_classify_sound, rainLabel, ambient)).assertDoesNotExist()

        // Preserve the empty-result assertion with an unambiguous Ambient query.
        search(whiteNoiseLabel)
        rule.onNodeWithText(string(R.string.classify_empty)).assertExists()

        search(rainLabel)
        rule.onNodeWithText(string(R.string.classify_filter_all)).performClick()
        // 묶음 순서로 펴면 '비' 줄은 기차 소리 줄들(위협음·탈것 묶음) 밑이라 화면 아래로 밀려날 수 있다. 그 줄까지 민다.
        scrollTo(hasContentDescription(string(R.string.cd_classify_sound, rainLabel, ambient)))
        rule.onNodeWithContentDescription(string(R.string.cd_classify_sound, rainLabel, ambient)).assertExists()
        assertEquals(AiClassification.AMBIENT, rain.defaultType)
    }

    @Test
    fun turningAdvancedOffWithAMixedGroupWarnsFirst() {
        val (siren, label) = sound("Civil defense siren")
        val (title, preview) = sirenCard()
        val danger = string(R.string.sound_type_danger)
        val ambient = string(R.string.sound_type_ambient)
        openTab()
        toggleAdvanced()
        search(label)
        rule.onNodeWithContentDescription(string(R.string.cd_classify_sound, label, danger)).performClick()
        rule.onNode(hasText(ambient) and hasAnyAncestor(isPopup())).performClick()
        rule.waitForIdle()
        assertEquals(mapOf(siren.name to AiClassification.AMBIENT), SettingsManager.soundTypes.value)
        clearSearch()

        // 사이렌 묶음의 소리 하나만 바꿨으므로, 끄기 전에 기본 모드에서 덮어써질 수 있다고 알린다.
        // 그대로 두기를 고르면 아무것도 바뀌지 않고 고급 모드 그대로다.
        toggleAdvanced()
        rule.onNodeWithText(string(R.string.classify_basic_warning_title)).assertExists()
        rule.onNodeWithText(string(R.string.classify_basic_warning_cancel)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(string(R.string.classify_basic_warning_title)).assertDoesNotExist()
        assertTrue(SettingsManager.classifyAdvanced.value)
        rule.onNode(advancedSwitch).assertIsOn()

        // 다시 끄고 확인하면 모드만 저장한다. 바꿔 둔 소리는 그대로다.
        toggleAdvanced()
        rule.onNodeWithText(string(R.string.classify_basic_warning_confirm)).performClick()
        rule.waitForIdle()
        assertFalse(SettingsManager.classifyAdvanced.value)
        rule.onNode(advancedSwitch).assertIsOff()
        assertEquals(mapOf(siren.name to AiClassification.AMBIENT), SettingsManager.soundTypes.value)

        // 기본 모드에서 사이렌 묶음은 '일부 다름' 이고, 목록에서 고른 것이 없다.
        val mixed = string(
            R.string.cd_classify_group_changed, title, string(R.string.classify_type_mixed), danger, sirens.size, preview
        )
        scrollTo(hasContentDescription(mixed))
        rule.onNodeWithText(string(R.string.classify_group_mixed_note), useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription(mixed).performClick()
        rule.onAllNodes(isSelected() and hasAnyAncestor(isPopup())).assertCountEquals(0)

        // 기본 종류를 고르면 먼저 묻고(바뀌는 것은 하나), 확인하면 묶음이 모두 기본 종류가 되어 바꾼 것이 없다.
        rule.onNode(hasText(string(R.string.classify_type_default, danger)) and hasAnyAncestor(isPopup())).performClick()
        rule.onNodeWithText(string(R.string.classify_group_overwrite_message, title, 1, danger)).assertExists()
        rule.onNodeWithText(string(R.string.classify_group_overwrite_confirm)).performClick()
        rule.waitForIdle()
        assertTrue(SettingsManager.soundTypes.value.isEmpty())
    }

    @Test
    fun pickingATypeOnAMixedGroupAsksBeforeOverwriting() {
        rule.runOnUiThread { SettingsManager.setSoundType("Civil defense siren", AiClassification.AMBIENT) }
        val (title, preview) = sirenCard()
        val danger = string(R.string.sound_type_danger)
        val speech = string(R.string.sound_type_speech)
        val ambient = string(R.string.sound_type_ambient)
        val mixed = string(
            R.string.cd_classify_group_changed, title, string(R.string.classify_type_mixed), danger, sirens.size, preview
        )
        openTab()

        // 대화음을 고르면 다섯 개가 모두 바뀐다고 묻는다. 취소하면 아무것도 바뀌지 않는다.
        pickOnCard(mixed, speech)
        rule.onNodeWithText(string(R.string.classify_group_overwrite_message, title, sirens.size, speech)).assertExists()
        rule.onNodeWithText(string(R.string.classify_group_overwrite_cancel)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(string(R.string.classify_group_overwrite_title)).assertDoesNotExist()
        assertEquals(mapOf("Civil defense siren" to AiClassification.AMBIENT), SettingsManager.soundTypes.value)

        // 환경음을 고르고 확인하면 나머지 네 개가 바뀌어 다섯 개 모두 환경음이 된다.
        pickOnCard(mixed, ambient)
        rule.onNodeWithText(string(R.string.classify_group_overwrite_message, title, sirens.size - 1, ambient)).assertExists()
        rule.onNodeWithText(string(R.string.classify_group_overwrite_confirm)).performClick()
        rule.waitForIdle()
        assertEquals(sirens.associateWith { AiClassification.AMBIENT }, SettingsManager.soundTypes.value)

        // 모두 같은 종류가 된 묶음은 묻지 않는다. 기본 종류를 고르면 바로 원래대로다.
        pickOnCard(
            string(R.string.cd_classify_group_changed, title, ambient, danger, sirens.size, preview),
            string(R.string.classify_type_default, danger)
        )
        rule.onNodeWithText(string(R.string.classify_group_overwrite_title)).assertDoesNotExist()
        assertTrue(SettingsManager.soundTypes.value.isEmpty())
    }

    @Test
    fun turningAdvancedOffWithoutMixedGroupsDoesNotAsk() {
        // 소리 하나뿐인 묶음(전기톱)을 바꾸거나 사이렌 다섯 개를 모두 같은 종류로 바꿔 두면 섞인 묶음이 없다.
        rule.runOnUiThread {
            SettingsManager.setSoundType("Chainsaw", AiClassification.AMBIENT)
            SettingsManager.setSoundTypes(sirens, AiClassification.AMBIENT)
            SettingsManager.setClassifyAdvanced(true)
        }
        openTab()
        rule.onNode(advancedSwitch).assertIsOn()

        toggleAdvanced()
        rule.onNodeWithText(string(R.string.classify_basic_warning_title)).assertDoesNotExist()
        assertFalse(SettingsManager.classifyAdvanced.value)
        rule.onNode(advancedSwitch).assertIsOff()
        assertEquals(sirens.size + 1, SettingsManager.soundTypes.value.size)
    }

    @Test
    fun modeAndWarningSurviveRecreate() {
        rule.runOnUiThread { SettingsManager.setSoundType("Civil defense siren", AiClassification.AMBIENT) }
        val (title, _) = sirenCard()
        openTab()
        toggleAdvanced()

        // 고급 모드는 저장값이라 액티비티를 다시 만들어도(화면 회전 등) 그대로다. 묶음 머리가 보인다.
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNode(advancedSwitch).assertIsOn()
        scrollTo(hasContentDescription(string(R.string.cd_classify_group_header, title, sirens.size)) and isHeading)

        // 끌 때 뜬 알림도 다시 만든 뒤에 그대로 떠 있다. 그대로 두기를 고르면 고급 모드다.
        toggleAdvanced()
        rule.onNodeWithText(string(R.string.classify_basic_warning_title)).assertExists()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithText(string(R.string.classify_basic_warning_title)).assertExists()
        rule.onNodeWithText(string(R.string.classify_basic_warning_cancel)).performClick()
        rule.waitForIdle()
        assertTrue(SettingsManager.classifyAdvanced.value)
    }
}

package com.example.soundvisualizer

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.soundvisualizer.ai.YamnetThreeClassMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * 분류 탭을 실제 화면에서 조작한다. 기기 언어와 상관없이 돌도록 기대하는 글자는 모두 리소스에서 만든다.
 *
 * AI 판정에 연결되기 전(#291)이라 탭은 개발자 모드에서만 보인다. 앱의 실제 설정을 쓰므로, 시작할 때의 튜토리얼 본 여부·
 * 개발자 모드·저장된 선택을 챙겨 두고 바꾼 뒤, 끝나면 그대로 되돌린다.
 */
@RunWith(AndroidJUnit4::class)
class ClassifyTabInstrumentedTest {

    /**
     * 액티비티가 뜨기 전에 정해야 하므로 액티비티를 여는 규칙보다 바깥에 둔다. 새로 설치한 테스트 기기에서는 튜토리얼이
     * 탭 화면 대신 뜨고, 개발자 모드가 꺼져 있으면 분류 탭이 없다.
     */
    private val settings = object : ExternalResource() {
        private var tutorialBefore = true
        private var developerBefore = false
        private var typesBefore: Map<String, String> = emptyMap()

        override fun before() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                SettingsManager.init(InstrumentationRegistry.getInstrumentation().targetContext)
                tutorialBefore = SettingsManager.tutorialSeen.value
                developerBefore = SettingsManager.developerMode.value
                typesBefore = SettingsManager.soundTypes.value
                SettingsManager.setTutorialSeen(true)
                SettingsManager.setDeveloperMode(true)
                SettingsManager.resetSoundTypes()
            }
        }

        override fun after() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                SettingsManager.resetSoundTypes()
                typesBefore.forEach { (name, type) -> SettingsManager.setSoundType(name, type) }
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

    private fun search(text: String) {
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput(text)
        rule.waitForIdle()
    }

    @Test
    fun previewNoteIsShownAboveTheList() {
        openTab()
        rule.onNodeWithText(string(R.string.classify_preview_note)).assertExists()
    }

    @Test
    fun pickTypeFromDropdownThenResetAll() {
        val (siren, label) = sound("Civil defense siren")
        val danger = string(R.string.sound_type_danger)
        val ambient = string(R.string.sound_type_ambient)
        openTab()
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
        rule.runOnUiThread { SettingsManager.setSoundType(doorbell.name, AiClassification.DANGER) }
        val danger = string(R.string.sound_type_danger)
        val ambient = string(R.string.sound_type_ambient)
        openTab()

        // 거르기 칸의 "위협음" 을 누른다. 줄 안의 종류 글자는 줄의 설명으로 묶여 있어 여기 걸리지 않는다.
        rule.onNodeWithText(danger).performClick()

        search(doorbellLabel)
        rule.onNodeWithContentDescription(string(R.string.cd_classify_sound_changed, doorbellLabel, danger, ambient)).assertExists()

        search(rainLabel)
        rule.onNodeWithText(string(R.string.classify_empty)).assertExists()

        rule.onNodeWithText(string(R.string.classify_filter_all)).performClick()
        rule.onNodeWithContentDescription(string(R.string.cd_classify_sound, rainLabel, ambient)).assertExists()
        assertEquals(AiClassification.AMBIENT, rain.defaultType)
    }
}

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
import com.example.soundvisualizer.ai.YamnetThreeClassMapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 분류 탭을 실제 화면에서 조작한다. 기기 언어와 상관없이 돌도록 기대하는 글자는 모두 리소스에서 만든다.
 *
 * 앱의 실제 설정을 쓰므로, 시작할 때 저장돼 있던 선택을 챙겨 두고 비운 뒤 끝나면 그대로 되돌린다.
 */
@RunWith(AndroidJUnit4::class)
class ClassifyTabInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** 테스트 전에 기기에 저장돼 있던 선택. 끝나면 그대로 되돌린다. */
    private var saved: Map<String, String> = emptyMap()

    @Before
    fun clearBefore() {
        rule.runOnUiThread {
            saved = SettingsManager.soundTypes.value
            SettingsManager.resetSoundTypes()
        }
    }

    @After
    fun restoreAfter() {
        rule.runOnUiThread {
            SettingsManager.resetSoundTypes()
            saved.forEach { (name, type) -> SettingsManager.setSoundType(name, type) }
        }
    }

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
        assertEquals(
            "켜 둔 분류기도 다음 판정부터 따른다",
            AiClassification.AMBIENT,
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
        assertEquals(AiClassification.DANGER, YamnetThreeClassMapper.mapDisplayNameToCoarse(siren.name))
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

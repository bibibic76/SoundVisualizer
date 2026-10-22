package com.example.soundvisualizer

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.soundvisualizer.feedback.EasyVibration
import com.example.soundvisualizer.feedback.HapticMode
import com.example.soundvisualizer.feedback.HapticPlayer
import com.example.soundvisualizer.feedback.HapticSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * 설정 탭의 쉬운 설정(#408). 그림으로 선택하는 화면이 화면 읽어주기로도 쓸 수 있고, 누르면 정해 둔 값을 바꾸는지 본다.
 *
 * - 맨 위 두 칸, 모양 카드, 크기 세 단계는 48dp 이상의 라디오 단추이고, 소리 종류 줄은 종류 이름으로 읽히는 스위치다.
 * - 자세한 설정에만 있는 것(모드 설정 제목, 모드별 슬라이더)은 보이지 않는다.
 * - 크기 단계는 선택한 모드에만 쓰고, 크기 칸은 선택한 모드의 크기를 보여 준다.
 * - 크기와 진동 세기가 자세한 설정에서 단계와 다르게 맞춰져 있으면 어느 칸도 선택해 두지 않고 한 줄로 알린다(#424).
 *
 * 진동 칸은 누르지 않는다. 누르면 2초 미리보기가 폰에서 울린다. 저장된 세기에 맞는 칸이 선택되어 보이는지만 보고,
 * 진동 단계의 규칙은 EasyVibrationTest 가 본다.
 * 시험이 바꾸는 값(튜토리얼을 봤는지, 설정 화면, 모드, 네 모드의 설정, 표시 여부, 진동, 외부 사운드 모드)은 모두 기억했다가
 * 되돌린다.
 * 기기 언어와 상관없이 돌도록 글자는 모두 리소스에서 만든다.
 */
@RunWith(AndroidJUnit4::class)
class EasySettingsInstrumentedTest {

    private val settings = object : ExternalResource() {
        private var tutorialBefore = true
        private var easyBefore = SettingsManager.EASY_SETTINGS_DEFAULT
        private var modeBefore = VisualMode.Wave
        private var modesBefore: List<ModeSettings> = emptyList()
        private var shownBefore: List<Boolean> = emptyList()
        private var hapticsBefore: Map<String, HapticSettings> = emptyMap()
        private var externalBefore = false

        override fun before() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                SettingsManager.init(InstrumentationRegistry.getInstrumentation().targetContext)
                tutorialBefore = SettingsManager.tutorialSeen.value
                easyBefore = SettingsManager.easySettings.value
                modeBefore = SettingsManager.visualMode.value
                modesBefore = modes()
                shownBefore = listOf(SettingsManager.showAmbient.value, SettingsManager.showSpeech.value, SettingsManager.showDanger.value)
                hapticsBefore = LABELS.associateWith { SettingsManager.hapticSettings(it).value }
                externalBefore = SettingsManager.externalSoundMode.value
                SettingsManager.setTutorialSeen(true)
                SettingsManager.setEasySettings(true)
                SettingsManager.setVisualMode(VisualMode.Wave)
                setModes(List(MODE_COUNT) { ModeSettings() })
                SettingsManager.updateAISettings(showAmbient = true, showSpeech = true, showDanger = true)
            }
        }

        override fun after() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                setModes(modesBefore)
                SettingsManager.setVisualMode(modeBefore)
                SettingsManager.updateAISettings(showAmbient = shownBefore[0], showSpeech = shownBefore[1], showDanger = shownBefore[2])
                hapticsBefore.forEach { (label, haptic) -> SettingsManager.updateHaptic(label, haptic) }
                SettingsManager.setExternalSoundMode(externalBefore)
                SettingsManager.setEasySettings(easyBefore)
                SettingsManager.setTutorialSeen(tutorialBefore)
            }
        }
    }

    private val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(settings).around(rule)

    private companion object {
        val LABELS = listOf(AiClassification.AMBIENT, AiClassification.SPEECH, AiClassification.DANGER)
        val MODE_COUNT = VisualMode.values().size

        fun modes(): List<ModeSettings> = listOf(
            SettingsManager.waveMode.value, SettingsManager.padMode.value,
            SettingsManager.circleMode.value, SettingsManager.outlineMode.value
        )

        /** 네 모드의 설정을 그대로 되돌리고 저장한다. 메인 스레드에서 부른다. */
        fun setModes(values: List<ModeSettings>) {
            val updates = listOf<(ModeSettings.() -> Unit) -> Unit>(
                SettingsManager::updateWaveMode, SettingsManager::updatePadMode,
                SettingsManager::updateCircleMode, SettingsManager::updateOutlineMode
            )
            updates.zip(values).forEach { (update, s) ->
                update {
                    intensity = s.intensity; speed = s.speed; opacity = s.opacity; circleRadius = s.circleRadius
                    useRippleDelay = s.useRippleDelay; sensitivity = s.sensitivity; isGlowMode = s.isGlowMode
                    glowIntensity = s.glowIntensity; intensityAsOpacity = s.intensityAsOpacity
                    opacityFixedSize = s.opacityFixedSize; opacityFixedMaxOpacity = s.opacityFixedMaxOpacity
                }
            }
            SettingsManager.flushModeSettings()
        }
    }

    private fun string(id: Int): String = rule.activity.getString(id)

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun openSettings() {
        rule.onNodeWithText(string(R.string.tab_settings)).performClick()
        rule.waitForIdle()
    }

    /**
     * 작은 화면(CI 기본 에뮬레이터 320×640)에서는 아래 칸이 화면 밖이라 목록을 먼저 민다.
     *
     * 목록은 찾는 칸을 품은 세로 목록으로 고른다. 쉬운 설정 전체가 목록의 한 항목이라, 아래로 밀면 맨 위 두 칸의 항목이
     * 목록에서 빠진다. 두 칸의 글자로 목록을 고르면 밀고 난 뒤 목록을 다시 찾지 못한다. 탭 화면(가로)과 옆 탭의 목록에는
     * 쉬운 설정의 칸이 없다.
     */
    private fun scrolledTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val list = hasScrollToNodeAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            hasAnyDescendant(matcher)
        rule.onNode(list).performScrollToNode(matcher)
        return rule.onNode(matcher)
    }

    private fun choice(id: Int) = hasText(string(id)) and isSelectable()

    @Test
    fun simpleViewIsMadeOfNamedChoicesAndSwitches() {
        openSettings()
        // 맨 위 두 칸은 둘 중 하나를 고르는 라디오 단추다. 쉬운 설정이 선택되어 있다.
        rule.onNode(choice(R.string.settings_view_easy)).assertIsSelected()
            .assert(hasRole(Role.RadioButton)).assertHeightIsAtLeast(48.dp)
        rule.onNode(choice(R.string.settings_view_detailed)).assertIsNotSelected()
            .assert(hasRole(Role.RadioButton)).assertHeightIsAtLeast(48.dp)
        rule.onNode(hasText(string(R.string.easy_section_shape)) and isHeading).assertExists()

        // 모양 카드는 모드 이름으로 읽히는 라디오 단추다. 그림은 읽지 않는다.
        for (mode in VisualMode.values()) {
            scrolledTo(choice(mode.labelRes)).assert(hasRole(Role.RadioButton)).assertHeightIsAtLeast(48.dp)
        }
        rule.onNode(choice(VisualMode.Wave.labelRes)).assertIsSelected()

        // 크기 세 단계도 라디오 단추이고, 처음 값은 보통이다.
        for (step in EasySize.values()) {
            scrolledTo(choice(step.labelRes)).assert(hasRole(Role.RadioButton)).assertHeightIsAtLeast(48.dp)
        }
        rule.onNode(choice(EasySize.Medium.labelRes)).assertIsSelected()

        // 소리 종류 줄은 줄 전체가 스위치이고, 자세한 설정과 같은 이름("위협음 표시")으로 읽힌다.
        for (id in listOf(R.string.ai_show_danger, R.string.ai_show_speech, R.string.ai_show_ambient)) {
            scrolledTo(hasContentDescription(string(id)) and isToggleable())
                .assert(hasRole(Role.Switch)).assertHeightIsAtLeast(48.dp)
        }

        // 자세한 설정에만 있는 것은 없다. 자세한 설정의 첫 항목(모드 설정 제목)과 펼쳐진 파도 카드를 본다. 그 아래 항목
        // (배터리, 색 단추)은 작은 화면에서는 자세한 설정에서도 처음에 만들어지지 않아 없다는 확인이 늘 통과한다.
        rule.onNode(hasText(string(R.string.settings_section_mode)) and isHeading).assertDoesNotExist()
        rule.onNode(hasText(string(R.string.settings_section_mode_detail))).assertDoesNotExist()
        rule.onNode(hasContentDescription(string(R.string.setting_size))).assertDoesNotExist()
    }

    @Test
    fun detailedViewIsOneTapAwayAndRemembered() {
        openSettings()
        rule.onNode(choice(R.string.settings_view_detailed)).performClick()
        rule.waitForIdle()
        rule.onNode(hasText(string(R.string.settings_section_mode)) and isHeading).assertExists()
        assertFalse("자세한 설정을 저장하지 않았다", SettingsManager.easySettings.value)

        rule.onNode(choice(R.string.settings_view_easy)).performClick()
        rule.waitForIdle()
        rule.onNode(hasText(string(R.string.easy_section_shape)) and isHeading).assertExists()
        assertTrue("쉬운 설정을 저장하지 않았다", SettingsManager.easySettings.value)
    }

    @Test
    fun modeCardSelectsTheMode() {
        openSettings()
        scrolledTo(choice(VisualMode.Pad.labelRes)).performClick()
        rule.waitForIdle()
        assertEquals(VisualMode.Pad, SettingsManager.visualMode.value)
        rule.onNode(choice(VisualMode.Pad.labelRes)).assertIsSelected()
        rule.onNode(choice(VisualMode.Wave.labelRes)).assertIsNotSelected()
    }

    @Test
    fun sizeStepIsWrittenToTheSelectedModeOnly() {
        // 크기는 모드마다 따로다(사용자 결정 2026-10-22). 처음에는 파도가 선택되어 있다.
        openSettings()
        scrolledTo(choice(EasySize.Large.labelRes)).performClick()
        rule.waitForIdle()
        val after = modes()
        assertEquals("파도가 크게가 아니다", EasySize.Large, EasySize.of(after[VisualMode.Wave.ordinal]))
        for (mode in listOf(VisualMode.Pad, VisualMode.CircleRipple, VisualMode.Outline)) {
            assertEquals("$mode 의 크기도 바뀌었다", ModeSettings(), after[mode.ordinal])
        }
        rule.onNode(choice(EasySize.Large.labelRes)).assertIsSelected()
    }

    @Test
    fun sizeRowFollowsTheSelectedMode() {
        // 자세한 설정에서 패드의 크기만 바꿔 둔 사람이다. 패드를 선택하면 크기 칸은 어느 단계도 아니다.
        rule.runOnUiThread {
            SettingsManager.updatePadMode { intensity = 63f }
            SettingsManager.flushModeSettings()
        }
        openSettings()
        val customNote = hasText(string(R.string.easy_size_custom))
        rule.onNode(choice(EasySize.Medium.labelRes)).assertIsSelected()
        rule.onNode(customNote).assertDoesNotExist()

        // 어느 칸도 선택해 두지 않고, 자세한 설정에서 맞춘 크기라고 한 줄로 알린다(#424).
        scrolledTo(choice(VisualMode.Pad.labelRes)).performClick()
        rule.waitForIdle()
        for (step in EasySize.values()) scrolledTo(choice(step.labelRes)).assertIsNotSelected()
        scrolledTo(customNote).assertExists()
        // 크기 제목은 어느 모드의 크기인지와 함께 읽힌다("크기, 패드").
        val sizeTitle = "${string(R.string.easy_section_size)}, ${string(VisualMode.Pad.labelRes)}"
        rule.onNode(hasContentDescription(sizeTitle) and isHeading).assertExists()

        // 패드에 작게를 누르면 패드만 바뀌고 안내는 사라진다. 파도로 돌아가면 파도의 크기(보통)가 보인다.
        scrolledTo(choice(EasySize.Small.labelRes)).performClick()
        rule.waitForIdle()
        assertEquals(EasySize.Small, EasySize.of(SettingsManager.padMode.value))
        assertEquals(EasySize.Medium, EasySize.of(SettingsManager.waveMode.value))
        rule.onNode(customNote).assertDoesNotExist()
        scrolledTo(choice(VisualMode.Wave.labelRes)).performClick()
        rule.waitForIdle()
        scrolledTo(choice(EasySize.Medium.labelRes)).assertIsSelected()
    }

    @Test
    fun vibrationStepsShowEachTypesStoredStrength() {
        // 위협음은 100%(세게), 대화음은 40%(약하게), 환경음은 꺼 둔 채 세기만 남아 있다(끔).
        setHaptics(speechLevel = 40)
        openSettings()
        // 줄은 위협음, 대화음, 환경음 순서다.
        assertVibrationSteps(listOf(EasyVibration.High, EasyVibration.Low, EasyVibration.Off))
        rule.onNode(hasText(string(R.string.easy_vibration_custom))).assertDoesNotExist()
    }

    @Test
    fun vibrationStrengthSetInDetailedLeavesTheStepsEmpty() {
        // 자세한 설정에서 대화음을 70% 로 맞춘 사람이다. 가장 가까운 '세게'로 보이지 않고, 어느 칸도 선택되지 않는다(#424).
        setHaptics(speechLevel = 70)
        openSettings()
        assertVibrationSteps(listOf(EasyVibration.High, null, EasyVibration.Off))
        // 그 줄에만 자세한 설정에서 맞춘 세기라고 알린다. 진동이 없는 기기(CI 에뮬레이터)에서는 진동이 없다는 안내가 앞선다.
        val notes = if (HapticPlayer(rule.activity).hasVibrator) 1 else 0
        rule.onAllNodes(hasText(string(R.string.easy_vibration_custom))).assertCountEquals(notes)
    }

    /** 위협음은 중간 100%, 대화음은 느림 [speechLevel]%, 환경음은 꺼 둔 채 세기 60. 외부 사운드 모드는 끈다. */
    private fun setHaptics(speechLevel: Int) {
        rule.runOnUiThread {
            SettingsManager.setExternalSoundMode(false)
            SettingsManager.updateHaptic(AiClassification.DANGER, HapticSettings(HapticMode.Medium, HapticSettings.MAX_LEVEL))
            SettingsManager.updateHaptic(AiClassification.SPEECH, HapticSettings(HapticMode.Slow, speechLevel))
            SettingsManager.updateHaptic(AiClassification.AMBIENT, HapticSettings(HapticMode.Off, 60))
        }
    }

    /** 위협음·대화음·환경음 줄마다 선택되어 보이는 칸. null 이면 그 줄은 어느 칸도 선택되지 않는다. 칸은 누르지 않는다. */
    private fun assertVibrationSteps(expected: List<EasyVibration?>) {
        val hasVibrator = HapticPlayer(rule.activity).hasVibrator
        for (step in EasyVibration.values()) {
            val cells = rule.onAllNodes(choice(step.labelRes)).assertCountEquals(expected.size)
            expected.forEachIndexed { row, shown ->
                if (step == shown) cells[row].assertIsSelected() else cells[row].assertIsNotSelected()
                // 진동이 없는 기기(CI 에뮬레이터)에서는 누를 수 없다.
                if (!hasVibrator) cells[row].assertIsNotEnabled()
            }
        }
    }

    @Test
    fun typeRowTurnsTheTypeOnAndOff() {
        openSettings()
        val danger = hasContentDescription(string(R.string.ai_show_danger)) and isToggleable()
        scrolledTo(danger).performClick()
        rule.waitForIdle()
        assertFalse(SettingsManager.showDanger.value)
        rule.onNode(danger).performClick()
        rule.waitForIdle()
        assertTrue(SettingsManager.showDanger.value)
    }
}

package com.example.soundvisualizer

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.soundvisualizer.feedback.HapticMode
import com.example.soundvisualizer.feedback.HapticPlayer
import com.example.soundvisualizer.feedback.HapticSettingRow
import com.example.soundvisualizer.feedback.HapticSettings
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 진동 줄의 세기 슬라이더를 화면 읽어주기가 화면과 같은 값으로 읽는지. 진동은 울리지 않는다(누르지 않는다).
 *
 * 슬라이더는 값 글자를 따로 주지 않으면 범위 안의 위치를 퍼센트로 읽는다. 세기는 10%부터라 50% 가 "44퍼센트" 로 읽혔다(#242).
 */
@RunWith(AndroidJUnit4::class)
class HapticSettingRowInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val label = AiClassification.DANGER
    private lateinit var before: HapticSettings

    @Before
    fun setUp() {
        // 진동 모터가 없는 기기에서는 세기 슬라이더가 나오지 않는다.
        assumeTrue(HapticPlayer(context).hasVibrator)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            SettingsManager.init(context)
            before = SettingsManager.hapticSettings(label).value
        }
    }

    @After
    fun tearDown() {
        if (!::before.isInitialized) return
        InstrumentationRegistry.getInstrumentation().runOnMainSync { SettingsManager.updateHaptic(label, before) }
    }

    @Test
    fun strengthSliderReadsTheShownPercent() {
        for (level in listOf(10, 50, 100)) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                SettingsManager.updateHaptic(label, HapticSettings(HapticMode.Medium, level))
            }
            if (level == 10) rule.setContent { HapticSettingRow(label, shown = true) }
            rule.waitForIdle()
            val shown = context.getString(R.string.haptic_level_percent, level)
            // 종류 이름이 붙는다(#312): "위협음, 세기".
            val name = context.getString(R.string.sound_type_danger) + ", " + context.getString(R.string.haptic_strength)
            rule.onNodeWithContentDescription(name)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, shown))
        }
    }
}

package com.example.soundvisualizer

import com.example.soundvisualizer.feedback.HapticMode
import com.example.soundvisualizer.feedback.HapticSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val KEY = "easy_settings"

/**
 * 설정 탭의 쉬운 설정·자세한 설정 선택의 저장·복원(#408).
 *
 * 새로 설치한 사람과 모드별 상세 값을 바꾼 적이 없는 사람은 쉬운 설정으로 연다. 바꾼 적이 있는 사람은 손에 익은 자세한
 * 설정으로 연다. 이 판단은 처음 한 번만 하고 곧바로 저장한다(분류 탭 고급 모드와 같은 방식, ClassifyAdvancedSettingTest).
 */
class EasySettingsSettingTest {

    @After
    fun restoreSingleton() {
        // SettingsManager 는 싱글턴이라 테스트끼리 상태를 공유한다 (ScreenOffPauseTest 와 같은 이유).
        SettingsManager.load(MemoryPrefs())
    }

    @Test
    fun `새로 설치하면 쉬운 설정이다`() {
        assertTrue("기본값을 끄려면 이 테스트부터 고쳐야 한다", SettingsManager.EASY_SETTINGS_DEFAULT)
        assertTrue("저장값이 없으면 쉬운 설정", SettingsManager.loadEasySettings(MemoryPrefs()))

        val prefs = MemoryPrefs()
        SettingsManager.load(prefs, deviceTag = "this-phone")
        assertTrue(SettingsManager.easySettings.value)
        assertTrue("정한 값을 곧바로 저장해야 한다", prefs.contains(KEY))
        assertTrue(prefs.getBoolean(KEY, false))
        // 튜토리얼 판단보다 먼저 적으면 새로 설치한 앱을 쓰던 사람으로 보고 튜토리얼을 건너뛴다(TutorialSettingTest).
        assertFalse(SettingsManager.tutorialSeen.value)
    }

    @Test
    fun `모드별 상세 값을 하나라도 바꾼 사람은 자세한 설정으로 연다`() {
        for (key in listOf("wave_speed", "pad_intensity", "circle_radius", "outline_glow_intensity")) {
            val prefs = MemoryPrefs()
            prefs.edit().putFloat(key, 77f).apply()
            assertFalse(key, SettingsManager.loadEasySettings(prefs))

            SettingsManager.load(prefs)
            assertFalse(key, SettingsManager.easySettings.value)
            assertTrue("정한 값을 곧바로 저장해야 한다", prefs.contains(KEY))
        }
        val glow = MemoryPrefs()
        glow.edit().putBoolean("pad_glow", true).apply()
        assertFalse("스위치도 바꾼 값이다", SettingsManager.loadEasySettings(glow))
    }

    @Test
    fun `처음 값과 같은 저장값이나 쉬운 설정에도 보이는 값만 바꾼 사람은 쉬운 설정이다`() {
        val prefs = MemoryPrefs()
        val d = ModeSettings()
        prefs.edit()
            .putFloat("wave_intensity", d.intensity)
            .putFloat("circle_radius", d.circleRadius)
            .putInt("visualMode", VisualMode.Outline.ordinal)
            .putInt("color_danger", 0xFF00FF00.toInt())
            .putString("haptic_speech_mode", HapticMode.Fast.name)
            .apply()
        assertTrue(SettingsManager.loadEasySettings(prefs))
    }

    @Test
    fun `저장한 값이 있으면 그 값을 쓴다`() {
        val easy = MemoryPrefs()
        easy.edit().putBoolean(KEY, true).putFloat("wave_speed", 90f).apply()
        assertTrue("바꾼 값이 있어도 저장한 값이 이긴다", SettingsManager.loadEasySettings(easy))

        val detailed = MemoryPrefs()
        detailed.edit().putBoolean(KEY, false).apply()
        assertFalse(SettingsManager.loadEasySettings(detailed))
    }

    @Test
    fun `처음 정한 값은 쉬운 설정에서 크기를 바꾼 뒤에도 그대로다`() {
        // 저장해 두지 않으면 다음에 열 때 크기가 처음 값과 다르다는 이유로 자세한 설정이 된다.
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.applyEasySize(VisualMode.Wave, EasySize.Large)

        SettingsManager.load(prefs)
        assertTrue(SettingsManager.easySettings.value)
    }

    @Test
    fun `고른 화면을 저장하고 다시 열 때 읽는다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setEasySettings(false)
        assertFalse(SettingsManager.easySettings.value)

        SettingsManager.load(MemoryPrefs())
        assertTrue(SettingsManager.easySettings.value)
        SettingsManager.load(prefs)
        assertFalse("다시 열면 자세한 설정이 풀렸다", SettingsManager.easySettings.value)

        SettingsManager.setEasySettings(true)
        SettingsManager.load(prefs)
        assertTrue(SettingsManager.easySettings.value)
    }

    @Test
    fun `다른 기기로 옮기거나 다시 깔아도 남긴다`() {
        // 사용자가 고른 화면이라 기기나 설치에만 뜻이 있는 값처럼 비우지 않는다(RestoredDeviceSettingsTest 와 같은 경우).
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs, deviceTag = "old-phone", installStamp = 1_000L)
        SettingsManager.setEasySettings(false)

        SettingsManager.load(prefs, deviceTag = "new-phone", installStamp = 1_000L)
        assertFalse("다른 기기에서 옮겨 왔다고 지웠다", SettingsManager.easySettings.value)

        SettingsManager.load(prefs, deviceTag = "new-phone", installStamp = 2_000L)
        assertFalse("다시 깔았다고 지웠다", SettingsManager.easySettings.value)
    }

    private fun modes() = listOf(
        SettingsManager.waveMode.value, SettingsManager.padMode.value,
        SettingsManager.circleMode.value, SettingsManager.outlineMode.value
    )

    @Test
    fun `크기 단계는 그 모드에만 쓰고 곧바로 저장한다`() {
        // 크기는 모드마다 따로다(사용자 결정 2026-10-22). 다른 모드는 자세한 설정에서 맞춘 값 그대로다.
        for (mode in VisualMode.values()) {
            val prefs = MemoryPrefs()
            prefs.edit()
                .putFloat("wave_intensity", 63f)
                .putFloat("pad_speed", 80f)
                .putBoolean("circle_intensity_as_opacity", true)
                .putFloat("circle_radius", 66f)
                .putFloat("outline_opacity", 90f)
                .apply()
            SettingsManager.load(prefs)
            val before = modes()

            SettingsManager.applyEasySize(mode, EasySize.Small)

            val after = modes()
            val expected = before.mapIndexed { i, s -> if (i == mode.ordinal) EasySize.Small.applyTo(s) else s }
            assertEquals("$mode: 그 모드의 크기 단계 말고는 바꾸지 않는다", expected, after)
            assertEquals(mode.name, EasySize.Small, EasySize.of(after[mode.ordinal]))

            // 미루지 않고 저장했으므로 다시 읽어도 같다.
            SettingsManager.load(MemoryPrefs())
            SettingsManager.load(prefs)
            assertEquals("$mode: 저장하지 않았다", after, modes())
        }
    }

    @Test
    fun `화면을 바꿔도 다른 설정은 그대로다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.updateHaptic(AiClassification.SPEECH, HapticSettings(HapticMode.Fast, 70))
        val before = prefs.all.filterKeys { it != KEY }

        SettingsManager.setEasySettings(false)
        SettingsManager.setEasySettings(true)

        assertEquals(before, prefs.all.filterKeys { it != KEY })
    }
}

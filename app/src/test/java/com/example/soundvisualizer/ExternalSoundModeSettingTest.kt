package com.example.soundvisualizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 외부 사운드 모드 설정의 저장·복원(#226).
 *
 * 켜면 마이크로 주변 소리를 듣는다. 사용자가 고르지 않았는데 켜져 있으면 안 되므로 기본값이 꺼짐인 것을 코드로 못박는다.
 */
class ExternalSoundModeSettingTest {

    @Test
    fun `새로 설치하면 꺼져 있다`() {
        assertFalse("기본값을 켜려면 이 테스트부터 고쳐야 한다", SettingsManager.EXTERNAL_SOUND_MODE_DEFAULT)
        assertFalse("저장값이 없으면 기본값", SettingsManager.loadExternalSoundMode(MemoryPrefs()))
        // SettingsManager 는 싱글턴이라 이 값은 테스트끼리 공유한다. 흐름의 초기값이 위 상수와 같은지만 본다.
        assertFalse("흐름의 초기값도 같은 상수를 쓴다", SettingsManager.externalSoundMode.value)
    }

    @Test
    fun `저장해 둔 설정을 그대로 읽는다`() {
        val prefs = MemoryPrefs()
        prefs.edit().putBoolean("external_sound_mode", true).apply()
        assertTrue("켜 둔 채로 앱을 다시 열면 켜져 있어야 한다", SettingsManager.loadExternalSoundMode(prefs))
    }

    @Test
    fun `다른 설정과 키가 겹치지 않는다`() {
        // 키가 겹치면 한쪽을 켤 때 다른 쪽이 따라 켜진다.
        val prefs = MemoryPrefs()
        prefs.edit().putBoolean("external_sound_mode", true).apply()

        assertTrue(SettingsManager.loadExternalSoundMode(prefs))
        assertTrue("화면 꺼짐 설정은 기본값 그대로여야 한다", SettingsManager.loadPauseWhenScreenOff(prefs))
        assertFalse("개발자 모드는 기본값 그대로여야 한다", SettingsManager.loadDeveloperMode(prefs))
    }
}

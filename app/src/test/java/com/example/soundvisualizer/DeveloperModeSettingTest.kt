package com.example.soundvisualizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 개발자 모드 설정의 저장·복원.
 *
 * 켜 두고 잊으면 사용자에게 영어 글자판이 그대로 남으므로, 기본값이 꺼짐인 것을 코드로 못박는다.
 */
class DeveloperModeSettingTest {

    @Test
    fun `새로 설치하면 꺼져 있다`() {
        assertFalse("기본값을 켜려면 이 테스트부터 고쳐야 한다", SettingsManager.DEVELOPER_MODE_DEFAULT)
        assertFalse("저장값이 없으면 기본값", SettingsManager.loadDeveloperMode(MemoryPrefs()))
        // SettingsManager 는 싱글턴이라 이 값은 테스트끼리 공유한다. 흐름의 초기값이 위 상수와
        // 같은지만 본다 (ScreenOffPauseTest 와 같은 방식).
        assertFalse("흐름의 초기값도 같은 상수를 쓴다", SettingsManager.developerMode.value)
    }

    @Test
    fun `저장해 둔 설정을 그대로 읽는다`() {
        val prefs = MemoryPrefs()
        prefs.edit().putBoolean("developer_mode", true).apply()
        assertTrue("켜 둔 채로 앱을 다시 열면 켜져 있어야 한다", SettingsManager.loadDeveloperMode(prefs))
    }

    @Test
    fun `화면 꺼짐 일시정지와 다른 키를 쓴다`() {
        // 키가 겹치면 한쪽을 켤 때 다른 쪽이 따라 켜진다.
        val prefs = MemoryPrefs()
        prefs.edit().putBoolean("developer_mode", true).apply()

        assertTrue(SettingsManager.loadDeveloperMode(prefs))
        assertTrue(
            "개발자 모드를 켜도 화면 꺼짐 설정은 기본값 그대로여야 한다",
            SettingsManager.loadPauseWhenScreenOff(prefs)
        )
    }
}

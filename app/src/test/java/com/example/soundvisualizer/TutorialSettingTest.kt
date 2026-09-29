package com.example.soundvisualizer

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 튜토리얼을 본 적이 있는지의 저장·복원.
 *
 * 처음 여는 사람에게만 튜토리얼을 저절로 띄운다. 이 기능 전부터 쓰던 사람은 이미 앱을 알므로 띄우지 않는다.
 * 이 판단은 저장된 값이 있는지로 하는데, 새로 설치한 앱도 첫 실행에 기기 표시가 적히므로 한 번 정한 값을
 * 바로 저장해 두지 않으면 두 번째 실행부터 "쓰던 사람"으로 잘못 친다.
 */
class TutorialSettingTest {

    @Test
    fun `새로 설치하면 아직 보지 않은 것이다`() {
        assertFalse(SettingsManager.loadTutorialSeen(MemoryPrefs()))
    }

    @Test
    fun `이 기능 전부터 쓰던 사람은 본 것으로 친다`() {
        val prefs = MemoryPrefs()
        prefs.edit().putInt("visualMode", 1).apply()
        assertTrue("설정을 바꿔 쓰던 사람에게 튜토리얼이 뜬다", SettingsManager.loadTutorialSeen(prefs))
    }

    @Test
    fun `저장한 값이 있으면 그 값을 쓴다`() {
        val notSeen = MemoryPrefs()
        notSeen.edit().putBoolean(KEY, false).putInt("visualMode", 1).apply()
        assertFalse("다른 설정이 있어도 저장한 값이 이긴다", SettingsManager.loadTutorialSeen(notSeen))

        val seen = MemoryPrefs()
        seen.edit().putBoolean(KEY, true).apply()
        assertTrue(SettingsManager.loadTutorialSeen(seen))
    }

    @Test
    fun `처음 정한 값은 기기 표시가 적힌 뒤에도 바뀌지 않는다`() {
        // 새로 설치하고 튜토리얼을 닫지 않은 채 나갔다가 다시 여는 경우
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs, deviceTag = "this-phone")
        assertFalse(SettingsManager.tutorialSeen.value)
        assertTrue("기기 표시가 적혀 있어야 이 테스트가 뜻이 있다", prefs.contains("device_tag"))

        SettingsManager.load(prefs, deviceTag = "this-phone")
        assertFalse("기기 표시만 보고 본 것으로 쳤다", SettingsManager.tutorialSeen.value)
    }

    @Test
    fun `쓰던 사람은 불러올 때 본 것으로 저장한다`() {
        val prefs = MemoryPrefs()
        prefs.edit().putBoolean("show_danger", false).apply()
        SettingsManager.load(prefs)

        assertTrue(SettingsManager.tutorialSeen.value)
        assertTrue("정한 값을 저장해야 다음에도 같게 본다", prefs.getBoolean(KEY, false))
    }

    @Test
    fun `닫으면 본 것으로 저장한다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setTutorialSeen(true)

        SettingsManager.load(prefs)
        assertTrue("다시 열었을 때 또 뜬다", SettingsManager.tutorialSeen.value)
    }

    @Test
    fun `다른 기기에서 옮겨 와도 본 기록은 남긴다`() {
        // 본 사람에 대한 값이라, 기기 전용 값처럼 비우면 새 폰에서 또 뜬다.
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs, deviceTag = "old-phone")
        SettingsManager.setTutorialSeen(true)

        SettingsManager.load(prefs, deviceTag = "new-phone")
        assertTrue(SettingsManager.tutorialSeen.value)
    }

    @After
    fun restoreSingleton() {
        // SettingsManager 는 싱글턴이라 테스트끼리 상태를 공유한다 (ScreenOffPauseTest 와 같은 이유).
        SettingsManager.load(MemoryPrefs())
    }

    private companion object {
        const val KEY = "tutorial_seen"
    }
}

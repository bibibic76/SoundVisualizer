package com.example.soundvisualizer

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 분류 탭 고급 모드 설정의 저장·복원(#349).
 *
 * 처음에는 모두 묶음째 고르는 기본 모드로 연다. 다만 묶음이 생기기 전에 소리를 하나씩 바꿔 둔 사람은 고급 모드로 열어,
 * 묶음째 고르다 그 선택을 모르는 새 덮어쓰지 않게 한다. 이 판단은 처음 한 번만 하고 곧바로 저장한다.
 */
class ClassifyAdvancedSettingTest {

    @After
    fun restoreSingleton() {
        // SettingsManager 는 싱글턴이라 테스트끼리 상태를 공유한다 (ScreenOffPauseTest 와 같은 이유).
        SettingsManager.load(MemoryPrefs())
    }

    @Test
    fun `새로 설치하면 기본 모드다`() {
        assertFalse("기본값을 켜려면 이 테스트부터 고쳐야 한다", SettingsManager.CLASSIFY_ADVANCED_DEFAULT)
        assertFalse("저장값이 없으면 기본값", SettingsManager.loadClassifyAdvanced(MemoryPrefs()))

        val prefs = MemoryPrefs()
        SettingsManager.load(prefs, deviceTag = "this-phone")
        assertFalse(SettingsManager.classifyAdvanced.value)
        assertTrue("정한 값을 곧바로 저장해야 한다", prefs.contains(KEY))
        assertFalse(prefs.getBoolean(KEY, true))
        // 튜토리얼 판단보다 먼저 적으면 새로 설치한 앱을 쓰던 사람으로 보고 튜토리얼을 건너뛴다(TutorialSettingTest).
        assertFalse(SettingsManager.tutorialSeen.value)
    }

    @Test
    fun `소리를 하나씩 바꿔 둔 사람은 고급 모드로 연다`() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("sound_types", """{"Doorbell":"danger"}""").apply()
        assertTrue(SettingsManager.loadClassifyAdvanced(prefs))

        SettingsManager.load(prefs)
        assertTrue(SettingsManager.classifyAdvanced.value)
        assertTrue("정한 값을 곧바로 저장해야 한다", prefs.getBoolean(KEY, false))
    }

    @Test
    fun `기본 종류와 같아 버려지는 저장값만 있으면 기본 모드다`() {
        // loadSoundTypes 가 버리는 값은 바꾼 소리가 아니다.
        val prefs = MemoryPrefs()
        prefs.edit().putString("sound_types", """{"Siren":"${SettingsManager.defaultSoundType("Siren")}","Rain":"loud"}""").apply()
        assertFalse(SettingsManager.loadClassifyAdvanced(prefs))
    }

    @Test
    fun `저장한 값이 있으면 그 값을 쓴다`() {
        val basic = MemoryPrefs()
        basic.edit().putBoolean(KEY, false).putString("sound_types", """{"Doorbell":"danger"}""").apply()
        assertFalse("바꾼 소리가 있어도 저장한 값이 이긴다", SettingsManager.loadClassifyAdvanced(basic))

        val advanced = MemoryPrefs()
        advanced.edit().putBoolean(KEY, true).apply()
        assertTrue(SettingsManager.loadClassifyAdvanced(advanced))
    }

    @Test
    fun `처음 정한 값은 기본 모드에서 묶음을 바꾼 뒤에도 그대로다`() {
        // 저장해 두지 않으면 다음에 열 때 바꾼 소리가 있다는 이유로 고급 모드가 된다.
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setSoundTypes(listOf("Doorbell", "Ding-dong", "Knock", "Tap"), AiClassification.DANGER)

        SettingsManager.load(prefs)
        assertFalse(SettingsManager.classifyAdvanced.value)
    }

    @Test
    fun `고른 모드를 저장하고 다시 열 때 읽는다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setClassifyAdvanced(true)
        assertTrue(SettingsManager.classifyAdvanced.value)

        // 프로세스가 새로 뜬 것처럼 빈 값으로 읽은 뒤 같은 프리퍼런스로 다시 읽는다.
        SettingsManager.load(MemoryPrefs())
        assertFalse(SettingsManager.classifyAdvanced.value)
        SettingsManager.load(prefs)
        assertTrue("다시 열면 고급 모드가 풀렸다", SettingsManager.classifyAdvanced.value)

        SettingsManager.setClassifyAdvanced(false)
        SettingsManager.load(prefs)
        assertFalse(SettingsManager.classifyAdvanced.value)
    }

    @Test
    fun `다른 기기로 옮기거나 다시 깔아도 남긴다`() {
        // 사용자가 고른 화면이라 기기나 설치에만 뜻이 있는 값처럼 비우지 않는다(RestoredDeviceSettingsTest 와 같은 경우).
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs, deviceTag = "old-phone", installStamp = 1_000L)
        SettingsManager.setClassifyAdvanced(true)

        SettingsManager.load(prefs, deviceTag = "new-phone", installStamp = 1_000L)
        assertTrue("다른 기기에서 옮겨 왔다고 지웠다", SettingsManager.classifyAdvanced.value)

        SettingsManager.load(prefs, deviceTag = "new-phone", installStamp = 2_000L)
        assertTrue("다시 깔았다고 지웠다", SettingsManager.classifyAdvanced.value)
    }

    @Test
    fun `모두 되돌려도 모드는 그대로다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setClassifyAdvanced(true)
        SettingsManager.setSoundType("Doorbell", AiClassification.DANGER)

        SettingsManager.resetSoundTypes()

        assertTrue(SettingsManager.classifyAdvanced.value)
        assertTrue(prefs.getBoolean(KEY, false))
    }

    @Test
    fun `다른 설정과 키가 겹치지 않는다`() {
        // 키가 겹치면 한쪽을 켤 때 다른 쪽이 따라 켜진다.
        val prefs = MemoryPrefs()
        prefs.edit().putBoolean(KEY, true).apply()

        assertTrue(SettingsManager.loadClassifyAdvanced(prefs))
        assertFalse("개발자 모드는 기본값 그대로여야 한다", SettingsManager.loadDeveloperMode(prefs))
        assertTrue("바꾼 소리는 없어야 한다", SettingsManager.loadSoundTypes(prefs).isEmpty())
    }

    private companion object {
        const val KEY = "classify_advanced"
    }
}

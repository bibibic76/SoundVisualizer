package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification
import com.example.soundvisualizer.MemoryPrefs
import com.example.soundvisualizer.SettingsManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * '계속 반복' 을 '소리 따라' 로 바꿔도 저장된 선택이 이어지는지. 설정은 enum 이름으로 저장하므로 이름이 바뀌면
 * 그 선택지를 골라 둔 사람의 설정이 기본값으로 돌아간다.
 */
class HapticPatternStorageTest {

    @After
    fun restoreSingleton() {
        SettingsManager.load(MemoryPrefs())
    }

    @Test
    fun `예전에 저장한 계속 반복은 소리 따라로 읽힌다`() {
        val prefs = MemoryPrefs()
        prefs.edit().putBoolean("haptic_${AiClassification.DANGER}_enabled", true)
            .putString("haptic_${AiClassification.DANGER}_pattern", "Repeat").apply()
        SettingsManager.load(prefs)
        assertEquals(HapticPattern.Repeat, SettingsManager.hapticSettings(AiClassification.DANGER).value.pattern)
    }

    @Test
    fun `모르는 이름은 기본값으로 돌아간다`() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("haptic_${AiClassification.DANGER}_pattern", "Follow").apply()
        SettingsManager.load(prefs)
        assertEquals(
            HapticSettings.defaultFor(AiClassification.DANGER).pattern,
            SettingsManager.hapticSettings(AiClassification.DANGER).value.pattern
        )
    }

    @Test
    fun `패턴 이름은 저장값과 맞물린 그대로다`() {
        assertEquals(listOf("Tap", "DoubleTap", "Hold", "Repeat"), HapticPattern.values().map { it.name })
        assertEquals(listOf("Weak", "Medium", "Strong"), HapticStrength.values().map { it.name })
    }
}

package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification
import com.example.soundvisualizer.MemoryPrefs
import com.example.soundvisualizer.SettingsManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DANGER = AiClassification.DANGER
private const val SPEECH = AiClassification.SPEECH
private const val AMBIENT = AiClassification.AMBIENT

/**
 * 진동 설정의 저장과, #242 전의 설정(켜기·패턴·약중강)을 방식과 세기로 옮기는 것. 설정은 enum 이름으로 저장하므로
 * 이름이 바뀌면 그 선택지를 골라 둔 사람의 설정이 기본값으로 돌아간다.
 */
class HapticSettingsStorageTest {

    @After
    fun restoreSingleton() {
        SettingsManager.load(MemoryPrefs())
    }

    private fun legacy(label: String, enabled: Boolean?, pattern: String?, strength: String?): MemoryPrefs {
        val prefs = MemoryPrefs()
        val e = prefs.edit()
        enabled?.let { e.putBoolean("haptic_${label}_enabled", it) }
        pattern?.let { e.putString("haptic_${label}_pattern", it) }
        strength?.let { e.putString("haptic_${label}_strength", it) }
        e.apply()
        return prefs
    }

    // ---------------------------------------------------------------
    // 기본값
    // ---------------------------------------------------------------

    @Test
    fun `기본값은 위협음만 중간으로 진동한다`() {
        assertEquals(HapticSettings(HapticMode.Medium, 100), HapticSettings.defaultFor(DANGER))
        assertFalse(HapticSettings.defaultFor(SPEECH).enabled)
        assertFalse(HapticSettings.defaultFor(AMBIENT).enabled)
    }

    @Test
    fun `모르는 라벨은 환경음 기본값을 쓴다`() {
        assertEquals(HapticSettings.defaultFor(AMBIENT), HapticSettings.defaultFor("unknown"))
    }

    @Test
    fun `새로 설치하면 기본값을 쓰고 아무것도 적지 않는다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        for (label in listOf(DANGER, SPEECH, AMBIENT)) {
            assertEquals(HapticSettings.defaultFor(label), SettingsManager.hapticSettings(label).value)
            assertFalse(prefs.contains("haptic_${label}_mode"))
        }
    }

    // ---------------------------------------------------------------
    // 저장
    // ---------------------------------------------------------------

    @Test
    fun `고친 설정은 다시 읽어도 같다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.updateHaptic(SPEECH, HapticSettings(HapticMode.Continuous, 70))
        SettingsManager.load(prefs)
        assertEquals(HapticSettings(HapticMode.Continuous, 70), SettingsManager.hapticSettings(SPEECH).value)
    }

    @Test
    fun `모르는 방식 이름은 기본값으로 돌아간다`() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("haptic_${DANGER}_mode", "Throb").putInt("haptic_${DANGER}_level", 40).apply()
        SettingsManager.load(prefs)
        assertEquals(HapticSettings(HapticSettings.defaultFor(DANGER).mode, 40), SettingsManager.hapticSettings(DANGER).value)
    }

    @Test
    fun `슬라이더에 없는 세기는 가까운 칸으로 맞춘다`() {
        assertEquals(40, HapticSettings.clampLevel(37))
        assertEquals(HapticSettings.MIN_LEVEL, HapticSettings.clampLevel(0))
        assertEquals(HapticSettings.MAX_LEVEL, HapticSettings.clampLevel(255))
    }

    @Test
    fun `방식 이름은 저장값과 맞물린 그대로다`() {
        assertEquals(listOf("Off", "Slow", "Medium", "Fast", "Continuous"), HapticMode.values().map { it.name })
    }

    // ---------------------------------------------------------------
    // #242 전의 설정 옮기기
    // ---------------------------------------------------------------

    @Test
    fun `꺼 두었던 진동은 꺼짐이 되고 세기는 이어진다`() {
        val settings = HapticSettings.fromLegacy(DANGER, enabled = false, pattern = "DoubleTap", strength = "Weak")
        assertEquals(HapticSettings(HapticMode.Off, 30), settings)
    }

    @Test
    fun `소리 따라는 연속이 되고 한 번 두 번 길게는 중간이 된다`() {
        assertEquals(HapticMode.Continuous, HapticSettings.fromLegacy(SPEECH, true, "Repeat", null)!!.mode)
        for (p in listOf("Tap", "DoubleTap", "Hold")) {
            assertEquals(p, HapticMode.Medium, HapticSettings.fromLegacy(SPEECH, true, p, null)!!.mode)
        }
    }

    @Test
    fun `약 중 강은 30 60 100 퍼센트가 된다`() {
        assertEquals(30, HapticSettings.fromLegacy(DANGER, true, "Tap", "Weak")!!.level)
        assertEquals(60, HapticSettings.fromLegacy(DANGER, true, "Tap", "Medium")!!.level)
        assertEquals(100, HapticSettings.fromLegacy(DANGER, true, "Tap", "Strong")!!.level)
    }

    @Test
    fun `저장된 것이 없으면 옮기지 않는다`() {
        assertNull(HapticSettings.fromLegacy(DANGER, null, null, null))
    }

    @Test
    fun `일부만 저장돼 있으면 나머지는 그 종류의 기본값을 따른다`() {
        // 켜기만 저장돼 있던 위협음: 기본 방식과 기본 세기.
        assertEquals(HapticSettings.defaultFor(DANGER), HapticSettings.fromLegacy(DANGER, true, null, null))
        // 패턴만 저장돼 있던 말소리: 예전 기본값이 꺼짐이었으므로 그대로 꺼짐.
        assertEquals(HapticMode.Off, HapticSettings.fromLegacy(SPEECH, null, "Repeat", null)!!.mode)
    }

    @Test
    fun `예전 설정을 읽으면 새 이름으로 옮겨 적고 예전 키를 지운다`() {
        val prefs = legacy(SPEECH, enabled = true, pattern = "Repeat", strength = "Strong")
        SettingsManager.load(prefs)

        assertEquals(HapticSettings(HapticMode.Continuous, 100), SettingsManager.hapticSettings(SPEECH).value)
        assertEquals("Continuous", prefs.getString("haptic_${SPEECH}_mode", null))
        assertEquals(100, prefs.getInt("haptic_${SPEECH}_level", -1))
        for (old in listOf("enabled", "pattern", "strength")) {
            assertFalse(old, prefs.contains("haptic_${SPEECH}_$old"))
        }
    }

    @Test
    fun `옮긴 뒤에는 새 설정만 읽는다`() {
        val prefs = legacy(DANGER, enabled = true, pattern = "Hold", strength = "Weak")
        SettingsManager.load(prefs)
        SettingsManager.updateHaptic(DANGER, HapticSettings(HapticMode.Fast, 50))
        SettingsManager.load(prefs)
        assertEquals(HapticSettings(HapticMode.Fast, 50), SettingsManager.hapticSettings(DANGER).value)
        assertTrue(prefs.contains("haptic_${DANGER}_mode"))
    }
}

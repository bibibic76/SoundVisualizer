package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiDiagnosticConfig
import com.example.soundvisualizer.ai.AiFrontendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDiagnosticSettingTest {

    @Test
    fun `새 설치는 기존 production 경로를 유지한다`() {
        assertEquals(AiDiagnosticConfig.DEFAULT, SettingsManager.AI_DIAGNOSTIC_CONFIG_DEFAULT)
        assertEquals(AiDiagnosticConfig.DEFAULT, SettingsManager.loadAiDiagnosticConfig(MemoryPrefs()))
        assertEquals(AiFrontendMode.CURRENT, AiDiagnosticConfig.DEFAULT.frontendMode)
        assertTrue(AiDiagnosticConfig.DEFAULT.boosterEnabled)
    }

    @Test
    fun `frontend와 Booster 선택을 저장하고 복원한다`() {
        val prefs = MemoryPrefs()
        prefs.edit()
            .putString("ai_frontend_mode", AiFrontendMode.QUALCOMM_SOURCE.name)
            .putBoolean("ai_booster_enabled", false)
            .apply()

        val loaded = SettingsManager.loadAiDiagnosticConfig(prefs)

        assertEquals(AiFrontendMode.QUALCOMM_SOURCE, loaded.frontendMode)
        assertFalse(loaded.boosterEnabled)
    }

    @Test
    fun `모르는 frontend 이름은 production 기본값으로 돌아간다`() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("ai_frontend_mode", "REMOVED_MODE").apply()

        assertEquals(AiFrontendMode.CURRENT, SettingsManager.loadAiDiagnosticConfig(prefs).frontendMode)
    }

    @Test
    fun `setter는 하나의 snapshot과 preferences를 함께 갱신한다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        try {
            SettingsManager.setAiFrontendMode(AiFrontendMode.QUALCOMM_SOURCE)
            SettingsManager.setAiBoosterEnabled(false)

            assertEquals(
                AiDiagnosticConfig(AiFrontendMode.QUALCOMM_SOURCE, boosterEnabled = false),
                SettingsManager.aiDiagnosticConfig.value
            )
            assertEquals(AiFrontendMode.QUALCOMM_SOURCE.name, prefs.getString("ai_frontend_mode", null))
            assertFalse(prefs.getBoolean("ai_booster_enabled", true))
        } finally {
            SettingsManager.load(MemoryPrefs())
        }
    }

    @Test
    fun `개발자 모드를 끄면 저장된 선택 대신 production 기본값을 사용한다`() {
        val prefs = MemoryPrefs()
        prefs.edit()
            .putString("ai_frontend_mode", AiFrontendMode.QUALCOMM_SOURCE.name)
            .putBoolean("ai_booster_enabled", false)
            .putBoolean("developer_mode", false)
            .apply()
        SettingsManager.load(prefs)
        try {
            assertEquals(AiDiagnosticConfig.DEFAULT, SettingsManager.activeAiDiagnosticConfig())

            SettingsManager.setDeveloperMode(true)
            assertEquals(
                AiDiagnosticConfig(AiFrontendMode.QUALCOMM_SOURCE, boosterEnabled = false),
                SettingsManager.activeAiDiagnosticConfig()
            )
        } finally {
            SettingsManager.load(MemoryPrefs())
        }
    }
}

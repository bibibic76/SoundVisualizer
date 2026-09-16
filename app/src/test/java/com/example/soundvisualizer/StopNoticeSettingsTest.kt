package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 사용자 모르게 꺼진 이유를 저장하고 다시 읽는 규칙 (SettingsManager.lastUnexpectedStop).
 *
 * 앱 알림을 꺼 두면 꺼짐 알림도 토스트도 뜨지 못해, 앱을 열었을 때 보이는 홈 안내가 무엇이 꺼졌는지
 * 알 수 있는 유일한 곳이다. 프로세스가 죽어도 남도록 이름으로 저장하므로, 항목 이름을 바꾸면
 * 저장해 둔 안내가 조용히 사라진다. 기기 없이 가짜 프리퍼런스(MemoryPrefs)로 검사한다.
 */
class StopNoticeSettingsTest {

    @Test
    fun `이유를 이름으로 저장하고 그대로 읽는다`() {
        // 사용자가 직접 끈 경우는 알리지 않으므로 저장되지 않는다 (StopAlertPlan.decide 가 거른다).
        // 저장·복원을 확인해야 하는 것은 안내가 뜨는 이유들뿐이다.
        for (reason in StopReason.values().filter { it != StopReason.UserRequested }) {
            val prefs = MemoryPrefs()
            SettingsManager.putLastUnexpectedStop(prefs.edit(), reason)

            assertEquals("$reason 는 이름으로 저장한다", reason.name, prefs.getString(KEY, null))
            assertEquals(reason, SettingsManager.loadLastUnexpectedStop(prefs))
        }
    }

    @Test
    fun `저장된 적이 없으면 알릴 것이 없다`() {
        assertNull(SettingsManager.loadLastUnexpectedStop(MemoryPrefs()))
    }

    @Test
    fun `모르는 이름이면 알릴 것이 없는 것으로 본다`() {
        // 항목 이름을 바꾼 뒤 예전 이름이 남아 있는 경우. 크래시하지 않고 안내만 뜨지 않는다.
        val prefs = MemoryPrefs()
        prefs.edit().putString(KEY, "ProjectionStoppedOld")

        assertNull(SettingsManager.loadLastUnexpectedStop(prefs))
    }

    @Test
    fun `null 이면 저장해 둔 이유를 지운다`() {
        // 다시 켜거나 홈에서 닫은 경우. 남겨 두면 다음에 앱을 열 때 지난 안내가 다시 뜬다.
        val prefs = MemoryPrefs()
        SettingsManager.putLastUnexpectedStop(prefs.edit(), StopReason.ProjectionStopped)
        SettingsManager.putLastUnexpectedStop(prefs.edit(), null)

        assertFalse("키가 남아 있다", prefs.contains(KEY))
        assertNull(SettingsManager.loadLastUnexpectedStop(prefs))
    }

    private companion object {
        /** 저장 키를 바꾸면 업데이트한 사용자의 안내가 사라진다. */
        const val KEY = "last_unexpected_stop"
    }
}

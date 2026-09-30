package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AiUnavailableNoticeTest {

    @Test
    fun `큰 소리 안내는 HapticPolicy 가 실제로 울릴 때만 쓴다`() {
        for (shown in listOf(true, false)) {
            for (enabled in listOf(true, false)) {
                val cfg: (String) -> HapticPolicy.ClassConfig = {
                    HapticPolicy.ClassConfig(shown, HapticSettings(enabled, HapticStrength.Strong, HapticPattern.DoubleTap))
                }
                val fires = HapticPolicy().onTick(0, null, 0.9f, cfg, unlabeledAlerts = true) != null
                assertEquals("표시 $shown, 진동 $enabled", fires, AiUnavailableNotice.loudAlerts(shown, enabled, hasVibrator = true))
            }
        }
        assertFalse("진동 모터가 없으면 울리지 않는다", AiUnavailableNotice.loudAlerts(true, true, hasVibrator = false))
    }

    @Test
    fun `울리지 않으면 진동 알림이 꺼져 있다고 알린다`() {
        assertEquals(R.string.home_ai_unavailable_loud, AiUnavailableNotice.home(true))
        assertEquals(R.string.home_ai_unavailable, AiUnavailableNotice.home(false))
        assertEquals(R.string.notification_text_ai_unavailable_loud, AiUnavailableNotice.notification(true))
        assertEquals(R.string.notification_text_ai_unavailable, AiUnavailableNotice.notification(false))
        assertEquals(R.string.haptic_ai_unavailable_other, AiUnavailableNotice.otherRow(true))
        assertEquals(R.string.haptic_ai_unavailable, AiUnavailableNotice.otherRow(false))
    }
}

package com.example.soundvisualizer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.soundvisualizer.feedback.HapticMode
import com.example.soundvisualizer.feedback.HapticPlayer
import com.example.soundvisualizer.feedback.HapticSettings
import com.example.soundvisualizer.feedback.HapticShapes
import com.example.soundvisualizer.feedback.HapticTuning
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 진동으로 보내는 모든 모양이 이 기기의 VibrationEffect 로 만들어지는지. 진동은 울리지 않는다.
 * 안드로이드는 잘못된 파형(길이 불일치, 음수, 255 초과)을 만들 때 예외를 던지는데, 그러면 그 진동은 조용히 빠진다.
 */
@RunWith(AndroidJUnit4::class)
class HapticEffectInstrumentedTest {

    private val player = HapticPlayer(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun 모든_방식과_세기가_진동효과로_만들어진다() {
        if (!player.hasVibrator) return
        assertNotNull(player.buildEffect(HapticShapes.STOPPED_ALERT))
        val levels = HapticSettings.MIN_LEVEL..HapticSettings.MAX_LEVEL step HapticSettings.LEVEL_STEP
        for (amp in listOf(true, false)) for (mode in HapticMode.values()) for (level in levels) {
            val settings = HapticSettings(mode, level)
            HapticShapes.preview(settings, amp)?.let {
                assertNotNull("미리보기 $mode $level amp=$amp", player.buildEffect(it))
            }
            if (mode == HapticMode.Off) continue
            val ms = if (mode == HapticMode.Continuous) HapticTuning.CONTINUOUS_CHUNK_MS else HapticTuning.onMs(mode)
            assertNotNull("$mode $level amp=$amp", player.buildEffect(HapticShapes.steady(level, ms, amp)))
        }
    }
}

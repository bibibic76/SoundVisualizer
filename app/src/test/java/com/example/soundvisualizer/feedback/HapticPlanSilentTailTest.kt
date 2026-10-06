package com.example.soundvisualizer.feedback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 10 은 5초보다 짧은 진동을 터치 진동으로 보아 '터치 진동' 설정을 따르게 한다(#302).
 * 그래서 Android 10 에서는 끝에 쉼을 붙여 5초를 넘겨 보낸다([HapticPlayer]). 쉼을 붙여도 울리는 부분은 같아야 한다.
 */
class HapticPlanSilentTailTest {

    /** 앱이 진동기로 보내는 모든 계획: 멈춤 알림, 모든 방식·세기의 미리보기와 한 번 울림. */
    private fun everyPlanTheAppSends(): List<Pair<String, HapticPlan>> {
        val out = mutableListOf("멈춤 알림" to HapticShapes.STOPPED_ALERT)
        val levels = HapticSettings.MIN_LEVEL..HapticSettings.MAX_LEVEL step HapticSettings.LEVEL_STEP
        for (amp in listOf(true, false)) for (mode in HapticMode.values()) for (level in levels) {
            HapticShapes.preview(HapticSettings(mode, level), amp)?.let { out += "미리보기 $mode $level amp=$amp" to it }
            if (mode == HapticMode.Off) continue
            val ms = if (mode == HapticMode.Continuous) HapticTuning.CONTINUOUS_CHUNK_MS else HapticTuning.onMs(mode)
            out += "$mode $level amp=$amp" to HapticShapes.steady(level, ms, amp)
        }
        return out
    }

    @Test
    fun `짧은 계획은 끝에 쉼을 붙여 정확히 그 길이가 된다`() {
        val plan = HapticPlan(longArrayOf(200), intArrayOf(180))
        val padded = plan.withSilentTail(5_000)
        assertArrayEquals(longArrayOf(200, 4_800), padded.timings)
        assertArrayEquals(intArrayOf(180, 0), padded.amplitudes)
        assertEquals(5_000L, padded.durationMs)
    }

    @Test
    fun `이미 충분히 길면 그대로 둔다`() {
        val plan = HapticPlan(longArrayOf(30_000), intArrayOf(200))
        assertSame(plan, plan.withSilentTail(5_000))
    }

    @Test
    fun `앱이 보내는 모든 진동이 Android 10 의 터치 진동 길이를 넘는다`() {
        for ((name, plan) in everyPlanTheAppSends()) {
            val padded = plan.withSilentTail(ANDROID_10_TOUCH_FEEDBACK_MS)
            assertTrue("$name: ${padded.durationMs}ms", padded.durationMs >= ANDROID_10_TOUCH_FEEDBACK_MS)
        }
    }

    @Test
    fun `쉼을 붙여도 울리는 부분과 세기 조절 여부는 같다`() {
        for ((name, plan) in everyPlanTheAppSends()) {
            val padded = plan.withSilentTail(ANDROID_10_TOUCH_FEEDBACK_MS)
            assertEquals(name, plan.binary, padded.binary)
            var t = 0L
            while (t < plan.durationMs) {
                assertEquals("$name @${t}ms", plan.amplitudeAt(t), padded.amplitudeAt(t))
                t += 10
            }
            if (plan.durationMs < ANDROID_10_TOUCH_FEEDBACK_MS) {
                assertEquals("$name 붙인 쉼", 0, padded.amplitudeAt(plan.durationMs))
                assertEquals("$name 끝 직전", 0, padded.amplitudeAt(padded.durationMs - 1))
            } else {
                assertSame("$name 은 이미 길어 그대로", plan, padded)
            }
        }
    }

    @Test
    fun `세기 조절이 없는 기기에서도 붙인 쉼은 마지막 쉼으로 이어진다`() {
        val plan = HapticShapes.steady(level = 10, durationMs = 200, amplitudeControl = false)
        assertArrayEquals(longArrayOf(0, 200, 4_800), plan.withSilentTail(5_000).toOnOffTimings())
    }
}

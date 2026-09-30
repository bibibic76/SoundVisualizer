package com.example.soundvisualizer.feedback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticShapesTest {

    @Test
    fun `꺼짐 알림은 예전 그대로다`() {
        assertArrayEquals(longArrayOf(0, 300, 150, 300, 150, 300), HapticShapes.STOPPED_ALERT.timings)
        assertArrayEquals(intArrayOf(0, 255, 0, 255, 0, 255), HapticShapes.STOPPED_ALERT.amplitudes)
    }

    @Test
    fun `꺼짐 알림은 쉼이 울림보다 짧아 어느 방식과도 박자가 다르다`() {
        // 꺼짐 알림: 0.3초 울림, 0.15초 쉼. 사용자가 고르는 방식은 모두 쉼이 울림보다 길거나(느림·중간·빠름) 쉼이 없다(연속).
        val t = HapticShapes.STOPPED_ALERT.timings
        assertTrue(t[2] < t[1])
        for (mode in listOf(HapticMode.Slow, HapticMode.Medium, HapticMode.Fast)) {
            val on = HapticTuning.onMs(mode)
            assertTrue("$mode", HapticTuning.periodMs(mode) - on > on)
        }
    }

    @Test
    fun `한결같은 울림은 한 칸이고 세기를 따른다`() {
        val plan = HapticShapes.steady(level = 60, durationMs = 200, amplitudeControl = true)
        assertArrayEquals(longArrayOf(200), plan.timings)
        assertArrayEquals(intArrayOf(HapticTuning.amplitude(60)), plan.amplitudes)
        assertEquals(false, plan.binary)
    }

    @Test
    fun `세기 조절이 없으면 켜고 끄기로 울린다`() {
        val plan = HapticShapes.steady(level = 10, durationMs = 200, amplitudeControl = false)
        assertTrue(plan.binary)
        assertArrayEquals(longArrayOf(0, 200), plan.toOnOffTimings())
    }

    @Test
    fun `꺼짐은 미리보기가 없다`() {
        assertNull(HapticShapes.preview(HapticSettings(HapticMode.Off, 60), amplitudeControl = true))
    }

    @Test
    fun `연속 미리보기는 끊김 없이 한 칸이다`() {
        val plan = HapticShapes.preview(HapticSettings(HapticMode.Continuous, 40), amplitudeControl = true)!!
        assertArrayEquals(longArrayOf(HapticTuning.PREVIEW_MS), plan.timings)
        assertArrayEquals(intArrayOf(HapticTuning.amplitude(40)), plan.amplitudes)
    }

    @Test
    fun `박자 미리보기는 실제와 같은 박자로 울리고 울림으로 끝난다`() {
        for (mode in listOf(HapticMode.Slow, HapticMode.Medium, HapticMode.Fast)) {
            val plan = HapticShapes.preview(HapticSettings(mode, 80), amplitudeControl = true)!!
            val on = HapticTuning.onMs(mode)
            val off = HapticTuning.periodMs(mode) - on
            for (i in plan.timings.indices) {
                val ringing = i % 2 == 0
                assertEquals("$mode 칸 $i", if (ringing) on else off, plan.timings[i])
                assertEquals("$mode 칸 $i", if (ringing) HapticTuning.amplitude(80) else 0, plan.amplitudes[i])
            }
            assertTrue("$mode 이 쉼으로 끝난다", plan.amplitudes.last() > 0)
            assertTrue("$mode 이 미리보기보다 길다", plan.durationMs <= HapticTuning.PREVIEW_MS)
            assertTrue("$mode 이 두 번도 울리지 않는다", plan.amplitudes.count { it > 0 } >= 2)
            // Android 10·11 은 칸이 많은 파형을 늘여 튼다. 옛 계획 한도(24칸) 안에 둔다.
            assertTrue("$mode 칸이 ${plan.timings.size}", plan.timings.size <= 24)
        }
    }

    @Test
    fun `세기 조절이 없는 기기의 박자 미리보기는 켜고 끄기다`() {
        val plan = HapticShapes.preview(HapticSettings(HapticMode.Medium, 10), amplitudeControl = false)!!
        assertTrue(plan.binary)
        assertTrue(plan.amplitudes.all { it == 0 || it == 255 })
        assertEquals(0L, plan.toOnOffTimings().first())
    }

    @Test
    fun `켜고 끄기 배열`() {
        val plan = HapticPlan(longArrayOf(100, 50, 100), intArrayOf(200, 0, 200))
        assertArrayEquals(longArrayOf(0, 100, 50, 100), plan.toOnOffTimings())
        assertEquals(250L, plan.durationMs)
        assertEquals(0, plan.amplitudeAt(120))
        assertEquals(200, plan.amplitudeAt(160))
        assertEquals(0, plan.amplitudeAt(250))
    }
}

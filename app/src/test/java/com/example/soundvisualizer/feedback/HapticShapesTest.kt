package com.example.soundvisualizer.feedback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 한 번·두 번·길게와 꺼짐 알림의 모양. */
class HapticShapesTest {

    private val oneShots = listOf(HapticPattern.Tap, HapticPattern.DoubleTap, HapticPattern.Hold)

    /** 200 이상이 200ms 넘게 이어지는 구간의 수. 꺼짐 알림을 사용자 패턴과 가르는 기준이다. */
    private fun plateaus(plan: HapticPlan): Int {
        var count = 0
        var run = 0L
        for (i in plan.timings.indices) {
            if (plan.amplitudes[i] >= 200) {
                run += plan.timings[i]
            } else {
                if (run >= 200) count++
                run = 0
            }
        }
        if (run >= 200) count++
        return count
    }

    @Test
    fun `꺼짐 알림은 예전 그대로다`() {
        assertArrayEquals(longArrayOf(0, 300, 150, 300, 150, 300), HapticShapes.STOPPED_ALERT.timings)
        assertArrayEquals(intArrayOf(0, 255, 0, 255, 0, 255), HapticShapes.STOPPED_ALERT.amplitudes)
    }

    @Test
    fun `세기 조절이 없는 한 번 패턴은 예전 배열과 똑같다`() {
        fun onOff(p: HapticPattern) = HapticShapes.oneShot(p, HapticStrength.Medium, amplitudeControl = false).toOnOffTimings()
        assertArrayEquals(longArrayOf(0, 60), onOff(HapticPattern.Tap))
        assertArrayEquals(longArrayOf(0, 60, 80, 60), onOff(HapticPattern.DoubleTap))
        assertArrayEquals(longArrayOf(0, 350), onOff(HapticPattern.Hold))
    }

    @Test
    fun `사용자 패턴은 꺼짐 알림과 구별된다`() {
        assertEquals(3, plateaus(HapticShapes.STOPPED_ALERT))
        for (s in HapticStrength.values()) {
            assertEquals(0, plateaus(HapticShapes.oneShot(HapticPattern.Tap, s, true)))
            assertEquals(0, plateaus(HapticShapes.oneShot(HapticPattern.DoubleTap, s, true)))
            assertTrue(plateaus(HapticShapes.oneShot(HapticPattern.Hold, s, true)) <= 1)
        }
    }

    @Test
    fun `치고 잦아든다`() {
        for (p in oneShots) for (s in HapticStrength.values()) {
            val plan = HapticShapes.oneShot(p, s, true)
            // 최고점은 처음 20ms 안에 온다.
            assertEquals("$p $s", plan.amplitudes.maxOrNull(), plan.amplitudes.first())
            assertEquals(20L, plan.timings.first())
            // 끝이 가장 약하다(0 쉼은 빼고).
            val nonZero = plan.amplitudes.filter { it > 0 }
            assertEquals("$p $s", nonZero.minOrNull(), plan.amplitudes.last())
            // 치는 세기는 몸통의 1.6배(255 까지).
            assertEquals(HapticTuning.oneShotStrike(s), plan.amplitudes.first())
        }
    }

    @Test
    fun `한 번은 예전보다 약하지 않다`() {
        for (s in HapticStrength.values()) {
            val plan = HapticShapes.oneShot(HapticPattern.Tap, s, true)
            val energy = plan.timings.indices.sumOf { plan.timings[it] * plan.amplitudes[it].toLong() * plan.amplitudes[it] }
            val old = 60L * s.amplitude * s.amplitude
            assertTrue("$s 에너지 $energy < 예전의 0.75배", energy >= old * 3 / 4)
            assertTrue(plan.amplitudes.max() >= s.amplitude)
        }
    }

    @Test
    fun `두 번은 두 톡 사이를 100ms 쉰다`() {
        val plan = HapticShapes.oneShot(HapticPattern.DoubleTap, HapticStrength.Strong, true)
        val gap = plan.timings.indices.filter { plan.amplitudes[it] == 0 }.sumOf { plan.timings[it] }
        assertEquals(100L, gap)
        assertEquals(260L, plan.durationMs)
    }

    @Test
    fun `소리 따라를 물으면 한 번 모양을 돌려준다`() {
        val once = HapticShapes.oneShot(HapticPattern.Tap, HapticStrength.Medium, true)
        val follow = HapticShapes.oneShot(HapticPattern.Repeat, HapticStrength.Medium, true)
        assertArrayEquals(once.amplitudes, follow.amplitudes)
    }
}

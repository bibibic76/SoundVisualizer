package com.example.soundvisualizer.feedback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticPlanTest {

    private val modern = HapticTuning.MODERN_LIMITS

    @Test
    fun `몸통의 거의 같은 이웃 칸은 합치고 앞머리는 합치지 않는다`() {
        val plan = HapticPlanBuilder(PlanReason.TEST, modern, true)
            .head(25, 180).head(25, 180)
            .body(20, 100).body(20, 101).body(20, 130)
            .build()
        assertArrayEquals(longArrayOf(25, 25, 40, 20), plan.timings)
        assertArrayEquals(intArrayOf(180, 180, 100, 130), plan.amplitudes)
        assertEquals(50L, plan.headMs)
    }

    @Test
    fun `몸통 세기는 앞머리를 건너뛴다`() {
        val plan = HapticPlanBuilder(PlanReason.TEST, modern, true).head(20, 3).head(25, 180).body(100, 90).build()
        assertEquals(3, plan.amplitudeAt(5))
        assertEquals(90, plan.bodyLevelAt(5))
        assertEquals(90, plan.bodyLevelAt(60))
        assertEquals(0, plan.amplitudeAt(plan.durationMs))
    }

    @Test
    fun `세기 모드에는 0 이 없고 약한 페이드 칸은 버린다`() {
        val plan = HapticPlanBuilder(PlanReason.TEST, modern, true)
            .body(100, 0)
            .fade(10, floatArrayOf(0.6f, 0.2f), 25)
            .build()
        assertTrue(plan.amplitudes.all { it >= HapticTuning.KEEP_ALIVE })
        assertEquals("0.2 x 10 = 2 는 버린다", 2, plan.timings.size)
    }

    @Test
    fun `칸이 넘치면 페이드를 남기고 몸통 끝을 줄인다`() {
        val b = HapticPlanBuilder(PlanReason.TEST, HapticTuning.LEGACY_LIMITS, true)
        for (i in 0 until 40) b.body(40, 40 + i * 5)
        b.fade(200, HapticTuning.LEGACY_LIMITS.endFade, 40)
        val plan = b.build()
        assertEquals(HapticTuning.LEGACY_LIMITS.maxSteps, plan.timings.size)
        val fadeTail = plan.amplitudes.takeLast(HapticTuning.LEGACY_LIMITS.endFade.size)
        assertEquals(HapticTuning.LEGACY_LIMITS.endFade.map { Math.round(200 * it) }, fadeTail)
    }

    @Test
    fun `켜고 끄기 배열`() {
        val plan = HapticPlan(longArrayOf(40, 120, 40), intArrayOf(255, 0, 255), PlanReason.START, binary = true)
        assertArrayEquals(longArrayOf(0, 40, 120, 40), plan.toOnOffTimings())
    }

    @Test
    fun `로그 한 줄`() {
        val plan = HapticPlanBuilder(PlanReason.ONSET, modern, true).head(25, 180).body(100, 90).build()
        assertEquals("plan ONSET t=12 dur=125 steps=2 head=25 first=180 max=180 last=90", plan.summary(12))
    }
}

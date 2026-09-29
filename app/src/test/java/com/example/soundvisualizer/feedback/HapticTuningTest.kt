package com.example.soundvisualizer.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/**
 * 진동 수치 사이의 관계. 폰에서 느껴 보고 [HapticTuning] 의 숫자를 바꿔도 이 관계는 지켜야 한다.
 */
class HapticTuningTest {

    @Test
    fun `세기 단계는 느낄 만큼 벌어진다`() {
        val w = HapticTuning.followLevels(HapticStrength.Weak)
        val m = HapticTuning.followLevels(HapticStrength.Medium)
        val s = HapticTuning.followLevels(HapticStrength.Strong)
        // 진동자는 1.4배는 되어야 다르게 느낀다.
        assertTrue(m.peak >= 1.4 * w.peak && s.peak >= 1.4 * m.peak)
        assertTrue(m.sustain >= 1.4 * w.sustain && s.sustain >= 1.4 * m.sustain)
        assertTrue(HapticTuning.oneShotBody(HapticStrength.Medium) >= 1.4 * HapticTuning.oneShotBody(HapticStrength.Weak))
        assertTrue(HapticTuning.oneShotBody(HapticStrength.Strong) >= 1.4 * HapticTuning.oneShotBody(HapticStrength.Medium))
    }

    @Test
    fun `바닥 지속 최고 순서이고 친 곳은 지속보다 도드라진다`() {
        for (st in HapticStrength.values()) {
            val l = HapticTuning.followLevels(st)
            assertTrue("$st", l.floor < l.sustain && l.sustain < l.peak && l.peak <= 255)
            assertTrue("$st 친 곳이 도드라지지 않는다", l.peak >= 1.35 * l.sustain)
            assertTrue("$st 바닥이 너무 약하다", l.floor >= 30)
        }
    }

    @Test
    fun `조용할 때 틱은 예전과 같은 100ms 다`() {
        assertEquals(100L, HapticTuning.tickPeriodMs(fast = false))
        assertTrue(HapticTuning.tickPeriodMs(fast = true) <= 20L)
    }

    @Test
    fun `페이드는 줄어들고 끝은 충분히 약하다`() {
        for (fade in listOf(HapticTuning.END_FADE, HapticTuning.RELEASE_FADE, HapticTuning.LEGACY_LIMITS.endFade)) {
            for (i in 1 until fade.size) assertTrue(fade[i] < fade[i - 1])
            assertTrue(fade.last() <= 0.35f)
        }
        // 울리는 도중 끝내려고 보내는 첫 칸도 이음매 규칙을 지킨다.
        assertTrue(-20 * log10(HapticTuning.END_FADE.first().toDouble()) >= HapticTuning.LEVEL_STEP_DB)
        assertTrue(-20 * log10(HapticTuning.RELEASE_FADE.first().toDouble()) >= HapticTuning.LEVEL_STEP_DB)
    }

    @Test
    fun `음높이 단계 간격은 이음매 기준보다 넓다`() {
        assertTrue(HapticTuning.PITCH_MIN_SPACING_DB > HapticTuning.LEVEL_STEP_DB)
    }

    @Test
    fun `옛 기기 한도는 50칸보다 한참 아래다`() {
        assertTrue(HapticTuning.LEGACY_LIMITS.maxSteps <= 30)
        assertEquals(HapticTuning.LEGACY_LIMITS, HapticTuning.limitsFor(29))
        assertEquals(HapticTuning.LEGACY_LIMITS, HapticTuning.limitsFor(30))
        assertEquals(HapticTuning.MODERN_LIMITS, HapticTuning.limitsFor(31))
    }

    @Test
    fun `눌림 세기는 0 이 아니고 시스템 세기에 줄어도 남는다`() {
        assertTrue(HapticTuning.KEEP_ALIVE * 0.6 >= 1.0)
    }
}

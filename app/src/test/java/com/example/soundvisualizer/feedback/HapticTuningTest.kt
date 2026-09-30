package com.example.soundvisualizer.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 진동 수치 사이의 관계. 폰에서 느껴 보고 [HapticTuning] 의 숫자를 바꿔도 이 관계는 지켜야 한다.
 */
class HapticTuningTest {

    @Test
    fun `느림은 중간의 절반 빠르기이고 빠름은 두 배 빠르기다`() {
        val medium = HapticTuning.periodMs(HapticMode.Medium)
        assertEquals(medium * 2, HapticTuning.periodMs(HapticMode.Slow))
        assertEquals(medium / 2, HapticTuning.periodMs(HapticMode.Fast))
        assertEquals(0L, HapticTuning.periodMs(HapticMode.Off))
        assertEquals(0L, HapticTuning.periodMs(HapticMode.Continuous))
    }

    @Test
    fun `빠름의 울림도 진동자가 올라와 느낄 만큼 길다`() {
        // 진동자는 올라오는 데 5~15ms 가 걸리고, 30ms 아래는 톡으로만 느껴진다.
        assertTrue(HapticTuning.onMs(HapticMode.Fast) >= 60L)
    }

    @Test
    fun `세기는 올릴수록 세고 가장 셀 때 255 다`() {
        val levels = (HapticSettings.MIN_LEVEL..HapticSettings.MAX_LEVEL step HapticSettings.LEVEL_STEP).toList()
        val amps = levels.map { HapticTuning.amplitude(it) }
        assertTrue("$amps", amps.zipWithNext().all { (a, b) -> b > a })
        assertEquals(255, amps.last())
        assertEquals(HapticTuning.MIN_AMPLITUDE, amps.first())
        // 폰의 진동 세기 설정이 한 번 더 줄이므로 가장 약한 세기도 너무 약하면 안 된다.
        assertTrue(HapticTuning.MIN_AMPLITUDE >= 30)
    }

    @Test
    fun `범위 밖의 세기는 끝값으로 친다`() {
        assertEquals(HapticTuning.amplitude(HapticSettings.MIN_LEVEL), HapticTuning.amplitude(0))
        assertEquals(255, HapticTuning.amplitude(150))
    }

    @Test
    fun `판단 주기는 예전과 같은 100ms 다`() {
        assertEquals(100L, HapticTuning.IDLE_TICK_MS)
    }

    @Test
    fun `연속은 드물게 다시 보내고 판단이 늦어도 끊기지 않는다`() {
        assertTrue(HapticTuning.CONTINUOUS_REFILL_MS >= 2 * HapticTuning.IDLE_TICK_MS)
        assertTrue(HapticTuning.CONTINUOUS_CHUNK_MS >= 10 * HapticTuning.CONTINUOUS_REFILL_MS)
    }

    @Test
    fun `미리보기는 느림도 두 번은 울린다`() {
        assertTrue(HapticTuning.PREVIEW_MS >= 2 * HapticTuning.periodMs(HapticMode.Slow))
    }
}

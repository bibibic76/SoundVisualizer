package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification
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

    @Test
    fun `외부 사운드 모드의 박자 방식은 울림 꼬리 뒤에도 듣는 쉼이 남는다`() {
        // 캡처 버퍼 하나는 48kHz 에서 10.7ms 다. 쉼에 버퍼가 세 개는 들어와야 소리가 이어지는지 볼 수 있다(#290).
        for (mode in listOf(HapticMode.Slow, HapticMode.Medium, HapticMode.Fast)) {
            val guard = HapticTuning.selfHearingGuardMs(mode)
            val listen = HapticTuning.periodMs(mode) - HapticTuning.onMs(mode) - guard - HapticTuning.SELF_HEARING_REOPEN_SLACK_MS
            assertTrue("$mode 의 듣는 쉼 ${listen}ms", listen >= 32)
            assertTrue("$mode 의 여유가 최대보다 길다", guard <= HapticTuning.SELF_HEARING_GUARD_MAX_MS)
        }
        // 빠름은 종류마다의 상한 때문에 외부 사운드 모드에서 울리지 않지만(#354), 상한을 바꿀 때를 위해 관계는 지킨다.
        assertTrue("빠름도 꼬리를 0.1초 넘게 막는다", HapticTuning.selfHearingGuardMs(HapticMode.Fast) >= 100)
    }

    @Test
    fun `외부 사운드 모드에서 울릴 수 있는 방식은 꼬리 뒤에 듣는 쉼이 0_1초 넘게 남는다`() {
        // 꼬리 여유 뒤에 듣는 쉼이 느림 0.42초, 중간 0.12초, 빠름 0.04초다. 빠름은 꼬리가 조금만 긴 폰에서도 쉼이 사라져
        // 자기 진동 소리로 이어질 수 있고 연속에는 쉼이 없어, 종류마다의 상한이 둘을 막는다(#354).
        for (label in listOf(AiClassification.AMBIENT, AiClassification.SPEECH, AiClassification.DANGER)) {
            val cap = HapticSettings.externalCap(label)
            for (mode in HapticMode.entries.filter { it != HapticMode.Off && it <= cap }) {
                val listen = HapticTuning.periodMs(mode) - HapticTuning.onMs(mode) - HapticTuning.selfHearingGuardMs(mode)
                assertTrue("$label 의 $mode 는 듣는 쉼이 ${listen}ms", listen >= 100)
            }
        }
    }

    @Test
    fun `외부 사운드 모드의 느림은 이어 준 소리의 여유가 다음 박자보다 넉넉히 먼저 끝난다`() {
        // 이어 준 크기는 꼬리 끝보다 SELF_HEARING_CREDIT_LEAD_MS 이른 소리로 친다. 거기서 소리가 끝났다고 보는 여유가 다음
        // 박자에 닿으면, 울림을 보내는 데 조금만 오래 걸려도 소리가 끝난 뒤 한 번 더 울린다(#290 리뷰). 중간·빠름은 다음 박자가
        // 늘 여유 안이라 박자마다 쉼에서 다시 듣는다.
        val slow = HapticMode.Slow
        val credited = HapticTuning.onMs(slow) + HapticTuning.selfHearingGuardMs(slow) - HapticTuning.SELF_HEARING_CREDIT_LEAD_MS
        val margin = HapticTuning.periodMs(slow) - (credited + HapticPolicy.RELEASE_MS)
        assertTrue("느림의 여유 ${margin}ms", margin >= 100)
    }
}

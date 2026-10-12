package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification
import com.example.soundvisualizer.ai.AiClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 외부 사운드 모드에서 AI 가 쉬면 진동과 잠금 화면 알림이 라벨을 쓰지 않는 규칙(#352). AI 는 쉬는 동안 같은 결과 객체를
 * 돌려주고, 추론할 때마다 새 객체를 낸다.
 */
class AiLabelFreshnessTest {

    /** 추론 한 번의 결과. 같은 내용이어도 추론마다 새 객체다. */
    private fun inference(coarse: String = AiClassification.SPEECH) =
        AiClassificationResult(coarse = coarse, display = "Speech", confidence = 0.9f, meetsThreshold = true, timestampMs = 0L)

    @Test
    fun `새 결과는 1초 동안 쓰고 그 뒤에는 라벨이 없다`() {
        val freshness = AiLabelFreshness()
        val first = inference()
        assertEquals(AiClassification.SPEECH, freshness.label(10_000, first))
        assertEquals(AiClassification.SPEECH, freshness.label(10_999, first))
        assertNull("1초 동안 새 결과가 없다", freshness.label(11_000, first))
        assertNull(freshness.label(11_100, first))
        val second = inference()
        assertEquals("새 결과는 바로 쓴다", AiClassification.SPEECH, freshness.label(11_200, second))
        assertEquals(AiClassification.SPEECH, freshness.label(12_199, second))
        assertNull(freshness.label(12_200, second))
    }

    @Test
    fun `내용이 같아도 새로 추론한 결과면 새 결과다`() {
        // 결과는 data class 라 내용이 같으면 equals 가 참이다. 새 결과인지는 객체로 가린다.
        val freshness = AiLabelFreshness()
        val first = inference()
        freshness.label(0, first)
        val same = inference()
        assertEquals(first, same)
        assertEquals(AiClassification.SPEECH, freshness.label(900, same))
        assertEquals("900ms 에 새로 받았으므로 1.5초에도 쓴다", AiClassification.SPEECH, freshness.label(1_500, same))
        assertNull(freshness.label(1_900, same))
    }

    @Test
    fun `결과가 없으면 라벨이 없고 그다음 결과는 새 결과다`() {
        val freshness = AiLabelFreshness()
        val result = inference(AiClassification.DANGER)
        assertEquals(AiClassification.DANGER, freshness.label(0, result))
        assertNull("AI 를 뗐거나 지웠다", freshness.label(500, null))
        assertEquals("같은 객체가 다시 와도 새 결과로 본다", AiClassification.DANGER, freshness.label(2_000, result))
        assertEquals(AiClassification.DANGER, freshness.label(2_999, result))
        assertNull(freshness.label(3_000, result))
    }

    @Test
    fun `AI 가 쉬며 같은 결과를 5초 동안 돌려주면 1초부터 라벨이 없다`() {
        val freshness = AiLabelFreshness()
        val stale = inference()
        var t = 0L
        while (t <= 5_000) {
            val label = freshness.label(t, stale)
            if (t < 1_000) {
                assertEquals("${t}ms", AiClassification.SPEECH, label)
            } else {
                assertNull("${t}ms", label)
            }
            t += 100
        }
    }

    @Test
    fun `다시 시작하면 지난 실행의 결과를 잊는다`() {
        val freshness = AiLabelFreshness()
        val result = inference()
        freshness.label(0, result)
        assertNull(freshness.label(1_000, result))
        freshness.reset()
        assertEquals(AiClassification.SPEECH, freshness.label(1_100, result))
    }
}

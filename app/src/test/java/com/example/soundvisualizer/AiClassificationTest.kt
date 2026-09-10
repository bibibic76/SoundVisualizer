package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiClassificationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 분류기와 렌더러를 잇는 지점. 오버레이는 매 프레임 [AiClassification.coarse] 만 부르고,
 * 분류기가 없거나 아직 준비되지 않았으면 환경음으로 떨어져야 한다.
 */
class AiClassificationTest {

    @After
    fun tearDown() {
        // object 라 테스트 사이에 상태가 남는다.
        AiClassification.detach()
    }

    private fun result(coarse: String) = AiClassificationResult(
        coarse = coarse,
        display = coarse,
        confidence = 1f,
        gunshotScore = 0f,
        preBoosterCoarse = coarse,
        boosterAccepted = false,
        meetsThreshold = true,
        useBoosterDangerPreview = false,
        timestampMs = 0L
    )

    @Test
    fun `분류기가 붙기 전에는 환경음이다`() {
        AiClassification.detach()
        assertEquals(AiClassification.AMBIENT, AiClassification.coarse())
    }

    @Test
    fun `붙인 분류기의 라벨을 그대로 돌려준다`() {
        AiClassification.attach { result(AiClassification.DANGER) }
        assertEquals(AiClassification.DANGER, AiClassification.coarse())

        AiClassification.attach { result(AiClassification.SPEECH) }
        assertEquals(AiClassification.SPEECH, AiClassification.coarse())
    }

    @Test
    fun `분류기를 떼면 다시 환경음으로 떨어진다`() {
        AiClassification.attach { result(AiClassification.DANGER) }
        assertEquals(AiClassification.DANGER, AiClassification.coarse())

        AiClassification.detach()
        assertEquals(AiClassification.AMBIENT, AiClassification.coarse())
    }

    @Test
    fun `아직 결과가 없으면 환경음이다`() {
        // 모델은 떴지만 첫 추론이 끝나지 않은 상태
        AiClassification.attach { null }
        assertEquals(AiClassification.AMBIENT, AiClassification.coarse())
    }

    @Test
    fun `매번 최신 결과를 읽는다`() {
        var current = AiClassification.AMBIENT
        AiClassification.attach { result(current) }

        assertEquals(AiClassification.AMBIENT, AiClassification.coarse())
        current = AiClassification.DANGER
        assertEquals(AiClassification.DANGER, AiClassification.coarse())
        current = AiClassification.SPEECH
        assertEquals(AiClassification.SPEECH, AiClassification.coarse())
    }

    @Test
    fun `라벨 상수는 서로 다르다`() {
        val labels = setOf(
            AiClassification.AMBIENT,
            AiClassification.SPEECH,
            AiClassification.DANGER
        )
        assertEquals(3, labels.size)
    }
}

package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiClassificationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart

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
        meetsThreshold = true,
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

    @Test
    fun `결과 알림과 detach 는 저장한 payload 대신 현재 source 를 읽게 한다`() = runBlocking {
        var current = result(AiClassification.SPEECH)
        AiClassification.attach { current }
        // Replay is just a wake-up; the provider may already have advanced.
        current = result(AiClassification.DANGER)
        withTimeout(2_000) { AiClassification.changes.first() }
        assertEquals(AiClassification.DANGER, AiClassification.latest()?.coarse)
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            // Drop the replay wake-up and wait for detach's signal.
            var count = 0
            AiClassification.changes.first { ++count == 2 }
        }
        AiClassification.detach()
        withTimeout(2_000) { next.await() }
        assertEquals(AiClassification.AMBIENT, AiClassification.coarse())
    }
}

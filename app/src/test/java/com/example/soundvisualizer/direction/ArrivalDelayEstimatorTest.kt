package com.example.soundvisualizer.direction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

private const val WINDOW = 2048

/** 시간차 범위. S25+ 의 두 마이크(약 14.3cm)를 48kHz 로 받을 때와 같다. */
private const val MAX_LAG = 24

/** 정규분포 잡음 [n] 개. 시드를 고정해 테스트가 늘 같은 소리를 쓴다. */
private fun noise(n: Int, seed: Long, scale: Float = 0.05f): FloatArray {
    val random = Random(seed)
    return FloatArray(n) { (random.nextGaussian() * scale).toFloat() }
}

/** 1차 저역 통과를 두 번 건다(48kHz 에서 약 1.7kHz 위를 깎는다). */
private fun lowPassed(x: FloatArray): FloatArray {
    val out = x.copyOf()
    repeat(2) {
        var y = 0f
        for (i in out.indices) {
            y += 0.2f * (out[i] - y)
            out[i] = y
        }
    }
    return out
}

/** 채널 0 은 [source], 채널 1 은 [delay] 샘플 늦게 받은 [source] 인 interleaved 스테레오. 앞쪽 여유를 둔다. */
private fun delayed(source: FloatArray, delay: Int, frames: Int, lead: Int): FloatArray {
    val out = FloatArray(frames * 2)
    for (i in 0 until frames) {
        out[i * 2] = source[lead + i]
        out[i * 2 + 1] = source[lead + i - delay]
    }
    return out
}

class ArrivalDelayEstimatorTest {

    private val estimator = ArrivalDelayEstimator(WINDOW, MAX_LAG)

    @Test
    fun `한쪽이 늦게 받은 만큼을 시간차로 읽는다`() {
        val source = noise(WINDOW + 200, seed = 1)
        for (delay in listOf(-19, -12, -7, -1, 3, 8, 15, 20)) {
            val estimate = estimator.estimate(delayed(source, delay, WINDOW, lead = 100), 0)
            assertNotNull("시간차 $delay 를 판단하지 않았다", estimate)
            assertEquals("시간차 $delay", delay.toFloat(), estimate!!.lagSamples, 0.25f)
            assertTrue("시간차 $delay 의 상관이 낮다: ${estimate.correlation}", estimate.correlation > 0.9f)
        }
    }

    @Test
    fun `샘플 사이의 시간차도 가까이 읽는다`() {
        // 채널 1 을 이웃 두 샘플의 평균으로 만들면 3.5 샘플 늦게 받은 소리가 된다. 두 샘플 평균은 아주 높은 음을
        // 깎으므로, 실제 소리처럼 몇 kHz 아래에 몰린 소리로 만든다(흰 잡음이면 채널 1 만 깎여 두 채널이 달라진다).
        val source = lowPassed(noise(WINDOW + 200, seed = 2))
        val frames = FloatArray(WINDOW * 2)
        for (i in 0 until WINDOW) {
            val n = 100 + i
            frames[i * 2] = source[n]
            frames[i * 2 + 1] = 0.5f * (source[n - 3] + source[n - 4])
        }
        val estimate = estimator.estimate(frames, 0)
        assertNotNull(estimate)
        assertEquals(3.5f, estimate!!.lagSamples, 0.3f)
    }

    @Test
    fun `같은 소리가 두 채널에 복제돼 들어오면 시간차가 없다`() {
        // VOICE_RECOGNITION 처럼 마이크 하나를 두 채널에 복제하는 기기에서는 늘 정면으로 나와야 한다.
        val frames = delayed(noise(WINDOW + 200, seed = 3), delay = 0, frames = WINDOW, lead = 100)
        val estimate = estimator.estimate(frames, 0)
        assertNotNull(estimate)
        assertEquals(0f, estimate!!.lagSamples, 0.01f)
        assertEquals(1f, estimate.correlation, 1e-4f)
    }

    @Test
    fun `두 마이크가 서로 다른 소리를 들으면 판단하지 않는다`() {
        // 방 전체에 퍼진 잡음처럼 두 마이크 사이에 공통 성분이 없을 때 방향을 지어내지 않는다.
        val left = noise(WINDOW, seed = 4)
        val right = noise(WINDOW, seed = 5)
        val frames = FloatArray(WINDOW * 2) { if (it % 2 == 0) left[it / 2] else right[it / 2] }
        assertNull(estimator.estimate(frames, 0))
    }

    @Test
    fun `조용한 창은 판단하지 않는다`() {
        val frames = delayed(noise(WINDOW + 200, seed = 6, scale = 0.001f), delay = 5, frames = WINDOW, lead = 100)
        assertNull(estimator.estimate(frames, 0))
    }

    @Test
    fun `범위 밖의 시간차는 판단하지 않는다`() {
        // 두 마이크 거리로는 나올 수 없는 시간차다. 범위 끝에 붙은 비탈을 방향으로 읽지 않는다.
        val frames = delayed(noise(WINDOW + 200, seed = 7), delay = 40, frames = WINDOW, lead = 100)
        assertNull(estimator.estimate(frames, 0))
    }

    @Test
    fun `버퍼 가운데서 시작한 창도 같게 읽는다`() {
        val source = noise(3 * WINDOW + 200, seed = 8)
        val frames = delayed(source, delay = -9, frames = 3 * WINDOW, lead = 100)
        val estimate = estimator.estimate(frames, startFrame = WINDOW + 37)
        assertNotNull(estimate)
        assertEquals(-9f, estimate!!.lagSamples, 0.25f)
    }

    @Test
    fun `시간차 한계는 마이크 거리와 샘플레이트로 정한다`() {
        // S25+ 의 두 마이크는 약 14.3cm 떨어져 있다: 48kHz 에서 최대 20샘플 + 여유 4.
        assertEquals(24, ArrivalDelayEstimator.maxLagFor(0.1428, 48_000))
        assertEquals(23, ArrivalDelayEstimator.maxLagFor(0.1428, 44_100))
    }
}

package com.example.soundvisualizer.direction

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 두 마이크에 같은 소리가 닿은 시간차를 창마다 잰다(#248). 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 폰의 아래쪽 마이크와 위쪽 뒷면 마이크는 긴 축으로 약 14cm 떨어져 있다(Galaxy S25+). 그래서 시간차는 최대
 * 약 ±0.41ms, 48kHz 에서 ±20샘플이다. 부호는 소리가 어느 끝에 가까운지를 말하고, 화면 회전과 묶으면 가로로 든 폰의
 * 왼쪽·오른쪽이 된다([ScreenSideMapper]).
 *
 * **아직 앱 동작에 연결하지 않는다.** 두 마이크를 따로 받으려면 마이크 입력을 `VOICE_RECOGNITION` 에서
 * `MIC`/`CAMCORDER` 스테레오로 바꿔야 하고, 그러면 AI 가 받는 소리도 달라진다. 그 변경은 지금 입력으로 하는 마이크
 * AI 평가가 끝난 뒤 따로 검토한다(#248). 그 전에는 기기 측정(`MicDirectionProbeInstrumentedTest`)에만 쓴다.
 *
 * 방법: 창마다 평균을 빼고 1차 고역 강조를 건다. 저음은 상관 봉우리를 넓게 퍼뜨려 시간차를 흐리기 때문이다.
 * 그다음 ±[maxLag] 안에서만 정규화 상호상관을 계산하고, 봉우리와 양옆 두 점으로 포물선을 맞춰 샘플보다 잘게 읽는다.
 * 반사가 많은 방에서는 직접음이 또렷한 순간에만 믿을 수 있으므로, 이럴 때는 판단하지 않고 null 을 낸다.
 * - 소리가 작을 때([minRms])
 * - 상관이 낮을 때([minCorrelation])
 * - 봉우리가 범위 끝에 붙을 때
 *
 * 창 길이는 만들 때 정한다. 버퍼는 그때 한 번만 만들고, 같은 인스턴스를 한 스레드에서 계속 쓴다.
 *
 * @param windowFrames 창 하나의 프레임 수
 * @param maxLag 찾을 시간차의 한계(샘플). 마이크 거리로 정한다([maxLagFor]).
 */
class ArrivalDelayEstimator(
    private val windowFrames: Int,
    private val maxLag: Int,
    private val minRms: Float = DEFAULT_MIN_RMS,
    private val minCorrelation: Float = DEFAULT_MIN_CORRELATION
) {
    /**
     * @property lagSamples 채널 1 이 채널 0 보다 늦게 받은 샘플 수. 양수면 채널 0 의 마이크에 먼저 닿았다.
     * @property correlation 봉우리의 정규화 상관(-1..1). 1 에 가까울수록 두 마이크가 같은 소리를 들었다.
     */
    data class Estimate(val lagSamples: Float, val correlation: Float)

    init {
        require(maxLag > 0) { "maxLag 는 1 이상이어야 한다: $maxLag" }
        require(windowFrames > 4 * maxLag) { "창($windowFrames)이 시간차 범위(±$maxLag)보다 넉넉히 길어야 한다" }
    }

    private val a = FloatArray(windowFrames)
    private val b = FloatArray(windowFrames)
    private val corr = FloatArray(2 * maxLag + 1)

    /**
     * interleaved 스테레오 [interleaved] 의 [startFrame] 번째 프레임부터 창 하나를 잰다.
     *
     * @return 믿을 만한 시간차. 소리가 작거나, 두 마이크가 같은 소리를 들었다고 보기 어렵거나, 봉우리가 범위 끝에
     *   붙으면 null
     */
    fun estimate(interleaved: FloatArray, startFrame: Int): Estimate? {
        require(startFrame >= 0 && (startFrame + windowFrames) * 2 <= interleaved.size) { "창이 버퍼를 벗어난다" }
        var meanA = 0.0
        var meanB = 0.0
        for (i in 0 until windowFrames) {
            val j = (startFrame + i) * 2
            a[i] = interleaved[j]
            b[i] = interleaved[j + 1]
            meanA += a[i]
            meanB += b[i]
        }
        meanA /= windowFrames
        meanB /= windowFrames
        var powerA = 0.0
        var powerB = 0.0
        for (i in 0 until windowFrames) {
            a[i] -= meanA.toFloat()
            b[i] -= meanB.toFloat()
            powerA += a[i] * a[i]
            powerB += b[i] * b[i]
        }
        val gate = minRms.toDouble() * minRms * windowFrames
        if (powerA < gate || powerB < gate) return null

        preEmphasize(a)
        preEmphasize(b)
        var energyA = 0.0
        var energyB = 0.0
        for (i in 0 until windowFrames) {
            energyA += a[i] * a[i]
            energyB += b[i] * b[i]
        }
        val norm = sqrt(energyA * energyB)
        if (norm <= 0.0) return null

        var best = 0
        for (k in -maxLag..maxLag) {
            var sum = 0.0
            val from = maxOf(0, -k)
            val until = minOf(windowFrames, windowFrames - k)
            for (n in from until until) sum += a[n] * b[n + k]
            val r = (sum / norm).toFloat()
            corr[k + maxLag] = r
            if (r > corr[best]) best = k + maxLag
        }
        val peak = corr[best]
        if (peak < minCorrelation) return null
        // 범위 끝에 붙은 봉우리는 마이크 사이 거리로 나올 수 없는 시간차이거나 범위 밖 봉우리의 비탈이다.
        if (best == 0 || best == corr.size - 1) return null

        val left = corr[best - 1]
        val right = corr[best + 1]
        val curve = left - 2 * peak + right
        val offset = if (curve < 0f) (0.5f * (left - right) / curve).coerceIn(-0.5f, 0.5f) else 0f
        return Estimate(lagSamples = best - maxLag + offset, correlation = peak)
    }

    /** 1차 고역 강조. 뒤에서부터 계산해 따로 버퍼를 두지 않는다. */
    private fun preEmphasize(x: FloatArray) {
        for (i in x.size - 1 downTo 1) x[i] -= PRE_EMPHASIS * x[i - 1]
        x[0] *= 1f - PRE_EMPHASIS
    }

    companion object {
        /**
         * 이보다 조용한 창은 판단하지 않는다(RMS, 약 -50dBFS).
         *
         * S25+ 의 사무실 잡음은 `VOICE_RECOGNITION` 으로 RMS -52dBFS 안팎이었다. 잡음만 있는 창은 걸러지고, 말소리처럼
         * 그보다 큰 소리가 날 때만 잰다. 기기 측정으로 다시 정한다.
         */
        const val DEFAULT_MIN_RMS = 0.003f

        /** 이보다 상관이 낮으면 두 마이크가 같은 소리를 들었다고 보지 않는다. 기기 측정으로 다시 정한다. */
        const val DEFAULT_MIN_CORRELATION = 0.6f

        /** 고역 강조 계수. 약 1kHz 아래를 눌러 상관 봉우리를 좁힌다. */
        private const val PRE_EMPHASIS = 0.97f

        /** 소리 빠르기(m/s, 실온). */
        private const val SPEED_OF_SOUND = 343.0

        /** 마이크 위치를 잴 때의 오차와 소리가 비스듬히 들어올 때를 감안한 여유(샘플). */
        private const val LAG_MARGIN = 4

        /** 두 마이크가 [distanceMeters] 떨어져 있을 때 찾을 시간차 한계. 실제 최대치에 [LAG_MARGIN] 을 더한다. */
        fun maxLagFor(distanceMeters: Double, sampleRate: Int): Int =
            ceil(abs(distanceMeters) / SPEED_OF_SOUND * sampleRate).toInt() + LAG_MARGIN
    }
}

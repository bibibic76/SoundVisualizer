package com.example.soundvisualizer.feedback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * #352 의 테스트([AiFeedFilterTest], [AiFeedLoopTest])가 함께 쓰는 소리와 잣대.
 *
 * 진동 소리는 폰에서 녹음한 것이 아니다. 시뮬레이션의 진동 소리(피크 0.024~0.03)처럼 170Hz 와 그 2·3배음을 섞어 피크 0.03 으로
 * 맞추고, 8ms 에 올라와 25ms 에 잦아든다. AI 입력이 이 소리를 빼는지 보는 데 쓴다.
 */
internal object AiFeedSignals {

    const val BUZZ_HZ = 170.0
    const val BUZZ_PEAK = 0.03

    /** 배음 합의 한 주기 최댓값. 피크를 [BUZZ_PEAK] 로 맞추는 데 쓴다. */
    private val harmonicMax: Double = (0 until 10_000).maxOf { i -> abs(harmonics(i / 10_000.0 / BUZZ_HZ)) }

    private fun harmonics(seconds: Double): Double {
        val p = 2 * PI * BUZZ_HZ * seconds
        return sin(p) + 0.5 * sin(2 * p) + 0.25 * sin(3 * p)
    }

    /**
     * 시각 [tMs] 의 진동 소리. 모터 소리는 [startMs] 에 시작해 [endMs] 에 끝난다. 처음 8ms 에 올라오고 마지막 25ms 에 잦아든다.
     * 구간 밖은 0.
     */
    fun buzzAt(tMs: Double, startMs: Double, endMs: Double, peak: Double = BUZZ_PEAK): Double {
        if (tMs < startMs || tMs >= endMs) return 0.0
        val attack = ((tMs - startMs) / 8.0).coerceAtMost(1.0)
        val release = ((endMs - tMs) / 25.0).coerceAtMost(1.0)
        return peak / harmonicMax * minOf(attack, release) * harmonics((tMs - startMs) / 1000.0)
    }

    /** [x] 에 [startMs]~[endMs] 의 진동 소리를 더한다. 0 번 프레임이 0ms 다. */
    fun addBuzz(x: FloatArray, rate: Int, startMs: Double, endMs: Double, peak: Double = BUZZ_PEAK) {
        val from = (startMs * rate / 1000).toInt().coerceIn(0, x.size)
        val to = (endMs * rate / 1000 + 1).toInt().coerceIn(0, x.size)
        for (i in from until to) x[i] += buzzAt(i * 1000.0 / rate, startMs, endMs, peak).toFloat()
    }

    /** 정해진 씨앗의 정규 잡음. */
    fun noise(seed: Long, n: Int, rms: Double): FloatArray {
        val random = java.util.Random(seed)
        return FloatArray(n) { (random.nextGaussian() * rms).toFloat() }
    }

    /**
     * 말소리 비슷한 잡음 덩어리: 0.08~0.6초 소리와 0.03~0.25초(가끔 0.25~0.6초) 쉼이 번갈아 온다. 덩어리마다 크기가 조금 다르다.
     * [fromMs]~[toMs] 에 더한다.
     */
    fun addSpeechLike(x: FloatArray, rate: Int, seed: Long, fromMs: Double, toMs: Double, rms: Double) {
        val random = java.util.Random(seed)
        var t = fromMs
        while (t < toMs) {
            val on = 80 + random.nextInt(520)
            val level = rms * (0.7 + 0.6 * random.nextDouble())
            val end = minOf(t + on, toMs)
            val from = (t * rate / 1000).toInt()
            val to = (end * rate / 1000).toInt().coerceAtMost(x.size)
            for (i in from until to) x[i] += (random.nextGaussian() * level).toFloat()
            t = end + if (random.nextInt(3) < 2) 30 + random.nextInt(220) else 250 + random.nextInt(350)
        }
    }

    /** [hz] 에서의 괴르첼 세기(제곱). [from]~[to] 프레임을 본다. */
    fun goertzel(x: FloatArray, from: Int, to: Int, rate: Int, hz: Double = BUZZ_HZ): Double {
        val coeff = 2 * cos(2 * PI * hz / rate)
        var s1 = 0.0
        var s2 = 0.0
        for (i in from until to) {
            val s = x[i] + coeff * s1 - s2
            s2 = s1
            s1 = s
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    /** [x] 의 [from]~[to] 프레임에서 앞뒤 차이의 RMS. 넓은 대역의 소리(말소리 대용)는 크고, 170Hz 진동 소리와 바닥 잡음은 작다. */
    fun diffRms(x: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from + 1 until to) {
            val d = (x[i] - x[i - 1]).toDouble()
            sum += d * d
        }
        return sqrt(sum / maxOf(1, to - from - 1))
    }

    /**
     * 진동 알림처럼 구간을 제때 연다. 앱에서는 울림을 보내기 직전에 열므로 캡처 시각이 그 구간에 닿기 조금 전이다. 미리 다
     * 열어 두면 목록이 2초 넘게 지난 구간을 버린다([SelfVibrationTimeline.KEEP_MS]).
     */
    class Schedule(val timeline: SelfVibrationTimeline = SelfVibrationTimeline()) {
        private val pending = ArrayList<LongArray>()

        /** `[start, end)` 구간, 멈추라고 한 시각 [cmdEnd]. */
        fun add(start: Long, end: Long, cmdEnd: Long): Schedule {
            pending.add(longArrayOf(start, end, cmdEnd))
            return this
        }

        /** 캡처 시각이 [untilMs] 까지 온 버퍼를 흘리기 전에, 그 버퍼에 닿는 구간을 연다. */
        fun openDue(untilMs: Double) {
            val due = pending.filter { it[0] - OPEN_LEAD_MS <= untilMs }
            for (w in due) timeline.open(w[0], w[1], w[2])
            pending.removeAll(due.toSet())
        }

        private companion object {
            /** 구간 시작(경사 5ms 앞) 조금 전에 연다. */
            const val OPEN_LEAD_MS = 10
        }
    }

    /**
     * 모노 [x] 를 512 프레임씩 좌우가 같은 버퍼로 [filter] 에 흘리고 나온 소리를 돌려준다. 0 번 프레임의 캡처 시각이 0ms 다.
     * [schedule] 이 있으면 그 구간을 제때 연다. 좌우가 늘 같은지도 본다. [afterBuffer] 는 버퍼마다 흘린 뒤 그 버퍼의 끝 시각(ms)으로
     * 부른다. 흘리는 도중의 필터 상태를 보는 데 쓴다.
     */
    fun runThrough(
        filter: AiFeedFilter,
        x: FloatArray,
        rate: Int,
        endExtraMs: Int = 0,
        frames: Int = 512,
        schedule: Schedule? = null,
        afterBuffer: ((endMs: Double) -> Unit)? = null
    ): FloatArray {
        val out = FloatArray(x.size)
        val buf = FloatArray(frames * 2)
        var at = 0
        while (at < x.size) {
            val n = minOf(frames, x.size - at)
            for (i in 0 until n) {
                buf[2 * i] = x[at + i]
                buf[2 * i + 1] = x[at + i]
            }
            schedule?.openDue((at + n) * 1000.0 / rate)
            filter.process(buf, n * 2, at * 1000.0 / rate, endExtraMs)
            for (i in 0 until n) {
                check(buf[2 * i] == buf[2 * i + 1]) { "프레임 ${at + i} 의 좌우가 다르다" }
                out[at + i] = buf[2 * i]
            }
            at += n
            afterBuffer?.invoke(at * 1000.0 / rate)
        }
        return out
    }
}

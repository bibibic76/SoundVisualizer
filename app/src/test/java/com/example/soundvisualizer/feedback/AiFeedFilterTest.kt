package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.feedback.AiFeedSignals.Schedule
import com.example.soundvisualizer.feedback.AiFeedSignals.addBuzz
import com.example.soundvisualizer.feedback.AiFeedSignals.addSpeechLike
import com.example.soundvisualizer.feedback.AiFeedSignals.goertzel
import com.example.soundvisualizer.feedback.AiFeedSignals.noise
import com.example.soundvisualizer.feedback.AiFeedSignals.runThrough
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 외부 사운드 모드에서 AI 가 받는 소리에서 우리 진동을 빼는 필터(#352, 설계 A 의 hold2). 버퍼는 512 프레임, 좌우가 같은
 * 버퍼이고 0 번 프레임의 캡처 시각이 0ms 다. 따로 적지 않으면 48kHz 다.
 *
 * 구간은 진동 알림처럼 연다: 보낸 시각 send 에서 `[send − 5, send + 울림 + 꼬리 여유)`, 멈추라고 한 시각 `send + 울림`.
 */
class AiFeedFilterTest {

    private val rate = 48_000

    /** 프레임 [i] 의 시각(ms). */
    private fun timeOf(i: Int, rate: Int = this.rate): Double = i * 1000.0 / rate

    /** [ms] 에 들어온 첫 프레임. */
    private fun frameAt(ms: Double, rate: Int = this.rate): Int = kotlin.math.ceil(ms * rate / 1000 - 1e-9).toInt()

    /**
     * 느림 한 번(울림 400ms, 꼬리 여유 180ms)을 [send] 에 보낼 구간을 [schedule] 에 넣는다. 흘리는 동안 제때 연다.
     * @return `[start, end)`
     */
    private fun slow(schedule: Schedule, send: Long): Pair<Long, Long> {
        val guard = HapticTuning.aiFeedGuardMs(HapticMode.Slow)
        schedule.add(send - HapticTuning.AI_FEED_PRE_MS, send + 400 + guard, send + 400)
        return (send - HapticTuning.AI_FEED_PRE_MS) to (send + 400 + guard)
    }

    /** [schedule] 의 구간으로 [input] 을 새 필터에 흘린다. */
    private fun filtered(
        input: FloatArray,
        schedule: Schedule,
        rate: Int = this.rate,
        endExtraMs: Int = 0,
        filter: AiFeedFilter = AiFeedFilter(rate, schedule.timeline::snapshot)
    ): FloatArray = runThrough(filter, input, rate, endExtraMs = endExtraMs, schedule = schedule)

    private fun bitsEqual(a: Float, b: Float) = a.toRawBits() == b.toRawBits()

    // ---------------------------------------------------------------
    // 1·2. 구간이 없으면 그대로, 버퍼 길이는 그대로
    // ---------------------------------------------------------------

    @Test
    fun `구간이 없으면 버퍼를 한 비트도 바꾸지 않는다`() {
        val filter = AiFeedFilter(rate, SelfVibrationTimeline()::snapshot)
        val random = java.util.Random(1)
        val buf = FloatArray(1024)
        for (b in 0 until 300) {
            for (i in 0 until 512) {
                val v = (random.nextGaussian() * 0.1).toFloat()
                buf[2 * i] = v
                buf[2 * i + 1] = v
            }
            if (b == 17) buf[10] = -0.0f.also { buf[11] = -0.0f }
            val copy = buf.copyOf()
            filter.process(buf, 1024, timeOf(b * 512), 0)
            assertTrue("버퍼 $b 가 바뀌었다", copy.contentEquals(buf))
        }
        // 좌우가 다른 버퍼도 건드리지 않는다(마이크는 늘 같게 만들어 넘기지만, 그렇지 않아도 구간 밖이면 그대로다).
        for (i in buf.indices) buf[i] = (random.nextGaussian() * 0.1).toFloat()
        val copy = buf.copyOf()
        filter.process(buf, 1024, timeOf(300 * 512), 0)
        assertTrue(copy.contentEquals(buf))
    }

    @Test
    fun `어떤 구간이든 받은 길이 안에서만 고치고 좌우를 같게 둔다`() {
        val random = java.util.Random(2)
        for (layout in 0 until 40) {
            val timeline = SelfVibrationTimeline()
            var t = random.nextInt(200).toLong()
            repeat(random.nextInt(6)) {
                val len = 50L + random.nextInt(2500)
                timeline.open(t, t + len, t + len / 2)
                t += len + random.nextInt(400)
            }
            val filter = AiFeedFilter(rate, timeline::snapshot)
            for (floatCount in listOf(1024, 2, 100, 600, 1024)) {
                val buf = FloatArray(1100) { SENTINEL }
                for (i in 0 until floatCount / 2) {
                    val v = (random.nextGaussian() * 0.05).toFloat()
                    buf[2 * i] = v
                    buf[2 * i + 1] = v
                }
                for (start in listOf(0.0, 333.3, 1500.0, 2999.9)) {
                    filter.process(buf, floatCount, start, if (layout % 2 == 0) 0 else 60)
                    for (i in floatCount until buf.size) assertEquals("layout $layout: 받은 길이 밖을 고쳤다", SENTINEL, buf[i], 0f)
                    for (i in 0 until floatCount / 2) {
                        assertTrue(bitsEqual(buf[2 * i], buf[2 * i + 1]))
                        assertTrue(buf[2 * i].isFinite())
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // 3·4·11. 진동 소리를 빼고 튀지 않는다
    // ---------------------------------------------------------------

    private class BuzzScene(val clean: FloatArray, val input: FloatArray, val output: FloatArray, val windows: List<Pair<Long, Long>>)

    /** 바닥 잡음(RMS 2e-4)에 1초마다 느림 한 번의 진동 소리를 구간 안에만 넣고 필터에 흘린다. */
    private fun buzzScene(rate: Int): BuzzScene {
        val n = 10 * rate
        val clean = noise(11, n, 2e-4)
        val input = clean.copyOf()
        val schedule = Schedule()
        val windows = ArrayList<Pair<Long, Long>>()
        for (k in 0 until 9) {
            val send = 500L + 1000L * k
            windows.add(slow(schedule, send))
            // 모터 소리는 보낸 뒤 8ms 에 시작해 멈추라고 한 뒤 150ms 에 끝난다(구간 안).
            addBuzz(input, rate, send + 8.0, send + 400 + 150.0)
        }
        val output = filtered(input, schedule, rate)
        return BuzzScene(clean, input, output, windows)
    }

    private fun assertBuzzRemoved(rate: Int) {
        val scene = buzzScene(rate)
        val n = scene.input.size
        val before = goertzel(scene.input, 0, n, rate)
        val after = goertzel(scene.output, 0, n, rate)
        val db = 10 * log10(after / before)
        assertTrue("$rate Hz: 170Hz 가 ${"%.1f".format(db)}dB 만 줄었다", db <= -30.0)
        var checked = 0
        for (i in 0 until n) {
            val t = timeOf(i, rate)
            val near = scene.windows.any { (s, e) -> t > s - HapticTuning.AI_FEED_XFADE_MS - 0.01 && t < e + HapticTuning.AI_FEED_XFADE_MS + 0.01 }
            if (near) continue
            assertTrue("$rate Hz: 구간 밖 ${"%.2f".format(t)}ms 를 고쳤다", bitsEqual(scene.input[i], scene.output[i]))
            checked++
        }
        assertTrue(checked > n / 3)
    }

    @Test
    fun `구간 안의 진동 소리를 30dB 넘게 줄이고 구간 밖은 그대로 둔다`() = assertBuzzRemoved(48_000)

    @Test
    fun `44_1kHz 에서도 진동 소리를 30dB 넘게 줄인다`() = assertBuzzRemoved(44_100)

    /** 구간 가장자리 앞뒤 10ms 에서 출력의 가장 큰 한 칸이, 깨끗한 소리의 앞뒤 20ms 에서 가장 큰 한 칸의 두 배 안인지. */
    private fun assertNoClicks(name: String, clean: FloatArray, output: FloatArray, windows: List<Pair<Long, Long>>) {
        val tenMs = rate / 100
        for ((s, e) in windows) {
            for (edge in listOf(s, e)) {
                val at = frameAt(edge.toDouble())
                var outStep = 0f
                for (i in at - tenMs until at + tenMs) outStep = maxOf(outStep, abs(output[i + 1] - output[i]))
                var cleanStep = 0f
                for (i in at - 2 * tenMs until at + 2 * tenMs) cleanStep = maxOf(cleanStep, abs(clean[i + 1] - clean[i]))
                assertTrue("$name ${edge}ms: 가장 큰 한 칸 $outStep > 원래 소리의 두 배(${2 * cleanStep})", outStep <= 2 * cleanStep)
            }
        }
    }

    @Test
    fun `구간 가장자리에서 튀지 않는다`() {
        val scene = buzzScene(rate)
        assertNoClicks("바닥 잡음", scene.clean, scene.output, scene.windows)
        // 매끄러운 소리(317Hz)에서도 그렇다. 경사가 없으면 원래 소리와 되풀이한 소리의 차이(최대 0.02)만큼 한 칸에 뛴다.
        // 317Hz 는 테이프 0.2초에 63.4 주기라 이어 읽어도 위상이 맞지 않는다.
        val sine = FloatArray(10 * rate) { i -> (0.01 * sin(2 * Math.PI * 317 * i / rate)).toFloat() }
        val schedule = Schedule()
        val windows = (0 until 9).map { k -> slow(schedule, 500L + 1000L * k) }
        assertNoClicks("317Hz", sine, filtered(sine, schedule), windows)
    }

    @Test
    fun `앞의 깨끗한 소리와 다음 쉼은 5ms 겹쳐 잇는다`() {
        // 0.01(1초) → 구간 → 0.02(쉼 40ms) → 구간 → 0.03(쉼 40ms) → 구간. 쉼이 짧아 셋째 구간의 테이프는 앞의 깨끗한 소리를
        // 이어 붙인 0.09초인데, 그 안에 0.01 과 0.02 를 이은 곳이 있다. 이을 때 5ms 겹치므로 그 사이 값이 나온다.
        val input = FloatArray(4 * rate) { i ->
            val t = timeOf(i)
            when {
                t < 1000 -> 0.01f
                t < 1625 -> 0.02f
                t < 2250 -> 0.03f
                else -> 0.04f
            }
        }
        val schedule = Schedule().add(1000, 1585, 1405).add(1625, 2210, 2030).add(2250, 2835, 2655)
        val output = filtered(input, schedule)
        val third = (frameAt(2250.0) until frameAt(2835.0)).map { output[it] }
        val between = third.filter { it > 0.0101f && it < 0.0199f }.toSet()
        assertTrue("이은 곳의 값이 ${between.size}가지뿐이다", between.size >= 100)
        assertTrue("테이프에 없는 값이 있다", third.all { it in 0.01f..0.03f })
    }

    // ---------------------------------------------------------------
    // 5·6. 테이프의 길이와 앞뒤로 읽기
    // ---------------------------------------------------------------

    /**
     * 1초 동안 깨끗한 소리 뒤에 585ms 구간을 [gapMs] 간격으로 연다. 소리는 RMS 0.01 의 잡음이다.
     * @return 구간마다 채운 소리가 스스로 되풀이되는 가장 짧은 간격(프레임). 정확히 되풀이되므로 자기상관이 1 인 첫 자리다.
     */
    private fun fillPeriods(rate: Int, gapMs: Long, count: Int = 6): List<Int> {
        val windows = ArrayList<Pair<Long, Long>>()
        val schedule = Schedule()
        var s = 1000L
        repeat(count) {
            schedule.add(s, s + 585, s + 405)
            windows.add(s to s + 585)
            s += 585 + gapMs
        }
        val input = noise(5, ((s + 500) * rate / 1000).toInt(), 0.01)
        val output = filtered(input, schedule, rate)
        return windows.map { (start, end) ->
            val seg = output.copyOfRange(frameAt(start.toDouble(), rate), frameAt(end.toDouble(), rate))
            repeatPeriod(seg)
        }
    }

    /** [seg] 가 스스로 되풀이되는 가장 짧은 간격. 없으면 -1. */
    private fun repeatPeriod(seg: FloatArray): Int {
        lag@ for (lag in 1 until seg.size - 64) {
            for (i in 0 until seg.size - lag) if (!bitsEqual(seg[i], seg[i + lag])) continue@lag
            return lag
        }
        return -1
    }

    private fun assertTapeLengths(rate: Int) {
        // 설계 시뮬레이션이 고른 길이(0.09초, 0.2초)를 숫자로 적는다.
        val tapeMin = 90 * rate / 1000
        val tapeMax = 200 * rate / 1000
        // 쉼이 40ms(깨끗한 소리 30ms)면 앞의 깨끗한 소리를 이어 붙여 0.09초를 채운다. 첫 구간은 앞의 1초에서 0.2초를 쓴다.
        val short = fillPeriods(rate, 40)
        assertTrue("$rate Hz 40ms: $short", short.all { it >= 2 * tapeMin })
        assertTrue("$rate Hz 40ms: $short", short.drop(1).all { it == 2 * tapeMin })
        // 쉼이 405ms 면 깨끗한 소리 395ms 의 마지막 0.2초를 쓴다.
        val long = fillPeriods(rate, 405)
        assertTrue("$rate Hz 405ms: $long", long.all { it == 2 * tapeMax })
        // 쉼이 150ms 면 깨끗한 소리 140ms 를 모두 쓴다(44.1kHz 는 ms 와 프레임이 맞지 않아 한 프레임 오갈 수 있다).
        val mid = fillPeriods(rate, 150)
        val expected = 2 * (140 * rate / 1000)
        assertTrue("$rate Hz 150ms: $mid ≠ $expected", mid.drop(1).all { abs(it - expected) <= 2 })
    }

    @Test
    fun `테이프는 앞 쉼의 마지막 0_2초이고 쉼이 짧으면 앞의 소리를 이어 0_09초를 채운다`() = assertTapeLengths(48_000)

    @Test
    fun `44_1kHz 에서도 테이프 길이는 같은 시간이다`() = assertTapeLengths(44_100)

    @Test
    fun `테이프를 앞으로 뒤로 번갈아 읽고 돌아서는 곳에서만 한 값을 두 번 쓴다`() {
        // 깨끗한 소리가 한 프레임에 1e-6 씩 오르는 경사라, 앞으로 읽으면 오르고 뒤로 읽으면 내린다.
        val input = FloatArray(2 * rate) { it * 1e-6f }
        val schedule = Schedule()
        val (s, e) = slow(schedule, 505)
        val output = filtered(input, schedule)
        val inside = output.copyOfRange(frameAt(s.toDouble()), frameAt(e.toDouble()))
        val tape = 200 * rate / 1000
        val turns = ArrayList<Int>()
        var direction = 0
        for (i in 0 until inside.size - 1) {
            val d = inside[i + 1] - inside[i]
            if (d == 0f) {
                turns.add(i)
                continue
            }
            val sign = if (d > 0) 1 else -1
            if (direction != 0 && turns.isNotEmpty() && turns.last() == i - 1) {
                assertEquals("돌아선 뒤에는 반대로 읽는다", -direction, sign)
            } else if (direction != 0) {
                assertEquals("돌아서지 않고 방향이 바뀌었다(프레임 $i)", direction, sign)
            }
            direction = sign
        }
        assertTrue("돌아선 곳이 ${turns.size}번", turns.size >= 2)
        turns.zipWithNext { a, b -> assertEquals("돌아서는 간격", tape, b - a) }
        // 테이프는 경사가 시작되기 전까지의 마지막 0.2초다. 경사(5ms) 동안 이미 앞으로 읽었으므로, 구간 안 첫 프레임은
        // 그만큼 나아간 자리, 곧 0.2초 앞의 원래 소리다.
        assertEquals(input[frameAt(s.toDouble()) - tape], inside[0], 0f)
        assertTrue("처음은 앞으로 읽는다", inside[1] > inside[0])
    }

    // ---------------------------------------------------------------
    // 7·8. 큰 소리는 그대로 넘기고, 진동 소리 크기를 배운다
    // ---------------------------------------------------------------

    /** 프레임 [from] 부터 [n] 개에 [peak] 크기의 1kHz 소리를 넣는다. */
    private fun addTone(x: FloatArray, from: Int, n: Int, peak: Double, hz: Double = 1000.0) {
        for (i in 0 until n) x[from + i] += (peak * sin(2 * Math.PI * hz * i / rate)).toFloat()
    }

    /** 버퍼 [b](512 프레임)의 출력이 입력과 같은지. */
    private fun bufferPassed(input: FloatArray, output: FloatArray, b: Int): Boolean =
        (b * 512 until (b + 1) * 512).all { bitsEqual(input[it], output[it]) }

    /** 버퍼 [b] 의 모든 프레임이 채워졌는지(원래 소리와 다르다). */
    private fun bufferFilled(input: FloatArray, output: FloatArray, b: Int): Boolean =
        (b * 512 until (b + 1) * 512).all { !bitsEqual(input[it], output[it]) }

    @Test
    fun `배우기 전에도 0_2 를 넘는 소리는 첫 구간부터 그대로 넘긴다`() {
        // 첫 구간은 995~1580ms 다. 버퍼 110~113 (1173~1216ms)은 모두 구간 안이다.
        fun scene(vararg peaks: Pair<Int, Double>): Pair<FloatArray, FloatArray> {
            val input = noise(21, 3 * rate, 2e-4)
            for ((b, peak) in peaks) addTone(input, b * 512 + 100, 300, peak)
            val schedule = Schedule()
            slow(schedule, 1000)
            return input to filtered(input, schedule)
        }
        val (loudIn, loudOut) = scene(110 to 0.6)
        assertTrue("0.6 이 그대로 가지 않았다", bufferPassed(loudIn, loudOut, 110))
        assertTrue("앞뒤의 조용한 버퍼는 채운다", bufferFilled(loudIn, loudOut, 109) && bufferFilled(loudIn, loudOut, 111))

        val (midIn, midOut) = scene(110 to 0.15)
        assertTrue("0.15 를 채우지 않았다", bufferFilled(midIn, midOut, 110))

        val (seqIn, seqOut) = scene(110 to 0.6, 111 to 0.12, 112 to 0.08)
        assertTrue(bufferPassed(seqIn, seqOut, 110))
        assertTrue("넘기는 중에는 0.1 을 넘는 0.12 도 넘긴다", bufferPassed(seqIn, seqOut, 111))
        assertTrue("0.08 은 채운다", bufferFilled(seqIn, seqOut, 112))

        val (oneIn, oneOut) = scene(111 to 0.12)
        assertTrue("넘기는 중이 아니면 0.12 는 채운다", bufferFilled(oneIn, oneOut, 111))
    }

    @Test
    fun `큰 소리를 넘긴 뒤 구간의 나머지는 그 소리를 되풀이한다`() {
        val input = noise(22, 3 * rate, 2e-4)
        addTone(input, 110 * 512, 512, 0.6)
        val schedule = Schedule()
        slow(schedule, 1000)
        val output = filtered(input, schedule)
        var loud = 0f
        for (i in 111 * 512 until frameAt(1580.0)) loud = maxOf(loud, abs(output[i]))
        assertTrue("구간의 나머지가 ${loud} 로 조용하다", loud > 0.3f)
    }

    /**
     * 1kHz 0.001 과 바닥 잡음이 늘 깔리고(쉼에 [gapRms] 의 잡음을 더할 수 있다), 1~5초의 느림 다섯 구간 안에만 0.1 크기의
     * 진동 소리가 있다. 6초의 여섯째 구간 안 버퍼 [LOUD_BUFFER] 에는 0.3 짜리 소리가 있다.
     */
    private fun learningInput(gapRms: Double = 0.0): FloatArray {
        val n = 7 * rate
        val input = noise(31, n, if (gapRms > 0) gapRms else 1e-4)
        addTone(input, 0, n, 0.001)
        for (k in 0 until 5) {
            val send = 1000L + 1000L * k
            addBuzz(input, rate, send + 8.0, send + 550.0, peak = 0.1)
        }
        addTone(input, LOUD_BUFFER * 512, 512, 0.3)
        return input
    }

    /** [learningInput] 의 여섯 구간. 여섯째는 5995~6580ms 이고 버퍼 565(6026~6037ms)가 그 안이다. */
    private fun learningWindows(): Schedule = Schedule().also { schedule ->
        for (k in 0 until 6) slow(schedule, 1000L + 1000L * k)
    }

    @Test
    fun `쉼보다 12dB 넘게 큰 구간에서 진동 소리 크기를 배우고 넘기는 기준을 올린다`() {
        val input = learningInput()
        // 다섯 구간을 지난 뒤(5.8초)의 상태.
        val schedule = learningWindows()
        val learner = AiFeedFilter(rate, schedule.timeline::snapshot)
        runThrough(learner, input.copyOfRange(0, frameAt(5800.0)), rate, schedule = schedule)
        val learned = learner.learnedBuzzPeak
        assertNotNull(learned)
        // 바닥(0.05)에서 시작해 다섯 구간 동안 0.1 쪽으로 다가간다(0.1 − 0.05·0.7⁵ ≈ 0.092).
        assertEquals("배운 크기 $learned", 0.1f, learned!!, 0.01f)
        assertEquals(4 * learned, learner.loudThreshold(), 1e-6f)
        // 여섯째 구간의 0.3 은 배운 기준(0.37쯤) 아래라 채운다.
        val output = filtered(input, learningWindows())
        assertTrue("배운 기준 아래의 0.3 을 넘겼다", bufferFilled(input, output, LOUD_BUFFER))
        var peak = 0f
        for (i in LOUD_BUFFER * 512 until (LOUD_BUFFER + 1) * 512) peak = maxOf(peak, abs(output[i]))
        assertTrue("채운 버퍼에 0.3 이 남았다($peak)", peak < 0.05f)
        // 같은 소리를 배운 적이 없는 필터는 바닥 기준(0.2)으로 보아 그대로 넘긴다.
        val unlearned = filtered(input, Schedule().add(5995, 6580, 6400))
        assertTrue("배우기 전에는 0.3 을 넘긴다", bufferPassed(input, unlearned, LOUD_BUFFER))
    }

    @Test
    fun `쉼에도 소리가 이어지면 배우지 않고 기준은 0_2 그대로다`() {
        val schedule = learningWindows()
        val filter = AiFeedFilter(rate, schedule.timeline::snapshot)
        runThrough(filter, learningInput(gapRms = 0.05), rate, schedule = schedule)
        assertNull(filter.learnedBuzzPeak)
        assertEquals(0.2f, filter.loudThreshold(), 1e-6f)
    }

    /** 조용한 방: 1kHz 0.001 과 바닥 잡음(RMS 1e-4). 구간 안의 진동 소리는 앞뒤 쉼보다 12dB 넘게 크다. */
    private fun quietRoom(n: Int, seed: Long): FloatArray = noise(seed, n, 1e-4).also { addTone(it, 0, n, 0.001) }

    /** [from]~[to] ms 의 원래 소리 피크. 구간 안의 피크를 잴 때 쓴다. */
    private fun peakOf(x: FloatArray, from: Long, to: Long): Double {
        var peak = 0f
        for (i in frameAt(from.toDouble()) until frameAt(to.toDouble())) peak = maxOf(peak, abs(x[i]))
        return peak.toDouble()
    }

    @Test
    fun `앞 쉼과 뒤 쉼이 모두 12dB 넘게 조용해야 배운다`() {
        // 2초에 보낸 느림 한 번(1995~2580ms) 안에 0.1 진동 소리. 쉼에 0.05 소리가 있으면 구간이 그보다 4배(12dB) 넘게 크지 않다.
        fun learnedWith(loudFromMs: Double, loudToMs: Double): Float? {
            val input = quietRoom(4 * rate, 81)
            addBuzz(input, rate, 2008.0, 2550.0, peak = 0.1)
            if (loudToMs > loudFromMs) addTone(input, frameAt(loudFromMs), frameAt(loudToMs) - frameAt(loudFromMs), 0.05)
            val schedule = Schedule()
            slow(schedule, 2000)
            val filter = AiFeedFilter(rate, schedule.timeline::snapshot)
            filtered(input, schedule, filter = filter)
            return filter.learnedBuzzPeak
        }
        assertNotNull("앞뒤 쉼이 조용한데 배우지 않았다", learnedWith(0.0, 0.0))
        // 앞 쉼의 소리는 구간에서 멀리(0.2초 앞에서) 끝난다. 구간 가까이에서만 앞 쉼을 보면 놓친다.
        assertNull("앞 쉼이 시끄러운데 배웠다", learnedWith(1500.0, 1800.0))
        // 뒤 쉼의 소리는 구간이 끝나자마자 시작한다(0.2 아래라 그대로 넘기지도 않는다).
        assertNull("뒤 쉼이 시끄러운데 배웠다", learnedWith(2580.0, 2900.0))
    }

    @Test
    fun `배운 크기는 바닥에서 시작하는 지수 평균이다`() {
        // 조용한 방에서 느림 다섯 구간의 진동 소리 크기가 저마다 다르다. 구간마다 배운 값이 바닥(0.05)에서 시작해 새 구간 피크를
        // 30% 섞은 값인지 본다. 첫 값을 그대로 쓰거나 평균하지 않으면 첫 구간 뒤부터 다르다.
        val peaks = listOf(0.1, 0.2, 0.06, 0.12, 0.08)
        val input = quietRoom(7 * rate, 82)
        val schedule = Schedule()
        val windows = peaks.indices.map { k -> slow(schedule, 1000L + 1000L * k) }
        for ((k, p) in peaks.withIndex()) addBuzz(input, rate, 1008.0 + 1000 * k, 1550.0 + 1000 * k, peak = p)
        val filter = AiFeedFilter(rate, schedule.timeline::snapshot)
        // 구간이 끝나고 0.2초 뒤(다음 구간 전)의 배운 값.
        val learned = ArrayList<Float?>()
        runThrough(filter, input, rate, schedule = schedule) { endMs ->
            if (learned.size < windows.size && endMs >= windows[learned.size].second + 200) learned.add(filter.learnedBuzzPeak)
        }
        assertEquals(windows.size, learned.size)
        val ema = HapticTuning.AI_FEED_LEARN_EMA.toDouble()
        var expected = HapticTuning.AI_FEED_BUZZ_FLOOR_PEAK.toDouble()
        for ((k, window) in windows.withIndex()) {
            expected = (1 - ema) * expected + ema * peakOf(input, window.first, window.second)
            val got = learned[k]
            assertNotNull("${k + 1}번째 구간에서 배우지 않았다", got)
            assertEquals("${k + 1}번째 구간 뒤", expected, got!!.toDouble(), 1e-4)
        }
    }

    @Test
    fun `넘긴 큰 소리가 든 구간에서는 배우지 않아 기준이 진동 소리 크기에 머문다`() {
        // 첫 구간에서 0.6 폭발음을 진동 소리로 배우면 기준이 올라, 셋째 구간의 0.6 폭발음을 채운다. 진동 소리가 바닥보다 작을
        // 때(0.03)와 클 때(0.1) 모두, 구간마다 기준이 4 × max(바닥, 진동 소리) 근처에 머물러야 한다.
        for (buzz in listOf(0.03, 0.1)) {
            val input = quietRoom(9 * rate, 83)
            val schedule = Schedule()
            val windows = (0 until 8).map { k -> slow(schedule, 1000L + 1000L * k) }
            for (k in 0 until 8) addBuzz(input, rate, 1008.0 + 1000 * k, 1550.0 + 1000 * k, peak = buzz)
            // 첫 구간(995~1580ms)과 셋째 구간(2995~3580ms) 한가운데의 버퍼.
            val first = frameAt(1200.0) / 512
            val third = frameAt(3200.0) / 512
            addTone(input, first * 512, 512, 0.6)
            addTone(input, third * 512, 512, 0.6)
            val filter = AiFeedFilter(rate, schedule.timeline::snapshot)
            val thresholds = ArrayList<Float>()
            val output = runThrough(filter, input, rate, schedule = schedule) { endMs ->
                if (thresholds.size < windows.size && endMs >= windows[thresholds.size].second + 200) {
                    thresholds.add(filter.loudThreshold())
                }
            }
            assertTrue("진동 소리 $buzz: 첫 폭발음을 넘기지 않았다", bufferPassed(input, output, first))
            assertTrue("진동 소리 $buzz: 셋째 구간의 폭발음을 넘기지 않았다", bufferPassed(input, output, third))
            val target = 4 * maxOf(HapticTuning.AI_FEED_BUZZ_FLOOR_PEAK.toDouble(), buzz)
            assertEquals(windows.size, thresholds.size)
            thresholds.forEachIndexed { k, t ->
                assertTrue("진동 소리 $buzz: ${k + 1}번째 구간 뒤 기준 $t 가 $target 보다 높다", t <= target * 1.05)
            }
            assertEquals("진동 소리 $buzz: 마지막 기준", target, thresholds.last().toDouble(), target * 0.1)
        }
    }

    @Test
    fun `구간이 끝나는 버퍼의 큰 소리도 그대로 넘기고 배우지 않는다`() {
        // 구간이 끝나는 버퍼에서는 그 구간을 배울지 정하는 때와 그 버퍼를 넘길지 정하는 때가 같다. 배운 값으로 넘길지 정하면
        // 이 버퍼의 0.6 이 스스로 기준을 올려 채워진다.
        val input = quietRoom(3 * rate, 84)
        addBuzz(input, rate, 1008.0, 1550.0)
        val schedule = Schedule()
        val (_, end) = slow(schedule, 1000) // 995~1580ms
        val endFrame = frameAt(end.toDouble())
        val last = endFrame / 512
        assertTrue("구간 끝이 버퍼 가운데에 있지 않다", endFrame - last * 512 in 32..480)
        addTone(input, last * 512, endFrame - last * 512, 0.6)
        val filter = AiFeedFilter(rate, schedule.timeline::snapshot)
        val output = filtered(input, schedule, filter = filter)
        assertTrue("구간 끝 버퍼의 0.6 을 넘기지 않았다", bufferPassed(input, output, last))
        assertNull("넘긴 소리를 진동 소리로 배웠다", filter.learnedBuzzPeak)
        assertEquals(0.2f, filter.loudThreshold(), 1e-6f)
    }

    // ---------------------------------------------------------------
    // 9·10. 안전판과 어림의 늘린 끝
    // ---------------------------------------------------------------

    @Test
    fun `채우는 구간이 1초를 넘으면 그 뒤는 그대로 넘기고 테이프로 쓰지 않는다`() {
        // 2.28초짜리 구간(설정 미리보기) 동안 말소리가 난다. 앞의 깨끗한 소리는 0.01, 뒤의 짧은 쉼은 0.02 로 둔다.
        val n = 5 * rate
        val input = FloatArray(n) { i -> if (timeOf(i) < 1000) 0.01f else 0.02f }
        val speech = FloatArray(n)
        addSpeechLike(speech, rate, 41, 1000.0, 3280.0, 0.03)
        for (i in frameAt(1000.0) until frameAt(3280.0)) input[i] = speech[i]
        // 쉼 50ms 뒤에 느림 한 번.
        val schedule = Schedule().add(1000, 3280, 3000).add(3330, 3915, 3735)
        val filter = AiFeedFilter(rate, schedule.timeline::snapshot)
        val output = filtered(input, schedule, filter = filter)
        assertTrue(filter.valveFrames > 0)
        for (i in frameAt(1000.0) until frameAt(1990.0)) {
            assertEquals("${timeOf(i)}ms: 1초 안은 앞의 소리로 채운다", 0.01f, output[i], 0f)
        }
        for (i in frameAt(1996.0) until frameAt(3280.0)) {
            assertTrue("${timeOf(i)}ms: 1초가 지나면 그대로 넘긴다", bitsEqual(input[i], output[i]))
        }
        for (i in frameAt(3330.0) until frameAt(3915.0)) {
            val v = output[i]
            assertTrue("${timeOf(i)}ms: 다음 구간이 안전판으로 넘긴 소리($v)를 되풀이한다", v == 0.01f || v == 0.02f)
        }
    }

    @Test
    fun `어림으로 셀 때는 끝 뒤 60ms 도 채우고 아니면 채우지 않는다`() {
        val input = noise(51, 3 * rate, 0.01)
        fun run(extra: Int): FloatArray {
            val schedule = Schedule()
            slow(schedule, 1000) // 995~1580ms
            return filtered(input, schedule, endExtraMs = extra)
        }
        val extended = run(HapticTuning.AI_FEED_FALLBACK_EXTRA_MS)
        for (i in frameAt(1580.0) until frameAt(1640.0)) assertTrue("${timeOf(i)}ms", !bitsEqual(input[i], extended[i]))
        val exact = run(0)
        for (i in frameAt(1585.0) until frameAt(1640.0)) assertTrue("${timeOf(i)}ms", bitsEqual(input[i], exact[i]))
    }

    // ---------------------------------------------------------------
    // 12. 되풀이는 구간 하나를 넘지 않는다
    // ---------------------------------------------------------------

    @Test
    fun `말소리가 끝난 뒤 처음 시작하는 구간이 끝나면 말소리 크기의 소리가 없다`() {
        for (talkEnd in listOf(5050.0, 5300.0, 5450.0, 5520.0, 5600.0, 5900.0)) {
            val n = 9 * rate
            val input = noise(61, n, 2e-4)
            val speech = FloatArray(n)
            addSpeechLike(speech, rate, 62, 1000.0, talkEnd, 0.03)
            for (i in 0 until n) input[i] += speech[i]
            val schedule = Schedule()
            val windows = (0 until 8).map { k -> slow(schedule, 505L + 1000L * k) }
            val output = filtered(input, schedule)
            // 말소리가 끝난 뒤 처음 시작하는 구간이 끝나고 경사(5ms)까지 지난 때.
            val firstAfter = windows.first { it.first > talkEnd }
            val limit = firstAfter.second + HapticTuning.AI_FEED_XFADE_MS
            val block = rate / 100
            var loudBefore = 0.0
            for (from in 0 until n - block step block) {
                val rms = sqrt((from until from + block).sumOf { (output[it] * output[it]).toDouble() } / block)
                if (timeOf(from) < talkEnd - 10) loudBefore = maxOf(loudBefore, rms)
                if (timeOf(from) >= limit) {
                    assertTrue("talkEnd=$talkEnd: ${timeOf(from)}ms 에 말소리 크기($rms)가 남았다", rms < 0.003)
                }
            }
            assertTrue("앞에는 말소리가 있었다", loudBefore > 0.01)
        }
    }

    @Test
    fun `깨끗한 소리가 2ms 도 없으면 조용함으로 채운다`() {
        val input = noise(71, rate, 0.01)
        // 구간이 6ms 에 시작하면 경사(5ms) 앞의 깨끗한 소리는 0~1ms(49 프레임)뿐이다.
        val empty = SelfVibrationTimeline().apply { open(6, 600, 400) }
        val silent = runThrough(AiFeedFilter(rate, empty::snapshot), input, rate)
        for (i in frameAt(6.0) until frameAt(600.0)) assertEquals(0f, silent[i], 0f)
        // 8ms 에 시작하면 0~3ms(145 프레임)가 있어 그것을 되풀이한다.
        val enough = SelfVibrationTimeline().apply { open(8, 600, 400) }
        val replayed = runThrough(AiFeedFilter(rate, enough::snapshot), input, rate)
        val period = repeatPeriod(replayed.copyOfRange(frameAt(8.0), frameAt(600.0)))
        assertEquals(2 * (3 * rate / 1000 + 1), period)
    }

    private companion object {
        const val SENTINEL = 7f

        /** 여섯째 구간(5995~6580ms) 안의 버퍼. */
        const val LOUD_BUFFER = 565
    }
}

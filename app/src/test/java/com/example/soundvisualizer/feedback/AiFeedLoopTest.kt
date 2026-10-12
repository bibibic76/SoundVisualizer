package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification
import com.example.soundvisualizer.CaptureClock
import com.example.soundvisualizer.CaptureSource
import com.example.soundvisualizer.ai.AiClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

private const val SPEECH = AiClassification.SPEECH
private const val AMBIENT = AiClassification.AMBIENT

/**
 * 외부 사운드 모드에서 대화음 진동이 자기 진동 소리로 이어지는 고리(#352)를 소리 없이 JVM 에서 돌린다([SelfHearingLoopTest] 와
 * 같은 방식). 판단·박자·문·상한·빼는 구간·필터·라벨 신선도는 실제 코드이고, AI 만 아래의 대역이다.
 *
 * - **마이크**: 48kHz, 512 프레임 버퍼, 입력 지연 20ms. 바닥 잡음 RMS 2e-4. 진동 소리는 [AiFeedSignals] 의 170Hz 배음
 *   (피크 0.03)이고 보낸 뒤 8ms 에 시작해 멈추라고 한 뒤 150ms 에 끝난다. 마이크 감도 ×32 는 진동이 보는 크기에만 곱한다.
 *   캡처 시각은 [CaptureClock] 이 오디오 서버의 시각 표시(정확한 값)로 센다.
 * - **장면**: 2초부터 말소리 대용(넓은 대역 잡음 덩어리, RMS 0.03)이 이어지다가 6.8~8초에는 작아진다(RMS 0.003, 피크 0.01 아래).
 *   대화음 진동은 '빠름'으로 정해 두어 상한 때문에 '느림'으로 울린다. 20초 동안 본다.
 * - **AI 대역**: 쉬는 판단은 실제와 같다(받은 소리가 0.01 을 넘은 지 0.975초 안이면 돌고, 그 뒤 두 번 더 추론하고 쉰다).
 *   도는 동안 280ms 마다 추론한다. 받은 0.975초 창의 170Hz 괴르첼 세기가 진동 소리 하나보다 30dB 아래보다 크거나, 창에
 *   말소리 대용(20ms 조각의 차이 RMS 1e-3 초과)이 있으면 대화음, 아니면 환경음이다. 두 번 연달아 같아야 바뀌고, 쉬면 마지막
 *   라벨이 남는다.
 *
 * 말끝이 작아지는 1.2초가 있는 까닭: YAMNet 은 0.01 아래의 말소리도 말소리로 듣는다(시뮬레이션의 `Speech 0.46` 프레임). 그러면
 * AI 가 쉬기 전 마지막 두 추론이 둘 다 환경음이 아니어서 라벨이 대화음으로 남는다. 대역이 그 경우를 보여야 쉬는 AI 의 라벨을
 * 쓰지 않는 규칙([AiLabelFreshness])이 왜 필요한지 고정할 수 있다. 우리 진동 소리가 없어도 생긴다(기준 실행).
 */
class AiFeedLoopTest {

    private enum class Variant(val external: Boolean, val filter: Boolean, val idleRule: Boolean, val buzz: Boolean) {
        /** #354 상한까지만 있는 지금(develop). */
        CapsOnly(external = true, filter = false, idleRule = false, buzz = true),

        /** 쉬는 AI 의 라벨만 버리고 AI 입력은 그대로. */
        IdleOnly(external = true, filter = false, idleRule = true, buzz = true),

        /** 깨끗한 AI 입력만. */
        FilterOnly(external = true, filter = true, idleRule = false, buzz = true),

        /** 고른 설계(#352). */
        Chosen(external = true, filter = true, idleRule = true, buzz = true),

        /** 우리 진동 소리를 아예 듣지 않는 AI(시뮬레이션의 기준 실행). */
        Reference(external = true, filter = false, idleRule = false, buzz = false),
        ReferenceIdle(external = true, filter = false, idleRule = true, buzz = false),

        /** 폰 안의 소리. 소스가 정한다. 재생 캡처는 진동 소리를 듣지 못한다. */
        Internal(
            external = CaptureSource.InternalPlayback.hearsOwnVibration,
            filter = CaptureSource.InternalPlayback.hearsOwnVibration,
            idleRule = CaptureSource.InternalPlayback.hearsOwnVibration,
            buzz = false
        )
    }

    private class Run(
        /** `[보낸 시각, 모터가 멈춘 시각]` */
        val pulses: List<LongArray>,
        val raw: FloatArray,
        val fed: FloatArray,
        val timelineWrites: Int,
        /** AI 가 마지막으로 결과를 낸 시각과 그때의 라벨. */
        val lastInference: Pair<Double, String>?
    ) {
        val lastMotorEnd: Long get() = pulses.maxOfOrNull { it[1] } ?: Long.MIN_VALUE
        fun pulsesBetween(fromMs: Long, toMs: Long) = pulses.count { it[0] in fromMs until toMs }
    }

    /** 진동 설정: 대화음은 '빠름'으로 정해 두었고(외부 사운드 모드에서 '느림'으로 울린다), 위협음은 기본값, 환경음은 꺼짐. */
    private val config: (String) -> HapticPolicy.ClassConfig = { label ->
        val haptic = when (label) {
            SPEECH -> HapticSettings(HapticMode.Fast, 100)
            AiClassification.DANGER -> HapticSettings(HapticMode.Medium, 100)
            else -> HapticSettings(HapticMode.Off, 30)
        }
        HapticPolicy.ClassConfig(shown = true, haptic = haptic)
    }

    /** 진동 소리 한 번(느림)이 든 0.975초 창의 170Hz 세기. AI 대역이 진동 소리를 대화음으로 듣는 기준이다. */
    private val buzzRefPower: Double = FloatArray(AI_WINDOW).let { window ->
        AiFeedSignals.addBuzz(window, RATE, 108.0, 650.0)
        AiFeedSignals.goertzel(window, 0, AI_WINDOW, RATE)
    }

    private fun frame(ms: Double): Int = (ms * RATE / 1000).toInt()

    private fun scene(seed: Long): FloatArray {
        val x = AiFeedSignals.noise(seed, frame(RUN_MS + 1000.0), 2e-4)
        AiFeedSignals.addSpeechLike(x, RATE, seed + 100, 2000.0, LOUD_END_MS, 0.03)
        // 말끝이 작아진다. 피크가 0.01 아래라 AI 는 이 소리로 깨지 않지만, 말소리 대용으로는 들린다.
        val random = java.util.Random(seed + 200)
        for (i in frame(LOUD_END_MS) until frame(TALK_END_MS)) {
            x[i] += ((random.nextDouble() * 2 - 1) * QUIET_PEAK).toFloat()
        }
        return x
    }

    /** AI 대역의 한 프레임. */
    private fun classify(fed: FloatArray, from: Int, to: Int): String {
        if (AiFeedSignals.goertzel(fed, from, to, RATE) > buzzRefPower * 1e-3) return SPEECH
        var end = to
        while (end - SPEECH_BLOCK >= from) {
            if (AiFeedSignals.diffRms(fed, end - SPEECH_BLOCK, end) > SPEECH_DIFF_RMS) return SPEECH
            end -= SPEECH_BLOCK
        }
        return AMBIENT
    }

    private fun run(variant: Variant, seed: Long, aiPhaseMs: Double): Run {
        val sound = scene(seed)
        val n = sound.size
        val raw = FloatArray(n)
        val fed = FloatArray(n)
        val external = variant.external
        val timeline = SelfVibrationTimeline()
        var timelineWrites = 0
        // 캡처 스레드: 외부 사운드 모드에서만 시각을 세고 AI 입력을 거른다(AudioCaptureService.captureLoop).
        val clock = if (external && variant.filter) CaptureClock(RATE) else null
        val filter = if (external && variant.filter) AiFeedFilter(RATE, timeline::snapshot) else null
        // 진동 알림(HapticNotifier): 외부 사운드 모드에서만 문과 상한, 라벨 신선도.
        val gate = if (external) SelfVibrationGate() else null
        val freshness = if (external && variant.idleRule) AiLabelFreshness() else null
        val tickConfig: (String) -> HapticPolicy.ClassConfig =
            if (external) { label -> config(label).inExternalSound(label) } else config
        val policy = HapticPolicy()
        val driver = HapticDriver(amplitudeControl = true)
        val gain = if (external) GAIN else 1f

        val pulses = ArrayList<LongArray>()
        var firstLivePulse = 0
        var hapticPeak = 0f

        // AI 대역
        var latest: AiClassificationResult? = null
        var lastActiveMs = Double.NaN
        var silentDone = 0
        var confirmed = AMBIENT
        var candidate = ""
        var streak = 0
        var lastInference: Pair<Double, String>? = null

        val buf = FloatArray(BUF * 2)
        // 오디오 서버의 정확한 시각 표시: 0 번 프레임이 0ms 에 들어왔다.
        val exactStamp: (LongArray) -> Boolean = { out ->
            out[0] = 0
            out[1] = 0
            true
        }

        var k = 0
        var tHaptic = 0L
        var tAi = aiPhaseMs
        while (true) {
            val tCapture = (k + 1) * BUF * 1000.0 / RATE + LATENCY_MS
            if (minOf(tCapture, tHaptic.toDouble(), tAi) > RUN_MS) break
            if (tCapture <= tHaptic && tCapture <= tAi) {
                // 캡처 버퍼 하나가 들어왔다.
                val from = k * BUF
                while (firstLivePulse < pulses.size && pulses[firstLivePulse][1] + BUZZ_TAIL_MS < from * 1000.0 / RATE - 50) {
                    firstLivePulse++
                }
                var peak = 0f
                for (i in 0 until BUF) {
                    val f = from + i
                    val t = f * 1000.0 / RATE
                    var v = sound[f].toDouble()
                    if (variant.buzz) {
                        for (p in firstLivePulse until pulses.size) {
                            v += AiFeedSignals.buzzAt(t, pulses[p][0] + BUZZ_LAG_MS, pulses[p][1] + BUZZ_TAIL_MS)
                        }
                    }
                    raw[f] = v.toFloat()
                    buf[2 * i] = raw[f]
                    buf[2 * i + 1] = raw[f]
                    peak = maxOf(peak, abs(raw[f]))
                }
                hapticPeak = maxOf(hapticPeak, minOf(1f, peak * gain))
                val startMs = clock?.onRead(BUF, (tCapture * 1e6).toLong(), exactStamp) ?: 0.0
                if (filter != null && clock != null) filter.process(buf, BUF * 2, startMs, clock.endExtraMs)
                var active = false
                for (i in 0 until BUF) {
                    fed[from + i] = buf[2 * i]
                    if (abs(buf[2 * i]) > ACTIVE) active = true
                }
                if (active) {
                    lastActiveMs = tCapture
                    silentDone = 0
                }
                k++
            } else if (tAi <= tHaptic) {
                // AI 대역의 틱.
                val open = !lastActiveMs.isNaN() && (tAi - lastActiveMs < AI_WINDOW_MS || silentDone < 2)
                if (open) {
                    val delivered = k * BUF
                    val label = classify(fed, maxOf(0, delivered - AI_WINDOW), delivered)
                    if (label == confirmed) {
                        streak = 0
                        candidate = ""
                    } else {
                        if (label == candidate) streak++ else {
                            candidate = label
                            streak = 1
                        }
                        if (streak >= 2) {
                            confirmed = label
                            streak = 0
                            candidate = ""
                        }
                    }
                    // 추론마다 새 결과 객체를 낸다.
                    latest = AiClassificationResult(
                        coarse = confirmed, display = confirmed, confidence = 1f, meetsThreshold = true, timestampMs = 0L
                    )
                    lastInference = tAi to confirmed
                    if (tAi - lastActiveMs >= AI_WINDOW_MS) silentDone = minOf(2, silentDone + 1)
                }
                tAi += AI_TICK_MS
            } else {
                // 진동 알림의 틱(HapticNotifier.tick 과 같은 순서).
                val now = tHaptic
                val label = if (freshness != null) freshness.label(now, latest) else latest?.coarse
                val peak = hapticPeak
                hapticPeak = 0f
                val level = gate?.read(now, peak) ?: peak
                val vibe = policy.onTick(
                    now, label, level, tickConfig, false,
                    holding = gate?.holding == true,
                    levelAtMs = gate?.levelAtMs ?: now
                )
                when (val command = driver.onTick(now, vibe)) {
                    is HapticDriver.Command.Play -> {
                        val duration = command.plan.durationMs
                        val mode = requireNotNull(vibe).mode
                        if (external) {
                            val guard = HapticTuning.aiFeedGuardMs(mode)
                            val window = timeline.open(now - HapticTuning.AI_FEED_PRE_MS, now + duration + guard, now + duration)
                            timeline.setEnd(window, now + duration + guard, now + duration)
                            timelineWrites += 2
                        }
                        gate?.onPlayed(now, duration, HapticTuning.selfHearingGuardMs(mode))
                        pulses.add(longArrayOf(now, now + duration))
                    }
                    HapticDriver.Command.Cancel -> {
                        for (p in pulses) if (p[0] <= now && p[1] > now) p[1] = now
                        gate?.onCancelled(now)
                        if (external) {
                            timeline.cutLatest(now)
                            timelineWrites++
                        }
                    }
                    HapticDriver.Command.None -> Unit
                }
                var wake = driver.nextWakeMs(now)
                gate?.reopenAtMs(now)?.let { wake = minOf(wake, it) }
                if (wake <= now) wake = now + 1
                tHaptic = wake
            }
        }
        return Run(pulses, raw, fed, timelineWrites, lastInference)
    }

    private fun runs(variant: Variant): List<Pair<String, Run>> =
        SEEDS.flatMap { seed -> PHASES.map { phase -> "seed=$seed phase=$phase" to run(variant, seed, phase) } }

    @Test
    fun `깨끗한 AI 입력이 없으면 진동 소리를 대화음으로 들어 20초 끝까지 진동한다`() {
        // #352 의 재현(S25+, 10/8). 쉬는 AI 의 라벨을 버리는 규칙만으로는 막지 못한다. 진동 소리가 AI 를 깨워 새 결과가 계속 나온다.
        for (variant in listOf(Variant.CapsOnly, Variant.IdleOnly)) {
            for ((name, r) in runs(variant)) {
                assertTrue("$variant $name: 말하는 동안 진동하지 않았다", r.pulsesBetween(3000, 6500) >= 3)
                assertTrue("$variant $name: ${r.lastMotorEnd}ms 에 멈췄다", r.lastMotorEnd > RUN_MS - 1000)
            }
        }
    }

    @Test
    fun `깨끗한 AI 입력과 쉬는 AI 의 라벨을 버리는 규칙이 있으면 말이 끝나고 2_5초 안에 멈춘다`() {
        // 시뮬레이션(실제 YAMNet, 33번)에서는 가장 늦은 것이 2.2초였다.
        for ((name, r) in runs(Variant.Chosen)) {
            assertTrue("$name: 말하는 동안 진동하지 않았다", r.pulsesBetween(3000, 6500) >= 3)
            val stop = r.lastMotorEnd - TALK_END_MS.toLong()
            assertTrue("$name: 말이 끝나고 ${stop}ms 뒤에 멈췄다", stop <= 2500)
        }
    }

    @Test
    fun `깨끗한 AI 입력만으로는 쉬는 AI 의 대화음 라벨이 남아 진동이 이어진다`() {
        // 쉬는 AI 의 라벨을 버리는 규칙이 왜 있는지 고정한다. AI 는 0.01 아래의 말끝을 대화음으로 듣는 채로 쉬고, 감도 ×32 의
        // 진동은 사무실 잡음에도 소리가 난다고 본다. 우리 진동 소리가 없는 기준 실행도 같다(시뮬레이션 J2 기준 실행 12번 중 1번).
        for (variant in listOf(Variant.FilterOnly, Variant.Reference)) {
            for ((name, r) in runs(variant)) {
                val (at, label) = requireNotNull(r.lastInference)
                assertEquals("$variant $name: AI 가 쉬며 남긴 라벨", SPEECH, label)
                assertTrue("$variant $name: AI 가 ${at}ms 에 쉬었다", at < TALK_END_MS + 2000)
                assertTrue("$variant $name: ${r.lastMotorEnd}ms 에 멈췄다", r.lastMotorEnd > RUN_MS - 1000)
            }
        }
        for ((name, r) in runs(Variant.ReferenceIdle)) {
            val stop = r.lastMotorEnd - TALK_END_MS.toLong()
            assertTrue("기준 + 규칙 $name: 말이 끝나고 ${stop}ms 뒤에 멈췄다", stop <= 2500)
        }
    }

    @Test
    fun `깨끗한 AI 입력은 조용한 방에서 이어지는 우리 진동 소리를 AI 에 넘기지 않는다`() {
        // 쉬는 AI 의 라벨이 남아 말이 끝난 뒤에도 느림이 1초마다 울리는 실행이다. 그동안 AI 가 받은 창에는 진동 소리(170Hz)가
        // 남지 않고, 같은 실행의 원래 소리에는 창마다 있다.
        for ((name, r) in runs(Variant.FilterOnly)) {
            assertTrue("$name: 조용한 방에서 진동하지 않았다", r.pulsesBetween(10_000, RUN_MS) >= 8)
            var heard = 0
            var raw = 0
            var windows = 0
            var at = frame(10_000.0)
            while (at + AI_WINDOW <= frame(RUN_MS.toDouble())) {
                if (AiFeedSignals.goertzel(r.fed, at, at + AI_WINDOW, RATE) > buzzRefPower * 1e-3) heard++
                if (AiFeedSignals.goertzel(r.raw, at, at + AI_WINDOW, RATE) > buzzRefPower * 1e-3) raw++
                windows++
                at += AI_WINDOW / 4
            }
            assertEquals("$name: AI 가 진동 소리를 들은 창", 0, heard)
            assertEquals("$name: 원래 소리에 진동 소리가 없는 창", windows, raw)
        }
    }

    @Test
    fun `폰 안의 소리는 AI 입력을 거르지 않고 구간도 알리지 않는다`() {
        assertTrue("폰 안의 소리는 앱의 진동을 듣지 않는다", !Variant.Internal.external)
        for ((name, r) in runs(Variant.Internal)) {
            assertTrue("$name: AI 입력이 원래 소리와 다르다", r.fed.contentEquals(r.raw))
            assertEquals("$name: 빼는 구간을 알렸다", 0, r.timelineWrites)
            assertTrue("$name: 말하는 동안 진동하지 않았다", r.pulsesBetween(3000, 6500) >= 3)
        }
    }

    private companion object {
        const val RATE = 48_000
        const val BUF = 512
        const val LATENCY_MS = 20.0
        const val GAIN = 32f
        const val RUN_MS = 20_000L
        const val LOUD_END_MS = 6_800.0
        const val TALK_END_MS = 8_000.0

        /** 말끝의 작은 말소리: 고른 잡음 ±0.0052(RMS 0.003). */
        const val QUIET_PEAK = 0.0052

        /** 모터 소리는 보낸 뒤 8ms 에 시작해 멈추라고 한 뒤 150ms 에 끝난다. */
        const val BUZZ_LAG_MS = 8.0
        const val BUZZ_TAIL_MS = 150.0

        const val AI_TICK_MS = 280.0
        const val AI_WINDOW_MS = 975.0
        const val AI_WINDOW = 46_800
        const val ACTIVE = 0.01f
        const val SPEECH_BLOCK = 960
        const val SPEECH_DIFF_RMS = 1e-3

        val SEEDS = listOf(1L, 2L)
        val PHASES = listOf(0.0, 93.0, 187.0)
    }
}

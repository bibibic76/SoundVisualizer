package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.max
import kotlin.random.Random

private const val DANGER = AiClassification.DANGER
private const val SPEECH = AiClassification.SPEECH
private const val AMBIENT = AiClassification.AMBIENT

/** 모든 종류가 같은 방식. */
private fun cfg(mode: HapticMode, level: Int = 100): (String) -> HapticPolicy.ClassConfig =
    { HapticPolicy.ClassConfig(true, HapticSettings(mode, level)) }

/** 외부 사운드 모드에서 [label] 로 [mode] 를 정해 두었을 때 실제로 울리는 방식(종류마다의 상한, #354). */
private fun playedIn(label: String, mode: HapticMode): HapticMode = HapticSettings(mode, 100).inExternalSound(label).mode

/**
 * 외부 사운드 모드에서 위협음이 그대로 울리는 박자 방식. 위협음의 상한까지라 지금은 느림·중간이다. 빠름·연속은 어느
 * 종류로도 울리지 않으므로(#354) 문의 시뮬레이션도 이 방식들로 본다.
 */
private val EXTERNAL_BEATS: List<HapticMode> =
    HapticMode.entries.filter { it != HapticMode.Off && it <= HapticSettings.externalCap(DANGER) }

/** 사용자가 고를 수 있는 진동 방식(꺼짐 빼고). 외부 사운드 모드에서는 상한까지로 낮춰 울린다. */
private val STORED_MODES: List<HapticMode> = HapticMode.entries.filter { it != HapticMode.Off }

/**
 * 외부 사운드 모드에서 폰이 자기 진동 소리를 듣는 고리(#290)를 소리 없이 JVM 에서 돌린다.
 *
 * 순서는 [HapticNotifier] 의 틱과 같다. 라벨 → 크기 읽기 → [SelfVibrationGate.read] → [HapticPolicy.onTick] →
 * [HapticDriver.onTick] → 울림·끊기를 문에 알림 → 다음 깨어날 시각은 driver 와 문 가운데 이른 것. 외부 사운드 모드
 * (`gated`)에서는 알림처럼 라벨마다 그 종류의 상한을 건 설정을 읽는다(#354).
 *
 * 마이크([Mic])는 48kHz 512프레임(10.67ms)마다 버퍼를 하나 내놓고, 입력 지연만큼 늦게 들어온다. 버퍼의 크기는 그 버퍼가
 * 덮는 시간의 실제 소리, 바닥 잡음, 앱의 진동 소리 가운데 가장 큰 것이다. 진동 소리는 울림을 보낸 뒤 `lagMs` 에 시작해
 * 울림이 끝난 뒤 `tailMs` 까지 남는다(모터가 늦게 멈추고 잦아드는 시간). 폰에서 잰 값이 아니라, 문의 꼬리 여유가 덮어야
 * 하는 범위를 고정하는 모델이다.
 */
class SelfHearingLoopTest {

    private class Mic(
        private val sounds: List<Triple<Long, Long, Float>>,
        private val buzzLevel: Float,
        private val lagMs: Long,
        private val tailMs: Long,
        private val latencyMs: Long,
        private val floorLevel: Float = 0.002f
    ) {
        private val bufferMs = 512.0 / 48.0
        private val motor = ArrayList<LongArray>()

        /** 새 울림은 울리던 것을 끝내고 새로 켠다(안드로이드 `vibrate()` 와 같다). */
        fun play(t: Long, onMs: Long) {
            cut(t)
            motor.add(longArrayOf(t, t + onMs))
        }

        fun cancel(t: Long) = cut(t)

        private fun cut(t: Long) {
            for (m in motor) if (m[0] <= t && m[1] > t) m[1] = t
        }

        /** 모터가 마지막으로 멈춘(멈출) 시각. */
        fun lastMotorEnd(): Long = motor.maxOfOrNull { it[1] } ?: Long.MIN_VALUE

        /** (prevMs, nowMs] 에 들어온 버퍼의 최대 크기([com.example.soundvisualizer.AudioEngine.takeHapticPeak] 와 같다). */
        fun take(prevMs: Long, nowMs: Long): Float {
            var k = floor(prevMs / bufferMs).toLong() + 1
            var peak = 0f
            while (k * bufferMs <= nowMs) {
                val captureEnd = k * bufferMs - latencyMs
                peak = max(peak, levelIn(captureEnd - bufferMs, captureEnd))
                k++
            }
            return peak
        }

        private fun levelIn(from: Double, to: Double): Float {
            var level = floorLevel
            for ((start, end, l) in sounds) if (start < to && end > from) level = max(level, l)
            for (m in motor) if (m[0] + lagMs < to && m[1] + tailMs > from) level = max(level, buzzLevel)
            return level
        }
    }

    private class Run(val plays: List<Pair<Long, HapticMode>>, val mic: Mic)

    private fun run(
        mic: Mic,
        untilMs: Long,
        label: (Long) -> String?,
        config: (String) -> HapticPolicy.ClassConfig,
        gated: Boolean,
        unlabeledAlerts: Boolean = false,
        lateness: (Long) -> Long = { 0L },
        sendDelay: (Long) -> Long = { 0L }
    ): Run {
        val policy = HapticPolicy()
        val driver = HapticDriver(amplitudeControl = true)
        val gate = if (gated) SelfVibrationGate() else null
        val tickConfig: (String) -> HapticPolicy.ClassConfig =
            if (gated) { l -> config(l).inExternalSound(l) } else config
        val plays = ArrayList<Pair<Long, HapticMode>>()
        var t = 0L
        var prev = 0L
        while (t <= untilMs) {
            val lab = label(t)
            val peak = mic.take(prev, t)
            prev = t
            val level = gate?.read(t, peak) ?: peak
            val vibe = policy.onTick(
                t, lab, level, tickConfig, unlabeledAlerts,
                holding = gate?.holding == true,
                levelAtMs = gate?.levelAtMs ?: t
            )
            when (val command = driver.onTick(t, vibe)) {
                is HapticDriver.Command.Play -> {
                    val mode = requireNotNull(vibe).mode
                    // 실제 알림은 울림을 보낸 직후의 시각을 문에 알린다(보내는 데 걸린 시간도 듣지 않는 구간에 든다).
                    val sent = t + sendDelay(t)
                    mic.play(sent, command.plan.durationMs)
                    gate?.onPlayed(sent, command.plan.durationMs, HapticTuning.selfHearingGuardMs(mode))
                    plays.add(t to mode)
                }
                HapticDriver.Command.Cancel -> {
                    mic.cancel(t)
                    gate?.onCancelled(t)
                }
                HapticDriver.Command.None -> Unit
            }
            var wake = driver.nextWakeMs(t)
            gate?.reopenAtMs(t)?.let { wake = minOf(wake, it) }
            if (wake <= t) wake = t + 1
            t = wake + lateness(wake)
        }
        return Run(plays, mic)
    }

    /** 꼬리 + 입력 지연 + 버퍼 하나 + 5ms 가 [played] 의 여유 안에 드는 꼬리들. */
    private fun tailsWithinGuard(played: HapticMode, latencyMs: Long): List<Long> {
        val guard = HapticTuning.selfHearingGuardMs(played)
        val max = guard - latencyMs - 11 - 5
        return listOf(20L, max / 2, max)
    }

    @Test
    fun `문이 없으면 자기 진동 소리만으로 진동이 끝나지 않는다`() {
        // #290 의 재현(문도 상한도 없을 때). 소리는 2초에 끝났는데 라벨은 남고, 진동 소리가 0.4초 여유 안에 계속 들린다.
        for (mode in listOf(HapticMode.Medium, HapticMode.Fast, HapticMode.Continuous)) {
            val mic = Mic(listOf(Triple(0L, 2000L, 0.3f)), buzzLevel = 0.02f, lagMs = 15, tailMs = 40, latencyMs = 20)
            val r = run(mic, 30_000, { DANGER }, cfg(mode), gated = false)
            assertTrue("$mode 가 멈췄다(${r.mic.lastMotorEnd()})", r.mic.lastMotorEnd() > 28_000)
        }
    }

    @Test
    fun `문이 있으면 소리가 언제 끝나든 1초 안에 멈춘다`() {
        // 소리가 박자의 어느 곳에서 끝나는지에 따라 멈추는 시각이 달라진다. 1초 동안 7ms 씩 옮겨 가며 본다.
        // 정해 둔 방식은 종류의 상한까지로 낮춰 울리므로(대화음은 느림, 위협음은 중간까지) 실제로 울리는 것은 느림·중간이다.
        // 모델에서 가장 늦은 경우는 느림 0.84초, 중간 0.85초였다. 폰 안의 소리(문도 진동 소리도 없음)는 0.63초다.
        // 도움말의 "보통 1초 안" 이 이 한도다.
        // 소리가 울림 사이의 쉼에서 끝나면 마지막 울림이 소리보다 먼저 끝나 음수가 나온다(폰 안의 소리도 같다).
        for (label in listOf(SPEECH, DANGER)) {
            for (mode in STORED_MODES) {
                val played = playedIn(label, mode)
                for (buzz in listOf(0.02f, 0.5f)) {
                    for (tail in tailsWithinGuard(played, latencyMs = 20)) {
                        var end = 2000L
                        while (end < 3000L) {
                            val mic = Mic(listOf(Triple(0L, end, 0.3f)), buzz, lagMs = 15, tailMs = tail, latencyMs = 20)
                            val r = run(mic, 10_000, { label }, cfg(mode), gated = true)
                            val stop = r.mic.lastMotorEnd() - end
                            assertTrue("$label $mode buzz=$buzz tail=$tail end=$end: 소리가 끝나고 ${stop}ms 뒤에 멈췄다", stop <= 900)
                            end += 7
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `울림을 보내거나 깨어나는 것이 늦어도 1초 안에 멈춘다`() {
        // 실제 알림은 울림을 보낸 직후의 시각을 문에 알리고(보내는 데 몇 ms 걸린다), 틱은 조금씩 늦게 깨어난다.
        // 느림은 이어 준 크기를 지금 들은 것으로 치면 다음 박자까지 여유가 18ms 뿐이라, 이런 지연에 한 번 더 울려
        // 1.5초 넘게 이어졌다. 지금은 400개 경우 모두에서 가장 늦은 것이 0.85초다.
        for (mode in EXTERNAL_BEATS) {
            for (seed in 1..400) {
                val random = Random(seed)
                val end = 2000L + random.nextLong(0, 1000)
                val mic = Mic(listOf(Triple(0L, end, 0.3f)), 0.5f, lagMs = 15, tailMs = 40, latencyMs = 20)
                val r = run(
                    mic, 10_000, { DANGER }, cfg(mode), gated = true,
                    lateness = { random.nextLong(0, 16) },
                    sendDelay = { random.nextLong(0, 9) }
                )
                val stop = r.mic.lastMotorEnd() - end
                assertTrue("$mode seed=$seed: 소리가 끝나고 ${stop}ms 뒤에 멈췄다", stop <= 950)
            }
        }
    }

    @Test
    fun `울림을 보내는 데 늘 오래 걸리는 폰에서도 느림이 한 번 더 울리지 않는다`() {
        // 이어 준 크기를 꼬리 끝에 가깝게 치면 느림은 다음 박자까지 여유가 20ms 남짓뿐이라, 보내는 데 21ms 넘게 걸리는
        // 폰에서는 소리가 끝난 뒤 거의 늘 0.4초짜리 울림이 한 번 더 나가 1.8초까지 이어졌다(리뷰에서 찾음).
        // 꼬리 끝 0.08초 전으로 막으면 0.08초씩 걸려도 가장 늦은 것이 느림 0.85초, 중간 0.84초다.
        for (mode in EXTERNAL_BEATS) {
            for (delay in listOf(21L, 30L, 50L, 80L)) {
                var end = 2000L
                while (end < 3000L) {
                    val mic = Mic(listOf(Triple(0L, end, 0.3f)), 0.5f, lagMs = 15, tailMs = 40, latencyMs = 20)
                    val r = run(mic, 10_000, { DANGER }, cfg(mode), gated = true, sendDelay = { delay })
                    val stop = r.mic.lastMotorEnd() - end
                    assertTrue("$mode delay=$delay end=$end: 소리가 끝나고 ${stop}ms 뒤에 멈췄다", stop <= 950)
                    end += 7
                }
            }
        }
    }

    @Test
    fun `소리가 이어지는 동안 상한까지의 박자로 그대로 울린다`() {
        // 상한보다 빠르게 정해 둔 방식은 상한의 박자로 울린다. '빠름'과 '연속'은 어느 종류로도 울리지 않는다(#354).
        for (label in listOf(SPEECH, DANGER)) {
            for (mode in STORED_MODES) {
                val mic = Mic(listOf(Triple(0L, 10_000L, 0.3f)), 0.5f, lagMs = 15, tailMs = 40, latencyMs = 20)
                val r = run(mic, 12_000, { label }, cfg(mode), gated = true)
                val played = playedIn(label, mode)
                assertTrue("$label $mode 가 $played 아닌 방식으로 울렸다", r.plays.all { it.second == played })
                val beats = r.plays.map { it.first }.filter { it in 0..9_000 }
                val period = HapticTuning.periodMs(played)
                assertTrue(beats.size > 5)
                beats.zipWithNext { a, b -> assertEquals("$label $mode 의 박자", period, b - a) }
            }
        }
    }

    @Test
    fun `외부 사운드 모드에서 환경음은 소리가 이어져도 울리지 않는다`() {
        // 환경음은 주변에서 늘 들려 진동하게 두면 그치지 않는다. 그래서 외부 사운드 모드에서는 상한이 꺼짐이다(#354).
        for (mode in STORED_MODES) {
            val mic = Mic(listOf(Triple(0L, 10_000L, 0.3f)), 0.5f, lagMs = 15, tailMs = 40, latencyMs = 20)
            val r = run(mic, 12_000, { AMBIENT }, cfg(mode), gated = true)
            assertTrue("$mode 로 정해 둔 환경음이 울렸다: ${r.plays.take(3)}", r.plays.isEmpty())
            val inside = run(Mic(listOf(Triple(0L, 10_000L, 0.3f)), 0f, 15, 40, 20), 12_000, { AMBIENT }, cfg(mode), gated = false)
            assertTrue("폰 안의 소리에서는 $mode 그대로 울린다", inside.plays.isNotEmpty() && inside.plays.all { it.second == mode })
        }
    }

    @Test
    fun `자기 진동에 라벨이 계속 오가도 소리가 끝나면 멈춘다`() {
        // AI 가 자기 진동 소리를 다른 종류로 번갈아 들으면, 방식이 바뀌거나 울림이 끊겼다 다시 날 때마다 새 울림이 바로 나가고
        // 그 울림 동안은 기다리므로 라벨이 그대로일 때보다 늦게 멈춘다. 상한 아래에서 서로 다르게 울리는 것은 위협음 중간과
        // 대화음 느림, 그리고 진동하지 않는 종류(외부 사운드 모드의 환경음, 꺼 둔 대화음)다. 모델에서 가장 늦은 경우는 위협음
        // 중간이 대화음 느림이나 진동하지 않는 종류와 오갈 때 1.22초였다(위협음이 느림이면 0.92초 안, 폰 안의 소리는 0.63초).
        // 도움말의 "보통 1초 안" 은 이 드문 경우를 뺀 것이다. 꼬리가 두 배로 늘어나는 회귀는 여기서 걸린다.
        val pairs = listOf(
            HapticMode.Fast to HapticMode.Medium, HapticMode.Medium to HapticMode.Fast,
            HapticMode.Slow to HapticMode.Medium, HapticMode.Medium to HapticMode.Slow,
            HapticMode.Slow to HapticMode.Fast, HapticMode.Fast to HapticMode.Slow,
            HapticMode.Medium to HapticMode.Off, HapticMode.Slow to HapticMode.Off
        )
        for (other in listOf(SPEECH, AMBIENT)) {
            for ((danger, otherMode) in pairs) {
                val config: (String) -> HapticPolicy.ClassConfig = { label ->
                    HapticPolicy.ClassConfig(true, HapticSettings(if (label == DANGER) danger else otherMode, 80))
                }
                for (flipMs in listOf(250L, 500L, 750L, 1000L)) {
                    var end = 2000L
                    while (end < 3000L) {
                        val mic = Mic(listOf(Triple(0L, end, 0.3f)), 0.5f, lagMs = 15, tailMs = 40, latencyMs = 20)
                        val r = run(mic, 10_000, { t -> if ((t / flipMs) % 2 == 0L) DANGER else other }, config, gated = true)
                        val stop = r.mic.lastMotorEnd() - end
                        assertTrue("$danger/$other $otherMode flip=${flipMs}ms end=$end: 소리가 끝나고 ${stop}ms 뒤에 멈췄다", stop <= 1300)
                        end += 7
                    }
                }
            }
        }
    }

    @Test
    fun `AI 를 쓸 수 없는 실행도 소리가 끝나면 멈춘다`() {
        val mic = Mic(listOf(Triple(0L, 2000L, 0.5f)), 0.3f, lagMs = 15, tailMs = 40, latencyMs = 20)
        val r = run(mic, 30_000, { null }, cfg(HapticMode.Medium), gated = true, unlabeledAlerts = true)
        assertTrue("큰 소리에 울리지 않았다", r.plays.isNotEmpty())
        val stop = r.mic.lastMotorEnd() - 2000
        assertTrue("소리가 끝나고 ${stop}ms 뒤에 멈췄다", stop <= 1000)
    }

    @Test
    fun `AI 를 쓸 수 없을 때 큰 소리는 외부 사운드 모드에서 위협음 상한인 중간까지만 울린다`() {
        // 종류를 모르는 큰 소리는 위협음 설정으로 울리므로(#225) 위협음의 상한을 따른다. 모델에서 가장 늦게 멈춘 경우는
        // 중간으로 정해 둔 것과 같은 0.85초였다.
        for (mode in listOf(HapticMode.Fast, HapticMode.Continuous)) {
            var end = 2000L
            while (end < 3000L) {
                val mic = Mic(listOf(Triple(0L, end, 0.5f)), 0.3f, lagMs = 15, tailMs = 40, latencyMs = 20)
                val r = run(mic, 10_000, { null }, cfg(mode), gated = true, unlabeledAlerts = true)
                assertTrue("$mode end=$end: 큰 소리에 울리지 않았다", r.plays.isNotEmpty())
                assertTrue("$mode end=$end: 중간 아닌 방식으로 울렸다", r.plays.all { it.second == HapticMode.Medium })
                val stop = r.mic.lastMotorEnd() - end
                assertTrue("$mode end=$end: 소리가 끝나고 ${stop}ms 뒤에 멈췄다", stop <= 900)
                end += 7
            }
        }
    }

    @Test
    fun `말소리처럼 끊어지는 소리에서 박자가 폰 안의 소리보다 더 흔들리지 않고 거의 빠지지 않는다`() {
        // 모델(20개 경우 평균)에서 박자가 어긋난 비율은 느림 25%→12%, 중간 11%→6% 로 오히려 줄었다.
        // 폰 안의 소리가 울린 박자 가운데 외부 사운드 모드가 울리지 않은 것은 느림 2.4%, 중간 1.4% 다.
        // 짧은 소리가 울림 도중에만 났다 끝나면 듣지 못해 박자 하나가 빠질 수 있다. 이어 주기를 빼면 중간 13% 가 빠진다.
        // 대화음은 외부 사운드 모드에서 느림까지라, 중간은 같은 소리를 위협음으로 들은 경우(끊어지는 경보음 등)로 본다.
        for (mode in EXTERNAL_BEATS) {
            val label = if (mode <= HapticSettings.externalCap(SPEECH)) SPEECH else DANGER
            val runs = (1..10).map { seed ->
                val sounds = speechLike(seed, 60_000)
                run(Mic(sounds, 0f, 15, 40, 20), 60_000, { label }, cfg(mode), gated = false) to
                    run(Mic(sounds, 0.5f, 15, 40, 20), 60_000, { label }, cfg(mode), gated = true)
            }
            assertNoWorseRhythm("$mode $label", runs, mode, tolerance = 0.03, maxMissing = 0.04)
        }
    }

    @Test
    fun `AI 를 쓸 수 없을 때 끊어지는 큰 소리에서도 박자가 크게 흔들리지 않는다`() {
        // 이어 준 크기로 큰 소리가 이어진 시각을 늘리지 않으면 중간은 박자의 18% 남짓이 어긋났다. 지금은 2% 남짓이다.
        // 빠진 박자는 중간 0.6% 다(이어 주기를 빼면 4.5%). 큰 소리는 위협음 설정을 따르므로 위협음의 상한까지 본다.
        for (mode in EXTERNAL_BEATS) {
            val runs = (1..10).map { seed ->
                val sounds = loudBursts(seed, 60_000)
                run(Mic(sounds, 0f, 15, 40, 20), 60_000, { null }, cfg(mode), gated = false, unlabeledAlerts = true) to
                    run(Mic(sounds, 0.5f, 15, 40, 20), 60_000, { null }, cfg(mode), gated = true, unlabeledAlerts = true)
            }
            assertNoWorseRhythm("$mode", runs, mode, tolerance = 0.06, maxMissing = 0.02)
        }
    }

    /**
     * 경우(폰 안의 소리, 외부 사운드 모드)마다 외부 사운드 모드의 박자가 폰 안의 소리보다 [tolerance] 넘게 더 어긋나지 않고,
     * 폰 안의 소리가 울린 박자 가운데 외부 사운드 모드가 울리지 않은 것([missing])이 모두 합쳐 [maxMissing] 을 넘지 않는다.
     */
    private fun assertNoWorseRhythm(
        name: String,
        runs: List<Pair<Run, Run>>,
        mode: HapticMode,
        tolerance: Double,
        maxMissing: Double
    ) {
        val period = HapticTuning.periodMs(mode)
        var played = 0
        var missed = 0
        runs.forEachIndexed { i, (inside, outside) ->
            val inRatio = irregular(inside.plays, period)
            val outRatio = irregular(outside.plays, period)
            assertTrue(
                "$name seed=${i + 1}: 어긋남 외부 ${"%.3f".format(outRatio)} > 폰 안 ${"%.3f".format(inRatio)}",
                outRatio <= inRatio + tolerance
            )
            played += inside.plays.size
            missed += missing(inside.plays, outside.plays, period)
        }
        val ratio = missed.toDouble() / played
        assertTrue("$name: 폰 안의 소리가 울린 박자 ${played}개 가운데 ${missed}개(${"%.3f".format(ratio)})를 울리지 않았다", ratio <= maxMissing)
    }

    @Test
    fun `꼬리가 여유보다 길면 걸러 내지 못한다`() {
        // 알려진 한계를 고정한다. 외부 사운드 모드에서 가장 빠른 중간도 여유(0.18초)보다 꼬리가 길면 다시 이어진다. 그런 폰이
        // 있으면 여유를 늘려야 한다. 여유가 0.11초인 빠름은 상한 때문에 외부 사운드 모드에서 울리지 않는다(#354).
        val fastest = EXTERNAL_BEATS.last()
        val guard = HapticTuning.selfHearingGuardMs(fastest)
        val mic = Mic(listOf(Triple(0L, 2000L, 0.3f)), 0.02f, lagMs = 15, tailMs = guard, latencyMs = 20)
        val r = run(mic, 30_000, { DANGER }, cfg(fastest), gated = true)
        assertTrue("꼬리가 여유보다 긴데 멈췄다", r.mic.lastMotorEnd() > 28_000)
    }

    @Test
    fun `바닥 잡음이 기준을 넘으면 문과 상관없이 이어진다`() {
        // #290 과 증상은 같지만 원인이 다르다. 마이크 감도를 높여 조용한 방의 잡음이 0.01 을 넘으면 소리가 계속 나는 것이다.
        val mic = Mic(listOf(Triple(0L, 2000L, 0.3f)), 0f, lagMs = 15, tailMs = 40, latencyMs = 20, floorLevel = 0.012f)
        val r = run(mic, 30_000, { DANGER }, cfg(HapticMode.Medium), gated = true)
        assertTrue("잡음이 기준을 넘는데 멈췄다", r.mic.lastMotorEnd() > 28_000)
    }

    /** 말소리처럼 0.08~0.6초 소리와 짧거나 긴 쉼이 번갈아 온다. */
    private fun speechLike(seed: Int, totalMs: Long): List<Triple<Long, Long, Float>> {
        val random = Random(seed)
        val out = ArrayList<Triple<Long, Long, Float>>()
        var t = 500L
        while (t < totalMs) {
            val on = random.nextLong(80, 600)
            out.add(Triple(t, t + on, 0.05f + random.nextFloat() * 0.45f))
            val pause = if (random.nextInt(3) < 2) random.nextLong(30, 250) else random.nextLong(250, 900)
            t += on + pause
        }
        return out
    }

    /** 0.15~0.7초짜리 큰 소리(0.3~0.8)와 0.4초보다 짧은 쉼이 번갈아 온다. 쉼이 짧아 폰 안의 소리는 박자가 이어진다. */
    private fun loudBursts(seed: Int, totalMs: Long): List<Triple<Long, Long, Float>> {
        val random = Random(seed)
        val out = ArrayList<Triple<Long, Long, Float>>()
        var t = 500L
        while (t < totalMs) {
            val on = random.nextLong(150, 700)
            out.add(Triple(t, t + on, 0.3f + random.nextFloat() * 0.5f))
            t += on + random.nextLong(40, 380)
        }
        return out
    }

    /**
     * [inside] 가 울린 박자 가운데 [outside] 가 반 박자 안에 울리지 않은 것의 수. 폰 안의 소리라면 울렸을 박자를 빠뜨린
     * 것이다(소리가 끝난 뒤 폰 안의 소리만 한 번 더 울린 박자도 든다).
     */
    private fun missing(inside: List<Pair<Long, HapticMode>>, outside: List<Pair<Long, HapticMode>>, period: Long): Int {
        val out = outside.map { it.first }
        return inside.count { (x, _) -> out.none { y -> y >= x - period / 2 && y < x + period / 2 } }
    }

    /** 이어진 두 울림 사이가 박자와 다른 비율. 소리가 끊겨 새로 시작한 것(두 박자 넘게 벌어진 것)은 세지 않는다. */
    private fun irregular(plays: List<Pair<Long, HapticMode>>, period: Long): Double {
        val gaps = plays.map { it.first }.zipWithNext { a, b -> b - a }.filter { it < period * 2 }
        if (gaps.isEmpty()) return 0.0
        return gaps.count { it != period }.toDouble() / gaps.size
    }
}

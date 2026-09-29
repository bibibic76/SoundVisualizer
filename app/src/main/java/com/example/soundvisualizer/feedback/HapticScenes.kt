package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** 흉내 낸 소리 하나. 스테레오 버퍼(512 프레임)를 차례로 채운다. */
fun interface SceneSource {
    /** [out] 에 인터리브 스테레오 1024 개를 채운다. 장면이 끝났으면 0 으로 채운다. */
    fun next(out: FloatArray)
}

/**
 * 진동 미리보기와 개발자 '진동 시험', 테스트가 쓰는 흉내 낸 소리.
 * @param label 이 소리를 어느 종류로 볼지([AiClassification] 라벨)
 */
class HapticScene(
    val id: String,
    val label: String,
    val durationMs: Long,
    private val factory: (totalSamples: Long) -> (Long) -> Float
) {
    /** 매번 처음부터 같은 소리를 낸다(잡음 씨앗 고정). */
    fun open(): SceneSource {
        val total = durationMs * HapticFeatureMirror.SAMPLE_RATE / 1000
        val gen = factory(total)
        var n = 0L
        return SceneSource { out ->
            for (i in 0 until HapticFeatureMirror.FRAMES_PER_BUFFER) {
                val v = if (n < total) gen(n) else 0f
                out[2 * i] = v
                out[2 * i + 1] = v
                n++
            }
        }
    }

    /** 앞 [ms] 만 쓰는 같은 소리. */
    fun trimmed(ms: Long): HapticScene = HapticScene(id, label, min(ms, durationMs), factory)
}

/**
 * 테스트용 소리 모음. 사이렌 세 가지(웨일·옐프·하이로)는 크기가 한결같고 음높이만 움직여, 크기만 따라가면 모두 같은
 * 웅 소리가 된다. 삐소리·화재경보·노크·총소리·말·음악은 박자와 크기로 구별된다.
 */
object HapticScenes {
    private const val SR = HapticFeatureMirror.SAMPLE_RATE.toDouble()

    val wail = tone("wail", AiClassification.DANGER, 8000) { t -> 1000 + 400 * sin(2 * PI * t / 4.0 - PI / 2) }
    val yelp = tone("yelp", AiClassification.DANGER, 6000) { t ->
        val s = sin(2 * PI * 3.0 * t)
        1000 + 400 * sign(s) * sqrt(abs(s))
    }
    val hilo = HapticScene("hilo", AiClassification.DANGER, 6000) { _ ->
        var phase = 0.0
        val g: (Long) -> Float = { n ->
            val t = n / SR
            val f = if (t % 1.0 < 0.5) 450.0 else 600.0
            phase += 2 * PI * f / SR
            (0.45 * (sin(phase) + 0.3 * sin(2 * phase))).toFloat()
        }
        g
    }
    val beeps = HapticScene("beeps", AiClassification.DANGER, 5000) { _ -> beepGen(0.1, 0.1, 1000.0, 0.5) }
    val t3 = HapticScene("t3", AiClassification.DANGER, 8000) { _ ->
        val g: (Long) -> Float = { n ->
            val t = n / SR
            val c = t % 4.0
            val on = c < 3.0 && (c % 1.0) < 0.5
            if (on) (0.55 * sin(2 * PI * 3100 * t)).toFloat() else 0f
        }
        g
    }
    val knock = HapticScene("knock", AiClassification.AMBIENT, 2550) { _ ->
        val rnd = Random(7)
        val g: (Long) -> Float = { n ->
            val t = n / SR
            var v = 0.0
            for (i in 0 until 5) {
                val s = 0.3 + i * 0.25
                val tt = t - s
                if (tt >= 0 && tt < 0.06) v += 0.6 * rnd.nextGaussian() * exp(-tt / 0.012) + 0.5 * sin(2 * PI * 180 * tt) * exp(-tt / 0.02)
            }
            v.coerceIn(-1.0, 1.0).toFloat()
        }
        g
    }
    val gunshots = HapticScene("gunshots", AiClassification.DANGER, 4100) { _ ->
        val rnd = Random(7)
        val times = doubleArrayOf(0.3, 0.9, 1.1, 1.3, 2.6)
        val g: (Long) -> Float = { n ->
            val t = n / SR
            var v = 0.0
            for (s in times) {
                val tt = t - s
                if (tt >= 0 && tt < 0.8) v += 0.9 * rnd.nextGaussian() * (exp(-tt / 0.004) + 0.25 * exp(-tt / 0.15))
            }
            v.coerceIn(-1.0, 1.0).toFloat()
        }
        g
    }
    val speech = HapticScene("speech", AiClassification.SPEECH, 6000) { _ ->
        val rnd = Random(7)
        var f0Phase = 0.0
        var sylPhase = 0.0
        val g: (Long) -> Float = { n ->
            val t = n / SR
            val f0 = 140 + 30 * sin(2 * PI * 0.7 * t)
            f0Phase += 2 * PI * f0 / SR
            var voiced = 0.0
            for (k in 1..7) voiced += (0.6 / k) * sin(k * f0Phase)
            val rate = 4.5 + 1.5 * sin(2 * PI * 0.3 * t)
            sylPhase += 2 * PI * rate / SR
            val syl = max(sin(sylPhase), 0.0).pow(1.5)
            val c = t % 2.5
            val phrase = ramp(c, 0.0, 0.02) * (1 - ramp(c, 1.9, 0.02))
            (0.35 * (voiced + 0.08 * rnd.nextGaussian()) * syl * phrase).toFloat()
        }
        g
    }
    val music = HapticScene("music", AiClassification.AMBIENT, 8000) { _ ->
        val g: (Long) -> Float = { n ->
            val t = n / SR
            var v = 0.12 * (sin(2 * PI * 220 * t) + sin(2 * PI * 277.2 * t) + sin(2 * PI * 329.6 * t))
            val tt = t % 0.5
            if (tt < 0.25) v += 0.7 * sin(2 * PI * (50 + 100 * exp(-tt / 0.03)) * tt) * exp(-tt / 0.08)
            v.coerceIn(-1.0, 1.0).toFloat()
        }
        g
    }
    val rain = HapticScene("rain", AiClassification.AMBIENT, 14_000) { _ ->
        val rnd = Random(7)
        var lp = 0.0
        var drop = 0.0
        val g: (Long) -> Float = { n ->
            val w = rnd.nextGaussian()
            lp += 0.02 * (w - lp)
            if (rnd.nextDouble() < 4.0 / SR) drop = 0.25
            val d = drop * rnd.nextGaussian()
            drop *= exp(-1.0 / (0.003 * SR))
            (0.6 * lp + 0.08 * w + d).coerceIn(-1.0, 1.0).toFloat()
        }
        g
    }
    val quietLoud = HapticScene("quiet_loud", AiClassification.DANGER, 6000) { _ ->
        val quiet = beepGen(0.15, 0.15, 800.0, 0.5 * 10.0.pow(-24.0 / 20))
        val loud = beepGen(0.15, 0.15, 800.0, 0.5)
        val g: (Long) -> Float = { n -> if (n / SR < 3.0) quiet(n) else loud(n) }
        g
    }
    val steady = HapticScene("steady", AiClassification.DANGER, 15_000) { _ ->
        val g: (Long) -> Float = { n -> (0.5 * sin(2 * PI * 1000 * n / SR)).toFloat() }
        g
    }

    /** 개발자 '진동 시험' 의 순서. */
    val all: List<HapticScene> = listOf(wail, yelp, hilo, beeps, t3, knock, gunshots, speech, music, rain, quietLoud, steady)

    fun byId(id: String): HapticScene? = all.firstOrNull { it.id == id }

    /** 설정의 소리 따라 미리보기: 그 종류의 흔한 소리 4초. 위협음은 한국 구급차의 '삐뽀' 인 하이로다. */
    fun previewFor(label: String): HapticScene = when (label) {
        AiClassification.DANGER -> hilo.trimmed(PREVIEW_MS)
        AiClassification.SPEECH -> speech.trimmed(PREVIEW_MS)
        else -> music.trimmed(PREVIEW_MS)
    }

    private const val PREVIEW_MS = 4000L

    private fun tone(id: String, label: String, ms: Long, freq: (Double) -> Double) = HapticScene(id, label, ms) { _ ->
        var phase = 0.0
        val g: (Long) -> Float = { n ->
            phase += 2 * PI * freq(n / SR) / SR
            (0.5 * sin(phase)).toFloat()
        }
        g
    }

    private fun beepGen(on: Double, off: Double, hz: Double, amp: Double): (Long) -> Float = { n ->
        val t = n / SR
        val c = t % (on + off)
        val gate = if (c < on) min(1.0, min(c, on - c) / 0.003) else 0.0
        (amp * sin(2 * PI * hz * t) * gate).toFloat()
    }

    private fun ramp(x: Double, at: Double, width: Double): Double = ((x - at) / width).coerceIn(0.0, 1.0)
}

/**
 * 네이티브(native-lib.cpp 의 pushAudioBuffer) 와 같은 계산을 Kotlin 으로 한다. 흉내 낸 소리를 실제와 같은 값으로
 * 진동 경로에 넣기 위해서다. 두 계산이 같은지는 계측 테스트가 확인한다.
 */
object HapticFeatureMirror {
    const val SAMPLE_RATE = 48_000L
    const val FRAMES_PER_BUFFER = 512
    const val FLOATS_PER_BUFFER = FRAMES_PER_BUFFER * 2
    const val BUFFER_MS = FRAMES_PER_BUFFER * 1000.0 / SAMPLE_RATE

    /** [out] = `[피크, RMS, 영교차율(u16 로 줄인 값)]`. */
    fun analyze(buf: FloatArray, floatCount: Int, out: FloatArray) {
        var l = 0f
        var r = 0f
        var sumSq = 0f
        var i = 0
        while (i + 1 < floatCount) {
            val a = abs(buf[i])
            val b = abs(buf[i + 1])
            if (a > l) l = a
            if (b > r) r = b
            sumSq += a * a + b * b
            i += 2
        }
        val peak = if (l > r) l else r
        val frames = floatCount / 2
        var rms = if (frames > 0) sqrt(sumSq / (2.0f * frames)) else 0f
        if (!(rms >= 0f && rms < 16f)) rms = 0f
        val h = max(0.1f * rms, 1e-4f)
        var state = 0
        var crossings = 0
        var first = -1
        var last = -1
        for (k in 0 until frames) {
            val m = 0.5f * (buf[2 * k] + buf[2 * k + 1])
            if (m > h) {
                if (state < 0) {
                    crossings++
                    if (first < 0) first = k
                    last = k
                }
                state = 1
            } else if (m < -h) {
                if (state > 0) {
                    crossings++
                    if (first < 0) first = k
                    last = k
                }
                state = -1
            }
        }
        val tone = if (crossings >= 2 && last > first) (crossings - 1).toFloat() / (last - first) else 0f
        val q = (min(tone, 1f) * 65535f).roundToInt().coerceIn(0, 65535)
        out[0] = peak
        out[1] = rms
        out[2] = q / 65535f
    }

    /** 네이티브의 누적과 같다: 피크는 최대, RMS 는 가장 큰 버퍼의 것을 그 영교차율과 함께, 개수는 더한다. */
    fun fold(acc: FloatArray, one: FloatArray) {
        if (one[0] > acc[HapticInput.PEAK]) acc[HapticInput.PEAK] = one[0]
        if (one[1] > acc[HapticInput.RMS]) {
            acc[HapticInput.RMS] = one[1]
            acc[HapticInput.TONE] = one[2]
        }
        acc[HapticInput.BUFFERS] = min(65535f, acc[HapticInput.BUFFERS] + 1f)
    }
}

/**
 * [HapticLoop] 를 흉내 낸 시계로 돌려, 어떤 계획이 언제 나가는지 적는다. 폰 없이 소리 따라를 확인하는 데 쓴다.
 * 틱 간격 규칙(조용할 때 100ms, 소리 따라 중 20ms)도 실제와 같다.
 */
object HapticSimulator {

    data class Cue(val atMs: Long, val plan: HapticPlan)

    /**
     * @param aiLagMs 이 시간까지는 라벨이 없다(AI 가 처음 판정하기까지의 지연).
     * @param labelAt 시각별 라벨. 없으면 [aiLagMs] 뒤로 장면의 라벨.
     * @param config 없으면 장면의 종류만 소리 따라로 켠다.
     * @param tailMs 장면이 끝난 뒤 더 돌리는 시간. 세션이 끝나는 것까지 본다.
     */
    fun run(
        scene: HapticScene,
        strength: HapticStrength = HapticStrength.Medium,
        sdk: Int = 36,
        amplitudeControl: Boolean = true,
        delivery: Delivery = Delivery.SMOOTH,
        aiLagMs: Long = 0L,
        labelAt: ((Long) -> String?)? = null,
        config: ((String) -> HapticPolicy.ClassConfig)? = null,
        tailMs: Long = 3000L
    ): List<Cue> {
        var now = 0L
        val input = SyntheticHapticInput(scene, { now }, delivery)
        val loop = HapticLoop(sdk, amplitudeControl)
        val frame = FloatArray(HapticInput.FRAME_SIZE)
        val cfg = config ?: { l: String ->
            HapticPolicy.ClassConfig(
                shown = true,
                haptic = HapticSettings(enabled = l == scene.label, strength = strength, pattern = HapticPattern.Repeat)
            )
        }
        val cues = ArrayList<Cue>()
        input.takeFrame(frame) // 진동 알림이 시작할 때처럼 한 번 비운다.
        val end = scene.durationMs + tailMs
        while (now <= end) {
            input.takeFrame(frame)
            val label = labelAt?.invoke(now) ?: if (now < aiLagMs) null else scene.label
            val plan = loop.onTick(
                now, label, frame[HapticInput.PEAK], frame[HapticInput.RMS], frame[HapticInput.TONE],
                frame[HapticInput.BUFFERS].toInt(), cfg
            )
            if (plan != null) cues.add(Cue(now, plan))
            now += HapticTuning.tickPeriodMs(loop.wantsFastTick(now))
        }
        return cues
    }

    /** ms 마다 느껴지는 세기. 가장 최근에 보낸 계획이 이기고, 계획이 끝나면 0. */
    fun felt(cues: List<Cue>, untilMs: Long): IntArray {
        val out = IntArray(untilMs.toInt().coerceAtLeast(0))
        for ((i, cue) in cues.withIndex()) {
            val stop = if (i + 1 < cues.size) cues[i + 1].atMs else untilMs
            var t = cue.atMs
            while (t < stop && t < untilMs) {
                out[t.toInt()] = cue.plan.amplitudeAt(t - cue.atMs)
                t++
            }
        }
        return out
    }

    /** 느껴지는 세기를 [stepMs] 칸짜리 한 파형으로 잇는다. 개발자 이음매 시험 C 에 쓴다. */
    fun stitch(cues: List<Cue>, untilMs: Long, stepMs: Long = 20L): HapticPlan {
        val f = felt(cues, untilMs)
        val times = ArrayList<Long>()
        val amps = ArrayList<Int>()
        var t = 0
        while (t < f.size) {
            val end = min(f.size, t + stepMs.toInt())
            var m = 0
            for (k in t until end) m = max(m, f[k])
            times.add((end - t).toLong())
            amps.add(m)
            t = end
        }
        if (times.isEmpty()) {
            times.add(1L)
            amps.add(0)
        }
        return HapticPlan(times.toLongArray(), amps.toIntArray(), PlanReason.TEST)
    }
}

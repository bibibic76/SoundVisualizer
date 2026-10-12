package com.example.soundvisualizer.feedback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 외부 사운드 모드에서 AI 가 받는 소리에서 우리 진동을 뺀다(#352, 설계 A "깨끗한 AI 입력"). 안드로이드에 의존하지 않아 JVM
 * 에서 테스트한다. 캡처 스레드에서만 쓰고, 버퍼마다 할당하지 않는다.
 *
 * 마이크는 폰의 진동을 그대로 듣고, AI(YAMNet)는 그 소리를 대개 말소리로 듣는다. 대화음 진동을 켜 두면 진동 소리가 대화음
 * 판정을 낳고 그 판정이 다시 진동을 울리는 고리가 생겼다(S25+, 10/8). 그래서 AI 에 넘기기 직전에, 우리 울림과 그 꼬리
 * 구간([SelfVibrationTimeline], 캡처 시각)에 든 프레임을 바로 앞의 깨끗한 소리로 바꾼다.
 *
 * - **무엇으로 채우나(hold2)**: 구간이 시작될 때 바로 앞의 깨끗한 소리 마지막 0.09~0.2초를 테이프로 얼리고, 앞으로·뒤로·앞으로
 *   번갈아 읽는다. 앞뒤로 읽으면 이음매에서 튀지 않는다. 바로 앞 쉼이 0.09초보다 짧으면 그보다 앞의 깨끗한 소리(최대 0.2초,
 *   5ms 겹쳐 이음)를 이어 붙여 0.09초를 채운다. 구간 양쪽 바깥 5ms 는 원래 소리와 채운 소리를 부드럽게 섞는다.
 * - **들어온 만큼 나간다**: 프레임 수와 시각을 바꾸지 않으므로 AI 의 링버퍼, 쉬는 판단(`AiSilenceGate`), 후처리의 시간 가정이
 *   그대로다. 우리 진동이 없으면 버퍼를 건드리지 않는다.
 * - **큰 소리는 그대로**: 구간에 걸친 버퍼의 피크가 진동 소리 크기의 [HapticTuning.AI_FEED_LOUD_RATIO] 배(0.2 이상)를 넘으면
 *   그 버퍼를 그대로 넘기고, 넘기는 동안은 [HapticTuning.AI_FEED_LOUD_RELEASE_RATIO] 배까지 이어 넘긴다. 진동 소리 크기는
 *   [HapticTuning.AI_FEED_BUZZ_FLOOR_PEAK] 를 바닥으로, 앞뒤 쉼보다 모두 12dB 넘게 큰 구간에서 배운 값이다. 넘긴 소리도 깨끗한
 *   소리로 쳐서, 그 구간의 나머지는 그 큰 소리를 되풀이한다.
 * - **배우기**: 바닥에서 시작하는 지수 평균이고, 그대로 넘긴 버퍼가 든 구간에서는 배우지 않는다. 그 구간의 피크는 진동이
 *   아니라 넘긴 큰 소리다. 첫 값을 그대로 쓰고 넘긴 구간에서도 배우던 때는, 첫 구간의 0.6 폭발음 하나가 넘기는 기준을 2.5 로
 *   올려 그 뒤 몇 구간 동안 다음 폭발음까지 채웠다. 그래서 넘길지를 먼저 정하고 배운다. 이 버퍼에서 배운 값으로 이 버퍼를
 *   넘길지 정하면, 구간 끝 버퍼의 큰 소리가 스스로 기준을 올려 채워진다.
 * - **안전판**: 채우는 구간이 [HapticTuning.AI_FEED_MAX_FILL_MS] 넘게 이어지면 그 뒤는 그대로 넘기고 테이프로도 쓰지 않는다.
 *
 * 대가: 진동하는 동안 느림은 시간의 58.5%, 중간은 63% 를 AI 가 직접 듣지 못한다. 작은 소리는 다음 쉼에서 듣고, 구간 안에서만
 * 났다 끝난 작은 소리는 듣지 못한다(지금도 진동 소리에 섞여 말소리로 들리므로 나빠지지는 않는다). 말소리가 끝난 뒤에도 구간
 * 하나(느림 0.585초)까지는 말소리를 되풀이한다.
 *
 * @param sampleRate 캡처 레이트
 * @param windows 지금의 빼는 구간들([SelfVibrationTimeline.snapshot]). 버퍼마다 한 번 부르고, 잠그지 않아야 한다.
 * @param maxFrames 한 번에 다룰 프레임 수. 더 긴 버퍼는 나눠 다룬다. 할당을 미리 해 두는 크기다.
 * @param diagnostics 구간마다 진단을 모을지(디버그 빌드). [reportReady] 참고.
 */
class AiFeedFilter(
    sampleRate: Int,
    private val windows: () -> LongArray,
    maxFrames: Int = DEFAULT_MAX_FRAMES,
    private val diagnostics: Boolean = false
) {
    private val dtMs = 1000.0 / sampleRate
    private val xfMs = HapticTuning.AI_FEED_XFADE_MS.toDouble()

    /** 5ms·0.09초·0.2초·2ms·1초를 프레임 수로(48kHz: 240, 4320, 9600, 96, 48000). */
    private val xf = frames(sampleRate, HapticTuning.AI_FEED_XFADE_MS)
    private val tapeMin = frames(sampleRate, HapticTuning.AI_FEED_TAPE_MIN_MS)
    private val cap = frames(sampleRate, HapticTuning.AI_FEED_TAPE_MAX_MS)
    private val minSource = frames(sampleRate, HapticTuning.AI_FEED_MIN_SOURCE_MS)
    private val maxFill = frames(sampleRate, HapticTuning.AI_FEED_MAX_FILL_MS)

    private val chunk = maxFrames

    /** 이번 버퍼의 왼쪽 채널(마이크는 좌우가 같다). */
    private val x = FloatArray(chunk)

    /** 프레임마다 원래 소리의 몫. 1 이면 건드리지 않는다. */
    private val w = FloatArray(chunk)

    /** 프레임이 빼는 구간 안인지. 진동 소리 크기를 배우고 진단하는 데 쓴다(그대로 넘긴 버퍼도 그대로 둔다). */
    private val inside = BooleanArray(chunk)

    /** 프레임을 채웠는지. 진단용. */
    private val filled = BooleanArray(chunk)

    /** 구간 바깥 경사 `0.5 − 0.5·cos(π·r)` (r: 0..1). 사이는 선형으로 읽는다. */
    private val ramp = FloatArray(RAMP_STEPS + 1) { k -> (0.5 - 0.5 * cos(PI * k / RAMP_STEPS)).toFloat() }

    /** 앞의 깨끗한 소리와 바로 앞 쉼을 잇는 겹침 `0.5 − 0.5·cos(π·k/XF)`. */
    private val join = FloatArray(xf) { k -> (0.5 - 0.5 * cos(PI * k / xf)).toFloat() }

    /** 지금 이어지는 깨끗한 소리의 마지막 0.2초(고리 버퍼). */
    private val run = FloatArray(cap)
    private var runEnd = 0
    private var runLen = 0

    /** [run] 보다 앞의 깨끗한 소리를 이어 붙인 마지막 0.2초(고리 버퍼). */
    private val recent = FloatArray(cap)
    private var recentEnd = 0
    private var recentLen = 0

    /** 얼린 테이프와 읽는 자리(0 ≤ tapePos < 2·tapeLen, 앞으로 갔다 뒤로 온다). */
    private val tape = FloatArray(cap)
    private var tapeLen = 0
    private var tapePos = 0
    private var frozen = false

    private var inDeaf = false
    private var curDeafPeak = 0f
    private var curGapPeak = 0f
    private var lastGapPeak = 0f

    /** 지금 구간에 그대로 넘긴 버퍼가 들었는지. 그 구간에서 배울지 따질 때 되돌린다. */
    private var deafPassed = false

    /** 배운 진동 소리 크기. 아직 배우지 못했으면 음수. */
    private var buzzRef = -1f
    private var passing = false

    /** 채운(원래 소리의 몫이 1 아래인) 프레임이 버퍼를 넘어 이어진 수. 안전판이 본다. */
    private var fillRun = 0

    /** 그대로 넘긴 버퍼 수. */
    var passedBuffers = 0L
        private set

    /** 안전판으로 그대로 넘긴 프레임 수. */
    var valveFrames = 0L
        private set

    /** 배운 진동 소리 크기. 아직 배우지 못했으면 null. 테스트와 진단용. */
    internal val learnedBuzzPeak: Float?
        get() = if (buzzRef < 0f) null else buzzRef

    /** 지금 구간에 걸친 버퍼를 그대로 넘기기 시작하는 피크. */
    internal fun loudThreshold(): Float = HapticTuning.AI_FEED_LOUD_RATIO * buzzReference()

    /**
     * AI 에 넘길 버퍼를 그 자리에서 고친다. [floatCount] 는 그대로다.
     *
     * @param buf 좌우가 번갈아 든 버퍼(좌우가 같다). 채운 프레임은 좌우에 같은 값을 쓴다.
     * @param bufferStartMs 첫 프레임의 캡처 시각(elapsedRealtime, ms, [com.example.soundvisualizer.CaptureClock])
     * @param endExtraMs 빼는 구간의 끝을 늘릴 시간. 캡처 시각을 어림으로 셀 때만 0 이 아니다.
     */
    fun process(buf: FloatArray, floatCount: Int, bufferStartMs: Double, endExtraMs: Int) {
        val frames = floatCount / 2
        val snap = windows()
        var done = 0
        while (done < frames) {
            val n = minOf(chunk, frames - done)
            processChunk(buf, done, n, bufferStartMs + done * dtMs, endExtraMs, snap)
            done += n
        }
    }

    private fun processChunk(buf: FloatArray, from: Int, n: Int, t0: Double, endExtraMs: Int, snap: LongArray) {
        for (i in 0 until n) x[i] = buf[2 * (from + i)]
        val tLast = t0 + (n - 1) * dtMs
        if (!frozen && fillRun == 0 && !anyNear(snap, t0, tLast, endExtraMs)) {
            // 가까운 구간이 없다. 진동 소리 크기를 배우는 장부와 깨끗한 소리만 적고 버퍼는 그대로 둔다.
            var peak = 0f
            for (i in 0 until n) {
                val a = abs(x[i])
                if (a > peak) peak = a
            }
            learnClean(peak)
            passing = false
            appendRun(0, n)
            if (diagnostics) diagnose(n, t0, endExtraMs, snap, fast = true, passed = false)
            return
        }
        weigh(snap, t0, tLast, n, endExtraMs)
        // 넘길지를 배우기 전에 정한다. 넘긴 버퍼가 든 구간은 배우지 않고, 이 버퍼의 큰 소리가 이 버퍼의 기준을 올리지 않는다.
        val passed = passThrough(n)
        learn(n, passed)
        fill(buf, from, n)
        if (diagnostics) diagnose(n, t0, endExtraMs, snap, fast = false, passed = passed)
    }

    /** [t0]~[tLast] 의 5ms 안에 걸친 구간이 있는지. */
    private fun anyNear(snap: LongArray, t0: Double, tLast: Double, endExtraMs: Int): Boolean {
        var k = 0
        while (k + SelfVibrationTimeline.FIELDS <= snap.size) {
            val s = snap[k + SelfVibrationTimeline.START].toDouble()
            val e = (snap[k + SelfVibrationTimeline.END] + endExtraMs).toDouble()
            k += SelfVibrationTimeline.FIELDS
            if (e + xfMs < t0 || s - xfMs > tLast) continue
            return true
        }
        return false
    }

    /** a) 프레임마다 구간 안인지와 원래 소리의 몫. 구간 안은 0, 바깥 5ms 는 경사, 그 밖은 1. 겹치면 작은 쪽. */
    private fun weigh(snap: LongArray, t0: Double, tLast: Double, n: Int, endExtraMs: Int) {
        for (i in 0 until n) {
            w[i] = 1f
            inside[i] = false
            filled[i] = false
        }
        var k = 0
        while (k + SelfVibrationTimeline.FIELDS <= snap.size) {
            val s = snap[k + SelfVibrationTimeline.START].toDouble()
            val e = (snap[k + SelfVibrationTimeline.END] + endExtraMs).toDouble()
            k += SelfVibrationTimeline.FIELDS
            if (e + xfMs < t0 || s - xfMs > tLast) continue
            val iFrom = floor((s - xfMs - t0) / dtMs).coerceIn(0.0, n.toDouble()).toInt()
            val iTo = (ceil((e + xfMs - t0) / dtMs) + 1).coerceIn(0.0, n.toDouble()).toInt()
            for (i in iFrom until iTo) {
                val t = t0 + i * dtMs
                if (t >= s && t < e) {
                    inside[i] = true
                    w[i] = 0f
                } else {
                    val d = if (t < s) s - t else t - e
                    if (d < xfMs) {
                        val r = rampAt(d / xfMs)
                        if (r < w[i]) w[i] = r
                    }
                }
            }
        }
    }

    private fun rampAt(r: Double): Float {
        val pos = r * RAMP_STEPS
        val i = pos.toInt().coerceIn(0, RAMP_STEPS - 1)
        val frac = (pos - i).toFloat()
        return ramp[i] + (ramp[i + 1] - ramp[i]) * frac
    }

    /**
     * b) 구간 안과 밖의 조각마다 피크를 보고 진동 소리 크기를 배운다. [passed] 면 이 버퍼를 그대로 넘겼으므로, 이 버퍼에 든
     * 구간에서는 배우지 않는다.
     */
    private fun learn(n: Int, passed: Boolean) {
        var i = 0
        while (i < n) {
            val m = inside[i]
            var peak = 0f
            var j = i
            while (j < n && inside[j] == m) {
                val a = abs(x[j])
                if (a > peak) peak = a
                j++
            }
            if (m) learnDeaf(peak, passed) else learnClean(peak)
            i = j
        }
    }

    private fun learnDeaf(peak: Float, passed: Boolean) {
        if (!inDeaf) {
            inDeaf = true
            curDeafPeak = 0f
            lastGapPeak = curGapPeak
        }
        if (peak > curDeafPeak) curDeafPeak = peak
        if (passed) deafPassed = true
    }

    private fun learnClean(peak: Float) {
        if (inDeaf) {
            inDeaf = false
            val gap = maxOf(lastGapPeak, peak)
            // 우리 구간이 앞뒤 쉼보다 모두 12dB 넘게 클 때만 그 피크를 진동 소리로 배운다. 이어지는 소리 속에서는 배우지 않는다.
            // 그대로 넘긴 버퍼가 든 구간도 배우지 않는다. 그 피크는 넘긴 큰 소리라, 배우면 기준이 올라 다음 큰 소리를 채운다.
            if (!deafPassed && curDeafPeak > HapticTuning.AI_FEED_LEARN_RATIO * gap) {
                // 첫 구간도 바닥에서 시작해 [HapticTuning.AI_FEED_LEARN_EMA] 만큼만 다가간다. 구간 하나가 기준을 크게 흔들지 않는다.
                val before = if (buzzRef < 0f) HapticTuning.AI_FEED_BUZZ_FLOOR_PEAK else buzzRef
                buzzRef = (1f - HapticTuning.AI_FEED_LEARN_EMA) * before + HapticTuning.AI_FEED_LEARN_EMA * curDeafPeak
            }
            deafPassed = false
            curGapPeak = 0f
        }
        if (peak > curGapPeak) curGapPeak = peak
    }

    /** 배운 진동 소리 크기와 바닥 가운데 큰 것. 배운 값은 기준을 올리기만 한다. */
    private fun buzzReference(): Float = maxOf(if (buzzRef < 0f) 0f else buzzRef, HapticTuning.AI_FEED_BUZZ_FLOOR_PEAK)

    /** c) 구간에 걸친 버퍼가 진동 소리보다 훨씬 크면 버퍼 전체를 그대로 넘긴다. 넘겼으면 true. */
    private fun passThrough(n: Int): Boolean {
        var any = false
        var peak = 0f
        for (i in 0 until n) {
            if (inside[i]) any = true
            val a = abs(x[i])
            if (a > peak) peak = a
        }
        if (!any) {
            passing = false
            return false
        }
        val ref = buzzReference()
        if (peak > HapticTuning.AI_FEED_LOUD_RATIO * ref ||
            (passing && peak > HapticTuning.AI_FEED_LOUD_RELEASE_RATIO * ref)
        ) {
            passing = true
            passedBuffers++
            for (i in 0 until n) w[i] = 1f
            return true
        }
        passing = false
        return false
    }

    /** d)·e) 원래 소리의 몫이 1 아래인 조각을 테이프로 채우고, 깨끗한 조각은 테이프의 재료로 모은다. */
    private fun fill(buf: FloatArray, from: Int, n: Int) {
        var i = 0
        while (i < n) {
            val filling = w[i] < 1f
            var j = i + 1
            while (j < n && (w[j] < 1f) == filling) j++
            if (filling) {
                if (!frozen) freeze()
                val len = tapeLen
                var ph = tapePos
                for (k in i until j) {
                    var f = 0f
                    if (len > 0) {
                        f = tape[if (ph < len) ph else 2 * len - 1 - ph]
                        ph++
                        if (ph == 2 * len) ph = 0
                    }
                    fillRun++
                    // 안전판: 1초 넘게 이어지면 그대로 넘긴다. 채우지 않은 프레임은 테이프의 재료로도 쓰지 않는다.
                    if (fillRun > maxFill) {
                        valveFrames++
                        continue
                    }
                    val wk = w[k]
                    val y = x[k] * wk + f * (1f - wk)
                    val idx = 2 * (from + k)
                    buf[idx] = y
                    buf[idx + 1] = y
                    filled[k] = true
                }
                tapePos = ph
            } else {
                fillRun = 0
                if (frozen) unfreeze()
                appendRun(i, j)
            }
            i = j
        }
    }

    /**
     * 구간이 시작됐다. 바로 앞 쉼이 0.09초 이상이면 그 마지막 0.2초까지를, 짧으면 앞의 깨끗한 소리를 이어 붙인 마지막 0.09초를
     * 테이프로 얼린다. 깨끗한 소리가 2ms 도 없으면(막 시작했을 때) 조용함으로 채운다.
     */
    private fun freeze() {
        frozen = true
        tapePos = 0
        if (runLen >= tapeMin) {
            for (k in 0 until runLen) tape[k] = runAt(k)
            tapeLen = runLen
            return
        }
        val sourceLen = recentLen + runLen
        if (sourceLen < minSource) {
            tapeLen = 0
            return
        }
        val take = minOf(sourceLen, tapeMin)
        val fromRecent = take - runLen
        var o = 0
        for (k in recentLen - fromRecent until recentLen) tape[o++] = recentAt(k)
        for (k in 0 until runLen) tape[o++] = runAt(k)
        tapeLen = take
    }

    /** 구간이 끝났다. 그 앞의 쉼을 앞의 깨끗한 소리 뒤에 5ms 겹쳐 잇고, 새 쉼을 센다. */
    private fun unfreeze() {
        frozen = false
        if (runLen > 0) {
            if (recentLen > xf && runLen > xf) {
                for (k in 0 until xf) {
                    val r = ringIndex(recentEnd, recentLen - xf + k, recentLen)
                    recent[r] = recent[r] * (1f - join[k]) + runAt(k) * join[k]
                }
                for (k in xf until runLen) pushRecent(runAt(k))
            } else {
                for (k in 0 until runLen) pushRecent(runAt(k))
            }
        }
        runLen = 0
    }

    private fun appendRun(i: Int, j: Int) {
        for (k in i until j) {
            run[runEnd] = x[k]
            runEnd++
            if (runEnd == cap) runEnd = 0
            if (runLen < cap) runLen++
        }
    }

    private fun pushRecent(v: Float) {
        recent[recentEnd] = v
        recentEnd++
        if (recentEnd == cap) recentEnd = 0
        if (recentLen < cap) recentLen++
    }

    private fun runAt(k: Int): Float = run[ringIndex(runEnd, k, runLen)]

    private fun recentAt(k: Int): Float = recent[ringIndex(recentEnd, k, recentLen)]

    /** 다음에 쓸 자리가 [end] 이고 [len] 개가 든 고리 버퍼에서 앞에서 [k] 번째 값의 자리. */
    private fun ringIndex(end: Int, k: Int, len: Int): Int {
        var r = end - len + k
        if (r < 0) r += cap
        if (r >= cap) r -= cap
        return r
    }

    // ---------------- 디버그 진단 (f) ----------------
    //
    // 폰에서 소리를 녹음하지 않고 시각 계산과 꼬리 여유가 맞는지 보려는 것이다(#352 폰 확인 4단계). 구간마다, 캡처 시각이
    // 모터를 멈춘 시각 + 0.3초를 지나거나 다음 구간이 시작되면 한 줄을 남긴다. 값은 원래 소리(채우기 전)로 재므로 쓰는 꼬리
    // 여유와 상관없다.

    /** 지금 보는 구간의 번호. 0 이면 없다. */
    private var trkHandle = 0L

    /** 보기 시작한(또는 건너뛴) 가장 큰 번호. */
    private var startedHandle = 0L
    private var trkStart = 0L
    private var trkEnd = 0L
    private var trkCmdEnd = 0L
    private var trkStopMs = 0.0
    private var trkDeafPeak = 0f
    private var trkGapPeak = 0f
    private var trkGapTaken = false
    private var trkOnsetTaken = false
    private var trkPassed = 0
    private var trkFilled = 0

    /** 구간 밖 프레임의 피크. 구간을 나오면 0 부터 다시 센다. */
    private var gapRun = 0f
    private var prevInside = false

    /** 최근 [HISTORY_MS] 동안 1ms 마다의 피크(원래 소리). 시작 전과 꼬리를 나중에 읽는다. */
    private val history = FloatArray(HISTORY_MS)
    private var historyMs = Long.MIN_VALUE
    private val onsetBins = FloatArray(ONSET_PRE_MS + ONSET_POST_MS)

    /** 마지막으로 끝난 구간의 진단이 있는지. 읽었으면 [consumeReport] 를 부른다. */
    var reportReady = false
        private set

    /** 울린 시간(ms). 모터에 멈추라고 한 시각 − 보낸 시각. */
    var reportDurMs = 0L
        private set

    /** 구간 안의 피크(원래 소리). */
    var reportDeafPeak = 0f
        private set

    /** 구간 바로 앞 쉼의 피크. */
    var reportGapPeak = 0f
        private set

    /** 보낸 시각부터 처음으로 구간 피크의 절반을 넘은 때(ms). 없으면 [NONE]. */
    var reportOnsetMs = NONE
        private set

    /** 멈추라고 한 시각부터 마지막으로 쉼 소리를 넘은 때(ms). 넘은 적이 없으면 0. */
    var reportTailMs = 0
        private set

    /** 그 구간에서 그대로 넘긴 버퍼 수. */
    var reportPassed = 0
        private set

    /** 그 구간에서 채운 시간(ms). */
    var reportFillMs = 0
        private set

    fun consumeReport() {
        reportReady = false
    }

    private fun diagnose(n: Int, t0: Double, endExtraMs: Int, snap: LongArray, fast: Boolean, passed: Boolean) {
        // 보는 구간이 끊겼으면 시각을 고치고, 그다음 구간을 찾는다.
        var nextHandle = 0L
        var nextStart = 0L
        var nextEnd = 0L
        var nextCmdEnd = 0L
        var k = 0
        while (k + SelfVibrationTimeline.FIELDS <= snap.size) {
            val h = snap[k + SelfVibrationTimeline.HANDLE]
            if (h == trkHandle) {
                trkEnd = snap[k + SelfVibrationTimeline.END] + endExtraMs
                trkCmdEnd = snap[k + SelfVibrationTimeline.CMD_END]
            } else if (h > startedHandle && nextHandle == 0L) {
                nextHandle = h
                nextStart = snap[k + SelfVibrationTimeline.START]
                nextEnd = snap[k + SelfVibrationTimeline.END] + endExtraMs
                nextCmdEnd = snap[k + SelfVibrationTimeline.CMD_END]
            }
            k += SelfVibrationTimeline.FIELDS
        }
        if (trkHandle != 0L) {
            trkStopMs = (trkCmdEnd + TAIL_MS).toDouble()
            if (nextHandle != 0L && nextStart < trkStopMs) trkStopMs = nextStart.toDouble()
            if (passed && trkStart <= t0 + (n - 1) * dtMs && t0 < trkEnd) trkPassed++
        }
        for (i in 0 until n) {
            val t = t0 + i * dtMs
            val a = abs(x[i])
            val inWindow = !fast && inside[i]
            remember(t, a)
            if (!inWindow) {
                if (prevInside) gapRun = 0f
                if (a > gapRun) gapRun = a
            }
            prevInside = inWindow
            if (trkHandle != 0L && t >= trkStopMs) finishTracking()
            if (trkHandle == 0L && nextHandle != 0L && t >= nextStart - ONSET_PRE_MS) {
                trkHandle = nextHandle
                startedHandle = nextHandle
                trkStart = nextStart
                trkEnd = nextEnd
                trkCmdEnd = nextCmdEnd
                trkStopMs = (nextCmdEnd + TAIL_MS).toDouble()
                trkDeafPeak = 0f
                trkGapPeak = 0f
                trkGapTaken = false
                trkOnsetTaken = false
                trkPassed = 0
                trkFilled = 0
                nextHandle = 0L
            }
            if (trkHandle == 0L) continue
            if (inWindow && t >= trkStart && t < trkEnd) {
                if (!trkGapTaken) {
                    trkGapPeak = gapRun
                    trkGapTaken = true
                }
                if (a > trkDeafPeak) trkDeafPeak = a
            }
            if (!fast && filled[i]) trkFilled++
            if (!trkOnsetTaken && t >= trkStart + ONSET_POST_MS) {
                for (b in onsetBins.indices) onsetBins[b] = historyAt(trkStart - ONSET_PRE_MS + b)
                trkOnsetTaken = true
            }
        }
    }

    /** 1ms 칸에 피크를 적는다. 지나간 칸은 비운다. */
    private fun remember(t: Double, a: Float) {
        val ms = floor(t).toLong()
        if (ms != historyMs) {
            if (historyMs == Long.MIN_VALUE || ms - historyMs >= HISTORY_MS || ms < historyMs) {
                history.fill(0f)
            } else {
                var m = historyMs + 1
                while (m <= ms) {
                    history[Math.floorMod(m, HISTORY_MS.toLong()).toInt()] = 0f
                    m++
                }
            }
            historyMs = ms
        }
        val slot = Math.floorMod(ms, HISTORY_MS.toLong()).toInt()
        if (a > history[slot]) history[slot] = a
    }

    /** [ms] 칸의 피크. 이미 지워졌거나 아직 오지 않았으면 0. */
    private fun historyAt(ms: Long): Float {
        if (historyMs == Long.MIN_VALUE || ms > historyMs || historyMs - ms >= HISTORY_MS) return 0f
        return history[Math.floorMod(ms, HISTORY_MS.toLong()).toInt()]
    }

    private fun finishTracking() {
        if (trkGapTaken) {
            var onset = NONE
            if (trkOnsetTaken) {
                for (b in onsetBins.indices) {
                    if (onsetBins[b] > trkDeafPeak / 2f) {
                        onset = (b - ONSET_PRE_MS - HapticTuning.AI_FEED_PRE_MS).toInt()
                        break
                    }
                }
            }
            val threshold = maxOf(2f * trkGapPeak, trkDeafPeak / 16f)
            var tail = 0
            var ms = ceil(trkStopMs).toLong() - 1
            while (ms >= trkCmdEnd) {
                if (historyAt(ms) > threshold) {
                    tail = (ms + 1 - trkCmdEnd).toInt()
                    break
                }
                ms--
            }
            reportDurMs = trkCmdEnd - (trkStart + HapticTuning.AI_FEED_PRE_MS)
            reportDeafPeak = trkDeafPeak
            reportGapPeak = trkGapPeak
            reportOnsetMs = onset
            reportTailMs = tail
            reportPassed = trkPassed
            reportFillMs = (trkFilled * dtMs).roundToInt()
            reportReady = true
        }
        trkHandle = 0L
    }

    companion object {
        /** 캡처 스레드가 한 번에 읽는 프레임 수(1024 float, 스테레오). */
        const val DEFAULT_MAX_FRAMES = 512

        /** [reportOnsetMs] 를 잴 수 없었다. */
        const val NONE = Int.MIN_VALUE

        private const val RAMP_STEPS = 256

        /** 진단: 시작 전 20ms 부터 시작 뒤 150ms 까지에서 모터가 돌기 시작한 때를 찾는다. */
        private const val ONSET_PRE_MS = 20
        private const val ONSET_POST_MS = 150

        /** 진단: 멈추라고 한 뒤 0.3초까지에서 꼬리를 찾는다. */
        private const val TAIL_MS = 300L
        private const val HISTORY_MS = 1024

        private fun frames(sampleRate: Int, ms: Int): Int = (ms.toLong() * sampleRate / 1000).toInt()
    }
}

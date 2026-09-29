package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.feedback.FollowAnalyzer.PitchMode
import com.example.soundvisualizer.feedback.HapticTuning.FollowLevels
import com.example.soundvisualizer.feedback.HapticTuning.FollowProfile
import com.example.soundvisualizer.feedback.HapticTuning.PlanLimits
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 소리 따라 한 세션의 진동을 만든다. 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 안드로이드에는 울리는 진동의 세기를 바꾸는 공개 API 가 없다. 새 파형을 보내면 앞 파형을 끊고 모터를 다시 켠다.
 * 그 틈이 "징징 끊기는" 느낌을 만든다. 그래서 이 엔진은
 *  - 앞으로 울릴 모양을 몇 초짜리 **계획** 하나에 미리 적어 보내고(파형 안에서는 끊기지 않는다),
 *  - 새 계획은 첫 칸이 지금 울리는 세기와 [HapticTuning.LEVEL_STEP_DB] 이상 다를 때만 보낸다(이음매 규칙).
 *    친 곳(앞의 눌림), 뚝 떨어지는 곳, 크게 오르는 곳, 멈춰 있던 곳에서만 보내므로 틈이 어차피 느껴지는 변화 속에 숨는다.
 *  - 계획 끝에는 늘 부드럽게 줄어드는 끝을 붙인다. 스레드가 멈춰도 3초 안에 스스로 잦아든다.
 *
 * 무엇을 언제 보내는지는 [update] 의 순서표가 정한다. 한 틱에 계획은 하나까지다.
 */
class FollowEngine(private val limits: PlanLimits, private val amplitudeMode: Boolean) {

    /** 지금 세션 번호. 0 이면 세션이 없다. */
    var sessionId = 0; private set

    private var profile = HapticTuning.profileFor("")
    private var levels = HapticTuning.followLevels(HapticStrength.Medium)
    private var sessionStart = 0L

    private var plan: HapticPlan? = null
    private var planStart = 0L
    private var bodyEndMs = 0L
    private var planIsFastThrob = false
    private var planHasHold = false
    private var planPeriodMs = 0L

    private var lastSendMs = Long.MIN_VALUE / 4
    private var lastAccentMs = Long.MIN_VALUE / 4
    private var lastPitchSendMs = Long.MIN_VALUE / 4
    private var oneShotUntilMs = Long.MIN_VALUE / 4
    private var pendingStart = false
    private var pitchIdx = -1

    // 최근 데이터 틱의 목표 세기. 올리기·내리기는 이 창의 최소·최대로 판단해 떨지 않는다.
    private val tTime = LongArray(8)
    private val tVal = IntArray(8)
    private var tCount = 0
    private var tHead = 0

    fun isPlaying(nowMs: Long): Boolean {
        val p = plan ?: return false
        return nowMs < planStart + p.durationMs
    }

    /** 지금 울리는 세기. 계획이 없거나 끝났으면 0. */
    fun playingAt(nowMs: Long): Int = plan?.amplitudeAt(nowMs - planStart) ?: 0

    private fun bodyLevelAt(nowMs: Long): Int = plan?.bodyLevelAt(nowMs - planStart) ?: 0

    /** 미리보기가 진동기를 잡고 있어 계획을 보내지 못했다. 보낸 셈 치지 않는다. 다음 틱에 멈춘 곳에서 다시 낸다. */
    fun forgetPlan() {
        plan = null
    }

    /** 한 번·두 번·길게가 끼어들었다. 그 모양이 끝날 때까지 아무것도 보내지 않는다. */
    fun abort(nowMs: Long, durationMs: Long) {
        plan = null
        oneShotUntilMs = nowMs + durationMs
    }

    /**
     * 세션을 시작한다. 위협음은 '톡톡', 나머지는 '톡' 으로 연다. 한 번 모양이 울리는 중이면 끝난 뒤로 미룬다.
     */
    fun start(nowMs: Long, id: Int, profile: FollowProfile, levels: FollowLevels, a: FollowAnalyzer): HapticPlan? {
        sessionId = id
        this.profile = profile
        this.levels = levels
        sessionStart = nowMs
        pitchIdx = -1
        tCount = 0
        pendingStart = true
        if (nowMs < oneShotUntilMs) return null
        return startPlan(nowMs, a)
    }

    /** 세션이 끝났다. 무언가 울리고 있으면 부드럽게 끝낸다. */
    fun end(nowMs: Long): HapticPlan? {
        sessionId = 0
        pendingStart = false
        if (nowMs < oneShotUntilMs) return null
        val p = playingAt(nowMs)
        if (p <= 2 * HapticTuning.KEEP_ALIVE || !amplitudeMode) {
            if (!amplitudeMode) plan = null
            return null
        }
        val b = builder(PlanReason.END)
        b.fade(p, limits.endFade, limits.endFadeStepMs)
        return send(nowMs, b.orMinimal(), accent = false, bodyEnd = nowMs)
    }

    /** 한 틱. 보낼 계획이 있으면 돌려준다. */
    fun update(nowMs: Long, a: FollowAnalyzer, levels: FollowLevels): HapticPlan? {
        this.levels = levels
        if (nowMs < oneShotUntilMs) return null
        if (pendingStart) return startPlan(nowMs, a)

        val t = target(nowMs, a)
        if (a.dataThisTick) pushTarget(nowMs, t)
        if (!amplitudeMode) return binaryUpdate(nowMs, a, t)

        val p = playingAt(nowMs)
        val pb = bodyLevelAt(nowMs)
        val playing = p > 2 * HapticTuning.KEEP_ALIVE

        // 1. 소리가 뚝 끊겼다: 모터를 바로 쉬게 한다. 세션은 남는다.
        if (a.silentNow && playing && plan!!.amplitudeAt(nowMs - planStart + 60) > 2 * HapticTuning.KEEP_ALIVE) {
            val b = builder(PlanReason.RELEASE)
            b.fade(p, limits.releaseFade, limits.releaseFadeStepMs)
            return send(nowMs, b.orMinimal(), accent = false, bodyEnd = nowMs)
        }
        if (!a.dataThisTick) return null

        // 2. 새 소리가 시작됐다: 친다.
        if (isOnset(nowMs, a)) return onsetPlan(nowMs, a, playing)

        // 3. 사이렌: 음높이를 싣는다. 이 동안에는 올리기·내리기·알림 톡을 하지 않는다.
        if (profile.pitchCue && a.pitchMode != PitchMode.NONE && t > 0) return pitchUpdate(nowMs, a, pb, playing)

        // 4. 알림 톡: 한결같은 소리 동안 몇 초마다. 계획이 끝나 갈 때 다시 채우는 자리이기도 하다.
        if (t > 0) {
            val steady = a.steadyFor(nowMs) >= HapticTuning.STEADY_MIN_MS
            val due = steady && nowMs >= sessionStart + profile.decayDelayMs &&
                nowMs - lastAccentMs >= reminderPeriod(nowMs, a)
            // 다시 채우기는 이어지는 구간이 있는 계획만. 치고 잦아드는 계획은 이어지는 소리면 아래의 올리기가 받는다.
            val refill = playing && planHasHold && bodyEndMs - nowMs <= HapticTuning.REFILL_MS
            if (due || refill) return reminderPlan(nowMs, a, t, playing)
        }

        // 5. 울리는 계획이 없는데 소리가 이어진다: 멈춘 곳에서 시작하므로 틈이 없다.
        //    '톡톡' 사이의 눌림처럼 계획 안에서 잠깐 약한 곳은 멈춘 것이 아니다.
        if (!isPlaying(nowMs) && t > 0 && nowMs - lastSendMs >= HapticTuning.DOWN_MIN_GAP_MS) {
            return holdPlan(nowMs, a, PlanReason.RESUME, t)
        }
        if (!playing) return null

        // 6·7. 크게 오르거나 내려간 채로 이어진다.
        val tUp = windowMin(nowMs)
        val tDown = windowMax(nowMs)
        if (nowMs - lastSendMs >= HapticTuning.UP_MIN_GAP_MS && tUp >= stepUp(pb)) {
            return holdPlan(nowMs, a, PlanReason.UP, tUp)
        }
        if (nowMs - lastSendMs >= HapticTuning.DOWN_MIN_GAP_MS && tDown in 1..stepDown(pb) &&
            bodyLevelAt(nowMs + HapticTuning.DROP_LOOKAHEAD_MS) > stepUp(tDown)
        ) {
            return holdPlan(nowMs, a, PlanReason.DOWN, tDown)
        }
        return null
    }

    // ---------------- 목표 세기 ----------------

    /** 지금 소리를 진동 세기로. 조용하면 0. */
    internal fun target(nowMs: Long, a: FollowAnalyzer): Int {
        if (a.silentNow) return 0
        val base = baseMapped(nowMs, a)
        if (base <= 0.0) return 0
        if (profile.pitchCue && a.pitchMode != PitchMode.NONE) return pitchLevel(nowMs, a)
        return predicted(base, decayTime(nowMs, a)).roundToInt()
    }

    /** 느린 봉투를 섞은 크기를 바닥~지속 범위에 옮긴 값(한결같은 소리 처리 전). */
    private fun baseMapped(nowMs: Long, a: FollowAnalyzer): Double {
        val sf = a.steadyFor(nowMs)
        val e = if (sf >= HapticTuning.STEADY_MIN_MS && profile.slowBed) {
            val w = ((sf - HapticTuning.STEADY_MIN_MS) / 500.0).coerceIn(0.0, 1.0)
            a.env + (a.slow - a.env) * w
        } else {
            a.env.toDouble()
        }
        return map(e.toDouble(), a.ceil.toDouble())
    }

    private fun map(e: Double, ceil: Double): Double {
        val lo = ceil - HapticTuning.WINDOW_DB
        if (e < lo) return 0.0
        val r = ((e - lo) / HapticTuning.WINDOW_DB).coerceIn(0.0, 1.0)
        return levels.floor * (levels.sustain.toDouble() / levels.floor).pow(r)
    }

    /** 한결같음이 이어진 시간(세션 시작 뒤 [FollowProfile.decayDelayMs] 부터). 한결같지 않으면 -1. */
    private fun decayTime(nowMs: Long, a: FollowAnalyzer): Long {
        if (a.steadyFor(nowMs) < HapticTuning.STEADY_MIN_MS) return -1L
        return nowMs - max(a.steadySince, sessionStart + profile.decayDelayMs)
    }

    private fun steadyGainDb(td: Long): Double =
        if (td < 0) 0.0 else -HapticTuning.STEADY_DROP_DB * (1.0 - exp(-td / HapticTuning.STEADY_TAU_MS.toDouble()))

    /** [base] 가 한결같음 [td] 뒤에 가질 세기. 6dB 까지 잔잔해지고, 10초 뒤에는 2초에 걸쳐 바닥으로. 바닥 아래로는 안 간다. */
    private fun predicted(base: Double, td: Long): Double {
        val floorD = levels.floor.toDouble()
        var lvl = max(floorD, base * 10.0.pow(steadyGainDb(td) / 20.0))
        if (td >= HapticTuning.LONG_STEADY_MS) {
            val x = min(1.0, (td - HapticTuning.LONG_STEADY_MS) / HapticTuning.LONG_STEADY_RAMP_MS.toDouble())
            lvl = floorD * (lvl / floorD).pow(1.0 - x)
        }
        return lvl
    }

    private fun reminderPeriod(nowMs: Long, a: FollowAnalyzer): Long =
        if (decayTime(nowMs, a) >= HapticTuning.LONG_STEADY_MS) HapticTuning.REMINDER_LONG_MS else HapticTuning.REMINDER_MS

    // ---------------- 음높이 ----------------

    private fun pitchTop(nowMs: Long, a: FollowAnalyzer): Double {
        val g = steadyGainDb(decayTime(nowMs, a))
        return min(
            levels.sustain.toDouble(),
            max(levels.sustain * 10.0.pow(g / 20.0), levels.floor * 10.0.pow(HapticTuning.PITCH_TOP_MIN_DB / 20.0))
        )
    }

    private fun pitchLevels(top: Double): IntArray {
        val rangeDb = 20.0 * log10(top / levels.floor)
        val n = 1 + min(2, floor(rangeDb / HapticTuning.PITCH_MIN_SPACING_DB).toInt().coerceAtLeast(0))
        if (n == 1) return intArrayOf(top.roundToInt())
        return IntArray(n) { k -> (levels.floor * (top / levels.floor).pow(k.toDouble() / (n - 1))).roundToInt() }
    }

    private fun pitchLevel(nowMs: Long, a: FollowAnalyzer): Int {
        val top = pitchTop(nowMs, a)
        if (a.pitchMode == PitchMode.FAST) return top.roundToInt()
        val lv = pitchLevels(top)
        return lv[quantize(a.pNorm, lv.size)]
    }

    /** 경계에서 떨지 않도록 여유를 두고 단계를 고른다. */
    private fun quantize(pNorm: Float, n: Int): Int {
        if (n <= 1) return 0
        if (pitchIdx !in 0 until n) pitchIdx = (pNorm * n).toInt().coerceIn(0, n - 1)
        while (pitchIdx < n - 1 && pNorm > (pitchIdx + 1).toFloat() / n + HapticTuning.QUANT_HYST) pitchIdx++
        while (pitchIdx > 0 && pNorm < pitchIdx.toFloat() / n - HapticTuning.QUANT_HYST) pitchIdx--
        return pitchIdx
    }

    private fun throbAt(t: Long, a: FollowAnalyzer, top: Double): Int {
        val period = a.throbPeriodMs.coerceAtLeast(1L)
        val phase = 2.0 * PI * (t - a.lastPitchMaxMs) / period
        val db = -HapticTuning.THROB_DEPTH_DB * (1.0 - cos(phase)) / 2.0
        return max(levels.floor.toDouble(), top * 10.0.pow(db / 20.0)).roundToInt()
    }

    private fun pitchUpdate(nowMs: Long, a: FollowAnalyzer, pb: Int, playing: Boolean): HapticPlan? {
        if (a.pitchMode == PitchMode.FAST) {
            val period = a.throbPeriodMs
            if (period <= 0) return null
            val due = !planIsFastThrob || bodyEndMs - nowMs <= HapticTuning.THROB_REFILL_MS ||
                abs(period - planPeriodMs) > period * 0.15
            if (!due || nowMs - lastSendMs < HapticTuning.PITCH_MIN_SEND_GAP_MS) return null
            // 골(가장 약한 곳)에서 시작해야 다시 켜는 틈이 약한 곳에 떨어진다.
            val sinceMax = Math.floorMod(nowMs - a.lastPitchMaxMs, period)
            val toTrough = Math.floorMod(period / 2 - sinceMax, period)
            val nearTrough = toTrough <= 10 || toTrough >= period - 20
            if (!nearTrough) return null
            return throbPlan(nowMs, a, playing)
        }
        if (nowMs - lastPitchSendMs < HapticTuning.PITCH_MIN_SEND_GAP_MS) return null
        val level = pitchLevel(nowMs, a)
        val stepTick = a.pitchMode == PitchMode.STEP && nowMs - a.lastStepJumpMs <= HapticTuning.STEP_TICK_WINDOW_MS
        return when {
            stepTick && (pb <= 0 || level != pb) -> pitchHoldPlan(nowMs, a, level, tick = true, playing = playing)
            pb <= 0 || abs(20.0 * log10(level.toDouble() / pb)) >= HapticTuning.LEVEL_STEP_DB ->
                pitchHoldPlan(nowMs, a, level, tick = false, playing = playing)
            playing && bodyEndMs - nowMs <= HapticTuning.REFILL_MS ->
                pitchHoldPlan(nowMs, a, level, tick = true, playing = playing)
            else -> null
        }
    }

    private fun pitchHoldPlan(nowMs: Long, a: FollowAnalyzer, level: Int, tick: Boolean, playing: Boolean): HapticPlan {
        val b = builder(PlanReason.PITCH)
        if (tick) {
            if (playing) b.head(HapticTuning.DUCK_MS, HapticTuning.KEEP_ALIVE)
            b.head(HapticTuning.STRIKE_MS, max(level, (HapticTuning.REMINDER_STRIKE * levels.peak).roundToInt()))
        }
        val bodyMs = HapticTuning.PITCH_HORIZON_MS
        b.body(bodyMs, level)
        b.fade(level, limits.endFade, limits.endFadeStepMs)
        lastPitchSendMs = nowMs
        return send(nowMs, b.build(), accent = tick, bodyEnd = nowMs + b.headMs() + bodyMs, hold = true)
    }

    private fun throbPlan(nowMs: Long, a: FollowAnalyzer, playing: Boolean): HapticPlan {
        val b = builder(PlanReason.PITCH)
        if (playing) b.head(HapticTuning.DUCK_MS, HapticTuning.KEEP_ALIVE)
        val period = a.throbPeriodMs
        val top = pitchTop(nowMs, a)
        val step = limits.throbStepMs
        val headMs = b.headMs()
        // 칸 수 안에 들어가는 만큼 온 주기로 채운다. 페이드 칸을 남긴다.
        val budget = limits.maxSteps - (if (playing) 1 else 0) - limits.endFade.size
        val stepsPerPeriod = ceil(period.toDouble() / step).toInt().coerceAtLeast(2)
        val periods = max(1, min(budget / stepsPerPeriod, ceil(HapticTuning.THROB_MIN_PLAN_MS.toDouble() / period).toInt()))
        // 칸은 모두 같은 길이로 둔다. 끝에 짧은 칸이 남으면 옛 안드로이드가 칸마다 늦어지는 몫이 커진다.
        val steps = max(1, ((periods * period + step / 2) / step).toInt())
        val bodyMs = steps * step
        val troughT = nowMs + headMs // 여기서 골
        var last = levels.floor
        for (k in 0 until steps) {
            val t = k * step
            // 골에서 시작: 위상 = 반 주기 + t
            val amp = throbAt(a.lastPitchMaxMs + period / 2 + t + step / 2, a, top)
            b.body(step, amp)
            last = amp
        }
        b.fade(last, limits.endFade, limits.endFadeStepMs)
        planIsFastThrob = true
        planPeriodMs = period
        lastPitchSendMs = nowMs
        val built = b.build()
        return send(nowMs, built, accent = false, bodyEnd = troughT + bodyMs, keepThrob = true, hold = true)
    }

    // ---------------- 계획 만들기 ----------------

    private fun startPlan(nowMs: Long, a: FollowAnalyzer): HapticPlan? {
        pendingStart = false
        if (!amplitudeMode) {
            val b = builder(PlanReason.START)
            b.head(HapticTuning.BINARY_PULSE_MS, 255)
            if (profile.start == HapticTuning.StartSignature.DoubleStrike) {
                b.head(HapticTuning.BINARY_DOUBLE_GAP_MS, 0)
                b.head(HapticTuning.BINARY_PULSE_MS, 255)
            }
            return send(nowMs, b.build(), accent = true, bodyEnd = nowMs)
        }
        val playing = playingAt(nowMs) > 2 * HapticTuning.KEEP_ALIVE
        val b = builder(PlanReason.START)
        if (playing) b.head(HapticTuning.DUCK_MS, HapticTuning.KEEP_ALIVE)
        val s = levels.peak
        b.head(HapticTuning.STRIKE_MS, s)
        if (profile.start == HapticTuning.StartSignature.DoubleStrike) {
            b.head(HapticTuning.DOUBLE_STRIKE_GAP_MS, HapticTuning.KEEP_ALIVE)
            b.head(HapticTuning.STRIKE_MS, s)
        }
        val base = if (a.silentNow) 0 else baseFrom(nowMs, a, max(a.lookbackMinDb, a.env))
        return decayPlan(nowMs, a, b, s, base, accent = true)
    }

    private fun isOnset(nowMs: Long, a: FollowAnalyzer): Boolean {
        if (a.riseDb < profile.onsetRiseDb) return false
        if (a.eIn < a.ceil - HapticTuning.ONSET_MIN_REL_DB) return false
        if (profile.phraseGapMs > 0 && a.quietForMs < profile.phraseGapMs) return false
        val refractory = if (amplitudeMode) HapticTuning.ACCENT_REFRACTORY_MS else HapticTuning.BINARY_REFRACTORY_MS
        return nowMs - lastAccentMs >= refractory
    }

    private fun onsetPlan(nowMs: Long, a: FollowAnalyzer, playing: Boolean): HapticPlan {
        val base = baseFrom(nowMs, a, a.lookbackMinDb)
        val rise = ((a.riseDb - profile.onsetRiseDb) / HapticTuning.RISE_SPAN_DB).coerceIn(0f, 1f)
        val strikeByRise = (levels.peak * (0.8f + 0.2f * rise)).roundToInt()
        val strike = max(strikeByRise, min(levels.peak, (1.4 * max(base, levels.floor)).roundToInt()))
        val b = builder(PlanReason.ONSET)
        if (playing) b.head(HapticTuning.DUCK_MS, HapticTuning.KEEP_ALIVE)
        b.head(HapticTuning.STRIKE_MS, strike)
        return decayPlan(nowMs, a, b, strike, base, accent = true)
    }

    private fun reminderPlan(nowMs: Long, a: FollowAnalyzer, bed: Int, playing: Boolean): HapticPlan {
        val strike = min(levels.peak, max((HapticTuning.REMINDER_STRIKE * levels.peak).roundToInt(), stepUp(bed)))
        val b = builder(PlanReason.REMINDER)
        if (playing) b.head(HapticTuning.DUCK_MS, HapticTuning.KEEP_ALIVE)
        b.head(HapticTuning.STRIKE_MS, strike)
        return decayPlan(nowMs, a, b, strike, bed, accent = true)
    }

    /** 친 뒤 [base] 로 잦아들고, base 가 있으면 이어서 그 세기로 이어지다 부드럽게 끝난다. */
    private fun decayPlan(nowMs: Long, a: FollowAnalyzer, b: HapticPlanBuilder, strike: Int, base: Int, accent: Boolean): HapticPlan {
        val step = limits.stepMs
        var k = 1
        var lastAmp = strike
        var decayMs = 0L
        while (k < 40) {
            val ak = base + (strike - base) * exp(-step * k / HapticTuning.DECAY_TAU_MS.toDouble())
            if (base > 0 && abs(20.0 * log10(ak / base)) < HapticTuning.MERGE_DB) break
            if (base <= 0 && ak < HapticTuning.MIN_TAIL_AMP) break
            lastAmp = ak.roundToInt()
            b.body(step, lastAmp)
            decayMs += step
            k++
        }
        val headMs = b.headMs()
        if (base <= 0) {
            // 치고 스스로 잦아드는 것으로 끝. 이어지는 소리면 곧 올리기가 모터가 도는 중에 들어오도록 조금 늘인다.
            val minTotal = HapticTuning.UP_MIN_GAP_MS + HapticTuning.FOLLOW_TICK_MS
            val have = headMs + decayMs
            if (have < minTotal) {
                b.body(max(minTotal - have, step), max(HapticTuning.KEEP_ALIVE, min(lastAmp, HapticTuning.MIN_TAIL_AMP)))
            }
            return send(nowMs, b.build(), accent = accent, bodyEnd = nowMs + headMs + decayMs)
        }
        val holdStart = nowMs + headMs + decayMs
        val bodyEnd = appendHold(nowMs, holdStart, a, b, base, accentNow = accent)
        return send(nowMs, b.build(), accent = accent, bodyEnd = bodyEnd, hold = true)
    }

    private fun holdPlan(nowMs: Long, a: FollowAnalyzer, reason: PlanReason, level: Int): HapticPlan {
        val b = builder(reason)
        val bodyEnd = appendHold(nowMs, nowMs, a, b, level, accentNow = false)
        return send(nowMs, b.build(), accent = false, bodyEnd = bodyEnd, hold = true)
    }

    /**
     * [from] 부터 [level] 로 이어지는 몸통과 끝 페이드를 붙인다. 한결같은 소리면 앞으로 잔잔해질 세기를 미리 적는다.
     * 다음 알림 톡 뒤까지 이어 두어, 틱이 늦어도 구멍이 나지 않는다. 몸통이 끝나는 시각을 돌려준다.
     */
    private fun appendHold(nowMs: Long, from: Long, a: FollowAnalyzer, b: HapticPlanBuilder, level: Int, accentNow: Boolean): Long {
        val lastAccent = if (accentNow) nowMs else lastAccentMs
        val nextReminder = max(sessionStart + profile.decayDelayMs, lastAccent + reminderPeriod(nowMs, a))
        val horizon = max(nowMs + HapticTuning.MIN_HOLD_MS, nextReminder) + HapticTuning.SAFETY_HOLD_MS
        val holdMs = max(limits.holdStepMs, horizon - from)
        val td0 = decayTime(nowMs, a)
        var t = 0L
        var last = level
        while (t < holdMs) {
            val d = min(limits.holdStepMs, holdMs - t)
            val amp = if (td0 >= 0) {
                // 한결같은 소리: 앞으로의 잔잔해짐을 같은 공식으로 미리 적는다.
                val ratio = predictedRatio(level, td0, td0 + (from - nowMs) + t + d / 2)
                (level * ratio).roundToInt().coerceAtLeast(levels.floor)
            } else {
                level
            }
            b.body(d, amp)
            last = amp
            t += d
        }
        b.fade(last, limits.endFade, limits.endFadeStepMs)
        return from + holdMs
    }

    /** 세기 [level] 인 한결같은 소리가 [td0] 에서 [td1] 로 갈 때의 비율. */
    private fun predictedRatio(level: Int, td0: Long, td1: Long): Double {
        val floorD = levels.floor.toDouble()
        if (level <= levels.floor) return 1.0
        // level = predicted(base, td0) 을 거꾸로 풀지 않고, 같은 공식의 비율로 근사한다.
        val g0 = steadyGainDb(td0)
        val g1 = steadyGainDb(td1)
        var r = 10.0.pow((g1 - g0) / 20.0)
        var lvl = level * r
        if (td1 >= HapticTuning.LONG_STEADY_MS) {
            val x = min(1.0, (td1 - HapticTuning.LONG_STEADY_MS) / HapticTuning.LONG_STEADY_RAMP_MS.toDouble())
            val x0 = if (td0 >= HapticTuning.LONG_STEADY_MS) {
                min(1.0, (td0 - HapticTuning.LONG_STEADY_MS) / HapticTuning.LONG_STEADY_RAMP_MS.toDouble())
            } else {
                0.0
            }
            if (x0 < 1.0) {
                // 바닥 쪽으로 (1-x)/(1-x0) 만큼 당긴다.
                val e = (1.0 - x) / (1.0 - x0)
                lvl = floorD * (max(lvl, floorD) / floorD).pow(e)
            } else {
                lvl = floorD
            }
            r = lvl / level
        }
        return r
    }

    private fun baseFrom(nowMs: Long, a: FollowAnalyzer, db: Float): Int {
        val m = map(db.toDouble(), a.ceil.toDouble())
        if (m <= 0.0) return 0
        return max(levels.floor.toDouble(), predicted(m, decayTime(nowMs, a))).roundToInt()
    }

    private fun builder(reason: PlanReason) = HapticPlanBuilder(reason, limits, amplitudeMode)

    /** 페이드 칸이 모두 너무 약해 버려지면 가장 약한 한 칸으로 앞 계획만 끊는다. */
    private fun HapticPlanBuilder.orMinimal(): HapticPlan =
        try {
            build()
        } catch (e: IllegalArgumentException) {
            HapticPlanBuilder(reason, limits, amplitudeMode).body(limits.releaseFadeStepMs, HapticTuning.KEEP_ALIVE).build()
        }

    private fun send(
        nowMs: Long,
        p: HapticPlan,
        accent: Boolean,
        bodyEnd: Long,
        keepThrob: Boolean = false,
        hold: Boolean = false
    ): HapticPlan {
        plan = p
        planHasHold = hold
        planStart = nowMs
        bodyEndMs = bodyEnd
        if (!keepThrob) planIsFastThrob = false
        lastSendMs = nowMs
        if (accent) lastAccentMs = nowMs
        return p
    }

    // ---------------- 세기 조절이 없는 기기 ----------------

    /** 짧은 박자 펄스만: 새 소리마다, 계단마다, 이어지는 동안 1초마다. 꺼짐 알림 같은 긴 울림은 만들지 않는다. */
    private fun binaryUpdate(nowMs: Long, a: FollowAnalyzer, t: Int): HapticPlan? {
        if (!a.dataThisTick || t <= 0) return null
        val onset = isOnset(nowMs, a)
        val step = profile.pitchCue && a.pitchMode == PitchMode.STEP &&
            nowMs - a.lastStepJumpMs <= HapticTuning.STEP_TICK_WINDOW_MS &&
            nowMs - lastAccentMs >= HapticTuning.BINARY_REFRACTORY_MS
        val reminder = nowMs - lastAccentMs >= HapticTuning.BINARY_REMINDER_MS
        if (!onset && !step && !reminder) return null
        val reason = if (onset) PlanReason.ONSET else if (step) PlanReason.PITCH else PlanReason.REMINDER
        val b = builder(reason).head(HapticTuning.BINARY_PULSE_MS, 255)
        return send(nowMs, b.build(), accent = true, bodyEnd = nowMs)
    }

    // ---------------- 창 ----------------

    private fun pushTarget(nowMs: Long, t: Int) {
        tTime[tHead] = nowMs
        tVal[tHead] = t
        tHead = (tHead + 1) % tTime.size
        if (tCount < tTime.size) tCount++
    }

    private fun windowMin(nowMs: Long): Int {
        var m = Int.MAX_VALUE
        for (i in 0 until tCount) {
            val idx = (tHead - 1 - i + tTime.size) % tTime.size
            if (nowMs - tTime[idx] >= HapticTuning.MEASURE_WINDOW_MS) break
            if (tVal[idx] < m) m = tVal[idx]
        }
        return if (m == Int.MAX_VALUE) 0 else m
    }

    private fun windowMax(nowMs: Long): Int {
        var m = 0
        for (i in 0 until tCount) {
            val idx = (tHead - 1 - i + tTime.size) % tTime.size
            if (nowMs - tTime[idx] >= HapticTuning.MEASURE_WINDOW_MS) break
            if (tVal[idx] > m) m = tVal[idx]
        }
        return m
    }

    companion object {
        /** 4dB 위의 가장 작은 정수 세기. */
        fun stepUp(x: Int): Int = ceil(x * 10.0.pow(HapticTuning.LEVEL_STEP_DB / 20.0)).toInt()

        /** 4dB 아래의 가장 큰 정수 세기. */
        fun stepDown(x: Int): Int = floor(x * 10.0.pow(-HapticTuning.LEVEL_STEP_DB / 20.0)).toInt()
    }
}

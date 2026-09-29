package com.example.soundvisualizer.feedback

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** 계획을 보낸 까닭. 로그와 테스트가 쓴다. */
enum class PlanReason { START, ONSET, PITCH, REMINDER, RESUME, UP, DOWN, RELEASE, END, ONE_SHOT, STOPPED_ALERT, TEST }

/**
 * 한 번에 진동기로 보내는 파형. `[칸 길이 ms]` 와 `[칸 세기 0..255]` 가 짝을 이룬다.
 *
 * 안드로이드는 파형 하나 안에서 0 이 아닌 칸이 이어지면 모터를 한 번 켜 두고 세기만 바꾼다. 그래서 소리 따라는
 * 소리를 이 파형 여러 칸에 미리 적어 보낸다. 새 파형을 보내면 앞 파형을 끊고 모터를 다시 켜므로 자주 보내지 않는다.
 *
 * @param headMs 앞머리(눌림·치기) 길이. [bodyLevelAt] 은 앞머리를 건너뛴 몸통 세기를 돌려준다.
 * @param binary 세기 조절이 없는 기기용 켜고 끄기 계획. 세기는 0 또는 255 뿐이다.
 */
class HapticPlan(
    val timings: LongArray,
    val amplitudes: IntArray,
    val reason: PlanReason,
    val headMs: Long = 0L,
    val binary: Boolean = false
) {
    init {
        require(timings.size == amplitudes.size) { "timings ${timings.size} != amplitudes ${amplitudes.size}" }
        require(timings.isNotEmpty()) { "empty plan" }
    }

    val durationMs: Long = timings.sum()

    /** 시작 뒤 [offsetMs] 에 울리는 세기. 끝난 뒤는 0. */
    fun amplitudeAt(offsetMs: Long): Int {
        if (offsetMs < 0) return 0
        var t = 0L
        for (i in timings.indices) {
            t += timings[i]
            if (offsetMs < t) return amplitudes[i]
        }
        return 0
    }

    /** 앞머리를 건너뛴 세기. 앞머리 안이면 몸통 첫 칸의 세기다. 친 순간을 "지금 울리는 세기" 로 보지 않기 위해서다. */
    fun bodyLevelAt(offsetMs: Long): Int = amplitudeAt(if (offsetMs < headMs) headMs else offsetMs)

    /** 세기 조절이 없는 기기용 `[쉼, 울림, 쉼, 울림 ...]`. 0 이 아닌 칸은 울림으로 본다. */
    fun toOnOffTimings(): LongArray {
        val out = ArrayList<Long>(timings.size + 1)
        var on = false
        var acc = 0L
        for (i in timings.indices) {
            val stepOn = amplitudes[i] > 0
            if (stepOn != on) {
                out.add(acc)
                acc = 0L
                on = stepOn
            }
            acc += timings[i]
        }
        out.add(acc)
        return out.toLongArray()
    }

    /** 로그 한 줄. 예: `plan ONSET t=1234 dur=812 steps=17 head=45 first=3 max=180 last=6` */
    fun summary(atMs: Long): String =
        "plan $reason t=$atMs dur=$durationMs steps=${timings.size} head=$headMs " +
            "first=${amplitudes.first()} max=${amplitudes.maxOrNull()} last=${amplitudes.last()}"

    override fun toString(): String = summary(0)
}

/**
 * 소리 따라 계획을 만든다. 앞머리(눌림·치기), 몸통(잦아듦·이어짐), 끝 페이드 순서로 쌓는다.
 *
 * [build] 가 지키는 것:
 *  1. 세기 모드에서는 0 이 한 칸도 없다. 0 은 모터를 껐다 다시 켜게 해서 이어지는 소리 속에 틈을 만든다.
 *     0 이 아닌 세기는 [HapticTuning.KEEP_ALIVE]..255 로 자른다.
 *  2. 페이드 칸 중 [HapticTuning.KEEP_ALIVE] 보다 약한 것은 버린다. 파형이 끝나면 모터는 알아서 꺼진다.
 *  3. 몸통의 이웃 칸이 [HapticTuning.MERGE_DB] 안에서 같으면 합친다. 앞머리는 합치지 않는다.
 *  4. 칸 수가 한도를 넘으면 몸통 끝부터 줄인다. 앞머리와 페이드는 남긴다.
 */
class HapticPlanBuilder(
    val reason: PlanReason,
    private val limits: HapticTuning.PlanLimits,
    private val amplitudeMode: Boolean
) {
    private enum class Kind { HEAD, BODY, FADE }

    private val times = ArrayList<Long>()
    private val amps = ArrayList<Int>()
    private val kinds = ArrayList<Kind>()

    fun head(ms: Long, amp: Int): HapticPlanBuilder = add(Kind.HEAD, ms, amp)

    fun body(ms: Long, amp: Int): HapticPlanBuilder = add(Kind.BODY, ms, amp)

    /** [fromAmp] 에 [ratios] 를 곱한 칸들을 [stepMs] 씩 붙인다. */
    fun fade(fromAmp: Int, ratios: FloatArray, stepMs: Long): HapticPlanBuilder {
        for (r in ratios) add(Kind.FADE, stepMs, (fromAmp * r).roundToInt())
        return this
    }

    val isEmpty: Boolean get() = times.isEmpty()

    /** 지금까지 쌓은 앞머리 길이. */
    fun headMs(): Long = times.indices.filter { kinds[it] == Kind.HEAD }.sumOf { times[it] }

    private fun add(kind: Kind, ms: Long, amp: Int): HapticPlanBuilder {
        if (ms <= 0) return this
        times.add(ms)
        amps.add(amp)
        kinds.add(kind)
        return this
    }

    fun build(): HapticPlan {
        val t = ArrayList<Long>(times.size)
        val a = ArrayList<Int>(times.size)
        val k = ArrayList<Kind>(times.size)
        for (i in times.indices) {
            var amp = amps[i]
            if (amplitudeMode) {
                if (kinds[i] == Kind.FADE && amp < HapticTuning.KEEP_ALIVE) continue
                amp = amp.coerceIn(HapticTuning.KEEP_ALIVE, 255)
            } else {
                amp = if (amp > 0) 255 else 0
            }
            // 몸통끼리, 페이드끼리 거의 같은 세기면 합친다.
            val last = a.size - 1
            if (last >= 0 && kinds[i] != Kind.HEAD && k[last] == kinds[i] && sameLevel(a[last], amp)) {
                t[last] = t[last] + times[i]
                continue
            }
            t.add(times[i])
            a.add(amp)
            k.add(kinds[i])
        }
        // 한도를 넘으면 몸통 끝부터 줄인다.
        while (t.size > limits.maxSteps) {
            val idx = k.indexOfLast { it == Kind.BODY }
            if (idx < 0) break
            t.removeAt(idx)
            a.removeAt(idx)
            k.removeAt(idx)
        }
        require(t.isNotEmpty()) { "empty plan ($reason)" }
        require(t.size <= limits.maxSteps) { "plan ${t.size} steps > ${limits.maxSteps} ($reason)" }
        require(t.all { it >= 1 }) { "step shorter than 1 ms ($reason)" }
        if (amplitudeMode) require(a.all { it > 0 }) { "zero step in amplitude plan ($reason)" }
        val headMs = t.indices.filter { k[it] == Kind.HEAD }.sumOf { t[it] }
        return HapticPlan(t.toLongArray(), a.toIntArray(), reason, headMs, binary = !amplitudeMode)
    }

    private fun sameLevel(x: Int, y: Int): Boolean {
        if (!amplitudeMode) return x == y
        if (x <= 0 || y <= 0) return x == y
        return abs(20.0 * log10(x.toDouble() / y)) < HapticTuning.MERGE_DB
    }
}

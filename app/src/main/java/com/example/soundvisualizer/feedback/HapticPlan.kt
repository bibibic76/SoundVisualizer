package com.example.soundvisualizer.feedback

/**
 * 한 번에 진동기로 보내는 파형. `[칸 길이 ms]` 와 `[칸 세기 0..255]` 가 짝을 이룬다. 세기 0 인 칸은 쉰다.
 *
 * @param binary 세기 조절이 없는 기기용 켜고 끄기 계획. 세기는 0 또는 255 뿐이다.
 */
class HapticPlan(
    val timings: LongArray,
    val amplitudes: IntArray,
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

    /**
     * 끝에 세기 0 칸(쉼)을 붙여 길이를 [minMs] 이상으로 늘린 계획. 이미 그만큼 길면 이 계획을 그대로 돌려준다.
     * 울리는 부분은 바뀌지 않는다. Android 10 이 짧은 진동을 터치 진동으로 보지 않게 할 때 쓴다([HapticPlayer]).
     */
    fun withSilentTail(minMs: Long): HapticPlan {
        if (durationMs >= minMs) return this
        return HapticPlan(timings + (minMs - durationMs), amplitudes + 0, binary)
    }

    /** 로그 한 줄. 예: `plan t=1234 dur=200 steps=1 max=255` */
    fun summary(atMs: Long): String =
        "plan t=$atMs dur=$durationMs steps=${timings.size} max=${amplitudes.maxOrNull()}"

    override fun toString(): String = summary(0)
}

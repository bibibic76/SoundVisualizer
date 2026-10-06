package com.example.soundvisualizer.feedback

/**
 * 진동의 모양. 모든 진동은 한 세기로 켰다 끄는 네모다. 세게 치거나 잦아드는 모양은 없다(#242).
 *
 * 꺼짐 알림(길게 세 번, 255)은 한 칸도 바꾸지 않는다. 사용자가 고르는 방식은 모두 한 박자에 한 번 울리므로,
 * 0.3초 울림이 0.15초 쉼을 두고 세 번 오는 이 모양과 섞이지 않는다.
 */
object HapticShapes {

    /** 꺼짐 알림: 길게 세 번, 가장 센 세기. 예전과 한 칸도 다르지 않다. */
    val STOPPED_ALERT = HapticPlan(
        timings = longArrayOf(0, 300, 150, 300, 150, 300),
        amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
    )

    /** [durationMs] 동안 [level] 세기로 한결같이 울린다. 느림·중간·빠름의 한 번 울림과 연속의 한 토막. */
    fun steady(level: Int, durationMs: Long, amplitudeControl: Boolean): HapticPlan =
        if (amplitudeControl) {
            HapticPlan(longArrayOf(durationMs), intArrayOf(HapticTuning.amplitude(level)))
        } else {
            HapticPlan(longArrayOf(durationMs), intArrayOf(255), binary = true)
        }

    /**
     * 설정의 미리보기. [HapticTuning.PREVIEW_MS] 동안 실제와 같은 박자로 울린다. 꺼짐이면 null.
     * 실제 진동은 울림마다 따로 보내지만, 미리보기는 파형 하나에 박자를 적어 보낸다. 쉬는 칸은 모터가 멈춰 있으므로
     * 느낌은 같다.
     */
    fun preview(settings: HapticSettings, amplitudeControl: Boolean): HapticPlan? {
        val mode = settings.mode
        if (mode == HapticMode.Off) return null
        if (mode == HapticMode.Continuous) return steady(settings.level, HapticTuning.PREVIEW_MS, amplitudeControl)
        val period = HapticTuning.periodMs(mode)
        val on = HapticTuning.onMs(mode)
        val amp = if (amplitudeControl) HapticTuning.amplitude(settings.level) else 255
        val beats = (HapticTuning.PREVIEW_MS / period).toInt().coerceAtLeast(1)
        val timings = ArrayList<Long>()
        val amps = ArrayList<Int>()
        repeat(beats) { i ->
            timings.add(on)
            amps.add(amp)
            // 마지막 쉼은 적지 않는다. 쉬는 칸으로 끝나면 미리보기가 그만큼 진동기를 붙잡는다.
            if (i < beats - 1) {
                timings.add(period - on)
                amps.add(0)
            }
        }
        return HapticPlan(timings.toLongArray(), amps.toIntArray(), binary = !amplitudeControl)
    }
}

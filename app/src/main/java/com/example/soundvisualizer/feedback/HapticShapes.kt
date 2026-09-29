package com.example.soundvisualizer.feedback

import kotlin.math.roundToInt

/**
 * 한 번·두 번·길게와 꺼짐 알림의 모양.
 *
 * 예전에는 모두 한 세기로 켰다 끄는 네모였다. 60ms 네모는 '톡' 이 아니라 짧은 '징' 으로 느껴지고(25~30ms 가 경계),
 * 갑자기 끊기면 진동자가 50ms 넘게 떨린다. 그래서 짧게 세게 치고(몸통의 1.6배, 20ms) 몸통을 지나 잦아들게 한다.
 * 세게 친 첫 칸이 진동자를 빨리 세우고, 잦아드는 꼬리가 떨림을 없앤다.
 *
 * 몸통 세기는 예전 세기(70/150/255)를 그대로 쓴다. 저장된 세기의 뜻이 바뀌지 않는다.
 *
 * 꺼짐 알림(길게 세 번, 255)은 한 칸도 바꾸지 않는다. 사용자 패턴과 소리 따라에는 200 이상의 세기가 200ms 넘게
 * 이어지는 구간이 길게에 한 번 있을 뿐이고, 꺼짐 알림에는 세 번 있다. 그래서 섞이지 않는다.
 */
object HapticShapes {

    private const val STEP = 20L

    /** 두 번의 두 톡 사이 쉼. 진동자의 떨림(20~50ms)보다 충분히 길다. 안드로이드 기본 두 번 톡(30/100/30)과 같다. */
    private const val DOUBLE_GAP_MS = 100L

    private val ONCE_DECAY = floatArrayOf(0.55f, 0.30f)
    private val LONG_DECAY = floatArrayOf(0.7f, 0.5f, 0.35f, 0.25f, 0.15f)
    private const val LONG_BODY_MS = 260L

    /** 세기 조절이 없는 기기의 예전 모양. 느껴 볼 수 없는 경로라 바꾸지 않는다. */
    private val LEGACY_TAP = longArrayOf(0, 60)
    private val LEGACY_DOUBLE_TAP = longArrayOf(0, 60, 80, 60)
    private val LEGACY_HOLD = longArrayOf(0, 350)

    /** 꺼짐 알림: 길게 세 번, 가장 센 세기. 예전과 한 칸도 다르지 않다. */
    val STOPPED_ALERT = HapticPlan(
        timings = longArrayOf(0, 300, 150, 300, 150, 300),
        amplitudes = intArrayOf(0, 255, 0, 255, 0, 255),
        reason = PlanReason.STOPPED_ALERT
    )

    /**
     * [pattern] 의 모양. [HapticPattern.Repeat](소리 따라)는 [FollowEngine] 이 소리를 보며 만들므로 여기서는 한 번 모양을
     * 돌려준다. 진동 알림은 소리 따라를 여기로 묻지 않는다. 혹시 불려도 아무것도 안 울리는 것보다 낫다.
     */
    fun oneShot(pattern: HapticPattern, strength: HapticStrength, amplitudeControl: Boolean): HapticPlan {
        if (!amplitudeControl) return legacy(pattern)
        val a = HapticTuning.oneShotBody(strength)
        val s = HapticTuning.oneShotStrike(strength)
        return when (pattern) {
            HapticPattern.Tap, HapticPattern.Repeat -> plan(once(a, s))
            HapticPattern.DoubleTap -> plan(once(a, s) + listOf(DOUBLE_GAP_MS to 0) + once(a, s))
            HapticPattern.Hold -> {
                val steps = ArrayList<Pair<Long, Int>>()
                steps.add(STEP to s)
                steps.add(LONG_BODY_MS to a)
                for (r in LONG_DECAY) steps.add(STEP to amp(a * r))
                plan(steps)
            }
        }
    }

    private fun once(a: Int, s: Int): List<Pair<Long, Int>> =
        listOf(STEP to s, STEP to a) + ONCE_DECAY.map { STEP to amp(a * it) }

    private fun amp(v: Float): Int = v.roundToInt().coerceAtLeast(HapticTuning.KEEP_ALIVE)

    private fun plan(steps: List<Pair<Long, Int>>) = HapticPlan(
        timings = steps.map { it.first }.toLongArray(),
        amplitudes = steps.map { it.second }.toIntArray(),
        reason = PlanReason.ONE_SHOT
    )

    private fun legacy(pattern: HapticPattern): HapticPlan {
        val timings = when (pattern) {
            HapticPattern.Tap, HapticPattern.Repeat -> LEGACY_TAP
            HapticPattern.DoubleTap -> LEGACY_DOUBLE_TAP
            HapticPattern.Hold -> LEGACY_HOLD
        }
        // 짝수 칸은 쉼, 홀수 칸은 울림
        val amps = IntArray(timings.size) { i -> if (i % 2 == 1) 255 else 0 }
        return HapticPlan(timings, amps, PlanReason.ONE_SHOT, binary = true)
    }
}

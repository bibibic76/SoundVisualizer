package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification

/**
 * 진동을 울릴지 판단한다. 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 라벨만 보고 판단하면 안 된다. AI 후처리는 확신도가 기준에 못 미치면 라벨을 바꾸지 않고
 * 그대로 두기 때문에, 조용해져도 마지막 라벨이 남는다. 라벨만 보면
 *  - 소리 따라는 무음 속에서 끝없이 울리고
 *  - 한 번 패턴은 "총소리 → 조용 → 총소리" 에서 라벨이 바뀐 적이 없어 두 번째를 놓친다.
 * 그래서 "실제로 소리가 나는 중" 과 "라벨" 이 함께 켜진 구간을 하나의 사건으로 본다.
 *
 * 한 번·두 번·길게는 사건이 시작될 때 [Decision] 하나를 낸다. 소리 따라([HapticPattern.Repeat])는 [Decision] 을
 * 내지 않고 [follow] 세션을 연다. 세션 동안 무엇을 울릴지는 [FollowEngine] 이 소리를 보며 정한다.
 *
 * 한 스레드에서만 호출한다.
 */
class HapticPolicy(
    private val levelThreshold: Float = LEVEL_THRESHOLD,
    private val releaseMs: Long = RELEASE_MS,
    private val cooldownMs: Long = COOLDOWN_MS,
    private val followReleaseMs: Long = HapticTuning.FOLLOW_RELEASE_MS,
    private val labelGraceMs: Long = HapticTuning.LABEL_GRACE_MS
) {
    companion object {
        /** 이보다 큰 피크를 "소리가 난다" 로 본다. 오버레이 엔진이 idle 에서 깨어나는 기준과 같다. */
        const val LEVEL_THRESHOLD = 0.01f

        /** 소리가 이만큼 끊겨야 사건이 끝난다. 말소리의 단어 사이 틈을 한 사건으로 묶는다. */
        const val RELEASE_MS = 400L

        /** 같은 종류가 다시 시작돼도 이 시간 안에는 울리지 않는다. 연발 총소리의 연타를 막는다. 소리 따라는 쓰지 않는다. */
        const val COOLDOWN_MS = 2000L
    }

    /** 한 종류에 대해 판단에 필요한 설정. */
    data class ClassConfig(val shown: Boolean, val haptic: HapticSettings)

    /** 이번 틱에 울릴 한 번·두 번·길게. */
    data class Decision(val pattern: HapticPattern, val strength: HapticStrength)

    /** 소리 따라 세션. [id] 가 바뀌면 새 세션이다. */
    data class Follow(val id: Int, val label: String, val strength: HapticStrength, val sinceMs: Long)

    /** 지금 이어지는 소리 따라 세션. 없으면 null. */
    var follow: Follow? = null
        private set

    private var lastLoudMs = Long.MIN_VALUE
    private var activeLabel: String? = null
    private val lastFireMs = HashMap<String, Long>()
    private var nextId = 0
    private var mismatchMs = 0L
    private var lastTickMs = Long.MIN_VALUE

    /** 쿨다운에 막혀 아직 울리지 못한 사건. 쿨다운이 끝났을 때 소리가 이어지고 있으면 그때 울린다(#174). */
    private var waitingForCooldown = false

    /**
     * @param nowMs 단조 증가하는 시각 (elapsedRealtime)
     * @param label 가장 최근 분류 라벨. 분류 결과가 아직 없으면 null
     * @param level 지난 틱 이후 구간 전체의 최대 진폭 (0..1). 가장 최근 버퍼만 보면 짧은 소리를 놓친다(#174).
     * @param config 라벨별 표시·진동 설정
     * @return 지금 울려야 할 한 번·두 번·길게. 소리 따라는 [follow] 로 알린다.
     */
    fun onTick(
        nowMs: Long,
        label: String?,
        level: Float,
        config: (String) -> ClassConfig
    ): Decision? {
        if (level > levelThreshold) lastLoudMs = nowMs
        val heard = lastLoudMs != Long.MIN_VALUE
        val soundShort = heard && nowMs - lastLoudMs <= releaseMs
        // 소리 따라는 쉼이 길다. T3 화재경보의 1.5초 쉼과 말 사이의 쉼을 한 세션으로 묶는다.
        val soundFollow = heard && nowMs - lastLoudMs <= followReleaseMs
        val dt = if (lastTickMs == Long.MIN_VALUE) 0L else nowMs - lastTickMs
        lastTickMs = nowMs

        val f = follow
        if (f != null) {
            val own = config(f.label)
            if (!own.shown || !own.haptic.enabled) {
                // 진동이나 표시를 끄면 같은 틱에 끝난다.
                endFollow()
                activeLabel = null
                return null
            }
            if (own.haptic.pattern != HapticPattern.Repeat) {
                // 사건 중간에 한 번 패턴으로 바꿨다. 이어지는 이 소리에 다시 알리지는 않는다(예전 activeLabel 규칙과 같다).
                endFollow()
                activeLabel = f.label
                return null
            }
            if (!soundFollow) {
                endFollow()
                activeLabel = null
                return null
            }
            if (own.haptic.strength != f.strength) follow = f.copy(strength = own.haptic.strength)
            if (label == null || label == f.label) {
                mismatchMs = 0L
                return null
            }
            val dangerPreempts = label == AiClassification.DANGER &&
                config(AiClassification.DANGER).let { it.shown && it.haptic.enabled }
            if (!dangerPreempts) {
                // AI 결과가 한두 번 흔들린 것은 넘긴다. 소리가 나는 동안만 센다. T3 의 쉼에서 바뀐 라벨은 세지 않는다.
                if (soundShort) mismatchMs += dt
                if (mismatchMs < labelGraceMs) return null
            }
            // 다른 종류가 충분히 이어졌거나 위협음이 끼어들었다. 세션을 넘기고 아래에서 새 라벨을 바로 다룬다.
            endFollow()
            activeLabel = null
        }

        val cfg = if (soundShort && label != null) config(label) else null
        if (label == null || cfg == null || !cfg.shown || !cfg.haptic.enabled) {
            activeLabel = null
            waitingForCooldown = false
            return null
        }

        if (cfg.haptic.pattern == HapticPattern.Repeat) {
            // 소리 따라는 쿨다운을 읽지도 쓰지도 않는다. 소리를 따라가는 것이라 연타를 막을 일이 없다.
            follow = Follow(++nextId, label, cfg.haptic.strength, nowMs)
            activeLabel = label
            waitingForCooldown = false
            mismatchMs = 0L
            return null
        }

        val lastFire = lastFireMs[label]
        val cooledDown = lastFire == null || nowMs - lastFire >= cooldownMs
        val fire = if (label != activeLabel) {
            // 새 사건: 소리가 새로 났거나 다른 종류로 바뀌었다.
            activeLabel = label
            // 쿨다운에 막혔으면 사건을 들고 있는다. 막힌 채로 잊으면 그 사건은 영영 울리지 않는다.
            // 총소리 한 발 뒤 1.5초 만에 시작된 경보음이 계속 울리는 동안에도 진동이 없었다(#174).
            waitingForCooldown = !cooledDown
            cooledDown
        } else if (waitingForCooldown) {
            // 들고 있던 사건: 쿨다운이 끝났고 소리가 아직 나는 중이면 한 번 울린다.
            if (cooledDown) waitingForCooldown = false
            cooledDown
        } else {
            false
        }
        if (!fire) return null

        lastFireMs[label] = nowMs
        return Decision(cfg.haptic.pattern, cfg.haptic.strength)
    }

    private fun endFollow() {
        follow = null
        waitingForCooldown = false
        mismatchMs = 0L
    }

    /** 사건 상태, 쿨다운, 세션을 모두 지운다. */
    fun reset() {
        lastLoudMs = Long.MIN_VALUE
        activeLabel = null
        waitingForCooldown = false
        lastFireMs.clear()
        follow = null
        mismatchMs = 0L
        lastTickMs = Long.MIN_VALUE
    }
}

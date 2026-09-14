package com.example.soundvisualizer.feedback

/**
 * 진동을 울릴지 판단한다. 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 라벨만 보고 판단하면 안 된다. AI 후처리는 확신도가 기준에 못 미치면 라벨을 바꾸지 않고
 * 그대로 두기 때문에, 조용해져도 마지막 라벨이 남는다. 라벨만 보면
 *  - 반복 패턴은 무음 속에서 끝없이 울리고
 *  - 한 번 패턴은 "총소리 → 조용 → 총소리" 에서 라벨이 바뀐 적이 없어 두 번째를 놓친다.
 * 그래서 "실제로 소리가 나는 중" 과 "라벨" 이 함께 켜진 구간을 하나의 사건으로 본다.
 *
 * 한 스레드에서만 호출한다.
 */
class HapticPolicy(
    private val levelThreshold: Float = LEVEL_THRESHOLD,
    private val releaseMs: Long = RELEASE_MS,
    private val cooldownMs: Long = COOLDOWN_MS,
    private val repeatIntervalMs: Long = REPEAT_INTERVAL_MS
) {
    companion object {
        /** 이보다 큰 피크를 "소리가 난다" 로 본다. 오버레이 엔진이 idle 에서 깨어나는 기준과 같다. */
        const val LEVEL_THRESHOLD = 0.01f

        /** 소리가 이만큼 끊겨야 사건이 끝난다. 말소리의 단어 사이 틈을 한 사건으로 묶는다. */
        const val RELEASE_MS = 400L

        /** 같은 종류가 다시 시작돼도 이 시간 안에는 울리지 않는다. 연발 총소리의 연타를 막는다. */
        const val COOLDOWN_MS = 2000L

        /** [HapticPattern.Repeat] 가 다시 울리는 간격. */
        const val REPEAT_INTERVAL_MS = 1500L
    }

    /** 한 종류에 대해 판단에 필요한 설정. */
    data class ClassConfig(val shown: Boolean, val haptic: HapticSettings)

    /** 이번 틱에 울릴 진동. */
    data class Decision(val pattern: HapticPattern, val strength: HapticStrength)

    private var lastLoudMs = Long.MIN_VALUE
    private var activeLabel: String? = null
    private val lastFireMs = HashMap<String, Long>()

    /**
     * @param nowMs 단조 증가하는 시각 (elapsedRealtime)
     * @param label 가장 최근 분류 라벨. 분류 결과가 아직 없으면 null
     * @param level 가장 최근 오디오 버퍼의 최대 진폭 (0..1)
     * @param config 라벨별 표시·진동 설정
     * @return 지금 울려야 하면 그 진동, 아니면 null
     */
    fun onTick(
        nowMs: Long,
        label: String?,
        level: Float,
        config: (String) -> ClassConfig
    ): Decision? {
        if (level > levelThreshold) lastLoudMs = nowMs
        val soundPresent = lastLoudMs != Long.MIN_VALUE && nowMs - lastLoudMs <= releaseMs

        val cfg = if (soundPresent && label != null) config(label) else null
        if (label == null || cfg == null || !cfg.shown || !cfg.haptic.enabled) {
            activeLabel = null
            return null
        }

        val lastFire = lastFireMs[label]
        val fire = if (label != activeLabel) {
            // 새 사건: 소리가 새로 났거나 다른 종류로 바뀌었다.
            activeLabel = label
            lastFire == null || nowMs - lastFire >= cooldownMs
        } else {
            cfg.haptic.pattern == HapticPattern.Repeat &&
                lastFire != null && nowMs - lastFire >= repeatIntervalMs
        }
        if (!fire) return null

        lastFireMs[label] = nowMs
        return Decision(cfg.haptic.pattern, cfg.haptic.strength)
    }

    /** 사건 상태와 쿨다운을 모두 지운다. */
    fun reset() {
        lastLoudMs = Long.MIN_VALUE
        activeLabel = null
        lastFireMs.clear()
    }
}

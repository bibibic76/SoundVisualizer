package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification

/**
 * 지금 어느 진동을 울려야 하는지 판단한다. 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 그 종류의 소리가 **이어지는 동안** 그 종류의 방식과 세기로 울리고, 소리가 끝나면 멈춘다(#242).
 *
 * 라벨만 보고 판단하면 안 된다. AI 후처리는 확신도가 기준에 못 미치면 라벨을 바꾸지 않고
 * 그대로 두기 때문에, 조용해져도 마지막 라벨이 남는다. 라벨만 보면 무음 속에서 끝없이 울린다.
 * 그래서 "실제로 소리가 나는 중" 과 "라벨" 이 함께 켜진 동안만 울린다. 말 사이처럼 [releaseMs] 안의 쉼은 이어진 것으로 본다.
 *
 * 울리는 중에 라벨이 다른 종류로 바뀌면 [labelGraceMs] 동안 이어져야 넘어간다. AI 결과가 한두 번 흔들렸다고 박자가
 * 바뀌거나 끊기지 않게 하기 위해서다. 위협음은 기다리지 않는다.
 *
 * 이번 실행에서 AI 를 쓸 수 없으면(모델 로딩 실패 등) 라벨이 끝내 오지 않는다. 그때는 종류를 가리지 않고
 * **큰 소리**가 이어지는 동안 위협음 설정으로 울린다([onTick] 의 `unlabeledAlerts`, #225). 화면을 볼 수 없을 때 진동이
 * 유일한 알림이라, AI 가 실패했다고 알림까지 사라지면 안 되기 때문이다.
 *
 * 한 스레드에서만 호출한다.
 */
class HapticPolicy(
    private val levelThreshold: Float = LEVEL_THRESHOLD,
    private val releaseMs: Long = RELEASE_MS,
    private val labelGraceMs: Long = HapticTuning.LABEL_GRACE_MS,
    private val unlabeledLoudLevel: Float = HapticTuning.UNLABELED_LOUD_LEVEL
) {
    companion object {
        /** 이보다 큰 피크를 "소리가 난다" 로 본다. 오버레이 엔진이 idle 에서 깨어나는 기준과 같다. */
        const val LEVEL_THRESHOLD = 0.01f

        /** 소리가 이만큼 끊겨야 멈춘다. 말소리의 단어 사이 틈을 한 소리로 묶는다. */
        const val RELEASE_MS = 400L
    }

    /** 한 종류에 대해 판단에 필요한 설정. */
    data class ClassConfig(val shown: Boolean, val haptic: HapticSettings) {
        val vibrates: Boolean get() = shown && haptic.enabled
    }

    /** 지금 울려야 할 진동. [mode] 는 꺼짐이 아니다. */
    data class Vibe(val mode: HapticMode, val level: Int)

    private var lastLoudMs = Long.MIN_VALUE
    private var lastTickMs = Long.MIN_VALUE

    /** 지금 울리고 있는 종류. 울리지 않으면 null. */
    private var active: String? = null

    /** 울리는 중에 다른 종류로 판정된 소리가 이어진 시간. */
    private var mismatchMs = 0L

    /** 종류를 모르는 큰 소리가 이어지는 중인지. 큰 소리가 끝나거나 소리가 끊기면 끝난다. */
    private var unlabeledEvent = false

    /** 피크가 큰 소리 기준의 [HapticTuning.UNLABELED_RELEASE_RATIO] 이상이던 마지막 시각. 아직 없으면 [Long.MIN_VALUE]. */
    private var lastUnlabeledHotMs = Long.MIN_VALUE

    /**
     * @param nowMs 단조 증가하는 시각 (elapsedRealtime)
     * @param label 가장 최근 분류 라벨. 분류 결과가 아직 없으면 null
     * @param level 지난 틱 이후 구간 전체의 최대 진폭 (0..1). 가장 최근 버퍼만 보면 짧은 소리를 놓친다(#174).
     * @param config 라벨별 표시·진동 설정
     * @param unlabeledAlerts 라벨이 끝내 오지 않는 실행(AI 를 쓸 수 없음)인지. 참이면 라벨 없이도 큰 소리에 울린다.
     *   로딩 중처럼 라벨이 곧 올 때는 거짓으로 둔다. 곧 올 라벨과 겹쳐 울리지 않게 하기 위해서다.
     * @return 지금 울려야 할 진동. 울리지 않아야 하면 null.
     */
    fun onTick(
        nowMs: Long,
        label: String?,
        level: Float,
        config: (String) -> ClassConfig,
        unlabeledAlerts: Boolean = false
    ): Vibe? {
        if (level > levelThreshold) lastLoudMs = nowMs
        val sounding = lastLoudMs != Long.MIN_VALUE && nowMs - lastLoudMs <= releaseMs
        val dt = if (lastTickMs == Long.MIN_VALUE) 0L else nowMs - lastTickMs
        lastTickMs = nowMs

        if (label == null && unlabeledAlerts) {
            active = null
            mismatchMs = 0L
            return onUnlabeled(nowMs, level, sounding, config)
        }
        unlabeledEvent = false

        if (!sounding) {
            active = null
            mismatchMs = 0L
            return null
        }

        // 울리던 종류의 진동이나 표시를 끄면 같은 틱에 멈춘다.
        active?.let { if (!config(it).vibrates) active = null }

        val current = active
        if (current != null && label != null && label != current) {
            val dangerPreempts = label == AiClassification.DANGER && config(AiClassification.DANGER).vibrates
            mismatchMs += dt
            // 다른 종류가 충분히 이어졌거나 위협음이 끼어들었다. 아래에서 새 라벨을 바로 다룬다.
            if (dangerPreempts || mismatchMs >= labelGraceMs) active = null
        } else {
            mismatchMs = 0L
        }

        if (active == null) {
            active = label?.takeIf { config(it).vibrates }
            mismatchMs = 0L
        }
        // 설정은 다른 스레드(설정 화면)가 바꾼다. 위에서 본 뒤 그 사이에 꺼졌을 수 있으므로 돌려줄 설정을 한 번 더 확인한다.
        // 확인 없이 돌려주면 꺼짐 방식의 진동을 내보낸다.
        val chosen = active ?: return null
        val cfg = config(chosen)
        return if (cfg.vibrates) vibeOf(cfg.haptic) else null
    }

    /**
     * 종류를 모르는 동안의 한 틱(#225). 큰 소리가 시작되면 그 소리가 이어지는 동안 위협음 설정으로 울린다.
     * 위협음의 표시나 진동을 꺼 두었으면 울리지 않는다.
     *
     * 큰 소리는 피크가 기준의 [HapticTuning.UNLABELED_RELEASE_RATIO] 아래로 [releaseMs] 넘게 내려가야 끝난다(#232).
     * 소리가 난다는 기준으로만 끝내면 게임 음악처럼 끊기지 않는 배경음 내내 울리고, 큰 소리 기준 그대로 끝내면 그 근처를
     * 오르내리는 음악에 끊겼다 울렸다 한다.
     */
    private fun onUnlabeled(
        nowMs: Long,
        level: Float,
        sounding: Boolean,
        config: (String) -> ClassConfig
    ): Vibe? {
        if (level >= unlabeledLoudLevel * HapticTuning.UNLABELED_RELEASE_RATIO) lastUnlabeledHotMs = nowMs
        val hot = lastUnlabeledHotMs != Long.MIN_VALUE && nowMs - lastUnlabeledHotMs <= releaseMs
        if (!sounding || !hot) unlabeledEvent = false
        if (sounding && level >= unlabeledLoudLevel) unlabeledEvent = true
        if (!unlabeledEvent) return null
        val danger = config(AiClassification.DANGER)
        if (!danger.vibrates) return null
        return vibeOf(danger.haptic)
    }

    private fun vibeOf(haptic: HapticSettings) = Vibe(haptic.mode, haptic.level)

    /** 모든 상태를 지운다. */
    fun reset() {
        lastLoudMs = Long.MIN_VALUE
        lastTickMs = Long.MIN_VALUE
        active = null
        mismatchMs = 0L
        unlabeledEvent = false
        lastUnlabeledHotMs = Long.MIN_VALUE
    }
}

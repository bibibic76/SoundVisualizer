package com.example.soundvisualizer.feedback

import androidx.annotation.StringRes
import com.example.soundvisualizer.AiClassification
import com.example.soundvisualizer.R

/**
 * 진동 방식. 그 종류의 소리가 이어지는 동안 이 박자로 울린다. 느림·중간·빠름은 같은 세기로 켰다 껐다 하고,
 * 연속은 끊기지 않고 이어진다. 박자는 [HapticTuning.periodMs] 와 [HapticTuning.onMs] 에 있다.
 *
 * 항목은 느린 것부터 빠른 것 순서로 둔다. 외부 사운드 모드의 상한([HapticSettings.externalCap])이 이 순서로 비교하고,
 * 설정 화면의 칸도 이 순서로 놓인다.
 *
 * 설정에는 이름(name)으로 저장하므로 항목 이름을 바꾸면 기존 설정이 기본값으로 돌아간다.
 */
enum class HapticMode(@StringRes val labelRes: Int) {
    Off(R.string.haptic_mode_off),

    /** 중간의 절반 빠르기 */
    Slow(R.string.haptic_mode_slow),

    /** 퉁 퉁 퉁 퉁 */
    Medium(R.string.haptic_mode_medium),

    /** 중간의 두 배 빠르기 */
    Fast(R.string.haptic_mode_fast),

    /** 끊김 없이 이어지는 진동 */
    Continuous(R.string.haptic_mode_continuous)
}

/**
 * 한 소리 종류의 진동 설정.
 *
 * @param level 세기(%). [MIN_LEVEL]~[MAX_LEVEL] 을 [LEVEL_STEP] 단위로. 진폭은 [HapticTuning.amplitude].
 *   세기 조절을 못 하는 기기에서는 무시되고 기본 세기로 울린다.
 */
data class HapticSettings(
    val mode: HapticMode,
    val level: Int
) {
    /** 이 종류가 진동하는지. */
    val enabled: Boolean get() = mode != HapticMode.Off

    /**
     * 외부 사운드 모드(마이크)에서 [label] 이 실제로 울릴 설정(#354). 그 종류의 상한([externalCap])보다 빠른 방식은 상한으로
     * 울리고, 상한까지의 방식과 세기는 그대로다.
     *
     * 저장된 설정은 바꾸지 않으므로 외부 사운드 모드를 끄면 정해 둔 대로 울린다.
     */
    fun inExternalSound(label: String): HapticSettings {
        val cap = externalCap(label)
        return if (mode > cap) copy(mode = cap) else this
    }

    companion object {
        const val MIN_LEVEL = 10
        const val MAX_LEVEL = 100
        const val LEVEL_STEP = 10

        /**
         * 종류별 기본값. 위협음만 켜둔다. 영상은 대화와 배경음이 끊임없이 나와서
         * 그 둘까지 기본으로 켜면 진동이 금방 성가셔진다.
         * 모르는 라벨은 환경음으로 본다 ([AiClassification.coarse] 와 같은 규칙).
         */
        fun defaultFor(label: String): HapticSettings = when (label) {
            AiClassification.DANGER -> HapticSettings(HapticMode.Medium, MAX_LEVEL)
            AiClassification.SPEECH -> HapticSettings(HapticMode.Off, 60)
            else -> HapticSettings(HapticMode.Off, 30)
        }

        /**
         * 외부 사운드 모드에서 그 종류가 울릴 수 있는 가장 빠른 방식(사용자 결정 2026-10-08, #354). 환경음은 꺼짐(진동하지
         * 않는다), 대화음은 느림, 위협음은 중간까지다. 그래서 '빠름'과 '연속'은 어느 종류로도 울리지 않는다.
         *
         * - 마이크는 앱의 진동을 소리로 듣는다. 진동 판단은 울림 사이의 쉼에서만 소리를 보는데([SelfVibrationGate]), 울림
         *   꼬리를 뺀 듣는 틈이 느림 0.42초, 중간 0.12초, 빠름 0.04초다([HapticTuning.selfHearingGuardMs]). 빠름은 진동
         *   꼬리가 긴 폰에서 그 틈이 사라져 자기 진동 소리로 다시 이어질 수 있고, '연속'에는 틈이 없다(#290).
         * - 환경음은 주변에서 늘 들린다. 외부 사운드 모드에서 환경음을 진동하게 두면 진동이 그치지 않는다.
         * - 느릴수록 울림 사이에 깨끗하게 듣는 조각이 길다. AI 가 진동 소리를 말소리로 듣는 문제(#352)를 풀 때 쓸 수 있는
         *   소리도 그만큼 길어진다.
         *
         * 위협음의 상한은 기본값(중간)과 같아 기본 설정은 그대로 울린다. AI 를 쓸 수 없을 때 큰 소리에 울리는 진동도 위협음
         * 설정을 따르므로([HapticPolicy]) 중간까지다. 모르는 라벨은 환경음으로 본다([defaultFor] 와 같은 규칙).
         *
         * 상한을 바꾸면 도움말(help_sound_haptic_external)과 README, 설계 문서에 적은 값도 함께 고친다. 설정 줄의 안내는
         * 이 값에서 이름을 가져온다.
         */
        fun externalCap(label: String): HapticMode = when (label) {
            AiClassification.DANGER -> HapticMode.Medium
            AiClassification.SPEECH -> HapticMode.Slow
            else -> HapticMode.Off
        }

        /** 저장된 세기를 범위와 단계에 맞춘다. 손으로 고친 값이 와도 슬라이더에 있는 값이 된다. */
        fun clampLevel(level: Int): Int {
            val stepped = Math.round(level / LEVEL_STEP.toFloat()) * LEVEL_STEP
            return stepped.coerceIn(MIN_LEVEL, MAX_LEVEL)
        }

        /**
         * #242 전의 설정(켜기, 패턴, 약·중·강)을 방식과 세기로 옮긴다. 저장된 것이 하나도 없으면 null 이다(기본값을 쓴다).
         *
         * 꺼 두었으면 꺼짐, 소리 따라(이름 Repeat)는 소리가 이어지는 동안 멈추지 않았으므로 연속, 한 번·두 번·길게는 중간.
         * 약·중·강은 예전 진폭(70/150/255)에 가까운 30·60·100%. 모르는 이름은 그 종류의 기본값을 따른다.
         */
        fun fromLegacy(label: String, enabled: Boolean?, pattern: String?, strength: String?): HapticSettings? {
            if (enabled == null && pattern == null && strength == null) return null
            val default = defaultFor(label)
            val mode = when {
                !(enabled ?: default.enabled) -> HapticMode.Off
                pattern == "Repeat" -> HapticMode.Continuous
                pattern == "Tap" || pattern == "DoubleTap" || pattern == "Hold" -> HapticMode.Medium
                default.enabled -> default.mode
                else -> HapticMode.Medium
            }
            val level = when (strength) {
                "Weak" -> 30
                "Medium" -> 60
                "Strong" -> MAX_LEVEL
                else -> default.level
            }
            return HapticSettings(mode, level)
        }
    }
}

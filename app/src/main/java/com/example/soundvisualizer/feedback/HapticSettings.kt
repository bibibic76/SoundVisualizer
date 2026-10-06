package com.example.soundvisualizer.feedback

import androidx.annotation.StringRes
import com.example.soundvisualizer.AiClassification
import com.example.soundvisualizer.R

/**
 * 진동 방식. 그 종류의 소리가 이어지는 동안 이 박자로 울린다. 느림·중간·빠름은 같은 세기로 켰다 껐다 하고,
 * 연속은 끊기지 않고 이어진다. 박자는 [HapticTuning.periodMs] 와 [HapticTuning.onMs] 에 있다.
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

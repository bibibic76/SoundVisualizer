package com.example.soundvisualizer.feedback

import androidx.annotation.StringRes
import com.example.soundvisualizer.AiClassification
import com.example.soundvisualizer.R

/**
 * 한 번 울릴 진동의 모양. [Repeat] 를 되풀이하는 판단은 [HapticPolicy] 가 한다.
 *
 * 설정에는 이름(name)으로 저장하므로 항목 이름을 바꾸면 기존 설정이 기본값으로 돌아간다.
 */
enum class HapticPattern(@StringRes val labelRes: Int) {
    /** 짧게 한 번 */
    Tap(R.string.haptic_pattern_tap),

    /** 짧게 두 번 */
    DoubleTap(R.string.haptic_pattern_double),

    /** 길게 한 번 */
    Hold(R.string.haptic_pattern_hold),

    /** 소리가 이어지는 동안 짧게 두 번을 되풀이 */
    Repeat(R.string.haptic_pattern_repeat)
}

/**
 * 진동 세기. [amplitude] 는 VibrationEffect 진폭(1..255)이다.
 * 세기 조절을 못 하는 기기에서는 무시되고 기본 세기로 울린다.
 */
enum class HapticStrength(@StringRes val labelRes: Int, val amplitude: Int) {
    Weak(R.string.haptic_strength_weak, 70),
    Medium(R.string.haptic_strength_medium, 150),
    Strong(R.string.haptic_strength_strong, 255)
}

/** 한 소리 종류의 진동 설정. */
data class HapticSettings(
    val enabled: Boolean,
    val strength: HapticStrength,
    val pattern: HapticPattern
) {
    companion object {
        /**
         * 종류별 기본값. 위협음만 켜둔다. 영상은 대화와 배경음이 끊임없이 나와서
         * 그 둘까지 기본으로 켜면 진동이 금방 성가셔진다.
         * 모르는 라벨은 환경음으로 본다 ([AiClassification.coarse] 와 같은 규칙).
         */
        fun defaultFor(label: String): HapticSettings = when (label) {
            AiClassification.DANGER -> HapticSettings(true, HapticStrength.Strong, HapticPattern.DoubleTap)
            AiClassification.SPEECH -> HapticSettings(false, HapticStrength.Medium, HapticPattern.Tap)
            else -> HapticSettings(false, HapticStrength.Weak, HapticPattern.Tap)
        }
    }
}

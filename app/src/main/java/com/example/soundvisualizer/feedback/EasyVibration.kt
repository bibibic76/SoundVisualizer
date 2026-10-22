package com.example.soundvisualizer.feedback

import androidx.annotation.StringRes
import com.example.soundvisualizer.AiClassification
import com.example.soundvisualizer.R

/**
 * 쉬운 설정의 진동 세 단계(#408): 끔·약하게·세게. 자세한 설정의 방식 다섯 칸과 세기 슬라이더를 하나로 줄였다.
 *
 * 쉬운 설정에서는 세기만 선택한다. 방식(박자)은 꺼져 있던 종류를 켤 때 그 종류에 맞는 것([onMode])으로 정하고, 이미 켜져
 * 있으면 자세한 설정에서 정해 둔 방식을 그대로 둔다. 끌 때는 세기를 남겨 둔다(자세한 설정의 '꺼짐'과 같다).
 * 외부 사운드 모드의 상한은 [HapticModeChoice] 와 같은 규칙을 따른다.
 *
 * 세기가 단계의 값과 같을 때만 그 단계로 보인다. 자세한 설정에서 그 사이 세기로 맞춰 두었으면 어느 단계도 아니다(크기와
 * 같은 규칙, 사용자 결정 2026-10-22, #424). 예전에는 가장 가까운 단계로 보였는데, 실제 세기와 다르고 그 칸을 눌러도
 * 바뀌지 않았다.
 *
 * 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 */
enum class EasyVibration(@StringRes val labelRes: Int, val level: Int) {
    Off(R.string.easy_vibration_off, 0),
    Low(R.string.easy_vibration_low, 40),
    High(R.string.easy_vibration_high, HapticSettings.MAX_LEVEL);

    companion object {
        /**
         * [label] 줄에 선택되어 보일 단계. 외부 사운드 모드에서는 실제로 울릴 설정([HapticModeChoice.shown])으로 정한다.
         * 켜져 있는데 세기가 어느 단계의 값과도 다르면 null 이다(자세한 설정에서 맞춘 세기).
         */
        fun shown(stored: HapticSettings, label: String, external: Boolean): EasyVibration? {
            val shown = HapticModeChoice.shown(stored, label, external)
            if (!shown.enabled) return Off
            return values().firstOrNull { it != Off && it.level == shown.level }
        }

        /** 이 단계를 선택할 수 있는지. 외부 사운드 모드에서 진동하지 않는 종류([HapticModeChoice.neverVibrates])는 끔만 된다. */
        fun selectable(step: EasyVibration, label: String, external: Boolean): Boolean =
            step == Off || !HapticModeChoice.neverVibrates(label, external)

        /**
         * [tapped] 를 눌렀을 때 저장할 설정. 저장하지 않으면 null 이다. 선택할 수 없는 단계이거나 이미 그 단계로 보이는 경우다.
         * 자세한 설정에서 맞춘 세기면 어느 단계를 눌러도 그 단계의 값으로 바꾼다.
         * 미리 울리기는 저장과 상관없이 한다(자세한 설정과 같다).
         */
        fun toStore(stored: HapticSettings, tapped: EasyVibration, label: String, external: Boolean): HapticSettings? {
            if (!selectable(tapped, label, external)) return null
            if (tapped == shown(stored, label, external)) return null
            if (tapped == Off) return stored.copy(mode = HapticMode.Off)
            val mode = if (HapticModeChoice.shown(stored, label, external).enabled) stored.mode else onMode(label)
            return HapticSettings(mode, tapped.level)
        }

        /**
         * 꺼져 있던 종류를 켤 때의 방식. 위협음은 처음 설정과 같은 중간, 나머지는 가장 덜 성가신 느림이다.
         * 둘 다 외부 사운드 모드의 상한([HapticSettings.externalCap]) 안이다.
         */
        fun onMode(label: String): HapticMode =
            if (label == AiClassification.DANGER) HapticMode.Medium else HapticMode.Slow
    }
}

package com.example.soundvisualizer

import androidx.annotation.StringRes

/**
 * 쉬운 설정의 크기 세 단계(#408). 자세한 설정의 크기·진하기 슬라이더 대신 그림 단추 셋으로 선택한다.
 *
 * 한 단계가 크기와 진하기를 함께 정한다. 작게는 덜 눈에 띄게, 크게는 더 눈에 띄게 그린다. 보통은 처음 값([ModeSettings] 의
 * 기본값)을 그대로 가리켜, 새로 설치한 사람에게는 보통이 선택되어 보인다. 기본값은 [ModeSettings] 한 곳에만 둔다.
 *
 * 크기는 모드마다 따로다(사용자 결정 2026-10-22). 쉬운 설정의 크기 칸은 지금 선택한 모드의 크기를 보여 주고 바꾼다. 자세한 설정도
 * 모드마다 따로 저장하므로, 모드를 바꾸면 그 모드에 정해 둔 크기로 돌아간다.
 */
enum class EasySize(@StringRes val labelRes: Int, val intensity: Float, val opacity: Float) {
    Small(R.string.easy_size_small, intensity = 30f, opacity = 40f),
    Medium(R.string.easy_size_medium, intensity = ModeSettings().intensity, opacity = ModeSettings().opacity),
    Large(R.string.easy_size_large, intensity = 75f, opacity = 70f);

    /**
     * 이 단계를 [settings] 에 쓴 값. 크기 고정을 켜 둔 모드는 크기가 움직이지 않으므로 끈다. 누른 크기가 실제로 보여야 한다.
     * 나머지 값(속도·민감도·광원·반지름 등)은 그대로 둔다.
     */
    fun applyTo(settings: ModeSettings): ModeSettings =
        settings.copy(intensity = intensity, opacity = opacity, intensityAsOpacity = false)

    companion object {
        /**
         * [settings] 가 한 단계에 맞으면 그 단계. 자세한 설정에서 크기나 진하기를 바꿨거나 크기 고정을 켰으면 어느 단계도
         * 아니다(null). 그때는 어느 칸도 선택해 두지 않는다.
         */
        fun of(settings: ModeSettings): EasySize? = values().firstOrNull { settings == it.applyTo(settings) }
    }
}

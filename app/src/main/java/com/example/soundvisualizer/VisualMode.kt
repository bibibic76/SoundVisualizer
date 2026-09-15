package com.example.soundvisualizer

import androidx.annotation.StringRes

/**
 * 표현 모드. 저장은 [Enum.ordinal] 로 하므로 순서를 바꾸면 기존 설정이 어긋난다.
 */
enum class VisualMode(@StringRes val labelRes: Int) {
    Wave(R.string.mode_wave),
    Pad(R.string.mode_pad),
    CircleRipple(R.string.mode_circle),
    Outline(R.string.mode_outline)
}

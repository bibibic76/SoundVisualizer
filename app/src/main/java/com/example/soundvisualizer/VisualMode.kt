package com.example.soundvisualizer

import androidx.annotation.StringRes

/**
 * 표현 모드. 저장은 [Enum.ordinal] 로 하므로 순서를 바꾸면 기존 설정이 어긋난다.
 */
enum class VisualMode(@StringRes val labelRes: Int) {
    Wave(R.string.mode_wave),
    Pad(R.string.mode_pad),
    CircleRipple(R.string.mode_circle),
    Outline(R.string.mode_outline);

    companion object {
        /**
         * [ordinal] 에 해당하는 모드. 없는 번호면 null.
         *
         * 실행 중 알림의 모드 칩이 누른 모드를 번호로 넘겨서 쓴다. 알림은 앱이 죽은 뒤에도 알림창에 남아 있을 수
         * 있어서, 모드가 줄어든 새 버전이 받는 번호가 옛 버전 것일 수 있다. 그때 튕기지 않고 무시하려고 null 을 낸다.
         */
        fun fromOrdinal(ordinal: Int): VisualMode? = values().getOrNull(ordinal)
    }
}

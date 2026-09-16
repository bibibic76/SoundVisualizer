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

    /**
     * 설정 화면에 보이는 차례대로 다음 모드. 마지막 다음은 처음으로 돌아온다.
     *
     * 실행 중 알림의 [모드 바꾸기] 가 쓴다. 알림에는 버튼을 많이 둘 수 없어 하나씩 넘긴다.
     */
    fun next(): VisualMode = values()[(ordinal + 1) % values().size]
}

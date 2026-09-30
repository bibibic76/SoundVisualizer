package com.example.soundvisualizer.feedback

import androidx.annotation.StringRes
import com.example.soundvisualizer.R

/**
 * AI 를 쓸 수 없는 실행에서 진동에 대해 무엇을 알릴지 고른다(#232). 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 이때는 종류를 가리지 않고 큰 소리에만 위협음 설정으로 울린다([HapticPolicy]). 위협음의 표시나 진동을 꺼 두었거나
 * 진동 모터가 없으면 아무것도 울리지 않는데, 그때도 "큰 소리에는 진동한다" 고 하면 진동에 기대는 사용자를
 * 거짓으로 안심시킨다. 그때는 진동 알림이 꺼져 있다고 알린다.
 */
object AiUnavailableNotice {

    /** 큰 소리에 실제로 울리는지. [HapticPolicy] 가 종류를 모를 때 쓰는 조건과 같다. */
    fun loudAlerts(dangerShown: Boolean, dangerVibrates: Boolean, hasVibrator: Boolean): Boolean =
        dangerShown && dangerVibrates && hasVibrator

    /** 홈의 안내. */
    @StringRes
    fun home(loudAlerts: Boolean): Int =
        if (loudAlerts) R.string.home_ai_unavailable_loud else R.string.home_ai_unavailable

    /** 실행 중 알림의 글. */
    @StringRes
    fun notification(loudAlerts: Boolean): Int =
        if (loudAlerts) R.string.notification_text_ai_unavailable_loud else R.string.notification_text_ai_unavailable

    /** 환경음·말소리 줄의 안내. 위협음 줄은 진동을 켜 두었을 때만 안내가 붙으므로 늘 큰 소리 문구다. */
    @StringRes
    fun otherRow(loudAlerts: Boolean): Int =
        if (loudAlerts) R.string.haptic_ai_unavailable_other else R.string.haptic_ai_unavailable
}

package com.example.soundvisualizer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

/** 앱 화면 배경. 창·스플래시 배경(res/values/colors.xml 의 app_background)과 같은 값이어야 한다. */
val BgColor = Color(0xFF2A2C31)

val CardColor = Color(0xFF1E2024)

/**
 * 강조색. 파랑을 쓰임에 따라 둘로 나눈다(#312). 한 색으로는 흰 글자를 얹는 바탕과 어두운 바탕 위의 글자 모두에서
 * 대비 4.5:1 을 넘길 수 없다(예전 #3182F6 은 흰 글자 3.7:1, 카드 위 글자 4.4:1).
 *
 * - [AccentColor]: 어두운 바탕 위의 글자·아이콘·표시(값, 글자 버튼, 상태 점). 카드 위 5.9:1, 배경 위 5.0:1.
 * - [AccentFillColor]: 흰 글자를 얹는 바탕(실행 버튼, 고른 칸)과 스위치·슬라이더 막대. 흰 글자 5.2:1, 카드 위 3.2:1(그림 요소 기준 3:1).
 */
val AccentColor = Color(0xFF5B9BFF)

val AccentFillColor = Color(0xFF2563EB)

/** 실행 종료 버튼 바탕. 흰 글자 5.0:1(#312, 예전 #E53935 는 4.2:1). */
val DangerColor = Color(0xFFD32F2F)

val PrimaryTextColor = Color(0xFFF2F4F6)

val SecondaryTextColor = Color(0xFF8B95A1)

/** 기능이 꺼져 있다는 안내 글자. DangerColor 는 어두운 배경에서 작은 글자로 읽기 어려워 밝은 주황을 쓴다. */
val WarningColor = Color(0xFFFFB74D)

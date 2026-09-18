package com.example.soundvisualizer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

/** 앱 화면 배경. 창·스플래시 배경(res/values/colors.xml 의 app_background)과 같은 값이어야 한다. */
val BgColor = Color(0xFF2A2C31)

val CardColor = Color(0xFF1E2024)

val AccentColor = Color(0xFF3182F6)

val DangerColor = Color(0xFFE53935)

val PrimaryTextColor = Color(0xFFF2F4F6)

val SecondaryTextColor = Color(0xFF8B95A1)

/** 기능이 꺼져 있다는 안내 글자. DangerColor 는 어두운 배경에서 작은 글자로 읽기 어려워 밝은 주황을 쓴다. */
val WarningColor = Color(0xFFFFB74D)

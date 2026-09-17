package com.example.soundvisualizer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF3182F6),
    secondary = Color(0xFF8B95A1),
    tertiary = Color(0xFFE53935),
    background = Color(0xFF2A2C31),
    surface = Color(0xFF1E2024)
)

// 여기 지정한 색은 DarkColorScheme 과 같지만, 지정하지 않은 역할(onSurface, surfaceContainerHigh 등)에는 밝은 기본값이 들어간다.
// 둘을 하나로 합치면 폰이 라이트 모드일 때 기본색을 쓰는 부분의 모양이 바뀌므로, 화면을 확인하고 합친다.
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3182F6),
    secondary = Color(0xFF8B95A1),
    tertiary = Color(0xFFE53935),
    background = Color(0xFF2A2C31),
    surface = Color(0xFF1E2024)
)

/**
 * 앱의 색과 글꼴.
 *
 * 창(상태 표시줄·내비게이션 바)은 건드리지 않는다. 앱 화면의 바는 MainActivity 가 `enableEdgeToEdge` 로 정하고,
 * 이 테마를 함께 쓰는 빠른 설정 타일의 투명 화면에는 그 설정이 따라가면 안 되기 때문이다.
 * 게다가 창의 `statusBarColor` 는 Android 15 이상에서 targetSdk 35 부터 아무 효과도 없다(#138).
 */
@Composable
fun SoundVisualizerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

package com.example.soundvisualizer.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF3182F6),
    secondary = Color(0xFF8B95A1),
    tertiary = Color(0xFFE53935),
    background = Color(0xFF2A2C31),
    surface = Color(0xFF1E2024)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3182F6),
    secondary = Color(0xFF8B95A1),
    tertiary = Color(0xFFE53935),
    background = Color(0xFF2A2C31),
    surface = Color(0xFF1E2024)
)

@Composable
fun SoundVisualizerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 상태바 색상을 앱 배경색(다크 그레이)으로 고정
            window.statusBarColor = Color(0xFF2A2C31).toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

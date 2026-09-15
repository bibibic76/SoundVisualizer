package com.example.soundvisualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
fun VisualizerOverlay() {
    val density = LocalDensity.current.density
    val engine = remember(density) { VisualizerEngine(density) }
    // 프레임 틱 → 그리기 무효화를 잇는 유일한 Compose 상태. 리컴포지션은 발생하지 않는다.
    val frame = remember { mutableLongStateOf(0L) }

    LaunchedEffect(engine) {
        val capturePaused = SettingsManager.isCapturePaused
        while (true) {
            if (engine.isIdle) {
                // 화면이 꺼져 캡처를 쉬는 동안에는 새 소리가 들어오지 않으니 33ms 확인도 멈추고 켜질 때까지 기다린다.
                // 화면이 꺼져도 이 루프는 저절로 멈추지 않아 CPU 를 계속 깨운다. 소리가 나던 중에 쉬기 시작해도
                // 캡처 서비스가 남은 소리 크기를 지우므로, 엔진이 1초 남짓 뒤 쉬기로 내려와 여기서 멈춘다.
                if (capturePaused.value) capturePaused.first { !it }
                delay(VisualizerEngine.IDLE_POLL_MS)
                engine.pollWake()
            } else {
                withFrameNanos { nanos ->
                    if (engine.tick(nanos)) frame.longValue++
                }
            }
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        engine.draw(drawContext.canvas.nativeCanvas, size.width, size.height, frame.longValue)
    }
}

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

@Composable
fun VisualizerOverlay() {
    val density = LocalDensity.current.density
    val engine = remember(density) { VisualizerEngine(density) }
    // 프레임 틱 → 그리기 무효화를 잇는 유일한 Compose 상태. 리컴포지션은 발생하지 않는다.
    val frame = remember { mutableLongStateOf(0L) }

    LaunchedEffect(engine) {
        while (true) {
            if (engine.isIdle) {
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

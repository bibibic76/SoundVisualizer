package com.example.soundvisualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

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
                // 쉬는 동안은 Compose 디스패처를 벗어나 메인 스레드 핸들러에서 기다린다(#170). Compose 디스패처는
                // delay 를 따로 타이머 스레드로 재고, 돌아올 때마다 vsync 까지 요청한다. 그리는 것도 없는데 확인
                // 한 번에 스레드가 네다섯 번 깨어났다. 같은 메인 스레드라 엔진을 만지는 스레드는 바뀌지 않는다.
                withContext(Dispatchers.Main) { rest(engine, capturePaused) }
            } else {
                withFrameNanos { nanos ->
                    if (engine.tick(nanos)) frame.longValue++
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            engine.draw(drawContext.canvas.nativeCanvas, size.width, size.height, frame.longValue)
        }
        // 개발자 모드 표시. 상태 읽기를 저 안에 가둬 두었으므로 이 함수는 여전히 리컴포지션되지 않는다.
        // 여기서 설정이나 분류 결과를 읽으면 그 성질이 깨진다.
        AiDebugOverlay()
    }
}

/**
 * 엔진이 깨어날 때까지 쉰다. 조용하고 보관한 소리도 없으면 캡처 스레드가 큰 소리를 알려 줄 때까지 잠들고,
 * 그렇지 않으면(표시를 꺼 둔 소리가 나는 중이거나 늦게 올 위협음 판정을 위해 소리를 보관하는 중) 33ms 마다 확인한다.
 */
private suspend fun rest(engine: VisualizerEngine, capturePaused: StateFlow<Boolean>) {
    while (engine.isIdle) {
        // 화면이 꺼져 캡처를 쉬는 동안에는 새 소리가 들어오지 않으니 확인도 멈추고 켜질 때까지 기다린다.
        // 화면이 꺼져도 이 루프는 저절로 멈추지 않아 CPU 를 계속 깨운다. 소리가 나던 중에 쉬기 시작해도
        // 캡처 서비스가 남은 소리 크기를 지우므로, 엔진이 1초 남짓 뒤 쉬기로 내려와 여기서 멈춘다.
        if (capturePaused.value) capturePaused.first { !it }
        if (engine.canSleepUntilLoud) OverlayWake.awaitLoud() else delay(VisualizerEngine.IDLE_POLL_MS)
        // 확인하기 직전부터 들어오는 소리만 다음 잠을 깨운다. 확인과 잠드는 사이에 온 소리도 놓치지 않는다.
        OverlayWake.arm()
        engine.pollWake()
    }
}

package com.example.soundvisualizer.feedback

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.example.soundvisualizer.AudioEngine
import com.example.soundvisualizer.LiveVisualizerInputs
import com.example.soundvisualizer.SettingsManager

/**
 * 캡처가 도는 동안 분류 결과와 소리 크기를 주기적으로 읽어 진동을 울린다.
 *
 * AI 파이프라인에 콜백을 넣지 않고 결과를 읽어 가는 방식이라 ai/ 코드를 건드리지 않는다.
 * 결과가 0.25초마다 나오므로 0.1초 주기면 결과를 놓치지 않는다.
 *
 * 한 인스턴스는 한 번만 시작하고 한 번만 멈춘다.
 *
 * @param labelSource 가장 최근 분류 라벨. 결과가 아직 없으면 null
 */
class HapticNotifier(
    context: Context,
    private val labelSource: () -> String?
) {
    private companion object {
        const val TICK_MS = 100L
    }

    private val player = HapticPlayer(context)
    private val policy = HapticPolicy()
    private val thread = HandlerThread("SV-Haptic", Process.THREAD_PRIORITY_BACKGROUND)

    @Volatile
    private var running = false

    @Volatile
    private var handler: Handler? = null

    private val configFor: (String) -> HapticPolicy.ClassConfig = { label ->
        HapticPolicy.ClassConfig(
            shown = LiveVisualizerInputs.isShown(label),
            haptic = SettingsManager.hapticSettings(label).value
        )
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val decision = policy.onTick(
                nowMs = SystemClock.elapsedRealtime(),
                label = labelSource(),
                level = AudioEngine.currentLevel(),
                config = configFor
            )
            // stop() 과 경합하면 마지막 한 번이 울릴 수 있으니 울리기 직전에 한 번 더 본다.
            if (decision != null && running) player.play(decision.pattern, decision.strength)
            handler?.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        // 진동 모터가 없으면 스레드를 띄울 이유가 없다.
        if (!player.hasVibrator || handler != null) return
        running = true
        thread.start()
        handler = Handler(thread.looper).also { it.post(tick) }
    }

    fun stop() {
        running = false
        val h = handler ?: return
        handler = null
        h.removeCallbacks(tick)
        thread.quitSafely()
        player.cancel()
    }
}

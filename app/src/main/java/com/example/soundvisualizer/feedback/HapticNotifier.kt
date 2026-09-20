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
 * 소리 크기는 [AudioEngine.takeHapticPeak] 로 **지난 틱 이후 구간 전체의 최대값**을 읽는다. 가장 최근 버퍼만
 * 보면 시간의 12% 남짓만 들여다보게 되어, 총소리 한 발처럼 짧은 소리가 확인과 확인 사이에 들어왔다 사라진다(#174).
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

        /** [stop] 이 진동 스레드를 기다리는 최대 시간. 틱 하나가 짧아 보통 바로 끝난다. */
        const val JOIN_TIMEOUT_MS = 200L
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
                level = AudioEngine.takeHapticPeak(),
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
        // 이미 울리기로 정해진 진동 한 번이 stop() 뒤에 나가지 않도록 스레드가 끝나기를 잠깐 기다린다.
        // 기다리지 않으면 꺼짐 알림 진동이 늦은 진동에 끊길 수 있다(Android 11 이하).
        try {
            thread.join(JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        player.cancel()
    }
}

package com.example.soundvisualizer.feedback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.example.soundvisualizer.AudioEngine
import com.example.soundvisualizer.LiveVisualizerInputs
import com.example.soundvisualizer.SettingsManager

/**
 * 캡처가 도는 동안 분류 결과와 소리 크기를 주기적으로 읽어 진동을 울린다.
 *
 * AI 파이프라인에 콜백을 넣지 않고 결과를 읽어 가는 방식이라 ai/ 코드를 건드리지 않는다.
 * 무엇을 울릴지는 100ms 마다 판단하고([HapticPolicy]), 느림·중간·빠름의 울림은 제 시각에 깨어나 보낸다([HapticDriver]).
 *
 * 한 인스턴스는 한 번만 시작하고 한 번만 멈춘다.
 *
 * @param configFor 라벨별 표시·진동 설정.
 * @param unlabeledAlerts 라벨이 끝내 오지 않는 실행(AI 를 쓸 수 없음)인지. 참인 동안은 라벨 없이도 큰 소리에 울린다(#225).
 * @param labelSource 가장 최근 분류 라벨. 결과가 아직 없으면 null. 마지막 인자라 `HapticNotifier(context) { … }` 로 쓴다.
 */
class HapticNotifier(
    context: Context,
    private val configFor: (String) -> HapticPolicy.ClassConfig = LIVE_CONFIG,
    private val unlabeledAlerts: () -> Boolean = { false },
    private val labelSource: () -> String?
) {
    private companion object {
        const val TAG = "SvHaptic"

        /** [stop] 이 진동 스레드를 기다리는 최대 시간. 틱 하나가 짧아 보통 바로 끝난다. */
        const val JOIN_TIMEOUT_MS = 200L

        const val STATS_MS = 10_000L

        val LIVE_CONFIG: (String) -> HapticPolicy.ClassConfig = { label ->
            HapticPolicy.ClassConfig(
                shown = LiveVisualizerInputs.isShown(label),
                haptic = SettingsManager.hapticSettings(label).value
            )
        }
    }

    private val player = HapticPlayer(context)
    private val policy = HapticPolicy()
    private val driver = HapticDriver(player.hasAmplitudeControl)

    // 울림은 제 시각에 보내야 박자가 고르다. 배경 우선순위는 렌더·AI 가 바쁠 때 늦게 깨어 박자가 흔들린다.
    private val thread = HandlerThread("SV-Haptic", Process.THREAD_PRIORITY_DISPLAY)
    private val debug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    @Volatile
    private var running = false

    @Volatile
    private var handler: Handler? = null

    private var statsSince = 0L
    private var ticks = 0
    private var plans = 0
    private var skipped = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val vibe = policy.onTick(now, labelSource(), AudioEngine.takeHapticPeak(), configFor, unlabeledAlerts())
            val command = driver.onTick(now, vibe)
            if (now < HapticPreviewGate.busyUntilMs) {
                // 미리보기가 진동기를 잡고 있다. 보내지 않고, 이 알림이 보낸 연속 울림도 끊긴 것으로 잊는다. 잊지 않으면
                // 미리보기가 끝난 뒤에도 그 울림이 끝날 때까지(최대 5초) 조용하다(#232).
                driver.onPreempted()
                if (command != HapticDriver.Command.None) skipped++
            } else {
                issue(command, now)
            }

            ticks++
            if (debug && now - statsSince >= STATS_MS) {
                Log.d(TAG, "ticks=$ticks plans=$plans skipped=$skipped")
                statsSince = now
                ticks = 0
                plans = 0
                skipped = 0
            }
            // 절대 시각으로 다음 틱을 잡는다. 다음 울림이 판단 주기보다 먼저면 그 시각에 깨어난다.
            val delay = (driver.nextWakeMs(now) - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            handler?.postAtTime(this, SystemClock.uptimeMillis() + delay)
        }
    }

    /** 진동기에 명령을 보내는 유일한 곳. 멈춘 뒤에는 보내지 않는다. 미리보기가 진동기를 잡은 동안에는 부르지 않는다. */
    private fun issue(command: HapticDriver.Command, now: Long) {
        if (!running) return
        when (command) {
            is HapticDriver.Command.Play -> if (player.playPlan(command.plan)) {
                plans++
                if (debug) Log.d(TAG, command.plan.summary(now))
            } else {
                driver.onPreempted()
                skipped++
            }
            HapticDriver.Command.Cancel -> player.cancel()
            HapticDriver.Command.None -> Unit
        }
    }

    fun start() {
        // 진동 모터가 없으면 스레드를 띄울 이유가 없다.
        if (!player.hasVibrator || handler != null) return
        running = true
        // 캡처를 켠 뒤(또는 화면이 켜진 뒤) 쌓인 값을 한 번 비운다. 안 비우면 첫 틱이 그동안 전체를 한 번에 본다.
        AudioEngine.takeHapticPeak()
        thread.start()
        statsSince = SystemClock.elapsedRealtime()
        // handler 를 먼저 두고 첫 틱을 보낸다. 틱은 handler 로만 다음 틱을 잡으므로, 보낸 뒤에 두면 진동 스레드가 그 틈에
        // 먼저 돌 때 다음 틱을 잡지 못해 이번 실행 내내 진동이 오류도 없이 멈춘다(#232).
        val h = Handler(thread.looper)
        handler = h
        h.post(tick)
        if (debug) Log.i(TAG, player.capabilityLine())
    }

    fun stop() {
        running = false
        val h = handler ?: return
        handler = null
        h.removeCallbacksAndMessages(null)
        thread.quitSafely()
        // 이미 울리기로 정해진 진동 한 번이 stop() 뒤에 나가지 않도록 스레드가 끝나기를 잠깐 기다린다.
        try {
            thread.join(JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // 기다림이 넘쳐도 플레이어를 잠금 안에서 닫으므로 이 뒤로는 아무것도 나가지 않는다. 닫으면서 울리던 연속 진동도 끊는다.
        // 그래서 꺼짐 알림 진동이 늦은 진동에 끊기지 않는다(Android 11 이하).
        player.close()
    }
}

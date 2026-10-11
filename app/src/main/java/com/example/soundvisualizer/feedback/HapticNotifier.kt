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
 * @param hearsOwnVibration 이번 실행의 소스가 앱의 진동을 소리로 듣는지([com.example.soundvisualizer.CaptureSource.hearsOwnVibration]).
 *   참이면 울림 사이의 쉼에서만 소리를 보고([SelfVibrationGate], #290), 종류마다 상한보다 빠른 방식은 상한으로 울린다
 *   ([HapticSettings.externalCap], #354). 실행 내내 바뀌지 않는다.
 * @param labelSource 가장 최근 분류 라벨. 결과가 아직 없으면 null. 마지막 인자라 `HapticNotifier(context) { … }` 로 쓴다.
 */
class HapticNotifier(
    context: Context,
    private val configFor: (String) -> HapticPolicy.ClassConfig = LIVE_CONFIG,
    private val unlabeledAlerts: () -> Boolean = { false },
    hearsOwnVibration: Boolean = false,
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

    /** 외부 사운드 모드에서만 있다. 폰 안의 소리는 앱의 진동을 듣지 못하므로 지금까지와 같다(#290). */
    private val selfGate: SelfVibrationGate? = if (hearsOwnVibration) SelfVibrationGate() else null

    /**
     * 틱이 쓰는 설정. 외부 사운드 모드에서는 종류마다 상한보다 빠른 방식을 상한으로 울린다([HapticSettings.inExternalSound]).
     * 종류를 모르는 큰 소리도 위협음 라벨로 읽으므로 위협음의 상한을 따른다. 저장된 설정은 그대로다.
     */
    private val tickConfig: (String) -> HapticPolicy.ClassConfig =
        if (hearsOwnVibration) { label -> configFor(label).inExternalSound(label) } else configFor

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

    /** 디버그 기록용. 자기 진동이 섞인 값 가운데 가장 큰 것. 마지막 값이 깨끗했으면 음수. */
    private var deafPeak = -1f

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val label = labelSource()
            val peak = AudioEngine.takeHapticPeak()
            // 외부 사운드 모드에서는 앱이 울린 진동이 섞였을 수 있는 값을 소리로 보지 않는다(#290). 마이크는 설정의 미리보기
            // 진동도 들으므로, 크기를 거르기 전에 미리보기가 진동기를 쓰는 시각을 알린다.
            val gate = selfGate
            gate?.onOtherVibration(HapticPreviewGate.busyUntilMs)
            val level = gate?.read(now, peak) ?: peak
            if (gate != null && debug) logGap(gate, now, peak)
            val vibe = policy.onTick(
                now, label, level, tickConfig, unlabeledAlerts(),
                holding = gate?.holding == true,
                levelAtMs = gate?.levelAtMs ?: now
            )
            val command = driver.onTick(now, vibe)
            if (now < HapticPreviewGate.busyUntilMs) {
                // 미리보기가 진동기를 잡고 있다. 보내지 않고, 이 알림이 보낸 연속 울림도 끊긴 것으로 잊는다. 잊지 않으면
                // 미리보기가 끝난 뒤에도 그 울림이 끝날 때까지([HapticTuning.CONTINUOUS_CHUNK_MS]) 조용하다(#232).
                driver.onPreempted()
                if (command != HapticDriver.Command.None) skipped++
            } else {
                issue(command, now, vibe)
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
            // 외부 사운드 모드에서는 울림의 꼬리가 끝날 때도 깨어나, 섞인 값을 버리고 쉼에서만 듣는다([SelfVibrationGate]).
            val wake = minOf(driver.nextWakeMs(now), gate?.reopenAtMs(now) ?: Long.MAX_VALUE)
            val delay = (wake - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            handler?.postAtTime(this, SystemClock.uptimeMillis() + delay)
        }
    }

    /**
     * 진동기에 명령을 보내는 유일한 곳. 멈춘 뒤에는 보내지 않는다. 미리보기가 진동기를 잡은 동안에는 부르지 않는다.
     * @param vibe 이번 틱의 진동. 울림을 보내면 그 방식의 꼬리 여유만큼 듣지 않는다.
     */
    private fun issue(command: HapticDriver.Command, now: Long, vibe: HapticPolicy.Vibe?) {
        if (!running) return
        when (command) {
            is HapticDriver.Command.Play -> if (player.playPlan(command.plan)) {
                plans++
                // 보낸 직후부터 센다. 보내는 데 걸린 시간도 마이크가 그 울림을 들을 수 있는 구간이다.
                selfGate?.onPlayed(
                    SystemClock.elapsedRealtime(),
                    command.plan.durationMs,
                    HapticTuning.selfHearingGuardMs(vibe?.mode ?: HapticMode.Off)
                )
                if (debug) Log.d(TAG, command.plan.summary(now))
            } else {
                driver.onPreempted()
                skipped++
            }
            HapticDriver.Command.Cancel -> {
                player.cancel()
                selfGate?.onCancelled(now)
            }
            HapticDriver.Command.None -> Unit
        }
    }

    /**
     * 디버그 빌드에서 외부 사운드 모드의 쉼마다 한 줄. 버린 값(마이크가 들은 자기 진동의 크기)과 쉼에서 처음 깨끗하게 읽은
     * 값을 남긴다. 집에서 폰을 울려 볼 때 꼬리 여유가 충분한지 이 줄로 잰다(#290).
     */
    private fun logGap(gate: SelfVibrationGate, now: Long, peak: Float) {
        if (!gate.lastReadClean) {
            deafPeak = maxOf(deafPeak, peak)
            return
        }
        if (deafPeak >= 0f) {
            Log.d(TAG, "gap t=$now deafPeak=$deafPeak firstClean=$peak")
            deafPeak = -1f
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

    /**
     * 화면이 꺼졌는데 이 알림은 계속 돈다(외부 사운드 모드, 또는 '화면이 꺼지면 일시정지'를 끈 경우). 사용자가 화면을 끄면
     * 안드로이드가 시스템 앱이 아닌 앱의 울리던 진동을 끊는데(`VibrationSettings.shouldCancelVibrationOnScreenOff`), 앱에는
     * 알리지 않는다. 그대로 두면 연속 울림을 다시 보낼 때까지([HapticTuning.CONTINUOUS_CHUNK_MS]) 조용하므로, 보낸 울림을
     * 끊긴 것으로 잊어 다음 틱에 다시 보낸다(#288). 느림·중간·빠름은 다음 박자부터 이어 가고, 외부 사운드 모드에는 상한
     * 때문에 연속이 없으므로(#354) 다시 보낼 것이 없다. 꺼진 뒤에 시작한 진동은 끊지 않는다. 어느 스레드에서 불러도 된다.
     */
    fun onScreenOff() {
        handler?.post { if (running) driver.onPreempted() }
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

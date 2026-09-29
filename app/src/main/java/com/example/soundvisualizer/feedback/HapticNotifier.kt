package com.example.soundvisualizer.feedback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.example.soundvisualizer.AudioEngine
import com.example.soundvisualizer.LiveVisualizerInputs
import com.example.soundvisualizer.SettingsManager

/**
 * 실제 캡처의 네이티브 누적값. 지난 틱 이후 구간 전체의 최대 피크와 가장 큰 버퍼의 RMS·영교차율을 읽는다.
 * 가장 최근 버퍼 하나만 담은 값(currentLevel)을 읽으면 짧은 소리를 놓친다(#174).
 */
internal object NativeHapticInput : HapticInput {
    override fun takeFrame(out: FloatArray) = AudioEngine.takeHapticFrame(out)
}

/**
 * 캡처가 도는 동안 분류 결과와 소리 특징을 주기적으로 읽어 진동을 울린다.
 *
 * AI 파이프라인에 콜백을 넣지 않고 결과를 읽어 가는 방식이라 ai/ 코드를 건드리지 않는다.
 * 조용할 때는 100ms 마다, 소리 따라가 울리는 동안은 20ms 마다 판단한다([HapticTuning.tickPeriodMs]).
 * AI 결과는 0.25초마다 나오므로 100ms 로도 놓치지 않고, 소리 따라는 20ms 마다 봐야 소리가 시작된 곳을 바로 친다.
 *
 * 한 인스턴스는 한 번만 시작하고 한 번만 멈춘다.
 *
 * @param input 소리 특징. 실제로는 네이티브 누적값이고, 미리보기는 흉내 낸 소리를 넣는다.
 * @param configFor 라벨별 표시·진동 설정.
 * @param gated 실제 진동 알림이면 true. 설정 화면의 미리보기가 진동기를 잡고 있는 동안에는 보내지 않는다.
 * @param labelSource 가장 최근 분류 라벨. 결과가 아직 없으면 null. 마지막 인자라 `HapticNotifier(context) { … }` 로 쓴다.
 */
class HapticNotifier(
    context: Context,
    private val input: HapticInput = NativeHapticInput,
    private val configFor: (String) -> HapticPolicy.ClassConfig = LIVE_CONFIG,
    private val gated: Boolean = true,
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
    private val loop = HapticLoop(Build.VERSION.SDK_INT, player.hasAmplitudeControl)

    // 소리 따라는 20ms 마다 모양을 정하므로 늦게 깨면 친 곳이 밀린다. 배경 우선순위는 렌더·AI 가 바쁠 때 흔들린다.
    private val thread = HandlerThread("SV-Haptic", Process.THREAD_PRIORITY_DISPLAY)
    private val frame = FloatArray(HapticInput.FRAME_SIZE)
    private val debug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    @Volatile
    private var running = false

    @Volatile
    private var handler: Handler? = null

    private var nextDueUptime = 0L
    private var statsSince = 0L
    private var idleTicks = 0
    private var fastTicks = 0
    private var plans = 0
    private var skipped = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            input.takeFrame(frame)
            val plan = loop.onTick(
                now,
                labelSource(),
                frame[HapticInput.PEAK],
                frame[HapticInput.RMS],
                frame[HapticInput.TONE],
                frame[HapticInput.BUFFERS].toInt(),
                configFor
            )
            if (plan != null) issue(plan, now)

            val fast = loop.wantsFastTick(now)
            if (fast) fastTicks++ else idleTicks++
            if (debug && now - statsSince >= STATS_MS) {
                Log.d(TAG, "ticks idle=$idleTicks fast=$fastTicks plans=$plans skipped=$skipped")
                statsSince = now
                idleTicks = 0
                fastTicks = 0
                plans = 0
                skipped = 0
            }
            // 절대 시각으로 다음 틱을 잡는다. 틱마다 조금씩 밀리지 않고, 늦었으면 몰아서 따라잡지 않는다.
            val period = HapticTuning.tickPeriodMs(fast)
            nextDueUptime += period
            val up = SystemClock.uptimeMillis()
            if (nextDueUptime <= up) nextDueUptime = up + period
            handler?.postAtTime(this, nextDueUptime)
        }
    }

    /** 계획을 보내는 유일한 곳. 멈춘 뒤, 또는 미리보기가 진동기를 잡은 동안에는 보내지 않는다. */
    private fun issue(plan: HapticPlan, now: Long) {
        if (!running) return
        if (gated && now < HapticPreviewGate.busyUntilMs) {
            loop.onIssueSkipped(now)
            skipped++
            return
        }
        if (!player.playPlan(plan)) {
            loop.onIssueSkipped(now)
            skipped++
            return
        }
        plans++
        if (debug) Log.d(TAG, plan.summary(now))
    }

    fun start() {
        // 진동 모터가 없으면 스레드를 띄울 이유가 없다.
        if (!player.hasVibrator || handler != null) return
        running = true
        // 캡처를 켠 뒤(또는 화면이 켜진 뒤) 쌓인 값을 한 번 비운다. 안 비우면 첫 틱이 그동안 전체를 한 번에 본다.
        input.takeFrame(frame)
        thread.start()
        statsSince = SystemClock.elapsedRealtime()
        handler = Handler(thread.looper).also {
            nextDueUptime = SystemClock.uptimeMillis()
            it.post(tick)
        }
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
        // 기다림이 넘쳐도 플레이어를 잠금 안에서 닫으므로 이 뒤로는 아무것도 나가지 않는다.
        // 그래서 꺼짐 알림 진동이 늦은 진동에 끊기지 않는다(Android 11 이하).
        player.close()
    }
}

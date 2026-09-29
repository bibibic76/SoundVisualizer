package com.example.soundvisualizer.feedback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.soundvisualizer.feedback.HapticSimulator.Cue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 설정 화면의 진동 미리보기와 개발자 '진동 시험' 을 돌리고, 그동안 실제 진동 알림이 끼어들지 못하게 한다.
 *
 * 진동기는 앱 전체에 하나라 새 진동이 앞 진동을 끊는다. 시각화가 켜진 채 설정을 보면, 소리 따라가 보내는 계획이
 * 미리보기를 중간에 끊는다. 그래서 미리보기가 도는 동안 [busyUntilMs] 를 세우고, 진동 알림은 그때까지 보내지 않는다.
 *
 * 소리 따라 미리보기는 흉내 낸 소리를 실제 진동 알림과 **같은 경로**([HapticNotifier] + [SyntheticHapticInput])로
 * 돌린다. 그래서 미리보기가 실제와 똑같이 울리고, 소리는 나지 않는다.
 *
 * [busyUntilMs] 말고는 메인 스레드에서만 쓴다.
 */
object HapticPreviewGate {

    /** 이 시각(elapsedRealtime)까지 실제 진동 알림은 보내지 않는다. */
    @Volatile
    var busyUntilMs: Long = 0L
        private set

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val token = Any()
    private var notifier: HapticNotifier? = null
    private var sequencePlayer: HapticPlayer? = null
    private val ownerFlow = MutableStateFlow<String?>(null)

    /** 지금 도는 미리보기의 주인. 없으면 null. 저절로 끝나면 화면이 알 수 있게 흐름으로 둔다. */
    val owner: StateFlow<String?> = ownerFlow

    /** 한 번·두 번·길게 미리보기가 끝날 때까지 실제 진동 알림을 막는다. */
    fun holdFor(durationMs: Long) {
        busyUntilMs = SystemClock.elapsedRealtime() + durationMs + 100L
    }

    /**
     * [scene] 을 [label] 의 소리 따라로 [strength] 세기에 울린다. 장면이 끝나고 끝 페이드가 지나면 스스로 멈춘다.
     * @param owner 누가 틀었는지. 같은 주인만 [stopPreview] 로 멈출 수 있다(다른 줄이 사라질 때 이 미리보기를 끄지 않게).
     */
    fun startScene(context: Context, owner: String, scene: HapticScene, label: String, strength: HapticStrength) {
        stopPreview()
        ownerFlow.value = owner
        val cfg: (String) -> HapticPolicy.ClassConfig = { l ->
            HapticPolicy.ClassConfig(
                shown = true,
                haptic = HapticSettings(enabled = l == label, strength = strength, pattern = HapticPattern.Repeat)
            )
        }
        val n = HapticNotifier(
            context.applicationContext,
            input = SyntheticHapticInput(scene, SystemClock::elapsedRealtime),
            configFor = cfg,
            gated = false
        ) { label }
        notifier = n
        busyUntilMs = Long.MAX_VALUE
        n.start()
        val endAt = SystemClock.uptimeMillis() + scene.durationMs + HapticTuning.PREVIEW_TAIL_MS
        main.postAtTime({ stopPreview(owner) }, token, endAt)
    }

    /** 미리 정한 계획들을 시각에 맞춰 차례로 보낸다. 개발자 이음매 시험이 쓴다. */
    fun startSequence(context: Context, owner: String, cues: List<Cue>) {
        stopPreview()
        ownerFlow.value = owner
        val player = HapticPlayer(context.applicationContext)
        sequencePlayer = player
        busyUntilMs = Long.MAX_VALUE
        val t0 = SystemClock.uptimeMillis() + 50L
        var end = t0
        for (cue in cues) {
            main.postAtTime({ player.playPlan(cue.plan) }, token, t0 + cue.atMs)
            end = maxOf(end, t0 + cue.atMs + cue.plan.durationMs)
        }
        main.postAtTime({ stopPreview(owner) }, token, end + 100L)
    }

    /**
     * 미리보기를 멈춘다. [owner] 를 주면 그 주인이 튼 것만 멈춘다. 꺼짐 알림 직전에는 주인 없이 불러 무엇이든 멈춘다.
     */
    fun stopPreview(owner: String? = null) {
        if (owner != null && owner != ownerFlow.value) return
        main.removeCallbacksAndMessages(token)
        notifier?.stop()
        notifier = null
        sequencePlayer?.close()
        sequencePlayer = null
        ownerFlow.value = null
        busyUntilMs = SystemClock.elapsedRealtime()
    }
}

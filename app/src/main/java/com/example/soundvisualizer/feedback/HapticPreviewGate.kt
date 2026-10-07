package com.example.soundvisualizer.feedback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 설정 화면의 진동 미리보기(와 튜토리얼 진동 쪽의 진동, #323)를 울리고, 그동안 실제 진동 알림이 끼어들지 못하게 한다.
 *
 * 진동기는 앱 전체에 하나라 새 진동이 앞 진동을 끊는다. 설정 화면은 시각화가 실행 중이면 미리보기를 울리지 않지만(#244),
 * 미리보기를 튼 직후 빠른 설정 타일 등으로 시각화를 켜면 둘이 겹칠 수 있다. 그래서 미리보기가 도는 동안 [busyUntilMs] 를
 * 세우고, 진동 알림은 그때까지 보내지 않는다.
 *
 * [busyUntilMs] 말고는 메인 스레드에서만 쓴다.
 */
object HapticPreviewGate {

    /** 이 시각(elapsedRealtime)까지 실제 진동 알림은 보내지 않는다. */
    @Volatile
    var busyUntilMs: Long = 0L
        private set

    /** 미리보기가 끝난 뒤 진동 알림이 다시 보내기까지의 여유. 미리보기의 마지막 울림과 겹치지 않게 한다. */
    private const val MARGIN_MS = 100L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val token = Any()
    private var player: HapticPlayer? = null
    private var owner: String? = null

    /**
     * [plan] 을 미리보기로 울린다. 누가 튼 미리보기든 먼저 멈추고 진동기를 가져온다. 이 줄 것만 멈추면 다른 줄의
     * 미리보기가 겹쳐 돌고, 그 미리보기가 세운 막음을 줄여 실제 진동이 끼어든다(#232).
     * @param owner 누가 틀었는지. 같은 주인만 [stopPreview] 로 멈출 수 있다(다른 줄이 사라질 때 이 미리보기를 끄지 않게).
     */
    fun play(context: Context, owner: String, plan: HapticPlan) {
        stopPreview()
        val p = HapticPlayer(context.applicationContext)
        if (!p.playPlan(plan)) return
        player = p
        this.owner = owner
        busyUntilMs = SystemClock.elapsedRealtime() + plan.durationMs + MARGIN_MS
        // 저절로 끝날 때는 끊지 않고 놓기만 한다. 끊으면 막음이 풀리자마자 나간 실제 진동까지 끊을 수 있다.
        main.postAtTime({ release(owner) }, token, SystemClock.uptimeMillis() + plan.durationMs + MARGIN_MS)
    }

    /**
     * 미리보기를 멈춘다. [owner] 를 주면 그 주인이 튼 것만 멈춘다. 꺼짐 알림 직전에는 주인 없이 불러 무엇이든 멈춘다.
     */
    fun stopPreview(owner: String? = null) {
        if (owner != null && owner != this.owner) return
        player?.close()
        release(this.owner)
    }

    private fun release(owner: String?) {
        if (owner != this.owner) return
        main.removeCallbacksAndMessages(token)
        player = null
        this.owner = null
        busyUntilMs = SystemClock.elapsedRealtime()
    }
}

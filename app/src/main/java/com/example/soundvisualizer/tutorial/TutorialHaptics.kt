package com.example.soundvisualizer.tutorial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.soundvisualizer.SettingsManager
import com.example.soundvisualizer.feedback.HapticPlayer
import com.example.soundvisualizer.feedback.HapticPreviewGate

/**
 * 진동 쪽 그림 속 폰이 떨 때 진짜 폰도 같은 박자로 울린다(#323). 그림이 보여 주는 위협음 진동을 손으로도 느끼게 한다.
 *
 * 그림이 프레임마다 대본 시각을 [onFrame] 으로 넘기고, 새 울림([TutorialScript.vibrationPulse])이 시작된 첫 프레임에서
 * 한 번 울린다. 그래서 보이는 떨림과 어긋나지 않고, 그림이 멈추면(쪽을 넘김·멈춤 버튼·앱을 내림) 더 울리지 않는다.
 * 프레임이 한 울림(0.2초)을 통째로 건너뛰면 그 울림은 울리지 않는다. 그림도 그 울림을 그리지 못했다.
 *
 * 메인 스레드에서만 쓴다.
 *
 * @param canPlay 지금 울려도 되는지. 아니면 그 울림은 건너뛴다.
 * @param play 울림 하나를 보낸다.
 * @param cancel 울리던 것을 끊는다.
 */
internal class TutorialHaptics(
    private val canPlay: () -> Boolean,
    private val play: () -> Unit,
    private val cancel: () -> Unit
) {
    private var lastPulse = -1

    /** 그림이 새로 그릴 때. [t] 는 그림이 움직이기 시작한 뒤 흐른 초다. */
    fun onFrame(t: Float) {
        val pulse = TutorialScript.vibrationPulse(TutorialScene.Vibration, t)
        if (pulse < 0 || pulse == lastPulse) return
        lastPulse = pulse
        if (canPlay()) play()
    }

    /** 그림이 멈출 때. 울리던 것을 끊고, 다시 움직이면 처음부터 센다. */
    fun stop() {
        lastPulse = -1
        cancel()
    }
}

/**
 * 이 기기의 [TutorialHaptics]. 진동 모터가 없으면 null 이다(그림만 떤다).
 *
 * - 설정 화면의 진동 미리보기와 같은 길([HapticPreviewGate])로 울린다. 울리는 동안은 실제 진동 알림이 끼어들지 않고,
 *   멈출 때는 튜토리얼이 튼 것만 끊는다.
 * - 시각화가 실행 중이면 울리지 않는다. 진동기는 앱에 하나라 튜토리얼 진동이 실제 위협음 진동을 끊을 수 있다
 *   (설정의 미리보기와 같은 규칙, #244). 실행 중에 튜토리얼을 열었거나, 보는 도중 빠른 설정 타일로 켰을 때다.
 */
@Composable
internal fun rememberTutorialHaptics(): TutorialHaptics? {
    val context = LocalContext.current
    return remember(context) {
        val player = HapticPlayer(context)
        if (!player.hasVibrator) return@remember null
        val plan = TutorialScript.vibrationPulsePlan(player.hasAmplitudeControl)
        TutorialHaptics(
            canPlay = { !SettingsManager.isServiceRunning.value },
            play = { HapticPreviewGate.play(context, OWNER, plan) },
            cancel = { HapticPreviewGate.stopPreview(owner = OWNER) }
        )
    }
}

/** [HapticPreviewGate] 에 알리는 주인 이름. 설정의 진동 줄(소리 종류 라벨)과 겹치지 않는다. */
private const val OWNER = "tutorial"

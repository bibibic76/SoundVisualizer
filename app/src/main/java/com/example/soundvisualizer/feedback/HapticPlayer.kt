package com.example.soundvisualizer.feedback

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 진동을 울린다. 캡처 중 알림과 설정 화면의 미리보기가 같은 파형을 쓰도록 한곳에 둔다.
 * Vibrator 는 시스템 서비스라 여러 스레드에서 불러도 된다.
 */
class HapticPlayer(context: Context) {

    private val vibrator: Vibrator? = obtainVibrator(context.applicationContext)

    /** 진동 모터가 있는지. 없으면 [play] 는 아무것도 하지 않는다. */
    val hasVibrator: Boolean = vibrator?.hasVibrator() == true

    /** 세기 조절이 되는지. 안 되면 세기와 상관없이 기본 세기로 울린다. */
    val hasAmplitudeControl: Boolean = hasVibrator && vibrator?.hasAmplitudeControl() == true

    fun play(pattern: HapticPattern, strength: HapticStrength) {
        val v = vibrator ?: return
        if (!hasVibrator) return
        val effect = buildEffect(pattern, strength)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VIBRATION_USAGE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, LEGACY_AUDIO_ATTRIBUTES)
        }
    }

    fun cancel() {
        vibrator?.cancel()
    }

    private fun buildEffect(pattern: HapticPattern, strength: HapticStrength): VibrationEffect {
        // 반복은 HapticPolicy 가 간격마다 다시 부르는 방식이다. OS 반복을 쓰면 멈출 때 따로 끊어야 한다.
        val timings = when (pattern) {
            HapticPattern.Tap -> TAP
            HapticPattern.DoubleTap, HapticPattern.Repeat -> DOUBLE_TAP
            HapticPattern.Hold -> HOLD
        }
        return if (hasAmplitudeControl) {
            // 짝수 칸은 쉼, 홀수 칸은 울림
            val amplitudes = IntArray(timings.size) { i -> if (i % 2 == 1) strength.amplitude else 0 }
            VibrationEffect.createWaveform(timings, amplitudes, NO_REPEAT)
        } else {
            VibrationEffect.createWaveform(timings, NO_REPEAT)
        }
    }

    private companion object {
        const val NO_REPEAT = -1

        /** [쉼, 울림, 쉼, 울림 ...] ms */
        val TAP = longArrayOf(0, 60)
        val DOUBLE_TAP = longArrayOf(0, 60, 80, 60)
        val HOLD = longArrayOf(0, 350)

        /**
         * 접근성 용도로 울린다. 무음 모드나 백그라운드에서 막히는 기기가 확인되면
         * [VibrationAttributes.USAGE_ALARM] / [AudioAttributes.USAGE_ALARM] 으로 바꾼다.
         */
        const val VIBRATION_USAGE = VibrationAttributes.USAGE_ACCESSIBILITY

        val LEGACY_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .build()

        fun obtainVibrator(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                context.getSystemService(Vibrator::class.java)
            }
    }
}

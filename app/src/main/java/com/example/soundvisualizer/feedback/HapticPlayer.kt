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
        val timings = when (pattern) {
            HapticPattern.Tap -> TAP
            HapticPattern.DoubleTap, HapticPattern.Repeat -> DOUBLE_TAP
            HapticPattern.Hold -> HOLD
        }
        vibrate(timings, strength)
    }

    /**
     * 시각화가 뜻하지 않게 꺼졌을 때의 진동. 소리 종류별 진동 설정과 상관없이 울린다.
     *
     * 사용자가 소리 종류에 고를 수 있는 패턴(한 번·두 번·길게·반복)과 겹치면 위협음 진동으로 착각하므로,
     * 그 어느 것과도 다른 "길게 세 번"을 가장 센 세기로 울린다.
     * 진동 알림을 멈출 때 부르는 [cancel] 은 이 진동까지 끊으므로, 그보다 뒤에 불러야 끝까지 울린다.
     *
     * 알람 용도로 울린다. 캡처 서비스가 포그라운드에서 내려오는 순간이라, 접근성 용도로 울리면 안드로이드가 백그라운드 앱의
     * 진동으로 보고 버릴 수 있다(백그라운드에서는 알람·알림·벨소리 같은 용도만 허용한다).
     * 알림 용도는 버전에 따라 무음 모드나 절전 모드에서 막히므로 알람을 쓴다.
     */
    fun playStoppedAlert() {
        vibrate(STOPPED_ALERT, HapticStrength.Strong, alarm = true)
    }

    fun cancel() {
        vibrator?.cancel()
    }

    /** @param alarm 알람 용도로 울릴지. 아니면 접근성 용도다. */
    private fun vibrate(timings: LongArray, strength: HapticStrength, alarm: Boolean = false) {
        val v = vibrator ?: return
        if (!hasVibrator) return
        val effect = buildEffect(timings, strength)
        // 소리 종류별 진동은 접근성 용도로 울린다. 캡처 서비스가 떠 있거나 설정 화면을 보는, 앱이 포그라운드일 때만 울리기 때문이다.
        // 무음 모드나 백그라운드에서 막히는 기기가 확인되면 두 갈래 모두 알람 용도로 바꾼다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 상수도 API 33 에 생겼으므로 버전 확인 안에서만 쓴다.
            // 용도 값을 변수로 넘기면 Lint(WrongConstant)가 허용 값인지 따라가지 못할 수 있어 갈래마다 상수로 만든다.
            val attributes = if (alarm) {
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
            } else {
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY)
            }
            v.vibrate(effect, attributes)
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, if (alarm) LEGACY_ALARM_ATTRIBUTES else LEGACY_AUDIO_ATTRIBUTES)
        }
    }

    private fun buildEffect(timings: LongArray, strength: HapticStrength): VibrationEffect {
        // 반복은 HapticPolicy 가 간격마다 다시 부르는 방식이다. OS 반복을 쓰면 멈출 때 따로 끊어야 한다.
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
        val STOPPED_ALERT = longArrayOf(0, 300, 150, 300, 150, 300)

        val LEGACY_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .build()

        val LEGACY_ALARM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        fun obtainVibrator(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                context.getSystemService(Vibrator::class.java)
            }
    }
}

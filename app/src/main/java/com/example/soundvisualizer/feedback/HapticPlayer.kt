package com.example.soundvisualizer.feedback

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/** Android 10 이 이보다 짧은 진동을 터치 진동으로 본다(AOSP android10 `VibratorService.MAX_HAPTIC_FEEDBACK_DURATION`). */
internal const val ANDROID_10_TOUCH_FEEDBACK_MS = 5_000L

/**
 * 계획([HapticPlan])을 진동기로 보낸다. 캡처 중 알림과 설정 화면의 미리보기가 모두 이것을 쓴다.
 * Vibrator 는 시스템 서비스라 여러 스레드에서 불러도 된다.
 *
 * 모든 `vibrate()` 는 한 잠금 안에서 [close] 여부를 확인한 뒤에만 부른다. 진동 알림을 멈출 때 [close] 가 같은 잠금을
 * 잡으므로, 스레드가 늦게 끝나도(Android 10·11 에서 200ms 기다림이 넘쳐도) 멈춘 뒤에 진동이 나가지 않는다.
 * 그래서 꺼짐 알림 진동이 늦은 진동 하나에 덮이지 않는다.
 *
 * 반복 효과는 쓰지 않는다. 같은 우선순위에서 반복 효과가 울리는 동안에는 반복이 아닌 새 진동이 무시되어, 방식을 바꾸거나
 * 미리보기를 틀어도 먹힌다. 또 반복 효과는 끄는 쪽이 한 번 빠지면 끝없이 울린다. 연속 진동도 끝이 있는 울림을 이어 보낸다.
 */
class HapticPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val vibrator: Vibrator? = obtainVibrator(appContext)
    private val lock = Any()
    private var closed = false

    @Volatile
    private var loggedInvalid = false

    /** 진동 모터가 있는지. 없으면 아무것도 하지 않는다. */
    val hasVibrator: Boolean = vibrator?.hasVibrator() == true

    /** 세기 조절이 되는지. 안 되면 세기와 상관없이 기본 세기로 울린다. */
    val hasAmplitudeControl: Boolean = hasVibrator && vibrator?.hasAmplitudeControl() == true

    /** 계획 하나를 접근성 용도로 울린다. 보내지 못하면 false. 효과는 잠금 밖에서 만든다. */
    fun playPlan(plan: HapticPlan): Boolean {
        val effect = buildEffect(plan) ?: return false
        return vibrate(effect, alarm = false)
    }

    /**
     * 시각화가 뜻하지 않게 꺼졌을 때의 진동. 소리 종류별 진동 설정과 상관없이 울린다.
     *
     * 사용자가 소리 종류에 고를 수 있는 방식(느림·중간·빠름·연속)과 겹치면 위협음 진동으로 착각하므로,
     * 그 어느 것과도 다른 "길게 세 번"을 가장 센 세기로 울린다([HapticShapes.STOPPED_ALERT]).
     *
     * 알람 용도로 울린다. 캡처 서비스가 아직 포그라운드일 때 부르므로 백그라운드 진동 규칙에 걸리지는 않지만,
     * 절전 모드에서는 접근성 용도가 막히는 버전(Android 14 이하)이 있고, **Android 12(API 31) 이상에서는**
     * 알람 용도가 소리 종류별 진동(접근성)보다 우선순위가 높아 뒤늦게 결정된 진동 한 번에 끊기지 않는다.
     * 그 아래(Android 10·11)는 [HapticNotifier.stop] 이 진동 알림의 플레이어를 닫아, 멈춘 뒤로는 진동 알림이 아무것도
     * 보내지 못하게 한다. 대신 방해 금지 모드에서 알람을 허용하지 않았거나 알람 진동을 꺼 두면 이 진동은 울리지 않는다
     * (접근성 용도는 그 설정들을 타지 않는다). 그때는 알림과 홈 안내가 알린다.
     */
    fun playStoppedAlert() {
        val effect = buildEffect(HapticShapes.STOPPED_ALERT) ?: return
        vibrate(effect, alarm = true)
    }

    /** 지금 울리는 것을 끊는다. 연속 진동의 소리가 끝났을 때 쓴다. 닫은 뒤에는 아무것도 하지 않는다. */
    fun cancel() {
        synchronized(lock) {
            if (closed) return
            vibrator?.cancel()
        }
    }

    /** 닫는다. 지금 울리는 것을 끊고, 이 플레이어로는 다시 울리지 않는다. */
    fun close() {
        synchronized(lock) {
            closed = true
            vibrator?.cancel()
        }
    }

    /** 기기의 진동 능력을 한 줄로. 디버그 빌드 로그용. 폰마다 무엇이 되는지 확인하는 데 쓴다. */
    fun capabilityLine(): String {
        val v = vibrator ?: return "vibrator=none"
        val sb = StringBuilder("sdk=${Build.VERSION.SDK_INT} vib=$hasVibrator amp=$hasAmplitudeControl")
        val power = appContext.getSystemService(PowerManager::class.java)
        sb.append(" powerSave=${power?.isPowerSaveMode}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            sb.append(" resonantHz=${v.resonantFrequency} q=${v.qFactor}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val prims = v.arePrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                VibrationEffect.Composition.PRIMITIVE_THUD
            )
            sb.append(" primitives(click,tick,lowTick,thud)=${prims.joinToString(",")}")
        }
        if (Build.VERSION.SDK_INT >= 36) {
            sb.append(" envelope=${v.areEnvelopeEffectsSupported()}")
        }
        return sb.toString()
    }

    /** 계획을 진동 효과로. 잘못된 계획(버그)이면 한 번 로그를 남기고 null. 앱을 죽이지 않는다. */
    internal fun buildEffect(plan: HapticPlan): VibrationEffect? = try {
        val sent = forPlatform(plan)
        if (hasAmplitudeControl && !sent.binary) {
            VibrationEffect.createWaveform(sent.timings, sent.amplitudes, NO_REPEAT)
        } else {
            VibrationEffect.createWaveform(sent.toOnOffTimings(), NO_REPEAT)
        }
    } catch (e: IllegalArgumentException) {
        if (!loggedInvalid) {
            loggedInvalid = true
            Log.w(TAG, "invalid plan ${plan.summary(0)}", e)
        }
        null
    }

    /**
     * Android 10 은 벨소리·알림이 아닌 진동 가운데 5초보다 짧은 것을, 넘긴 용도(접근성·알람)와 상관없이 터치 진동으로
     * 본다(AOSP android10 `VibratorService.Vibration.isHapticFeedback`). 그러면 폰 설정의 '터치 진동' 세기를 따라,
     * 그것을 꺼 둔 사람에게는 박자 진동·미리보기·멈춤 알림이 조용히 울리지 않는다(#302). 그래서 끝에 쉼을 붙여 5초를
     * 넘긴다. 다음 박자가 오면 시스템이 이 진동을 끊고 새로 울리므로 느낌은 같다. Android 11 부터는 용도로 분류해
     * 길이를 보지 않는다.
     */
    private fun forPlatform(plan: HapticPlan): HapticPlan =
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) plan.withSilentTail(ANDROID_10_TOUCH_FEEDBACK_MS) else plan

    /** @param alarm 알람 용도로 울릴지. 아니면 접근성 용도다. */
    private fun vibrate(effect: VibrationEffect, alarm: Boolean): Boolean {
        val v = vibrator ?: return false
        if (!hasVibrator) return false
        synchronized(lock) {
            if (closed) return false
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
            return true
        }
    }

    private companion object {
        const val TAG = "SvHaptic"
        const val NO_REPEAT = -1

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

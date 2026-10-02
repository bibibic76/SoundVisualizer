package com.example.soundvisualizer.feedback

import kotlin.math.roundToLong

/**
 * 진동의 모든 수치를 한곳에 둔다. 폰에서 느껴 보고 고칠 곳은 이 파일 하나다.
 *
 * 테스트는 이 값들의 **관계**(느림은 중간의 절반 빠르기, 빠름은 두 배, 세기는 올릴수록 세다 등)를 확인하고
 * 숫자 자체는 묻지 않는다. 그래서 체감 조정은 여기만 바꾸면 된다.
 *
 * "소리가 나는가"(0.01)와 사건 여유(400ms)는 AI 쪽도 참조하는 기존 값이라 [HapticPolicy] 에 그대로 둔다.
 *
 * 근거가 되는 사실: 안드로이드는 `vibrate()` 를 새로 받을 때마다 앞 진동을 끝내고 새로 켠다. 모터가 도는 도중에
 * 새로 보내면 그 틈이 끊김으로 느껴질 수 있다. 그래서 느림·중간·빠름은 모터가 쉬는 사이에만 보내고(한 번 울림씩),
 * 연속은 긴 울림 하나를 보내 다시 보내는 횟수를 줄인다.
 */
object HapticTuning {

    // ---------------- 틱 ----------------

    /**
     * 울릴지 판단하는 주기. AI 결과는 250ms 이상마다 나오므로 이것으로 충분하다. 느림·중간·빠름의 울림은 이 주기와
     * 상관없이 제 시각에 맞춰 깨어나 보낸다([HapticDriver.nextWakeMs]).
     */
    const val IDLE_TICK_MS = 100L

    // ---------------- 박자 ----------------

    /** 중간의 한 박자. 느림은 이것의 두 배(절반 빠르기), 빠름은 절반(두 배 빠르기)이다. */
    const val MEDIUM_PERIOD_MS = 500L

    /** 한 박자 중 울리는 몫. 나머지는 쉰다. 쉼이 울림보다 길어야 박자가 또렷하다. */
    const val ON_RATIO = 0.4f

    /** 한 박자의 길이. 꺼짐과 연속은 박자가 없어 0. */
    fun periodMs(mode: HapticMode): Long = when (mode) {
        HapticMode.Slow -> MEDIUM_PERIOD_MS * 2
        HapticMode.Medium -> MEDIUM_PERIOD_MS
        HapticMode.Fast -> MEDIUM_PERIOD_MS / 2
        HapticMode.Off, HapticMode.Continuous -> 0L
    }

    /** 한 박자 중 울리는 시간. */
    fun onMs(mode: HapticMode): Long = (periodMs(mode) * ON_RATIO).roundToLong()

    // ---------------- 연속 ----------------

    /**
     * 연속은 이만큼 긴 울림 하나를 보내고, 끝나기 전에 다시 보낸다. 반복 효과는 쓰지 않으므로([HapticPlayer]) 앱이
     * 멈춰도 이 시간 안에 저절로 멈춘다. 소리가 끝나면 바로 끊는다.
     *
     * **다시 보낼 때마다 이음매가 생긴다.** 안드로이드는 새 진동을 받으면 앞 진동을 끝내고 모터를 다시 켠다. 2초였을 때는
     * 1.8초마다 끊겼다 다시 울리는 것처럼 느껴졌다(#286). 그래서 이음매가 30초에 한 번만 생기게 길게 잡는다.
     *
     * **대가(#262).** 알림이나 다른 앱의 진동이 오면 안드로이드가 우리 울림을 끊기도 한다(Android 14 이상은 알림 진동이
     * 접근성 진동보다 우선이고, 10~13 은 키보드·터치 진동에도 끊는다). 진동 상태를 보는 API 는 시스템 앱 전용이라 앱은
     * 끊긴 줄 모르고, 다음에 다시 보낼 때까지 최대 이 시간만큼 조용하다. 사용자가 "거의 끊김 없이"(30초에 한 번 이음매,
     * 끊겨도 30초 안에 다시 울림)를 골랐다.
     *
     * 반복 효과로 바꿔도 이음매는 없어지지 않는다. Android 16 은 반복 진동도 5초(한 번 울림이 더 길면 그 길이)마다 모터를
     * 다시 켠다(`SetAmplitudeVibratorStep.REPEATING_EFFECT_ON_DURATION`).
     */
    const val CONTINUOUS_CHUNK_MS = 30_000L

    /** 연속 울림이 이만큼 남으면 다시 보낸다. 판단 주기([IDLE_TICK_MS])가 한 번 늦어도 끊기지 않을 만큼. */
    const val CONTINUOUS_REFILL_MS = 200L

    // ---------------- 종류를 모를 때 (#225) ----------------

    /**
     * AI 를 쓸 수 없는 실행에서 이 피크 이상이면 "큰 소리" 로 보고 위협음 설정으로 울린다(-12dBFS).
     *
     * 소리가 난다는 기준([HapticPolicy.LEVEL_THRESHOLD], 0.01)으로 울리면 게임·영상이 켜져 있는 내내 울린다.
     * 내부 소리는 미디어 볼륨이 곱해진 뒤라 볼륨을 낮춰 두면 이 크기에 닿지 않을 수 있다. 폰 체감 확인에서 정한다.
     */
    const val UNLABELED_LOUD_LEVEL = 0.25f

    /**
     * 종류를 모르는 큰 소리는 피크가 [UNLABELED_LOUD_LEVEL] 의 이 비율 아래로 0.4초 넘게 내려가야 끝난다(#232).
     *
     * 소리가 난다는 기준(0.01)으로 끝내면 게임 음악처럼 끊기지 않는 배경음 내내 울린다. 큰 소리 기준 그대로 끝내면
     * 그 근처를 오르내리는 음악에 끊겼다 울렸다 한다. 폰 체감 확인에서 함께 정한다.
     */
    const val UNLABELED_RELEASE_RATIO = 0.5f

    // ---------------- 세기 ----------------

    /**
     * 가장 약한 세기(10%)의 진폭. 진동자는 이보다 약하면 잘 느껴지지 않고, 폰의 진동 세기 설정이 한 번 더 줄인다.
     * 가장 센 세기(100%)는 255 이다.
     */
    const val MIN_AMPLITUDE = 40

    /** 세기(%)를 진폭(1..255)으로. [HapticSettings.MIN_LEVEL] 이 [MIN_AMPLITUDE], [HapticSettings.MAX_LEVEL] 이 255. */
    fun amplitude(level: Int): Int {
        val l = level.coerceIn(HapticSettings.MIN_LEVEL, HapticSettings.MAX_LEVEL)
        val span = HapticSettings.MAX_LEVEL - HapticSettings.MIN_LEVEL
        return Math.round(MIN_AMPLITUDE + (255 - MIN_AMPLITUDE) * (l - HapticSettings.MIN_LEVEL) / span.toFloat())
    }

    // ---------------- 미리보기 ----------------

    /** 설정에서 방식이나 세기를 바꾸면 이만큼 울려 본다. 느림도 두 번은 울린다. */
    const val PREVIEW_MS = 2000L
}

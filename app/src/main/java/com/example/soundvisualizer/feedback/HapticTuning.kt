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

    // ---------------- 외부 사운드 모드: 자기 진동 (#290) ----------------

    /**
     * 울림이 끝난 뒤 이만큼은 마이크가 그 울림을 아직 들을 수 있다고 본다([SelfVibrationGate]). 박자 방식은 쉼이 짧으면
     * 이보다 줄인다([selfHearingGuardMs]).
     *
     * **재 본 값이 아니다.** 모터가 명령보다 늦게 멈추는 시간, 모터가 잦아들며 책상을 울리는 시간, 마이크 입력이 늦게
     * 들어오는 시간, 캡처 버퍼 하나(48kHz 에서 10.7ms)를 모두 덮어야 한다. S25+ 에서 앱의 울림은 명령보다 16~55ms 늦게
     * 끝났다(`dumpsys vibrator_manager`). 짧으면 자기 진동 소리가 쉼에 새어 들어와 진동이 다시 이어진다.
     */
    const val SELF_HEARING_GUARD_MAX_MS = 180L

    /** 박자 방식에서 쉼 끝에 남겨 두는 최소한의 듣는 시간. 캡처 버퍼 서너 개다. */
    const val SELF_HEARING_MIN_LISTEN_MS = 40L

    /**
     * 막아 둔 구간이 끝나는 시각보다 이만큼 늦게 깨어난다. 다음 틱은 두 시계(uptime·elapsedRealtime)를 오가며 잡히므로
     * 1~2ms 일찍 깨어날 수 있다. 일찍 깨어나도 잘못 읽지는 않지만(구간은 지난번에 읽은 시각으로 가른다), 섞인 값을 버리러
     * 한 번 더 깨어나야 한다. 그 한 번을 아낀다.
     */
    const val SELF_HEARING_REOPEN_SLACK_MS = 2L

    /**
     * 이어 준 크기([SelfVibrationGate])를 묶음 첫 울림의 꼬리가 끝나기 이만큼 전보다 늦은 소리로 치지 않는다.
     *
     * 느림은 꼬리 끝(울림 0.4초 + 여유 0.18초)에 소리가 끝났다고 보는 여유(0.4초)를 더하면 다음 박자 0.02초 앞이다. 꼬리 끝
     * 바로 앞에서 시작한 구간을 그 시각의 소리로 치면, 울림을 보내는 데 21ms 넘게 걸리는 폰에서는 소리가 끝난 뒤 거의 늘
     * 0.4초짜리 울림이 한 번 더 나간다. 이만큼 막으면 여유가 0.1초가 된다. 박자에 맞춰 깨어나면 마지막으로 이어 주는 틱이
     * 꼬리 끝 0.08초 전(느림·중간)이라 평소에는 그대로다. 0.1초로 늘리면 그 틱까지 당겨져 느림의 말소리 박자가 더
     * 어긋났다(모델에서 12%→16%).
     */
    const val SELF_HEARING_CREDIT_LEAD_MS = 80L

    /**
     * 그 방식의 울림 뒤에 소리를 듣지 않는 시간. 쉼이 [SELF_HEARING_MIN_LISTEN_MS] 만큼은 남게 [SELF_HEARING_GUARD_MAX_MS]
     * 에서 줄인다. 느림·중간은 180ms, 빠름은 쉼이 150ms 라 110ms 다. 꺼짐·연속은 박자가 없어 최대값이다.
     *
     * 외부 사운드 모드에서는 종류마다의 상한([HapticSettings.externalCap], #354) 때문에 느림·중간만 울린다. 빠름·연속의
     * 값은 쓰이지 않지만 모든 방식에 값을 준다.
     */
    fun selfHearingGuardMs(mode: HapticMode): Long = when (mode) {
        HapticMode.Slow, HapticMode.Medium, HapticMode.Fast ->
            minOf(SELF_HEARING_GUARD_MAX_MS, periodMs(mode) - onMs(mode) - SELF_HEARING_MIN_LISTEN_MS)
        HapticMode.Off, HapticMode.Continuous -> SELF_HEARING_GUARD_MAX_MS
    }

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

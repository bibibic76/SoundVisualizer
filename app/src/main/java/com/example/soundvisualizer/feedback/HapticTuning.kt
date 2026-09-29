package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification

/**
 * 진동의 모든 수치를 한곳에 둔다. 폰에서 느껴 보고 고칠 곳은 이 파일 하나다.
 *
 * 테스트는 이 값들의 **관계**(세기 단계가 1.4배 이상 벌어지는지, 이음매 기준보다 음높이 단계가 넓은지 등)를 확인하고
 * 숫자 자체는 묻지 않는다. 그래서 체감 조정은 여기만 바꾸면 된다. 조정 순서는 [LEVEL_STEP_DB] → [DUCK_MS] →
 * 세기별 바닥([followLevels]) → [STRIKE_OVERDRIVE] → [THROB_DEPTH_DB] → [REMINDER_MS] 가 좋다.
 *
 * "소리가 나는가"(0.01), 한 번·두 번·길게의 사건 여유(400ms), 쿨다운(2초)은 AI 쪽도 참조하는 기존 값이라
 * [HapticPolicy] 에 그대로 둔다.
 *
 * 근거가 되는 사실 두 가지:
 *  - 안드로이드는 `vibrate()` 를 새로 받을 때마다 앞 진동을 끝내고(HAL off) 새로 켠다(HAL on). 모터가 멈췄다
 *    다시 도는 이 틈이 "징징 끊기는" 느낌의 원인이다. 파형 **하나 안에서** 세기만 바뀌는 것은 끊기지 않는다.
 *  - 진동자(LRA)는 세기 차이가 1.4배(약 2.9dB)는 되어야 다르게 느낀다.
 */
object HapticTuning {

    // ---------------- 틱 ----------------

    /** 소리 따라가 울리지 않을 때의 판단 주기. 예전과 같다. AI 결과는 250ms 이상마다 나온다. */
    const val IDLE_TICK_MS = 100L

    /** 소리 따라가 울리는 동안의 판단 주기. 계획 한 칸(20ms)과 같아서 소리가 시작된 뒤 20ms 안에 친다. */
    const val FOLLOW_TICK_MS = 20L

    fun tickPeriodMs(fast: Boolean): Long = if (fast) FOLLOW_TICK_MS else IDLE_TICK_MS

    /** 이만큼 버퍼가 한 개도 오지 않으면 캡처가 멈춘 것으로 보고 조용함으로 친다. 버퍼는 늦어도 ~43ms 마다 온다. */
    const val NO_DATA_MS = 200L

    // ---------------- 세션 ----------------

    /**
     * 소리가 이만큼 끊겨야 소리 따라 세션이 끝난다. T3 화재경보(0.5초 울림, 1.5초 쉼)와 말 사이의 쉼을 한 세션으로 묶는다.
     * 모터는 소리가 끊기는 순간 쉬고, 세션과 빠른 틱만 이만큼 남는다.
     */
    const val FOLLOW_RELEASE_MS = 2000L

    /**
     * 세션 중 다른 종류로 판정된 소리가 이만큼 이어져야 세션을 넘겨준다. AI 결과 두 번 정도의 흔들림은 넘긴다.
     * 소리가 나는 동안만 센다. 위협음은 기다리지 않는다.
     */
    const val LABEL_GRACE_MS = 600L

    // ---------------- 소리 분석 ----------------

    /** 봉투의 내려가는 시간상수. 4~8Hz 음절의 골을 살린다. 이보다 빠른 것은 진동자의 잔떨림(20~50ms)이 가린다. */
    const val RELEASE_TAU_MS = 80f

    /** 느린 봉투. 한결같은 소리인지 가리고, 비·배경음을 매끄러운 바닥으로 깐다. */
    const val SLOW_TAU_MS = 300f

    /**
     * 동적 천장. 최근 가장 큰 소리를 1초 붙잡았다가 초당 6dB 씩 내린다. 미디어 볼륨에 따라 캡처 크기가 달라지므로
     * 절대 크기가 아니라 천장 아래 몇 dB 인지로 세기를 정한다. -45dBFS 밑으로는 내리지 않아 작은 잡음을 키우지 않는다.
     */
    const val CEIL_HOLD_MS = 1000L
    const val CEIL_FALL_DB_PER_S = 6f
    const val CEIL_FLOOR_DBFS = -45f

    /** 천장 아래 이만큼을 진동 세기 범위(바닥~지속)에 옮긴다. 말·음악의 30dB 폭을 진동의 6~11dB 에 3:1 로 누른다. */
    const val WINDOW_DB = 30f

    /** 절대 조용함. 천장보다 [WINDOW_DB] 이상 작아도 조용함으로 본다. */
    const val SILENCE_DBFS = -60f

    /** 소리가 시작됐는지는 최근 이만큼 중 가장 작았던 크기와 비교한다. */
    const val ONSET_LOOKBACK_MS = 100L

    /** 천장보다 이만큼 이상 작은 곳에서 오른 소리는 치지 않는다. */
    const val ONSET_MIN_REL_DB = 20f

    /** 친 뒤 이만큼은 다시 치지 않는다. 진동자의 톡은 ~100ms 이상 떨어져야 따로 느껴진다. */
    const val ACCENT_REFRACTORY_MS = 120L

    /** 느린 봉투가 이 폭 안에 머물면 "한결같다". 조용해지거나 이만큼 바뀌면 다시 센다. */
    const val STEADY_BAND_DB = 6f

    /** 한결같음이 이만큼 이어져야 한결같은 소리로 다룬다. */
    const val STEADY_MIN_MS = 500L

    // ---------------- 한결같은 소리 ----------------

    /**
     * 한결같은 소리는 6dB 까지 잔잔해진다(시간상수 1.5초). 계속 같은 세기로 울리면 손이 무뎌지고,
     * 청각장애인 연구에서도 쉬지 않는 진동은 성가시다는 답이 많았다. 그래도 바닥 아래로는 내리지 않는다.
     */
    const val STEADY_DROP_DB = 6f
    const val STEADY_TAU_MS = 1500f

    /** 이만큼 한결같으면 2초에 걸쳐 바닥까지 내린다. 멈추지는 않는다. */
    const val LONG_STEADY_MS = 10_000L
    const val LONG_STEADY_RAMP_MS = 2000L

    /** 한결같은 소리 동안 가볍게 톡 치는 간격. 이어지는 진동보다 끊어 치는 진동이 알림으로 낫다. 오래 이어지면 늘린다. */
    const val REMINDER_MS = 2500L
    const val REMINDER_LONG_MS = 3000L

    /** 알림 톡의 세기(최고 세기에 대한 배율). 잔잔해진 바닥보다 2배 이상 세다. */
    const val REMINDER_STRIKE = 0.8f

    // ---------------- 음높이 (사이렌) ----------------

    /** 영교차율(샘플당)이 이 범위일 때만 음높이로 본다. 48kHz 에서 120Hz~3.6kHz. 잡음과 킥의 저음은 밖이다. */
    const val TONE_MIN = 0.005f
    const val TONE_MAX = 0.15f

    /** 최근 이만큼의 빠른 틱 중 [TONAL_MIN_VALID] 이상이 음높이를 갖고, 틱 사이 변화의 중앙값이 작아야 음이 있는 소리다. */
    const val TONAL_WINDOW_MS = 500L
    const val TONAL_MIN_VALID = 0.8f
    const val TONAL_MAX_MEDIAN_DP_OCT = 0.15f

    /** 최근 5초의 음높이 폭이 1/6 옥타브 이상이고, 음높이를 1초 이상 모았어야 음높이를 진동에 싣는다. 하이로 폭은 0.415 옥타브다. */
    const val PITCH_RANGE_WINDOW_MS = 5000L
    const val PITCH_MIN_RANGE_OCT = 1f / 6f
    const val PITCH_MIN_WINDOW_MS = 1000L

    /** 이만큼 안에 소리가 새로 시작된 적이 있으면 음높이를 싣지 않는다. 박이 있는 음악은 크기로 따라간다. */
    const val PITCH_NO_ONSET_MS = 1000L

    /** 음높이 단계 가장 위는 바닥보다 최소 이만큼 위. 단계 사이는 최소 이만큼. 이음매 기준([LEVEL_STEP_DB])보다 넓다. */
    const val PITCH_TOP_MIN_DB = 9f
    const val PITCH_MIN_SPACING_DB = 4.3f

    /** 음높이 단계 경계에서 떨지 않게 하는 여유(0~1 척도). */
    const val QUANT_HYST = 0.08f

    /** 음높이 때문에 다시 보내는 것은 초당 4번까지. 그보다 빠른 움직임은 FAST(두근거림)로 다룬다. */
    const val PITCH_MIN_SEND_GAP_MS = 250L

    /** 방향이 바뀐 것으로 치는 음높이 움직임, 그리고 1초에 이만큼 바뀌면 빠른 사이렌(옐프: 초당 6번). */
    const val REV_HYST_OCT = 0.15f
    const val FAST_MIN_REVERSALS = 4

    /** 빠른 사이렌의 두근거림 깊이(꼭대기와 골의 차이)와 한 칸 길이, 다시 채우는 시점. */
    const val THROB_DEPTH_DB = 8f
    const val THROB_STEP_MS = 40L
    const val THROB_REFILL_MS = 400L

    /** 하이로처럼 음이 계단으로 바뀌면 톡 친다. 이만큼 뛰고, 앞 4틱이 이만큼 안에 머물렀어야 계단이다. */
    const val STEP_MIN_OCT = 0.2f
    const val STEP_STABLE_OCT = 0.08f
    const val STEP_TICK_WINDOW_MS = 60L

    // ---------------- 다시 보내기 (이음매) ----------------

    /**
     * **이음매 기준.** 울리는 도중에 새 계획을 보낼 때는 그 첫 칸이 지금 울리는 세기와 이만큼 이상 달라야 한다.
     * 모터가 멈췄다 도는 틈이 어차피 느껴지는 변화 속에 숨는다. 한결같은 곳에서는 절대 다시 보내지 않는다.
     * 폰에서 틈이 안 느껴지면 낮추고, 느껴지면 올린다.
     */
    const val LEVEL_STEP_DB = 4f

    /** 올리기·내리기 판단에 쓰는 창. 창 안의 최소·최대로 판단해 떨림을 막는다. */
    const val MEASURE_WINDOW_MS = 40L

    /** 올리기는 앞 계획 뒤 150ms 부터(친 뒤 이어지는 소리인지 본다). 내리기는 40ms 부터. */
    const val UP_MIN_GAP_MS = 150L
    const val DOWN_MIN_GAP_MS = 40L

    /** 계획이 곧 스스로 내려갈 예정이면 내리기를 보내지 않는다. */
    const val DROP_LOOKAHEAD_MS = 100L

    // ---------------- 계획 모양 ----------------

    /** 계획 한 칸. 진동자가 올라오는 시간(5~15ms)보다 길다. */
    const val STEP_MS = 20L

    /** 친 소리는 이 시간상수로 스스로 잦아든다(톡-음). 이보다 작아지면 끝낸다. */
    const val DECAY_TAU_MS = 60f
    const val MIN_TAIL_AMP = 8

    /** 이어지는 부분은 이 간격으로 적는다. 한결같이 줄어드는 폭이 한 칸마다 느낄 수 없을 만큼 작다. */
    const val HOLD_STEP_MS = 100L

    /** 계획은 다음 알림 톡 뒤로 이만큼 더 이어진다. 틱이 늦어도 구멍이 나지 않는다. */
    const val SAFETY_HOLD_MS = 300L
    const val MIN_HOLD_MS = 600L
    const val PITCH_HORIZON_MS = 2500L

    /** 계획이 이만큼 남으면 다시 채운다. 다시 채우기는 알림 톡·음높이 톡·두근거림의 골에서만 한다. */
    const val REFILL_MS = 300L

    /** 치는 칸의 길이. 톡과 떨림의 경계(25~30ms) 아래다. */
    const val STRIKE_MS = 25L

    /**
     * 울리는 도중에 치기 전에 이만큼 거의 0 으로 눌렀다가 친다. 모터가 다시 도는 틈을 이 눌림 속에 숨긴다.
     * 삼성 폰이 끌 때 서서히 줄이는 설정(rampDownDurationMs)을 20ms 이상 쓰면 0 으로 둔다.
     */
    const val DUCK_MS = 20L

    /** 위협음의 시작 '톡톡' 사이. */
    const val DOUBLE_STRIKE_GAP_MS = 75L

    /** 눌림·쉼의 세기. 0 이면 모터가 꺼졌다 다시 켜진다. 시스템 세기 0.6배와 8비트 절삭을 거쳐도 0 이 되지 않는다. */
    const val KEEP_ALIVE = 3

    /** 새로 시작한 소리를 치는 세기: 최고 × (0.8 + 0.2 × 오른 폭 비율). 오른 폭이 클수록 세다. */
    const val RISE_SPAN_DB = 12f

    /**
     * 소리 따라가 끝날 때의 부드러운 끝. 갑자기 0 으로 떨어지면 진동자가 50ms 넘게 떨린다.
     * 첫 칸이 0.6배(-4.4dB)라, 울리는 도중 끝내려고 보낼 때도 이음매 규칙([LEVEL_STEP_DB])을 지킨다.
     */
    val END_FADE = floatArrayOf(0.6f, 0.45f, 0.32f, 0.22f, 0.14f, 0.08f)
    const val END_FADE_STEP_MS = 25L

    /**
     * 소리가 뚝 끊길 때의 짧은 끝. 끊긴 것을 알아채는 데 20~40ms 가 걸리므로 끝은 한 칸(15ms)만 둔다.
     * 그래야 5Hz 삐소리(0.1초 쉼) 사이에 40ms 넘게 쉰다. 첫 칸이 -9dB 라 이음매 규칙도 지킨다.
     */
    val RELEASE_FADE = floatArrayOf(0.35f)
    const val RELEASE_FADE_STEP_MS = 15L

    /** 이만큼 안에서 차이 나는 이웃 칸은 합친다(느낄 수 있는 차이의 1/3). 치는 칸은 합치지 않는다. */
    const val MERGE_DB = 0.5f

    /** 한 계획의 칸 수 한도. */
    const val MAX_STEPS = 64

    /** 빠른 사이렌 두근거림은 이만큼 이상 한 계획으로 적는다. */
    const val THROB_MIN_PLAN_MS = 1200L

    // ---------------- 세기 조절이 없는 기기 ----------------

    /** 세기를 못 바꾸는 기기에서는 소리 따라를 짧은 박자 펄스로만 낸다. 꺼짐 알림 같은 긴 울림은 만들지 않는다. */
    const val BINARY_PULSE_MS = 40L
    const val BINARY_REMINDER_MS = 1000L
    const val BINARY_REFRACTORY_MS = 150L
    const val BINARY_DOUBLE_GAP_MS = 120L

    // ---------------- 한 번·두 번·길게 ----------------

    /** 한 번·두 번·길게의 첫 칸은 몸통 세기의 1.6배로 쳐서 진동자를 빨리 세운다. 폰에서 '툭 튀는' 느낌이면 1.3 까지 낮춘다. */
    const val STRIKE_OVERDRIVE = 1.6f

    /** 미리보기는 흉내 낸 소리가 끝나고 끝 페이드가 지난 뒤 멈춘다. */
    const val PREVIEW_TAIL_MS = 400L

    // ---------------- 세기 ----------------

    /**
     * 소리 따라의 세기. [peak] 는 치는 세기, [sustain] 은 이어지는 소리의 가장 센 세기(최고의 1/1.4, 친 곳이
     * 느낄 만큼 도드라진다), [floor] 는 가장 약한 세기. 바닥은 세기에 비례해 줄이지 않는다. 약에서 0.4배로 줄이면
     * 느낄 수 없는 세기가 되어 박자가 사라진다.
     */
    data class FollowLevels(val peak: Int, val sustain: Int, val floor: Int)

    fun followLevels(strength: HapticStrength): FollowLevels = when (strength) {
        HapticStrength.Weak -> FollowLevels(peak = 110, sustain = 79, floor = 40)
        HapticStrength.Medium -> FollowLevels(peak = 180, sustain = 129, floor = 45)
        HapticStrength.Strong -> FollowLevels(peak = 255, sustain = 182, floor = 50)
    }

    /** 한 번·두 번·길게의 몸통 세기. 예전 세기(70/150/255)를 그대로 두어 저장된 세기의 뜻이 바뀌지 않는다. */
    fun oneShotBody(strength: HapticStrength): Int = when (strength) {
        HapticStrength.Weak -> 70
        HapticStrength.Medium -> 150
        HapticStrength.Strong -> 255
    }

    fun oneShotStrike(strength: HapticStrength): Int =
        minOf(255, Math.round(oneShotBody(strength) * STRIKE_OVERDRIVE))

    // ---------------- 종류별 성격 ----------------

    enum class StartSignature { Strike, DoubleStrike }

    /**
     * 소리 종류별 소리 따라의 성격.
     * @param onsetRiseDb 이만큼 오르면 새 소리로 보고 친다. 말소리는 단어마다 치지 않도록 높다.
     * @param phraseGapMs 말소리는 이만큼 쉰 뒤의 첫머리만 친다.
     * @param pitchCue 음높이를 진동에 싣는지. 사이렌이 들어오는 위협음과 환경음만.
     * @param slowBed 한결같은 소리를 느린 봉투로 매끄럽게 깐다.
     * @param decayDelayMs 세션 시작 뒤 이만큼은 잔잔해지지 않는다. 위협음은 2초 동안 온 세기다.
     */
    data class FollowProfile(
        val onsetRiseDb: Float,
        val phraseGapMs: Long,
        val pitchCue: Boolean,
        val slowBed: Boolean,
        val decayDelayMs: Long,
        val start: StartSignature
    )

    fun profileFor(label: String): FollowProfile = when (label) {
        AiClassification.DANGER -> FollowProfile(8f, 0L, pitchCue = true, slowBed = true, decayDelayMs = 2000L, start = StartSignature.DoubleStrike)
        AiClassification.SPEECH -> FollowProfile(12f, 250L, pitchCue = false, slowBed = false, decayDelayMs = 500L, start = StartSignature.Strike)
        else -> FollowProfile(8f, 0L, pitchCue = true, slowBed = true, decayDelayMs = 500L, start = StartSignature.Strike)
    }

    // ---------------- 옛 기기 ----------------

    /**
     * 계획 한도. Android 10·11 은 칸마다 지연이 쌓여 50칸 가까운 파형을 늘여 틀었다(Android 12 에서 고쳐짐).
     * 그래서 API 30 이하는 칸을 두 배로 길게, 칸 수는 24 로 넉넉히 줄인다.
     */
    data class PlanLimits(
        val stepMs: Long,
        val holdStepMs: Long,
        val maxSteps: Int,
        val endFade: FloatArray,
        val endFadeStepMs: Long,
        val releaseFade: FloatArray,
        val releaseFadeStepMs: Long,
        val throbStepMs: Long
    )

    val MODERN_LIMITS = PlanLimits(
        stepMs = STEP_MS,
        holdStepMs = HOLD_STEP_MS,
        maxSteps = MAX_STEPS,
        endFade = END_FADE,
        endFadeStepMs = END_FADE_STEP_MS,
        releaseFade = RELEASE_FADE,
        releaseFadeStepMs = RELEASE_FADE_STEP_MS,
        throbStepMs = THROB_STEP_MS
    )

    val LEGACY_LIMITS = PlanLimits(
        stepMs = 40L,
        holdStepMs = 300L,
        maxSteps = 24,
        endFade = floatArrayOf(0.6f, 0.35f, 0.2f, 0.1f),
        endFadeStepMs = 40L,
        releaseFade = floatArrayOf(0.3f),
        releaseFadeStepMs = 40L,
        throbStepMs = 40L
    )

    /** Android 11(API 30) 이하는 [LEGACY_LIMITS]. */
    fun limitsFor(sdkInt: Int): PlanLimits = if (sdkInt <= 30) LEGACY_LIMITS else MODERN_LIMITS
}

package com.example.soundvisualizer.feedback

/**
 * 외부 사운드 모드에서 앱이 울린 진동을 마이크가 다시 듣는 것을 진동 판단에서 걸러 낸다(#290). 안드로이드에 의존하지
 * 않아 JVM 에서 테스트한다. 진동 스레드에서만 부른다.
 *
 * 진동 판단이 읽는 크기([com.example.soundvisualizer.AudioEngine.takeHapticPeak])에는 시각이 없다. 지난번에 읽은 뒤로
 * 들어온 캡처 버퍼 전체의 최대값이다. 그래서 읽은 구간(지난번에 읽은 시각, 지금]이 앱이 울린 진동과 그 꼬리가 끝난 뒤에
 * 시작했으면 깨끗하고, 아니면 자기 진동이 섞였을 수 있다고 본다. 꼬리가 끝나는 시각에 한 번 깨어나 섞인 값을 버리므로
 * ([reopenAtMs]), 그다음부터 다음 박자 직전까지 읽은 값은 쉼에서 들은 소리뿐이다. 다음 울림은 크기를 읽은 뒤에 보내므로
 * 그 울림은 섞이지 않는다.
 *
 * 섞인 값은 소리가 났다는 증거로 쓰지 않는다. 그 대신 세 가지를 한다. 하나라도 빼면 모델(#290 설계 시뮬레이션)에서
 * 다음 문제가 생겼다.
 * - **이어 주기(credit)**: 울림 묶음(사이에 깨끗한 값이 없는 울림들)이 시작되기 바로 전의 쉼에서 소리가 들렸으면, 그 묶음
 *   동안 섞인 값 자리에 그 소리 크기를 돌려준다. 그 값은 읽은 구간이 시작된 시각의 것으로 치되, 첫 울림의 꼬리가 끝나기
 *   [HapticTuning.SELF_HEARING_CREDIT_LEAD_MS] 전보다 늦게 치지 않는다([levelAtMs]). 꼬리 끝에 가깝게 치면 느림은 다음
 *   박자까지 여유가 20ms 남짓뿐이라, 울림을 보내는 데 그보다 오래 걸리는 폰에서는 소리가 끝난 뒤 거의 늘 한 번 더 울린다.
 *   이어 주기가 없으면 말소리처럼 끊어지는 소리에서 듣지 않는 구간 뒤의 쉼을 잇지 못해, 중간·느림의 박자가 자주 빠진다.
 * - **기다리기(hold)**: 그 묶음 동안은 소리가 난다·멈췄다를 새로 정하지 않고 마지막 깨끗한 판단을 그대로 둔다([holding]).
 *   없으면 말소리처럼 끊어지는 소리에서 빠름·중간의 박자가 자주 어긋난다.
 * - **첫 울림에 맞추기(anchor)**: 이어 주기와 기다리기는 묶음의 첫 울림이 끝난 뒤(꼬리 포함)까지만 한다. 묶음 안에서 다시
 *   보낸 울림(방식이나 종류가 바뀌어 새로 울린 것)은 듣지 않는 끝만 늘린다. 없으면 자기 진동 소리에 AI 라벨이 오가는 동안
 *   기다리기가 끝없이 이어진다.
 *
 * 그래서 자기 진동 소리만으로는 진동이 이어지지 않는다. 깨끗한 값만 소리를 새로 알리고, 깨끗한 값 하나는 묶음 하나만
 * 이어 주며, 기다리기는 첫 울림에 맞춘 끝에서 멈춘다. 박자 방식은 박자마다 듣는 쉼이 남으므로
 * ([HapticTuning.selfHearingGuardMs]) 박자마다 새 묶음이 된다. 외부 사운드 모드에서는 종류마다 상한이 있어 느림·중간만
 * 울린다([HapticSettings.externalCap], #354). 쉼이 없는 '연속'과 듣는 쉼이 0.04초뿐인 '빠름'은 울리지 않는다.
 *
 * 설정 화면의 미리보기 진동도 마이크가 듣는다. 미리보기가 진동기를 잡은 동안과 그 꼬리도 듣지 않는다([onOtherVibration]).
 *
 * 한계: 꼬리가 [HapticTuning.selfHearingGuardMs] 보다 길면 걸러 내지 못한다. 다른 앱의 진동은 모른다. 화면 그림과 AI 는
 * 이 값을 쓰지 않으므로 짧은 울림을 그대로 듣는다.
 */
class SelfVibrationGate(private val levelThreshold: Float = HapticPolicy.LEVEL_THRESHOLD) {

    /** 지난번에 읽은 시각. 아직 읽지 않았으면 [Long.MIN_VALUE]. */
    private var lastReadMs = Long.MIN_VALUE

    /** 이 시각 전에 시작한 구간은 자기 진동이 섞였을 수 있다. 울린 적이 없으면 [Long.MIN_VALUE]. */
    private var deafEndMs = Long.MIN_VALUE

    /** 미리보기 진동과 그 꼬리가 끝나는 시각. 미리보기를 일찍 멈추면 줄어든다. */
    private var otherEndMs = Long.MIN_VALUE

    /** 묶음 첫 울림의 꼬리가 끝나는 시각. 이어 주기와 기다리기는 여기까지다. */
    private var anchorEndMs = Long.MIN_VALUE

    /** 울림 묶음 안인지. 묶음이 시작된 뒤 아직 깨끗한 값을 읽지 않았으면 참이다. */
    private var inSpan = false

    /** 지금 묶음이 섞인 값 자리에 돌려줄 크기. 묶음 전 쉼이 조용했으면 0 이다. */
    private var credit = 0f

    /** 지난 묶음이 끝난 뒤로 읽은 깨끗한 값 가운데 가장 큰 것. */
    private var cleanPeakSinceSpan = 0f

    /** 마지막 울림의 꼬리 여유. 울림을 끊으면 이만큼만 더 듣지 않는다. */
    private var lastGuardMs = HapticTuning.SELF_HEARING_GUARD_MAX_MS

    /** 이번에 읽은 값이 자기 진동과 섞여, 소리가 이어지는지 판단을 기다려야 하는지([HapticPolicy.onTick] 의 `holding`). */
    var holding = false
        private set

    /** 이번에 읽은 값이 깨끗했는지. 디버그 기록에 쓴다. */
    var lastReadClean = true
        private set

    /**
     * 이번에 돌려준 크기가 어느 시각의 소리인지([HapticPolicy.onTick] 의 `levelAtMs`). 깨끗한 값은 읽은 시각이고, 이어 준
     * 값은 읽은 구간이 시작된 시각이되 첫 울림의 꼬리가 끝나기 [HapticTuning.SELF_HEARING_CREDIT_LEAD_MS] 전을 넘지 않는다.
     * 듣지 못한 구간 끝까지 소리가 이어졌다고 치지 않고, 꼬리 끝에 깨어나는 틱이 바로 앞 틱보다 얼마나 늦는지(울림을 보내는
     * 데 걸린 시간)에도 흔들리지 않는다.
     */
    var levelAtMs = Long.MIN_VALUE
        private set

    /**
     * 진동 판단에 쓸 크기. 깨끗하면 [peak] 그대로, 섞였으면 이어 주기 값이나 0 이다.
     * 틱마다 한 번, 크기를 읽은 바로 뒤에 부른다.
     */
    fun read(nowMs: Long, peak: Float): Float {
        val windowStart = lastReadMs
        lastReadMs = nowMs
        levelAtMs = nowMs
        if (windowStart == Long.MIN_VALUE || windowStart >= deafEnd()) {
            if (inSpan) {
                inSpan = false
                cleanPeakSinceSpan = 0f
            }
            cleanPeakSinceSpan = maxOf(cleanPeakSinceSpan, peak)
            holding = false
            lastReadClean = true
            return peak
        }
        lastReadClean = false
        if (windowStart < anchorEndMs) {
            holding = true
            levelAtMs = minOf(windowStart, anchorEndMs - HapticTuning.SELF_HEARING_CREDIT_LEAD_MS)
            return credit
        }
        holding = false
        return 0f
    }

    /**
     * 진동기에 울림을 보냈다.
     * @param nowMs 보낸 직후의 시각. 보내는 데 걸린 시간도 듣지 않는 구간에 넣는다.
     * @param onMs 울리는 시간
     * @param guardMs 그 방식의 꼬리 여유([HapticTuning.selfHearingGuardMs])
     */
    fun onPlayed(nowMs: Long, onMs: Long, guardMs: Long) {
        val end = nowMs + onMs + guardMs
        if (!inSpan) {
            inSpan = true
            anchorEndMs = end
            credit = if (cleanPeakSinceSpan > levelThreshold) cleanPeakSinceSpan else 0f
        }
        deafEndMs = maxOf(deafEndMs, end)
        lastGuardMs = guardMs
    }

    /** 울리던 진동을 끊었다. 남은 것은 꼬리뿐이다. */
    fun onCancelled(nowMs: Long) {
        val end = nowMs + lastGuardMs
        if (deafEndMs > end) deafEndMs = end
        if (anchorEndMs > end) anchorEndMs = end
    }

    /**
     * 다른 곳(설정 화면의 미리보기)이 [untilMs] 까지 진동기를 쓴다. 그 꼬리까지 듣지 않는다. 틱마다 크기를 읽기 전에
     * [HapticPreviewGate.busyUntilMs] 로 부른다. 미리보기를 일찍 멈추면 그 값이 멈춘 시각으로 줄어, 듣지 않는 구간도 준다.
     */
    fun onOtherVibration(untilMs: Long) {
        otherEndMs = untilMs + HapticTuning.SELF_HEARING_GUARD_MAX_MS
    }

    /** 섞인 값을 버리러 깨어날 시각. 듣지 않는 구간이 이미 끝났으면 null. */
    fun reopenAtMs(nowMs: Long): Long? {
        val end = deafEnd()
        return if (end != Long.MIN_VALUE && end > nowMs) end + HapticTuning.SELF_HEARING_REOPEN_SLACK_MS else null
    }

    private fun deafEnd(): Long = maxOf(deafEndMs, otherEndMs)
}

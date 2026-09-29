package com.example.soundvisualizer.feedback

/**
 * 진동 판단 틱 하나: 소리 분석 → 울릴지 판단 → 한 번·두 번·길게 모양, 또는 소리 따라 계획.
 *
 * 안드로이드에 의존하지 않는다. 실제 진동 알림([HapticNotifier]), 오프라인 시뮬레이터([HapticSimulator]),
 * 테스트가 모두 이 한 경로를 쓴다. 그래서 JVM 테스트가 확인한 것이 폰에서 나가는 것과 같다.
 *
 * 한 스레드에서만 부른다.
 *
 * @param sdkInt 계획 한도를 고른다. Android 11 이하는 긴 파형을 늘여 트는 문제가 있어 칸을 줄인다.
 * @param amplitudeControl 세기 조절이 되는 기기인지. 안 되면 짧은 박자 펄스로만 울린다.
 */
class HapticLoop(
    sdkInt: Int,
    private val amplitudeControl: Boolean,
    private val policy: HapticPolicy = HapticPolicy()
) {
    private val analyzer = FollowAnalyzer()
    private val engine = FollowEngine(HapticTuning.limitsFor(sdkInt), amplitudeControl)
    private var lastTickMs = Long.MIN_VALUE

    /** 지금 이어지는 소리 따라 세션. */
    val follow: HapticPolicy.Follow? get() = policy.follow

    /**
     * @return 지금 보낼 계획. 없으면 null.
     */
    fun onTick(
        nowMs: Long,
        label: String?,
        peak: Float,
        rms: Float,
        tone: Float,
        buffers: Int,
        config: (String) -> HapticPolicy.ClassConfig
    ): HapticPlan? {
        val fast = lastTickMs != Long.MIN_VALUE && nowMs - lastTickMs <= FAST_TICK_LIMIT_MS
        lastTickMs = nowMs
        analyzer.onFrame(nowMs, rms, tone, buffers, fast)

        val decision = policy.onTick(nowMs, label, peak, config)
        if (decision != null) {
            val shot = HapticShapes.oneShot(decision.pattern, decision.strength, amplitudeControl)
            engine.abort(nowMs, shot.durationMs)
            return shot
        }

        val f = policy.follow
        if (f == null) {
            return if (engine.sessionId != 0) engine.end(nowMs) else null
        }
        val levels = HapticTuning.followLevels(f.strength)
        if (f.id != engine.sessionId) {
            return engine.start(nowMs, f.id, HapticTuning.profileFor(f.label), levels, analyzer)
        }
        return engine.update(nowMs, analyzer, levels)
    }

    /** 소리 따라 세션이 있거나 그 계획이 아직 울리는 동안만 빠른 틱을 쓴다. 조용할 때는 예전처럼 100ms. */
    fun wantsFastTick(nowMs: Long): Boolean = policy.follow != null || engine.isPlaying(nowMs)

    /** 계획을 보내지 못했다(미리보기가 진동기를 잡고 있었다). 보낸 셈 치지 않고, 다음 틱에 다시 낸다. */
    fun onIssueSkipped(nowMs: Long) {
        engine.forgetPlan()
    }

    private companion object {
        /** 앞 틱에서 이만큼 안이면 빠른 틱이다. 느린 틱(100ms)의 음높이는 버린다. */
        const val FAST_TICK_LIMIT_MS = 40L
    }
}

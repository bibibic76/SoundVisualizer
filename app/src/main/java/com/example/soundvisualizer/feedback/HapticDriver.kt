package com.example.soundvisualizer.feedback

/**
 * 울려야 할 진동([HapticPolicy.Vibe])을 시각에 맞춘 진동기 명령으로 바꾼다. 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * - 느림·중간·빠름: 박자마다 울림 하나([HapticShapes.steady])를 보낸다. 모터가 쉬는 사이에만 보내므로 끊김이 없다.
 * - 연속: [HapticTuning.CONTINUOUS_CHUNK_MS] 짜리 울림을 보내고 끝나기 전에 다시 보낸다.
 * - 울리지 않아야 하면(소리가 끝났거나 꺼진 종류로 바뀌었으면) 울리던 울림도 [Command.Cancel] 로 바로 끊는다.
 *   박자 방식의 울림을 끝까지 두면 느림은 0.4초까지 앞 종류가 더 울린다(#244).
 *
 * 박자는 처음 울린 시각에서 센다. 틱이 늦어 한 박자의 울림 시간을 통째로 놓쳤으면 그 박자는 건너뛴다. 몰아서 울리면
 * 두 울림이 붙어 한 번의 긴 울림이 된다.
 *
 * 한 스레드에서만 부른다.
 *
 * @param amplitudeControl 세기 조절이 되는 기기인지. 안 되면 기본 세기로 켰다 끈다.
 */
class HapticDriver(private val amplitudeControl: Boolean) {

    /** 진동기에 할 일. */
    sealed interface Command {
        object None : Command
        data class Play(val plan: HapticPlan) : Command
        object Cancel : Command
    }

    private var current: HapticPolicy.Vibe? = null

    /** 다음 울림의 시각. 느림·중간·빠름에서만 쓴다. */
    private var nextBeatMs = Long.MIN_VALUE

    /** 보낸 연속 울림이 끝나는 시각. 보낸 것이 없거나 잊었으면 [Long.MIN_VALUE]. */
    private var continuousUntilMs = Long.MIN_VALUE

    /** 마지막으로 보낸 박자 울림이 끝나는 시각. 보낸 것이 없거나 잊었으면 [Long.MIN_VALUE]. */
    private var beatUntilMs = Long.MIN_VALUE

    /**
     * @param vibe 지금 울려야 할 진동. 없으면 null.
     * @return 지금 진동기에 할 일.
     */
    fun onTick(nowMs: Long, vibe: HapticPolicy.Vibe?): Command {
        // 꺼짐은 울리지 않는 것과 같다. 박자가 없어(0) 그대로 다루면 0으로 나눈다.
        val want = vibe?.takeIf { it.mode != HapticMode.Off }
        val prev = current
        current = want
        if (want == null) {
            val running = (continuousUntilMs != Long.MIN_VALUE && nowMs < continuousUntilMs) ||
                (beatUntilMs != Long.MIN_VALUE && nowMs < beatUntilMs)
            nextBeatMs = Long.MIN_VALUE
            continuousUntilMs = Long.MIN_VALUE
            beatUntilMs = Long.MIN_VALUE
            return if (prev != null && running) Command.Cancel else Command.None
        }
        if (want.mode == HapticMode.Continuous) {
            nextBeatMs = Long.MIN_VALUE
            beatUntilMs = Long.MIN_VALUE
            val stale = prev != want || continuousUntilMs == Long.MIN_VALUE ||
                continuousUntilMs - nowMs <= HapticTuning.CONTINUOUS_REFILL_MS
            if (!stale) return Command.None
            continuousUntilMs = nowMs + HapticTuning.CONTINUOUS_CHUNK_MS
            return Command.Play(HapticShapes.steady(want.level, HapticTuning.CONTINUOUS_CHUNK_MS, amplitudeControl))
        }

        // 느림·중간·빠름. 방식이 바뀌면(연속에서 넘어왔으면 그 울림을 덮으며) 지금부터 새로 센다.
        // 세기만 바뀌면 박자는 그대로 두고 다음 울림부터 새 세기로 울린다.
        continuousUntilMs = Long.MIN_VALUE
        val period = HapticTuning.periodMs(want.mode)
        val on = HapticTuning.onMs(want.mode)
        if (prev?.mode != want.mode || nextBeatMs == Long.MIN_VALUE) nextBeatMs = nowMs
        if (nowMs > nextBeatMs) {
            // 틱이 늦었다. 지난 박자 중 가장 최근 것으로 옮기고, 그 박자의 울림 시간도 지났으면 다음 박자를 기다린다.
            nextBeatMs += (nowMs - nextBeatMs) / period * period
            if (nowMs - nextBeatMs >= on) nextBeatMs += period
        }
        if (nowMs < nextBeatMs - EARLY_MS) return Command.None
        nextBeatMs += period
        beatUntilMs = nowMs + on
        return Command.Play(HapticShapes.steady(want.level, on, amplitudeControl))
    }

    /**
     * 다음에 깨어나야 할 시각. 판단 주기([HapticTuning.IDLE_TICK_MS])보다 다음 울림이나 연속을 다시 보낼 때가 먼저면 그때다.
     */
    fun nextWakeMs(nowMs: Long): Long {
        val idle = nowMs + HapticTuning.IDLE_TICK_MS
        val c = current ?: return idle
        val due = when {
            c.mode == HapticMode.Continuous && continuousUntilMs != Long.MIN_VALUE ->
                continuousUntilMs - HapticTuning.CONTINUOUS_REFILL_MS
            nextBeatMs != Long.MIN_VALUE -> nextBeatMs
            else -> idle
        }
        return minOf(idle, maxOf(due, nowMs))
    }

    /**
     * 보낸 것이 끊겼거나 보내지 못했다(미리보기가 진동기를 가져갔다). 연속 울림을 보낸 것으로 치지 않아, 다음 틱에 다시 보낸다.
     * 느림·중간·빠름은 놓친 울림을 다시 보내지 않고 다음 박자부터 이어 간다.
     */
    fun onPreempted() {
        continuousUntilMs = Long.MIN_VALUE
        beatUntilMs = Long.MIN_VALUE
    }

    private companion object {
        /** 이만큼 일찍 깨어나도 울림 시각으로 본다. 두 시계(uptime·elapsedRealtime)를 오가는 반올림 여유. */
        const val EARLY_MS = 2L
    }
}

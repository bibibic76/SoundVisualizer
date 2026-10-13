package com.example.soundvisualizer.feedback

/**
 * 우리가 울린 진동을 마이크가 들을 수 있는 최근 구간들(#352). 외부 사운드 모드에서 캡처 스레드가 이 구간의 소리를 AI 에 넘기기
 * 전에 바꾼다([AiFeedFilter]). 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 구간은 `[start, end)` 이고 시각은 elapsedRealtime(ms)이다. `end` 는 꼬리 여유([HapticTuning.aiFeedGuardMs])까지 넣은
 * 끝이고, `cmdEnd` 는 모터에 멈추라고 한 시각이다. 울림을 끊으면 꼬리 여유를 그대로 둔 채 앞당기고([cut]), 디버그 진단이
 * 꼬리를 잴 때도 쓴다.
 *
 * 쓰는 쪽은 진동 스레드(진동 알림의 울림)와 메인 스레드(설정 미리보기, 튜토리얼)이고, 읽는 쪽은 캡처 스레드다. 캡처 스레드는
 * 잠그지도 할당하지도 않아야 하므로, 쓸 때마다 새 배열을 만들어 `@Volatile` 로 바꿔 끼운다. 쓰기는 초에 다섯 번 남짓이다.
 * 읽는 쪽은 버퍼마다 [snapshot] 을 한 번 읽고, 받은 배열은 그 뒤로 바뀌지 않는다.
 *
 * 앱에서는 [shared] 하나만 쓴다. 테스트는 저마다 새로 만든다.
 */
class SelfVibrationTimeline {

    /**
     * `[handle, start, end, cmdEnd]` 를 오래된 것부터 이은 배열. 한 번 내보낸 배열은 바꾸지 않는다.
     *
     * 처음 값은 여기서 바로 만든다. companion 의 상수에 기대면, companion 이 [shared] 를 먼저 만들 때 그 상수가 아직 null 이라
     * 앱이 쓰는 [shared] 만 null 배열을 들고 시작한다(#383: v1.7.0 에서 진동 미리 듣기와 외부 사운드 모드 진동 때 앱이 죽음).
     */
    @Volatile
    private var windows: LongArray = LongArray(0)

    /** 다음 구간의 번호. 0 은 "없음" 으로 남겨 둔다. 잠금 안에서만 쓴다. */
    private var nextHandle = 1L

    /** 지금의 구간들. `[handle, start, end, cmdEnd]` 를 오래된 것부터 잇는다. 잠그지 않고 할당하지 않는다. */
    fun snapshot(): LongArray = windows

    /**
     * 구간을 하나 연다. 울림을 보내기 **전에** 불러야 모터가 돌기 전에 캡처 스레드가 구간을 안다.
     * 가장 오래된 구간을 버려 [HapticTuning.AI_FEED_TIMELINE_CAPACITY] 개까지만 두고, [startMs] 보다 [KEEP_MS] 넘게 전에
     * 끝난 구간도 버린다. 그런 구간은 캡처 시각이 이미 한참 지났다.
     *
     * @return 이 구간의 번호. [setEnd]·[retract]·[cut] 에 넘긴다.
     */
    fun open(startMs: Long, endMs: Long, cmdEndMs: Long): Long = synchronized(this) {
        val handle = nextHandle++
        val old = windows
        val count = old.size / FIELDS
        val oldest = maxOf(0, count - (HapticTuning.AI_FEED_TIMELINE_CAPACITY - 1))
        var kept = 0
        for (i in oldest until count) if (old[i * FIELDS + END] >= startMs - KEEP_MS) kept++
        val next = LongArray((kept + 1) * FIELDS)
        var o = 0
        for (i in oldest until count) {
            if (old[i * FIELDS + END] < startMs - KEEP_MS) continue
            System.arraycopy(old, i * FIELDS, next, o, FIELDS)
            o += FIELDS
        }
        next[o + HANDLE] = handle
        next[o + START] = startMs
        next[o + END] = endMs
        next[o + CMD_END] = cmdEndMs
        windows = next
        handle
    }

    /** 울림을 보낸 뒤의 시각으로 [handle] 구간의 끝을 고친다. 다른 구간은 그대로다. 없는 번호면 아무것도 하지 않는다. */
    fun setEnd(handle: Long, endMs: Long, cmdEndMs: Long) = synchronized(this) {
        val i = indexOf(handle)
        if (i < 0) return@synchronized
        replace(i, endMs, cmdEndMs)
    }

    /** 보내지 못한 울림의 구간을 지운다. 없는 번호면 아무것도 하지 않는다. */
    fun retract(handle: Long) = synchronized(this) {
        val i = indexOf(handle)
        if (i < 0) return@synchronized
        val old = windows
        val next = LongArray(old.size - FIELDS)
        System.arraycopy(old, 0, next, 0, i * FIELDS)
        System.arraycopy(old, (i + 1) * FIELDS, next, i * FIELDS, old.size - (i + 1) * FIELDS)
        windows = next
    }

    /** 가장 최근 구간의 모터를 [nowMs] 에 멈췄다. 진동 알림이 울림을 끊을 때 부른다. 규칙은 [cut] 과 같다. */
    fun cutLatest(nowMs: Long) = synchronized(this) {
        val count = windows.size / FIELDS
        if (count == 0) return@synchronized
        cutAt(count - 1, nowMs)
    }

    /**
     * [handle] 구간의 모터를 [nowMs] 에 멈췄다. 꼬리 여유(`end − cmdEnd`)는 그대로 둔 채 끝을 앞당기고, 늦추지는 않는다.
     * 저절로 끝난 뒤에 불러도 아무것도 바뀌지 않는다. 없는 번호면 아무것도 하지 않는다.
     */
    fun cut(handle: Long, nowMs: Long) = synchronized(this) {
        val i = indexOf(handle)
        if (i < 0) return@synchronized
        cutAt(i, nowMs)
    }

    /** 잠금 안에서 부른다. */
    private fun cutAt(i: Int, nowMs: Long) {
        val old = windows
        val end = old[i * FIELDS + END]
        val cmdEnd = old[i * FIELDS + CMD_END]
        val newEnd = minOf(end, nowMs + (end - cmdEnd))
        val newCmdEnd = minOf(cmdEnd, nowMs)
        if (newEnd == end && newCmdEnd == cmdEnd) return
        replace(i, newEnd, newCmdEnd)
    }

    /** 잠금 안에서 부른다. */
    private fun replace(i: Int, endMs: Long, cmdEndMs: Long) {
        val next = windows.copyOf()
        next[i * FIELDS + END] = endMs
        next[i * FIELDS + CMD_END] = cmdEndMs
        windows = next
    }

    /** 잠금 안에서 부른다. */
    private fun indexOf(handle: Long): Int {
        val w = windows
        for (i in 0 until w.size / FIELDS) if (w[i * FIELDS + HANDLE] == handle) return i
        return -1
    }

    companion object {
        /** 앱 전체에 하나뿐인 목록. 진동 알림과 미리보기가 쓰고, 외부 사운드 모드의 캡처 스레드가 읽는다. */
        val shared = SelfVibrationTimeline()

        /** 구간 하나가 차지하는 칸 수와 칸의 자리. */
        const val FIELDS = 4
        const val HANDLE = 0
        const val START = 1
        const val END = 2
        const val CMD_END = 3

        /** 이보다 오래전에 끝난 구간은 새로 열 때 버린다. 캡처 시각은 지금보다 길어야 0.4초쯤 늦다. */
        const val KEEP_MS = 2_000L
    }
}

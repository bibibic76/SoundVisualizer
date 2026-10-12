package com.example.soundvisualizer

import com.example.soundvisualizer.feedback.HapticTuning
import kotlin.math.floor

/**
 * 캡처 버퍼의 프레임이 마이크에 들어온 시각(elapsedRealtime, ms)(#352). 외부 사운드 모드에서 우리 울림이 든 구간을 AI 입력에서
 * 빼려면([com.example.soundvisualizer.feedback.AiFeedFilter]) 진동을 보낸 시각과 같은 시계로 각 프레임의 캡처 시각을 알아야 한다.
 * 안드로이드 호출은 [onRead] 에 함수로 받아 JVM 에서 테스트한다. 캡처 스레드에서만 쓰고 할당하지 않는다.
 *
 * - **프레임 번호**는 `startRecording` 뒤로 `read()` 가 돌려준 프레임 수다. `AudioTimestamp.framePosition` 과 같은 기준이다
 *   (AOSP `AudioRecord::start()` 가 읽은 수를 0 으로 되돌리고 서버 위치를 그만큼 옮긴다). 캡처 스레드마다 새로 만들므로
 *   다시 시작할 때마다 0 부터 센다.
 * - **어림(fallback)**: 읽기가 돌아온 시각에서 그때까지 읽은 길이를 빼면 0 번 프레임의 시각이 나온다(지연이 없다고 친 값).
 *   최근 2초 동안 가장 이른 값을 쓴다(0.5초 칸 네 개의 최솟값). 실제 입력 지연만큼 늦을 수는 있어도 이르지는 않다.
 *   단, 캡처 스레드가 밀려 오디오 서버가 프레임을 버리면(오버런) 읽은 수가 실제 캡처보다 그만큼 뒤처져, 지난 최솟값은 버려진
 *   만큼 이르게 남는다.
 * - **기준(timestamp)**: [HapticTuning.AI_FEED_ANCHOR_INTERVAL_MS] 마다 오디오 서버에 묻는다
 *   (`AudioRecord.getTimestamp(…, TIMEBASE_BOOTTIME)`, elapsedRealtimeNanos 와 같은 시계). 그 시각이 지연 없는 어림보다
 *   2ms 넘게 늦거나(캡처가 그보다 늦을 수는 없다) [HapticTuning.AI_FEED_MAX_LATENCY_MS] 넘게 이르면 틀린 것으로 보고 버린다.
 *   프레임 번호의 기준이 다른 기기는 모든 기준을 버리고 어림으로 돈다.
 * - **오버런 뒤**: 시각 표시가 최근 2초의 어림보다는 늦어도 이번 읽기 하나로 센 어림과는 맞으면(그보다 늦지 않고 300ms 넘게
 *   이르지도 않으면), 어림의 최솟값이 낡은 것으로 보고 이번 읽기부터 다시 센 뒤 그 시각 표시를 받아들인다. 낡은 최솟값과
 *   견주면 맞는 시각 표시까지 늦다고 버리게 되어, 오버런 뒤 2초쯤 지난 기준과 어림으로 버려진 만큼(예: 80ms) 이르게 셌기
 *   때문이다. 이번 읽기보다도 늦은 시각 표시는 그대로 버리고 최솟값도 건드리지 않는다. 읽기가 돌려준 프레임이 읽기가 돌아온
 *   뒤에 들어왔다는 것이라 틀린 값이다.
 * - **방식**: 받아들인 기준이 [ANCHOR_MAX_AGE_NS] 보다 새롭고 연달아 버리거나 실패한 것이 [MAX_BAD_IN_A_ROW] 번 아래면
 *   [Mode.TIMESTAMP], 아니면 [Mode.FALLBACK] 이다. 어림으로 돌 때는 빼는 구간의 끝을 [endExtraMs] 만큼 늘린다.
 *
 * @param sampleRate 캡처 레이트(48000 또는 44100)
 */
class CaptureClock(sampleRate: Int) {

    enum class Mode { TIMESTAMP, FALLBACK }

    /** 마지막 [onRead] 가 쓴 방식. 기준을 받아들이기 전까지는 어림이다. */
    var mode: Mode = Mode.FALLBACK
        private set

    /** 빼는 구간의 끝을 늘릴 시간. 어림은 입력 지연만큼 늦을 수 있어 그만큼 덮는다. */
    val endExtraMs: Int
        get() = if (mode == Mode.TIMESTAMP) 0 else HapticTuning.AI_FEED_FALLBACK_EXTRA_MS

    /** 받아들인 기준의 수. 디버그 기록용. */
    var anchors = 0
        private set

    /** 시각 표시는 받았지만 틀려서 버린 수. 디버그 기록용. */
    var rejects = 0
        private set

    /** 오디오 서버가 시각 표시를 주지 못한 수. 디버그 기록용. */
    var failures = 0
        private set

    /** 오디오 서버에 물은 수. */
    var polls = 0
        private set

    /** 마지막으로 받아들인 기준이 어림보다 이른 만큼(ms). 입력 지연의 어림이다. 기준이 없으면 NaN. */
    var latencyMs = Double.NaN
        private set

    private val nsPerFrame = 1e9 / sampleRate

    /** `startRecording` 뒤로 읽은 프레임 수. */
    private var framesRead = 0L

    /** 어림의 0 번 프레임 시각(ns). 칸마다의 최솟값 가운데 가장 이른 것이다. */
    private var epochNs = 0.0
    private val bucketIds = LongArray(BUCKETS) { Long.MIN_VALUE }
    private val bucketMins = DoubleArray(BUCKETS)

    private var anchorFrame = 0L
    private var anchorNs = 0L
    private var anchorAcceptedNs = Long.MIN_VALUE
    private var lastPollNs = Long.MIN_VALUE
    private var badInARow = 0
    private val polled = LongArray(2)

    /**
     * 프레임 [frames] 개를 읽었다. 읽기가 돌아온 직후에 부르고, AI 가 아직 없어도 버퍼마다 불러야 프레임 번호가 맞는다.
     *
     * @param readNs 읽기가 돌아온 시각(elapsedRealtimeNanos)
     * @param poll 오디오 서버의 시각 표시를 `[framePosition, nanoTime]` 로 채우고 받았는지 돌려준다. 많아야
     *   [HapticTuning.AI_FEED_ANCHOR_INTERVAL_MS] 마다 한 번 부른다.
     * @return 이번 버퍼의 첫 프레임이 마이크에 들어온 시각(elapsedRealtime, ms)
     */
    fun onRead(frames: Int, readNs: Long, poll: (out: LongArray) -> Boolean): Double {
        val n = frames.toLong()
        val estimateNs = readNs - (framesRead + n) * nsPerFrame
        updateEpoch(readNs, estimateNs)
        if (lastPollNs == Long.MIN_VALUE || readNs - lastPollNs >= ANCHOR_INTERVAL_NS) {
            lastPollNs = readNs
            polls++
            if (poll(polled)) {
                val last = framesRead + n - 1
                val stamped = polled[1] + (last - polled[0]) * nsPerFrame
                var estimated = epochNs + last * nsPerFrame
                if (stamped > estimated + EARLY_TOLERANCE_NS && fits(stamped, estimateNs + last * nsPerFrame)) {
                    // 최근 2초의 어림보다 늦지만 이번 읽기와는 맞다. 오버런으로 프레임이 버려져 최솟값이 이르게 남은 것이다.
                    // 이번 읽기부터 다시 세고 이 시각 표시를 받는다. 그대로 두면 맞는 시각 표시를 2초쯤 늦다고 버린다.
                    restartEpoch(readNs, estimateNs)
                    estimated = epochNs + last * nsPerFrame
                }
                if (fits(stamped, estimated)) {
                    anchorFrame = polled[0]
                    anchorNs = polled[1]
                    anchorAcceptedNs = readNs
                    badInARow = 0
                    anchors++
                    latencyMs = (estimated - stamped) / 1e6
                } else {
                    rejects++
                    badInARow++
                }
            } else {
                failures++
                badInARow++
            }
        }
        val anchored = anchorAcceptedNs != Long.MIN_VALUE &&
            readNs - anchorAcceptedNs < ANCHOR_MAX_AGE_NS &&
            badInARow < MAX_BAD_IN_A_ROW
        mode = if (anchored) Mode.TIMESTAMP else Mode.FALLBACK
        val startNs = if (anchored) {
            anchorNs + (framesRead - anchorFrame) * nsPerFrame
        } else {
            epochNs + framesRead * nsPerFrame
        }
        framesRead += n
        return startNs / 1e6
    }

    /** 이번 읽기의 어림 [estimateNs] 를 그 칸의 최솟값에 넣고, 최근 칸들 가운데 가장 이른 값을 [epochNs] 로 둔다. */
    private fun updateEpoch(readNs: Long, estimateNs: Double) {
        val id = floor(readNs / BUCKET_NS).toLong()
        val slot = Math.floorMod(id, BUCKETS.toLong()).toInt()
        if (bucketIds[slot] != id) {
            bucketIds[slot] = id
            bucketMins[slot] = estimateNs
        } else if (estimateNs < bucketMins[slot]) {
            bucketMins[slot] = estimateNs
        }
        var earliest = Double.MAX_VALUE
        for (i in 0 until BUCKETS) {
            if (bucketIds[i] != Long.MIN_VALUE && bucketIds[i] > id - BUCKETS && bucketMins[i] < earliest) earliest = bucketMins[i]
        }
        epochNs = earliest
    }

    /** 마지막 프레임의 시각 표시 [stamped] 가 지연 없는 어림 [estimated] 보다 2ms 넘게 늦지 않고 300ms 넘게 이르지 않은지. */
    private fun fits(stamped: Double, estimated: Double): Boolean =
        stamped <= estimated + EARLY_TOLERANCE_NS && estimated - stamped <= MAX_LATENCY_NS

    /** 지난 칸들을 버리고 이번 읽기의 어림 [estimateNs] 부터 다시 센다. */
    private fun restartEpoch(readNs: Long, estimateNs: Double) {
        for (i in 0 until BUCKETS) bucketIds[i] = Long.MIN_VALUE
        updateEpoch(readNs, estimateNs)
    }

    private companion object {
        /** 어림의 최솟값을 나눠 두는 칸. 0.5초 칸 네 개로 최근 2초를 본다. */
        const val BUCKETS = 4
        const val BUCKET_NS = 500_000_000.0

        const val ANCHOR_INTERVAL_NS = HapticTuning.AI_FEED_ANCHOR_INTERVAL_MS * 1_000_000L
        const val MAX_LATENCY_NS = HapticTuning.AI_FEED_MAX_LATENCY_MS * 1e6

        /** 시각 표시가 지연 없는 어림보다 이만큼까지 늦어도 받아들인다. 프레임 하나와 반올림의 여유다. */
        const val EARLY_TOLERANCE_NS = 2_000_000.0

        /** 받아들인 기준을 이만큼 쓴다. 그 뒤로 새 기준이 없으면 어림으로 돌아간다. */
        const val ANCHOR_MAX_AGE_NS = 2_000_000_000L

        /** 연달아 이만큼 버리거나 실패하면 어림으로 돌아간다. */
        const val MAX_BAD_IN_A_ROW = 3
    }
}

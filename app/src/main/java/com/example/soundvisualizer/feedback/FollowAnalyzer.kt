package com.example.soundvisualizer.feedback

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * 소리 따라가 쓰는 소리 특징을 틱마다 갱신한다. 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 틱마다 네이티브가 모은 "가장 큰 버퍼의 RMS 와 그 버퍼의 영교차율, 버퍼 수" 하나를 받는다. 소리 따라 세션이
 * 없어도 계속 받아서, AI 가 소리 종류를 알려 주는 0.25~1초 뒤에 세션이 시작될 때 봉투와 천장이 이미 데워져 있다.
 *
 * 만든 뒤에는 틱마다 할당하지 않는다. 한 스레드에서만 부른다.
 */
class FollowAnalyzer {

    enum class PitchMode {
        /** 음높이를 싣지 않는다. 크기만 따라간다. */
        NONE,

        /** 느리게 오르내림(웨일). 음높이를 몇 단계 세기로 옮긴다. */
        SLOW,

        /** 계단처럼 바뀜(하이로). 단계 세기에 바뀔 때마다 톡. */
        STEP,

        /** 빠르게 오르내림(옐프). 한 계획 안에서 두근거린다. */
        FAST
    }

    // ---------------- 크기 ----------------

    /** 이번 틱의 크기(dBFS). */
    var eIn = SILENT_DB; private set

    /** 봉투: 바로 오르고 80ms 시간상수로 내려간다. */
    var env = SILENT_DB; private set

    /** 느린 봉투(300ms). 조용한 틱에서는 멈춘다. */
    var slow = SILENT_DB; private set

    /** 동적 천장. */
    var ceil = HapticTuning.CEIL_FLOOR_DBFS; private set

    var silentNow = true; private set

    /** 이번 틱에 새 버퍼가 있었는지. 버퍼가 잠깐 안 온 틱은 조용함이 아니라 "모름" 이다. */
    var dataThisTick = false; private set

    /** 최근 [HapticTuning.ONSET_LOOKBACK_MS] 의 가장 작은 크기보다 이번 크기가 얼마나 올랐는지(dB). */
    var riseDb = 0f; private set

    /** 그 가장 작은 크기. 없으면 -120. */
    var lookbackMinDb = -120f; private set

    /** 이번 틱 전까지 조용했던 시간. 말소리 첫머리 판단에 쓴다. */
    var quietForMs = Long.MAX_VALUE; private set

    /** 한결같음이 시작된 시각. */
    var steadySince = 0L; private set

    fun steadyFor(nowMs: Long): Long = nowMs - steadySince

    /** 새 소리가 시작된 것 같았던 마지막 시각. 박이 있는 음악을 음높이 모드에서 빼는 데 쓴다. */
    var lastOnsetCandidateMs = Long.MIN_VALUE / 4; private set

    // ---------------- 음높이 ----------------

    var pitchMode = PitchMode.NONE; private set

    /** 최근 5초 음높이 폭 안에서 지금 음높이의 자리(0~1). */
    var pNorm = 0f; private set

    /** 빠른 사이렌 한 주기(ms). */
    var throbPeriodMs = 0L; private set

    /** 음높이가 마지막으로 꼭대기를 찍은 시각(오르다 내리기 시작한 때). 두근거림의 박자를 맞춘다. */
    var lastPitchMaxMs = 0L; private set

    /** 마지막 계단 바뀜 시각. */
    var lastStepJumpMs = Long.MIN_VALUE / 4; private set

    // ---------------- 안쪽 상태 ----------------

    private var lastTickMs = Long.MIN_VALUE
    private var lastProcessedMs = Long.MIN_VALUE
    private var lastDataMs = Long.MIN_VALUE
    private var lastNonSilentMs = Long.MIN_VALUE
    private var holdUntil = 0L
    private var steadyRef = SILENT_DB
    private var wasSilent = true

    // 최근 데이터 틱의 (시각, 봉투). 100ms 되돌아보기용.
    private val backTime = LongArray(BACK)
    private val backEnv = FloatArray(BACK)
    private var backCount = 0
    private var backHead = 0

    // 빠른 틱 음높이 고리: 1초. valid 가 아니면 p 는 쓰지 않는다.
    private val fastTime = LongArray(FAST_RING)
    private val fastValid = BooleanArray(FAST_RING)
    private val fastP = FloatArray(FAST_RING)
    private var fastCount = 0
    private var fastHead = 0

    // 5초 음높이 폭: 200ms 칸마다 최소·최대·개수.
    private val bucketId = LongArray(BUCKETS) { Long.MIN_VALUE }
    private val bucketMin = FloatArray(BUCKETS)
    private val bucketMax = FloatArray(BUCKETS)
    private val bucketN = IntArray(BUCKETS)

    // 방향 바뀜 시각과 방향(+1 = 꼭대기, -1 = 골).
    private val revTime = LongArray(REVS)
    private val revSign = IntArray(REVS)
    private var revCount = 0
    private var revHead = 0
    private var dir = 0
    private var ext = 0f

    // 원래 영교차율 최근 3개(중앙값), 유효 음높이 최근 7개(계단 판단).
    private val rawTone = FloatArray(3)
    private var rawCount = 0
    private val validHist = FloatArray(7)
    private var validHistCount = 0

    private val scratch = FloatArray(FAST_RING)

    /**
     * @param nowMs 단조 증가 시각
     * @param rms 가장 큰 버퍼의 RMS (0..1)
     * @param tone 그 버퍼의 영교차율(샘플당 교차 수)
     * @param buffers 그사이 도착한 버퍼 수
     * @param fastTick 앞 틱에서 40ms 안인지. 느린 틱(100ms)의 음높이는 3Hz 옐프를 엉뚱하게 보여 주므로 버린다.
     */
    fun onFrame(nowMs: Long, rms: Float, tone: Float, buffers: Int, fastTick: Boolean) {
        lastTickMs = nowMs
        var level = rms
        if (buffers <= 0) {
            if (lastDataMs != Long.MIN_VALUE && nowMs - lastDataMs < HapticTuning.NO_DATA_MS) {
                // 버퍼가 몰려서 오는 사이의 빈 틱: 조용한 게 아니라 아직 모르는 것이다.
                dataThisTick = false
                return
            }
            // 오래 안 오면 캡처가 멈춘 것이다. 조용함으로 본다.
            level = 0f
        } else {
            lastDataMs = nowMs
        }
        dataThisTick = true
        val dt = if (lastProcessedMs == Long.MIN_VALUE) 0f else (nowMs - lastProcessedMs).toFloat()
        lastProcessedMs = nowMs

        eIn = 20f * log10(max(level, 1e-5f))
        env = if (eIn >= env) eIn else max(eIn, env - DB_PER_TAU * dt / HapticTuning.RELEASE_TAU_MS)
        if (env >= ceil) {
            ceil = env
            holdUntil = nowMs + HapticTuning.CEIL_HOLD_MS
        } else if (nowMs > holdUntil) {
            ceil = maxOf(ceil - HapticTuning.CEIL_FALL_DB_PER_S * dt / 1000f, HapticTuning.CEIL_FLOOR_DBFS, env)
        }
        silentNow = eIn < max(HapticTuning.SILENCE_DBFS, ceil - HapticTuning.WINDOW_DB)

        if (!silentNow) {
            slow = if (wasSilent) env else slow + (env - slow) * (1f - exp(-dt / HapticTuning.SLOW_TAU_MS))
        }

        // 되돌아보기: 이번 틱 전 100ms 의 가장 작은 크기.
        var minBack = Float.MAX_VALUE
        for (i in 0 until backCount) {
            val idx = (backHead - 1 - i + BACK) % BACK
            if (nowMs - backTime[idx] > HapticTuning.ONSET_LOOKBACK_MS) break
            if (backEnv[idx] < minBack) minBack = backEnv[idx]
        }
        lookbackMinDb = if (minBack == Float.MAX_VALUE) -120f else minBack
        riseDb = eIn - max(lookbackMinDb, ceil - HapticTuning.WINDOW_DB)
        backTime[backHead] = nowMs
        // 순간 크기를 적는다. 봉투(천천히 내려감)로 비교하면 버퍼가 몰려 올 때 삐 소리 사이의 짧은 쉼을 못 보고 새 소리를 놓친다.
        backEnv[backHead] = eIn
        backHead = (backHead + 1) % BACK
        if (backCount < BACK) backCount++

        quietForMs = if (lastNonSilentMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - lastNonSilentMs
        if (!silentNow) lastNonSilentMs = nowMs

        if (silentNow || abs(slow - steadyRef) >= HapticTuning.STEADY_BAND_DB || wasSilent) {
            steadySince = nowMs
            steadyRef = slow
        }
        if (riseDb >= ONSET_CANDIDATE_DB && eIn >= ceil - HapticTuning.ONSET_MIN_REL_DB) {
            lastOnsetCandidateMs = nowMs
        }
        wasSilent = silentNow

        updatePitch(nowMs, tone, fastTick)
    }

    private fun updatePitch(nowMs: Long, tone: Float, fastTick: Boolean) {
        if (silentNow) {
            pitchMode = PitchMode.NONE
            dir = 0
            rawCount = 0
            validHistCount = 0
        }
        if (!fastTick) {
            // 느린 틱은 음높이 창에 넣지 않는다. 모드는 새 음높이가 들어올 때까지 둔다.
            if (!silentNow) pitchMode = modeNow(nowMs)
            return
        }
        val valid = !silentNow && tone >= HapticTuning.TONE_MIN && tone <= HapticTuning.TONE_MAX &&
            eIn >= ceil - 20f
        var p = 0f
        if (valid) {
            rawTone[rawCount % 3] = tone
            rawCount++
            p = log2(median3())
        }
        fastTime[fastHead] = nowMs
        fastValid[fastHead] = valid
        fastP[fastHead] = p
        fastHead = (fastHead + 1) % FAST_RING
        if (fastCount < FAST_RING) fastCount++

        if (valid) {
            // 5초 폭
            val id = nowMs / BUCKET_MS
            val b = (id % BUCKETS).toInt()
            if (bucketId[b] != id) {
                bucketId[b] = id
                bucketMin[b] = p
                bucketMax[b] = p
                bucketN[b] = 0
            }
            if (p < bucketMin[b]) bucketMin[b] = p
            if (p > bucketMax[b]) bucketMax[b] = p
            bucketN[b]++

            // 방향 바뀜
            if (validHistCount == 0) {
                ext = p
            } else if (dir == 0) {
                if (p > ext + HapticTuning.REV_HYST_OCT) { dir = 1; ext = p } else if (p < ext - HapticTuning.REV_HYST_OCT) { dir = -1; ext = p }
            } else if (dir > 0) {
                if (p > ext) ext = p
                else if (ext - p >= HapticTuning.REV_HYST_OCT) { addReversal(nowMs, 1); lastPitchMaxMs = nowMs; dir = -1; ext = p }
            } else {
                if (p < ext) ext = p
                else if (p - ext >= HapticTuning.REV_HYST_OCT) { addReversal(nowMs, -1); dir = 1; ext = p }
            }

            // 계단: 두 칸 전과 크게 다르고, 그 앞 4칸은 가만히 있었다.
            if (validHistCount >= 6) {
                val prev2 = hist(2)
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                for (k in 3..6) {
                    val v = hist(k)
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
                if (abs(p - prev2) >= HapticTuning.STEP_MIN_OCT && hi - lo <= 2f * HapticTuning.STEP_STABLE_OCT) {
                    lastStepJumpMs = nowMs
                }
            }
            validHist[validHistCount % validHist.size] = p
            validHistCount++

            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until BUCKETS) {
                if (bucketId[i] == Long.MIN_VALUE || nowMs / BUCKET_MS - bucketId[i] >= BUCKETS) continue
                if (bucketMin[i] < lo) lo = bucketMin[i]
                if (bucketMax[i] > hi) hi = bucketMax[i]
            }
            val range = hi - lo
            pNorm = if (range > 1e-6f) ((p - lo) / range).coerceIn(0f, 1f) else 0.5f
        }
        pitchMode = if (silentNow) PitchMode.NONE else modeNow(nowMs)
    }

    /** 이번 음높이를 넣기 전에 부르면 hist(1) 은 한 칸 전, hist(2) 는 두 칸 전 유효 음높이다. */
    private fun hist(k: Int): Float {
        val n = validHist.size
        return validHist[((validHistCount - k) % n + n) % n]
    }

    private fun addReversal(nowMs: Long, sign: Int) {
        revTime[revHead] = nowMs
        revSign[revHead] = sign
        revHead = (revHead + 1) % REVS
        if (revCount < REVS) revCount++
    }

    private fun modeNow(nowMs: Long): PitchMode {
        if (!tonal(nowMs)) return PitchMode.NONE
        val range = pitchRange(nowMs)
        if (range < HapticTuning.PITCH_MIN_RANGE_OCT) return PitchMode.NONE
        if (pitchWindowMs(nowMs) < HapticTuning.PITCH_MIN_WINDOW_MS) return PitchMode.NONE
        if (steadyFor(nowMs) < HapticTuning.STEADY_MIN_MS) return PitchMode.NONE
        if (nowMs - lastOnsetCandidateMs < HapticTuning.PITCH_NO_ONSET_MS) return PitchMode.NONE

        var reversals = 0
        var sumGap = 0L
        var gaps = 0
        var prev = Long.MIN_VALUE
        for (i in revCount - 1 downTo 0) {
            // 오래된 것부터
            val idx = (revHead - 1 - i + REVS) % REVS
            val t = revTime[idx]
            if (nowMs - t <= 1000L) reversals++
            if (nowMs - t <= 1500L) {
                if (prev != Long.MIN_VALUE) { sumGap += t - prev; gaps++ }
                prev = t
            }
        }
        if (reversals >= HapticTuning.FAST_MIN_REVERSALS && gaps > 0) {
            throbPeriodMs = 2L * sumGap / gaps
            return PitchMode.FAST
        }
        if (nowMs - lastStepJumpMs <= 2000L) return PitchMode.STEP
        return PitchMode.SLOW
    }

    /** 최근 [HapticTuning.TONAL_WINDOW_MS] 의 빠른 틱 대부분이 음높이를 갖고, 틱 사이 변화가 작으면 음이 있는 소리다. */
    private fun tonal(nowMs: Long): Boolean {
        var total = 0
        var validN = 0
        var dn = 0
        var prevValid = false
        var prevP = 0f
        for (i in fastCount - 1 downTo 0) {
            val idx = (fastHead - 1 - i + FAST_RING) % FAST_RING
            if (nowMs - fastTime[idx] > HapticTuning.TONAL_WINDOW_MS) { prevValid = false; continue }
            total++
            if (fastValid[idx]) {
                validN++
                if (prevValid) scratch[dn++] = abs(fastP[idx] - prevP)
                prevP = fastP[idx]
                prevValid = true
            } else {
                prevValid = false
            }
        }
        if (total < 5 || validN < HapticTuning.TONAL_MIN_VALID * total || dn == 0) return false
        return median(scratch, dn) < HapticTuning.TONAL_MAX_MEDIAN_DP_OCT
    }

    private fun pitchRange(nowMs: Long): Float {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0 until BUCKETS) {
            if (bucketId[i] == Long.MIN_VALUE || nowMs / BUCKET_MS - bucketId[i] >= BUCKETS) continue
            if (bucketMin[i] < lo) lo = bucketMin[i]
            if (bucketMax[i] > hi) hi = bucketMax[i]
        }
        return if (lo == Float.MAX_VALUE) 0f else hi - lo
    }

    /** 최근 5초에 모은 유효 음높이의 길이(빠른 틱 수 × 20ms). */
    private fun pitchWindowMs(nowMs: Long): Long {
        var n = 0
        for (i in 0 until BUCKETS) {
            if (bucketId[i] == Long.MIN_VALUE || nowMs / BUCKET_MS - bucketId[i] >= BUCKETS) continue
            n += bucketN[i]
        }
        return n * HapticTuning.FOLLOW_TICK_MS
    }

    private fun median3(): Float {
        val n = min(rawCount, 3)
        if (n == 1) return rawTone[0]
        if (n == 2) return (rawTone[0] + rawTone[1]) / 2f
        val a = rawTone[0]
        val b = rawTone[1]
        val c = rawTone[2]
        return max(min(a, b), min(max(a, b), c))
    }

    private companion object {
        const val SILENT_DB = -100f

        /** 진폭이 시간상수 하나만큼 지나면 1/e, 곧 8.686dB 줄어든다. */
        const val DB_PER_TAU = 8.686f

        /** 음높이 모드를 막는 "새 소리 같은 것" 의 기준. 종류와 상관없이 크게 오른 것만 본다. */
        const val ONSET_CANDIDATE_DB = 8f

        const val BACK = 8
        const val FAST_RING = 50
        const val BUCKETS = 25
        const val BUCKET_MS = 200L
        const val REVS = 16

        val LN2 = ln(2.0).toFloat()

        fun log2(x: Float): Float = ln(max(x, 1e-9f)) / LN2

        /** [a] 의 앞 [n] 개의 중앙값. 삽입 정렬로 제자리에서 정렬한다(n ≤ 50). */
        fun median(a: FloatArray, n: Int): Float {
            for (i in 1 until n) {
                val v = a[i]
                var j = i - 1
                while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j-- }
                a[j + 1] = v
            }
            return if (n % 2 == 1) a[n / 2] else (a[n / 2 - 1] + a[n / 2]) / 2f
        }
    }
}

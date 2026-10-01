package com.example.soundvisualizer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.soundvisualizer.direction.ArrivalDelayEstimator
import com.example.soundvisualizer.direction.ScreenSide
import com.example.soundvisualizer.direction.ScreenSideMapper
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 가로 좌우 방향(#248)을 앱에 연결하기 전에, 이 기기에서 소리 없이 잴 수 있는 것을 잰다. 소리를 내지 않고, 들은 소리는
 * 숫자만 계산하고 버린다. 결과는 `SvMicDirection` 로그로 남긴다.
 *
 * 1. **두 마이크 배치**: `MIC`·`CAMCORDER` 스테레오에서 어느 채널이 위쪽 마이크인지, 두 마이크 거리가 얼마인지.
 * 2. **방향을 지어내지 않는지**: 일부러 낸 소리 없이 방의 소리만 들을 때 [ArrivalDelayEstimator] 가 얼마나 자주
 *    판단하는지, 판단하면 가로 화면에서 어느 쪽으로 나오는지.
 * 3. **AI 입력이 얼마나 달라지는지**: 지금 쓰는 `VOICE_RECOGNITION` 과 바꿀 후보인 `MIC` 의 각 채널을 차례로 열어
 *    크기와 대역별 크기를 비교한다. 둘을 동시에 열면 무엇이 오는지도 본다(같은 흐름을 나눠 받는지).
 *
 * 3번은 AI 입력 변경을 검토할 때 볼 기기별 근거다(가람 님, #248). 기본으로는 건너뛴다. 이렇게 돌린다(약 50초).
 * ```
 * adb shell am instrument -w -e micDirectionProbe true \
 *   -e class com.example.soundvisualizer.MicDirectionProbeInstrumentedTest \
 *   com.example.soundvisualizer.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class MicDirectionProbeInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun probeDirectionAndAiInput() {
        assumeTrue(
            "마이크를 여는 측정이라 기본으로는 건너뛴다. -e micDirectionProbe true 로 돌린다",
            InstrumentationRegistry.getArguments().getString("micDirectionProbe") == "true"
        )
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        Log.i(TAG, "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")

        for ((name, source) in DIRECTION_SOURCES) probeDirection(name, source)
        checkConcurrent()
        compareAiInput()
    }

    // ---------------- 1·2. 마이크 배치와 방향 ----------------

    private fun probeDirection(name: String, source: Int) {
        val record = open(source) ?: run {
            Log.i(TAG, "$name: 스테레오로 열리지 않음")
            return
        }
        try {
            record.startRecording()
            val mics = activeMics(record)
            val layout = layoutOf(mics)
            Log.i(TAG, "$name: ch=${record.channelCount} activeMics=${mics.joinToString { describe(it) }} layout=$layout")
            if (layout == null) Log.i(TAG, "$name: 채널 배치로는 위쪽 마이크 채널을 알 수 없다. 아래 좌우 집계는 위쪽=ch1 로 가정한다")
            if (record.channelCount != 2) return

            val frames = recordFrames(record, DIRECTION_MS)
            val maxLag = ArrivalDelayEstimator.maxLagFor(layout?.distanceMeters ?: DEFAULT_DISTANCE_M, RATE)
            val estimator = ArrivalDelayEstimator(WINDOW, maxLag)
            val topChannel = layout?.topChannel ?: 1

            var windows = 0
            var quiet = 0
            var rejected = 0
            val lags = ArrayList<Float>()
            val correlations = ArrayList<Float>()
            val sides90 = IntArray(ScreenSide.entries.size)
            val sides270 = IntArray(ScreenSide.entries.size)
            var start = 0
            while ((start + WINDOW) * 2 <= frames.size) {
                windows++
                val estimate = estimator.estimate(frames, start)
                when {
                    estimate == null && windowRms(frames, start) < ArrivalDelayEstimator.DEFAULT_MIN_RMS -> quiet++
                    estimate == null -> rejected++
                    else -> {
                        lags += estimate.lagSamples
                        correlations += estimate.correlation
                        val dead = maxLag * DEAD_ZONE_RATIO
                        sides90[ScreenSideMapper.side(estimate.lagSamples, topChannel, Surface.ROTATION_90, dead).ordinal]++
                        sides270[ScreenSideMapper.side(estimate.lagSamples, topChannel, Surface.ROTATION_270, dead).ordinal]++
                    }
                }
                start += HOP
            }
            val rms = channelRms(frames)
            Log.i(
                TAG,
                "$name: ${frames.size / 2} frames, rmsDb=${rms.joinToString { "%.1f".format(db(it)) }}, maxLag=$maxLag, " +
                    "corrLR=${"%.3f".format(zeroLagCorrelation(frames))} windows=$windows quiet=$quiet rejected=$rejected judged=${lags.size}"
            )
            if (lags.isNotEmpty()) {
                Log.i(TAG, "$name: lag histogram ${histogram(lags, maxLag)}")
                Log.i(TAG, "$name: correlation median=${"%.2f".format(median(correlations))} lag |median|=${"%.1f".format(median(lags.map { abs(it) }))}")
                Log.i(TAG, "$name: ROTATION_90 L/C/R=${sides90.joinToString("/")}  ROTATION_270 L/C/R=${sides270.joinToString("/")}")
            }
        } finally {
            record.release()
        }
    }

    // ---------------- 3. AI 입력 비교 ----------------

    /**
     * 한 앱이 `VOICE_RECOGNITION` 과 `MIC` 를 동시에 열면 무엇이 오는지 본다. 같은 입력 흐름을 나눠 받는다면, AI 는
     * `VOICE_RECOGNITION` 으로 두고 방향만 `MIC` 스테레오로 받는 길은 이 기기에서 막힌다.
     */
    private fun checkConcurrent() {
        val voice = open(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        val mic = open(MediaRecorder.AudioSource.MIC)
        try {
            if (voice == null || mic == null) {
                Log.i(TAG, "concurrent: 열리지 않음 voice=${voice != null} mic=${mic != null}")
                return
            }
            voice.startRecording()
            mic.startRecording()
            if (voice.recordingState != AudioRecord.RECORDSTATE_RECORDING || mic.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.i(TAG, "concurrent: 동시에 녹음되지 않음 voice=${voice.recordingState} mic=${mic.recordingState}")
                return
            }
            val (v, m) = recordTogether(voice, mic, CONCURRENT_MS)
            val (lag, value) = bestCorrelation(channel(v, 0), channel(m, 0), maxLag = RATE / 10)
            val shared = value > 0.999 && abs(db(rms(channel(v, 0))) - db(rms(channel(m, 0)))) < 0.1
            Log.i(
                TAG,
                "concurrent: silenced voice=${voice.activeRecordingConfiguration?.isClientSilenced} " +
                    "mic=${mic.activeRecordingConfiguration?.isClientSilenced} " +
                    "voice~mic ch0 peak=${"%.3f".format(value)} at lag=$lag, mic corrLR=${"%.3f".format(zeroLagCorrelation(m))}, " +
                    "sharedStream=$shared"
            )
        } finally {
            voice?.release()
            mic?.release()
        }
    }

    /**
     * 지금 AI 입력(`VOICE_RECOGNITION`)과 후보(`MIC` 의 두 채널)를 따로 열어 크기와 대역별 크기를 비교한다.
     * 동시에 열면 같은 흐름을 나눠 받으므로([checkConcurrent]) 차례로 연다. 그 사이 방 소리가 바뀌는 만큼을 줄이려고
     * 짧게 번갈아 [PAIRS] 번 재서, 쌍마다의 차이를 중앙값과 범위로 낸다. 범위가 좁을수록 소스 처리의 차이다.
     */
    private fun compareAiInput() {
        val diffs = Array(2) { Array(OCTAVES.size) { ArrayList<Double>() } }
        val rmsDiffs = Array(2) { ArrayList<Double>() }
        repeat(PAIRS) {
            val v = openAndRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, PAIR_MS) ?: return
            val m = openAndRecord(MediaRecorder.AudioSource.MIC, PAIR_MS) ?: return
            val voice = channel(v, 0)
            val bandsV = octaveBands(voice)
            for (c in 0..1) {
                val mic = channel(m, c)
                val bandsM = octaveBands(mic)
                rmsDiffs[c] += db(rms(voice)) - db(rms(mic))
                for (i in OCTAVES.indices) diffs[c][i] += bandsV[i] - bandsM[i]
            }
        }
        for (c in 0..1) {
            Log.i(
                TAG,
                "compare: VOICE_RECOGNITION - MIC ch$c (번갈아 ${PAIRS}쌍, 각 ${PAIR_MS / 1000}초) rms ${spread(rmsDiffs[c])}, octave " +
                    OCTAVES.indices.joinToString { i -> "${OCTAVES[i].toInt()}Hz ${spread(diffs[c][i])}" }
            )
        }
    }

    /** 중앙값[최소..최대] (dB). */
    private fun spread(values: List<Double>): String {
        val sorted = values.sorted()
        return "%+.1f[%+.1f..%+.1f]".format(sorted[sorted.size / 2], sorted.first(), sorted.last())
    }

    // ---------------- 녹음 ----------------

    // 권한은 위에서 준다.
    @SuppressLint("MissingPermission")
    private fun open(source: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        if (min <= 0) return null
        return try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(max(min * 4, 65536))
                .build()
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: Exception) {
            Log.i(TAG, "source $source: build failed ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun openAndRecord(source: Int, ms: Long): FloatArray? {
        val record = open(source) ?: return null
        return try {
            record.startRecording()
            recordFrames(record, ms)
        } finally {
            record.release()
        }
    }

    /** [ms] 동안 받은 interleaved 스테레오. 처음 [WARM_UP_MS] 는 마이크가 켜지며 들어오는 소리라 버린다. */
    private fun recordFrames(record: AudioRecord, ms: Long): FloatArray {
        val out = FloatArray((RATE * ms / 1000).toInt() * 2)
        val chunk = FloatArray(READ_FLOATS)
        val warmUpEnd = SystemClock.uptimeMillis() + WARM_UP_MS
        var filled = 0
        while (filled < out.size) {
            val n = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) break
            if (SystemClock.uptimeMillis() < warmUpEnd) continue
            val take = minOf(n, out.size - filled)
            System.arraycopy(chunk, 0, out, filled, take)
            filled += take
        }
        return if (filled == out.size) out else out.copyOf(filled - filled % 2)
    }

    /** 두 녹음을 번갈아 같은 양씩 읽는다. 둘 다 48kHz 라 끝까지 가도 버퍼가 밀리지 않는다. */
    private fun recordTogether(a: AudioRecord, b: AudioRecord, ms: Long): Pair<FloatArray, FloatArray> {
        val size = (RATE * ms / 1000).toInt() * 2
        val outA = FloatArray(size)
        val outB = FloatArray(size)
        val chunk = FloatArray(READ_FLOATS)
        val warmUpEnd = SystemClock.uptimeMillis() + WARM_UP_MS
        var filledA = 0
        var filledB = 0
        while (filledA < size || filledB < size) {
            if (filledA < size) filledA = readInto(a, chunk, outA, filledA, warmUpEnd) ?: break
            if (filledB < size) filledB = readInto(b, chunk, outB, filledB, warmUpEnd) ?: break
        }
        return outA.copyOf(filledA - filledA % 2) to outB.copyOf(filledB - filledB % 2)
    }

    private fun readInto(record: AudioRecord, chunk: FloatArray, out: FloatArray, filled: Int, warmUpEnd: Long): Int? {
        val n = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
        if (n <= 0) return null
        if (SystemClock.uptimeMillis() < warmUpEnd) return filled
        val take = minOf(n, out.size - filled)
        System.arraycopy(chunk, 0, out, filled, take)
        return filled + take
    }

    // ---------------- 마이크 배치 ----------------

    private data class Layout(val topChannel: Int, val distanceMeters: Double)

    private fun activeMics(record: AudioRecord): List<MicrophoneInfo> = try {
        record.activeMicrophones
    } catch (e: Exception) {
        Log.i(TAG, "activeMicrophones failed: ${e.message}")
        emptyList()
    }

    /** 두 채널이 서로 다른 마이크로 들어오면, 위쪽(y 가 큰) 마이크의 채널과 두 마이크 거리. */
    private fun layoutOf(mics: List<MicrophoneInfo>): Layout? {
        val byChannel = HashMap<Int, MicrophoneInfo>()
        for (mic in mics) for (mapping in mic.channelMapping) byChannel[mapping.first] = mic
        val zero = byChannel[0] ?: return null
        val one = byChannel[1] ?: return null
        if (zero.id == one.id) return null
        val p0 = zero.position
        val p1 = one.position
        val distance = sqrt(sq(p0.x - p1.x) + sq(p0.y - p1.y) + sq(p0.z - p1.z))
        return Layout(topChannel = if (p0.y > p1.y) 0 else 1, distanceMeters = distance)
    }

    private fun describe(mic: MicrophoneInfo): String {
        val p = mic.position
        return "id=${mic.id}@(%.3f, %.3f, %.3f) ch=${mic.channelMapping.joinToString { "${it.first}:${it.second}" }}"
            .format(p.x, p.y, p.z)
    }

    // ---------------- 숫자 ----------------

    private fun channel(frames: FloatArray, c: Int): FloatArray = FloatArray(frames.size / 2) { frames[it * 2 + c] }

    private fun channelRms(frames: FloatArray): List<Double> = listOf(rms(channel(frames, 0)), rms(channel(frames, 1)))

    /** 두 채널의 시간차 없는 상관. 1 이면 같은 소리를 복제해 받는다. */
    private fun zeroLagCorrelation(frames: FloatArray): Double {
        var cross = 0.0
        var e0 = 0.0
        var e1 = 0.0
        var i = 0
        while (i + 1 < frames.size) {
            cross += frames[i].toDouble() * frames[i + 1]
            e0 += sq(frames[i])
            e1 += sq(frames[i + 1])
            i += 2
        }
        return if (e0 > 0 && e1 > 0) cross / sqrt(e0 * e1) else Double.NaN
    }

    private fun windowRms(frames: FloatArray, start: Int): Float {
        var s0 = 0.0
        var s1 = 0.0
        for (i in start until start + WINDOW) {
            s0 += sq(frames[i * 2])
            s1 += sq(frames[i * 2 + 1])
        }
        return sqrt(minOf(s0, s1) / WINDOW).toFloat()
    }

    private fun rms(x: FloatArray): Double = if (x.isEmpty()) 0.0 else sqrt(x.sumOf { sq(it) } / x.size)

    private fun peak(x: FloatArray): Double = x.maxOfOrNull { abs(it).toDouble() } ?: 0.0

    private fun db(v: Double): Double = if (v <= 0.0) -200.0 else 20 * log10(v)

    private fun sq(v: Float): Double = v.toDouble() * v

    private fun median(values: List<Float>): Float = values.sorted()[values.size / 2]

    /** 시간차를 4샘플 칸으로 센다. */
    private fun histogram(lags: List<Float>, maxLag: Int): String {
        val counts = sortedMapOf<Int, Int>()
        for (lag in lags) {
            val bin = Math.floorDiv(lag.toInt().coerceIn(-maxLag, maxLag), 4) * 4
            counts[bin] = (counts[bin] ?: 0) + 1
        }
        return counts.entries.joinToString { "${it.key}..${it.key + 3}:${it.value}" }
    }

    /** 옥타브 대역별 평균 세기(dB, 상대값). Hann 창 4096 점을 반씩 겹쳐 평균한다. */
    private fun octaveBands(x: FloatArray): DoubleArray {
        val n = 4096
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val power = DoubleArray(n / 2)
        var count = 0
        var start = 0
        while (start + n <= x.size) {
            for (i in 0 until n) {
                re[i] = x[start + i] * (0.5 - 0.5 * cos(2 * PI * i / (n - 1)))
                im[i] = 0.0
            }
            fft(re, im, inverse = false)
            for (k in 0 until n / 2) power[k] += re[k] * re[k] + im[k] * im[k]
            count++
            start += n / 2
        }
        return DoubleArray(OCTAVES.size) { band ->
            val low = OCTAVES[band] / sqrt(2.0)
            val high = OCTAVES[band] * sqrt(2.0)
            var sum = 0.0
            var bins = 0
            for (k in 1 until n / 2) {
                val f = k.toDouble() * RATE / n
                if (f >= low && f < high) {
                    sum += power[k]
                    bins++
                }
            }
            if (bins == 0 || count == 0 || sum <= 0.0) -200.0 else 10 * log10(sum / bins / count)
        }
    }

    /** 가운데 약 1.4초를 FFT 로 상호상관해 ±[maxLag] 안의 봉우리를 찾는다. 두 녹음은 시작 시각이 달라 시간이 어긋나 있다. */
    private fun bestCorrelation(a: FloatArray, b: FloatArray, maxLag: Int): Pair<Int, Double> {
        val segment = 65_536
        val n = segment * 2
        if (a.size < segment || b.size < segment) return 0 to Double.NaN
        val offset = (minOf(a.size, b.size) - segment) / 2
        val reA = DoubleArray(n)
        val imA = DoubleArray(n)
        val reB = DoubleArray(n)
        val imB = DoubleArray(n)
        var energyA = 0.0
        var energyB = 0.0
        for (i in 0 until segment) {
            reA[i] = a[offset + i].toDouble()
            reB[i] = b[offset + i].toDouble()
            energyA += reA[i] * reA[i]
            energyB += reB[i] * reB[i]
        }
        if (energyA <= 0.0 || energyB <= 0.0) return 0 to Double.NaN
        fft(reA, imA, inverse = false)
        fft(reB, imB, inverse = false)
        // conj(A) * B
        for (k in 0 until n) {
            val r = reA[k] * reB[k] + imA[k] * imB[k]
            val i = reA[k] * imB[k] - imA[k] * reB[k]
            reA[k] = r
            imA[k] = i
        }
        fft(reA, imA, inverse = true)
        val norm = sqrt(energyA * energyB)
        var bestLag = 0
        var best = 0.0
        for (lag in -maxLag..maxLag) {
            val value = reA[(lag + n) % n] / norm
            if (abs(value) > abs(best)) {
                best = value
                bestLag = lag
            }
        }
        return bestLag to best
    }

    /** 제자리 radix-2 FFT. [inverse] 면 1/n 까지 곱한다. */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = 2 * PI / len * if (inverse) 1 else -1
            val wRe = cos(angle)
            val wIm = sin(angle)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) {
            re[i] /= n
            im[i] /= n
        }
    }

    private companion object {
        const val TAG = "SvMicDirection"
        const val RATE = 48_000
        const val WINDOW = 2048
        const val HOP = 1024
        const val READ_FLOATS = 2048
        const val WARM_UP_MS = 300L
        const val DIRECTION_MS = 10_000L
        const val CONCURRENT_MS = 4_000L
        const val PAIRS = 5
        const val PAIR_MS = 2_000L

        /** 배치를 알 수 없을 때 쓰는 두 마이크 거리(S25+ 측정값). */
        const val DEFAULT_DISTANCE_M = 0.1428

        /** 시간차 한계의 이 비율보다 작으면 가운데로 둔다. */
        const val DEAD_ZONE_RATIO = 0.3f

        val DIRECTION_SOURCES = listOf(
            "MIC" to MediaRecorder.AudioSource.MIC,
            "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER
        )

        val OCTAVES = doubleArrayOf(125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    }
}

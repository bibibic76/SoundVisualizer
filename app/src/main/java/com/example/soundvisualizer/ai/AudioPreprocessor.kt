package com.example.soundvisualizer.ai

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * tools/ai_reference/preprocess.py 와 동일한 알고리즘.
 * 입력: 16kHz mono float (권장 길이 15600)
 * 출력: flat log-mel float[6144], layout = time * 64 + mel  (== logical [1,1,96,64])
 *
 * 기존 AudioEngine/C++ DSP와 독립. 알고리즘을 "개선"하지 않고 레퍼런스 동작을 그대로 복제한다.
 */
class AudioPreprocessor {

    companion object {
        const val SAMPLE_RATE = 16000
        const val MEL_BINS = 64
        const val TIME_FRAMES = 96
        const val WINDOW_LENGTH = 400
        const val HOP_LENGTH = 160
        const val FFT_SIZE = 512
        const val MEL_FMIN = 125f
        const val MEL_FMAX = 7500f
        const val LOG_EPS = 0.001f
        const val REQUIRED_MONO_16K_SAMPLES = WINDOW_LENGTH + HOP_LENGTH * (TIME_FRAMES - 1) // 15600
        const val LOG_MEL_SIZE = TIME_FRAMES * MEL_BINS // 6144
        private const val FREQ_BINS = FFT_SIZE / 2 + 1 // 257
    }

    private val hannWindow: FloatArray = createHannWindow(WINDOW_LENGTH)
    private val melFilterBank: Array<FloatArray> =
        createMelFilterBank(MEL_BINS, FFT_SIZE, SAMPLE_RATE, MEL_FMIN, MEL_FMAX)
    private val melStartBins: IntArray
    private val melEndBins: IntArray
    private val bitReversed: IntArray = createBitReversedIndices(FFT_SIZE)

    // Reusable buffers (not thread-safe across concurrent calls on same instance)
    private val preprocessed = FloatArray(REQUIRED_MONO_16K_SAMPLES)
    private val fftReal = DoubleArray(FFT_SIZE)
    private val fftImag = DoubleArray(FFT_SIZE)
    private val power = FloatArray(FREQ_BINS)

    init {
        val starts = IntArray(MEL_BINS)
        val ends = IntArray(MEL_BINS)
        for (m in 0 until MEL_BINS) {
            var start = 0
            while (start < FREQ_BINS && melFilterBank[m][start] == 0f) start++
            var last = FREQ_BINS - 1
            while (last >= start && melFilterBank[m][last] == 0f) last--
            starts[m] = start
            ends[m] = min(FREQ_BINS, last + 1)
        }
        melStartBins = starts
        melEndBins = ends
    }

    /**
     * @param mono16k 16kHz mono PCM float. 길이가 15600보다 짧으면 뒤를 0-pad,
     *                길면 앞에서 required 길이만 사용 (ComputeLogMelSpectrogram 과 동일).
     */
    fun computeLogMelSpectrogram(mono16k: FloatArray): FloatArray {
        preprocessed.fill(0f)
        val copyLen = min(mono16k.size, REQUIRED_MONO_16K_SAMPLES)
        if (copyLen > 0) {
            System.arraycopy(mono16k, 0, preprocessed, 0, copyLen)
        }

        val logMel = FloatArray(LOG_MEL_SIZE)
        for (t in 0 until TIME_FRAMES) {
            val start = t * HOP_LENGTH
            for (i in 0 until FFT_SIZE) {
                if (i < WINDOW_LENGTH) {
                    fftReal[i] = (preprocessed[start + i] * hannWindow[i]).toDouble()
                } else {
                    fftReal[i] = 0.0
                }
                fftImag[i] = 0.0
            }

            fftInPlace(fftReal, fftImag, bitReversed)

            for (k in 0 until FREQ_BINS) {
                val re = fftReal[k]
                val im = fftImag[k]
                power[k] = (re * re + im * im).toFloat()
            }

            for (mel in 0 until MEL_BINS) {
                var melSum = 0.0
                val startBin = melStartBins[mel]
                val endBin = melEndBins[mel]
                val weights = melFilterBank[mel]
                for (k in startBin until endBin) {
                    melSum += weights[k].toDouble() * power[k].toDouble()
                }
                val value = ln(melSum + LOG_EPS.toDouble()).toFloat()
                logMel[t * MEL_BINS + mel] = value
            }
        }
        return logMel
    }

    /** Logical tensor view helper: [1,1,96,64] row-major same as flat layout. */
    fun reshapeToNchw(logMelFlat: FloatArray): Array<Array<Array<FloatArray>>> {
        require(logMelFlat.size == LOG_MEL_SIZE)
        val out = Array(1) {
            Array(1) {
                Array(TIME_FRAMES) { t ->
                    FloatArray(MEL_BINS) { m -> logMelFlat[t * MEL_BINS + m] }
                }
            }
        }
        return out
    }
}

// --- static helpers mirroring preprocess.py ---

internal fun createHannWindow(length: Int): FloatArray {
    val window = FloatArray(length)
    if (length <= 1) return window
    for (n in 0 until length) {
        // 0.5 - 0.5 * cos(2π n / length)  — denominator is length, not length-1
        window[n] = (0.5 - 0.5 * cos(2.0 * PI * n / length)).toFloat()
    }
    return window
}

internal fun hzToMel(hz: Float): Float = (2595.0 * log10(1.0 + hz / 700.0)).toFloat()

internal fun melToHz(mel: Float): Float = (700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)).toFloat()

internal fun createMelFilterBank(
    melBins: Int,
    fftSize: Int,
    sampleRate: Int,
    fMin: Float,
    fMax: Float
): Array<FloatArray> {
    val freqBins = fftSize / 2 + 1
    val weights = Array(melBins) { FloatArray(freqBins) }

    val melMin = hzToMel(fMin)
    val melMax = hzToMel(fMax)
    val melPoints = DoubleArray(melBins + 2)
    for (i in melPoints.indices) {
        melPoints[i] = melMin + (melMax - melMin) * i / (melBins + 1.0)
    }

    val bin = IntArray(melBins + 2)
    for (i in melPoints.indices) {
        val hz = melToHz(melPoints[i].toFloat())
        var b = floor((fftSize + 1) * hz / sampleRate).toInt()
        b = max(0, min(freqBins - 1, b))
        bin[i] = b
    }

    for (m in 0 until melBins) {
        val f0 = bin[m]
        val f1 = bin[m + 1]
        val f2 = bin[m + 2]
        if (f1 <= f0 || f2 <= f1) continue
        for (k in f0 until f1) {
            weights[m][k] = (k - f0).toFloat() / (f1 - f0).toFloat()
        }
        for (k in f1 until f2) {
            weights[m][k] = (f2 - k).toFloat() / (f2 - f1).toFloat()
        }
    }
    return weights
}

internal fun createBitReversedIndices(n: Int): IntArray {
    val bits = Integer.numberOfTrailingZeros(n)
    require(1 shl bits == n) { "FFT size must be power of two" }
    val arr = IntArray(n)
    for (i in 0 until n) {
        var v = i
        var r = 0
        for (b in 0 until bits) {
            r = (r shl 1) or (v and 1)
            v = v shr 1
        }
        arr[i] = r
    }
    return arr
}

/**
 * FFTInPlace — radix-2 Cooley–Tukey, bit-reversed input order.
 * Matching System.Numerics.Complex double precision path.
 */
internal fun fftInPlace(real: DoubleArray, imag: DoubleArray, bitReversed: IntArray) {
    val n = real.size
    require(imag.size == n && bitReversed.size == n)

    for (i in 0 until n) {
        val j = bitReversed[i]
        if (j > i) {
            val tr = real[i]; real[i] = real[j]; real[j] = tr
            val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
        }
    }

    var len = 2
    while (len <= n) {
        val ang = -2.0 * PI / len
        val wLenRe = cos(ang)
        val wLenIm = sin(ang)
        var i = 0
        while (i < n) {
            var wRe = 1.0
            var wIm = 0.0
            val half = len shr 1
            for (j in 0 until half) {
                val uRe = real[i + j]
                val uIm = imag[i + j]
                val vRe = real[i + j + half] * wRe - imag[i + j + half] * wIm
                val vIm = real[i + j + half] * wIm + imag[i + j + half] * wRe
                real[i + j] = uRe + vRe
                imag[i + j] = uIm + vIm
                real[i + j + half] = uRe - vRe
                imag[i + j + half] = uIm - vIm
                val nextWRe = wRe * wLenRe - wIm * wLenIm
                val nextWIm = wRe * wLenIm + wIm * wLenRe
                wRe = nextWRe
                wIm = nextWIm
            }
            i += len
        }
        len = len shl 1
    }
}

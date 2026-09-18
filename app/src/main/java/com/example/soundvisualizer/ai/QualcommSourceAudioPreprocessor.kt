package com.example.soundvisualizer.ai

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reproduction of the source frontend used by Qualcomm's YAMNet recipe.
 *
 * Provenance:
 * - qai-hub-models commit 7925bdd04586a87b09914164903f51b3f889c4aa
 *   - yamnet/app.py::preprocessing_yamnet_from_source
 *   - yamnet/model.py pins torch_audioset commit e8852c5
 * - w-hc/torch_audioset commit e8852c53becef811784754a2de9c4617d8db2156
 *   - torch_input_processing.py::WaveformToInput
 *   - torch_input_processing.py::VGGishLogMelSpectrogram
 *
 * Contract differences from [AudioPreprocessor]: centered 512-point STFT with
 * reflect padding, a centered 400-point periodic Hann window, magnitude (not
 * power), and torchaudio's default HTK mel filter bank.
 *
 * This class is intentionally separate from the production preprocessor while
 * Issue #145 measures parity and YAMNet-only behavior. It is not thread-safe.
 */
internal class QualcommSourceAudioPreprocessor {

    companion object {
        const val SAMPLE_RATE = AudioPreprocessor.SAMPLE_RATE
        const val MEL_BINS = AudioPreprocessor.MEL_BINS
        const val TIME_FRAMES = AudioPreprocessor.TIME_FRAMES
        const val WINDOW_LENGTH = AudioPreprocessor.WINDOW_LENGTH
        const val HOP_LENGTH = AudioPreprocessor.HOP_LENGTH
        const val FFT_SIZE = AudioPreprocessor.FFT_SIZE
        const val MEL_FMIN = AudioPreprocessor.MEL_FMIN
        const val MEL_FMAX = AudioPreprocessor.MEL_FMAX
        const val LOG_EPS = AudioPreprocessor.LOG_EPS
        const val REQUIRED_MONO_16K_SAMPLES = AudioPreprocessor.REQUIRED_MONO_16K_SAMPLES
        const val LOG_MEL_SIZE = AudioPreprocessor.LOG_MEL_SIZE

        private const val FREQ_BINS = FFT_SIZE / 2 + 1
        private const val REFLECT_PADDING = FFT_SIZE / 2
        private const val WINDOW_OFFSET = (FFT_SIZE - WINDOW_LENGTH) / 2
    }

    private val hannWindow = createDoubleHannWindow(WINDOW_LENGTH)
    private val melFilterBank = createTorchaudioHtkMelFilterBank(
        MEL_BINS,
        FFT_SIZE,
        SAMPLE_RATE,
        MEL_FMIN.toDouble(),
        MEL_FMAX.toDouble(),
    )
    private val melStartBins: IntArray
    private val melEndBins: IntArray
    private val bitReversed = createBitReversedIndices(FFT_SIZE)

    private val fitted = FloatArray(REQUIRED_MONO_16K_SAMPLES)
    private val fftReal = DoubleArray(FFT_SIZE)
    private val fftImag = DoubleArray(FFT_SIZE)
    private val magnitude = DoubleArray(FREQ_BINS)

    init {
        melStartBins = IntArray(MEL_BINS)
        melEndBins = IntArray(MEL_BINS)
        for (mel in 0 until MEL_BINS) {
            var start = 0
            while (start < FREQ_BINS && melFilterBank[mel][start] == 0.0) start++
            var last = FREQ_BINS - 1
            while (last >= start && melFilterBank[mel][last] == 0.0) last--
            melStartBins[mel] = start
            melEndBins[mel] = min(FREQ_BINS, last + 1)
        }
    }

    /**
     * Returns a flat [1, 1, 96, 64] tensor in time-major order.
     * Input is truncated or right-zero-padded to 15,600 samples before the
     * centered STFT reflect padding is applied, matching the pinned source.
     */
    fun computeLogMelSpectrogram(mono16k: FloatArray): FloatArray {
        fitted.fill(0f)
        val copyLength = min(mono16k.size, REQUIRED_MONO_16K_SAMPLES)
        if (copyLength > 0) {
            System.arraycopy(mono16k, 0, fitted, 0, copyLength)
        }

        val logMel = FloatArray(LOG_MEL_SIZE)
        for (time in 0 until TIME_FRAMES) {
            fftReal.fill(0.0)
            fftImag.fill(0.0)

            // torch.stft(center=true, pad_mode="reflect") pads 256 samples on
            // each side, then centers the 400-point window in the 512-point FFT.
            val unpaddedFrameStart = time * HOP_LENGTH - REFLECT_PADDING
            for (windowIndex in 0 until WINDOW_LENGTH) {
                val fftIndex = WINDOW_OFFSET + windowIndex
                val sourceIndex = reflectIndex(
                    unpaddedFrameStart + fftIndex,
                    REQUIRED_MONO_16K_SAMPLES,
                )
                fftReal[fftIndex] = fitted[sourceIndex].toDouble() * hannWindow[windowIndex]
            }

            fftInPlace(fftReal, fftImag, bitReversed)
            for (frequency in 0 until FREQ_BINS) {
                val real = fftReal[frequency]
                val imaginary = fftImag[frequency]
                magnitude[frequency] = sqrt(real * real + imaginary * imaginary)
            }

            for (mel in 0 until MEL_BINS) {
                var sum = 0.0
                val weights = melFilterBank[mel]
                for (frequency in melStartBins[mel] until melEndBins[mel]) {
                    sum += weights[frequency] * magnitude[frequency]
                }
                logMel[time * MEL_BINS + mel] = ln(sum + LOG_EPS.toDouble()).toFloat()
            }
        }
        return logMel
    }
}

private fun reflectIndex(index: Int, length: Int): Int {
    require(length > 1) { "reflect padding requires at least two samples" }
    var reflected = index
    while (reflected < 0 || reflected >= length) {
        reflected = if (reflected < 0) -reflected else 2 * length - 2 - reflected
    }
    return reflected
}

private fun createDoubleHannWindow(length: Int): DoubleArray =
    DoubleArray(length) { index ->
        0.5 - 0.5 * cos(2.0 * PI * index / length)
    }

private fun createTorchaudioHtkMelFilterBank(
    melBins: Int,
    fftSize: Int,
    sampleRate: Int,
    minimumFrequency: Double,
    maximumFrequency: Double,
): Array<DoubleArray> {
    val frequencyBins = fftSize / 2 + 1
    val minimumMel = hertzToHtkMel(minimumFrequency)
    val maximumMel = hertzToHtkMel(maximumFrequency)
    val frequencyEdges = DoubleArray(melBins + 2) { edge ->
        val mel = minimumMel + (maximumMel - minimumMel) * edge / (melBins + 1.0)
        htkMelToHertz(mel)
    }
    val weights = Array(melBins) { DoubleArray(frequencyBins) }

    for (mel in 0 until melBins) {
        val lower = frequencyEdges[mel]
        val center = frequencyEdges[mel + 1]
        val upper = frequencyEdges[mel + 2]
        for (frequencyBin in 0 until frequencyBins) {
            val frequency = frequencyBin * (sampleRate / 2.0) / (frequencyBins - 1)
            val downSlope = (frequency - lower) / (center - lower)
            val upSlope = (upper - frequency) / (upper - center)
            weights[mel][frequencyBin] = max(0.0, min(downSlope, upSlope))
        }
    }
    return weights
}

private fun hertzToHtkMel(hertz: Double): Double = 1127.0 * ln1p(hertz / 700.0)

private fun htkMelToHertz(mel: Double): Double = 700.0 * expm1(mel / 1127.0)

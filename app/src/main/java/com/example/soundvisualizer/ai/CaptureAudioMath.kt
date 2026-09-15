package com.example.soundvisualizer.ai

import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.sin

/**
 * Capture→16k helpers (downmix + ResampleMonoFloatTo16kCustom).
 */
object CaptureAudioMath {

    const val TARGET_SAMPLE_RATE = 16000
    const val REQUIRED_MONO_16K_SAMPLES = AudioPreprocessor.REQUIRED_MONO_16K_SAMPLES // 15600
    private const val FIR_TAPS = 96
    private const val FIR_HALF_TAPS = FIR_TAPS / 2
    private const val FIR_CUTOFF_HZ = 7800.0
    private const val FIR_KAISER_BETA = 8.6
    private const val MAX_CACHED_PHASES = 1024

    private data class FirTable(
        val phaseCount: Int,
        val coefficients: Array<FloatArray>
    )

    /** Per capture rate, reused across every 250ms AI tick. */
    private val firTables = HashMap<Int, FirTable>()

    fun captureSamplesForOneYamnetWindow(captureSampleRate: Int): Int {
        return ceil(
            REQUIRED_MONO_16K_SAMPLES * captureSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        ).toInt()
    }

    /**
     * Interleaved stereo/float PCM → mono frames.
     * channels==2 → (L+R)/2. Odd trailing sample dropped.
     */
    fun downmixInterleavedToMono(
        interleaved: FloatArray,
        floatCount: Int,
        channels: Int,
        dest: FloatArray,
        destOffset: Int = 0
    ): Int {
        if (floatCount <= 0 || channels <= 0) return 0
        val frames = floatCount / channels
        if (frames <= 0) return 0
        require(destOffset + frames <= dest.size)
        if (channels == 1) {
            System.arraycopy(interleaved, 0, dest, destOffset, frames)
            return frames
        }
        var si = 0
        var di = destOffset
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += interleaved[si++]
            }
            dest[di++] = sum / channels
        }
        return frames
    }

    /**
     * Fixed-length mono resample to 16kHz.
     *
     * Downsampling uses a 96-tap, Kaiser-windowed sinc low-pass FIR (7.8kHz cutoff,
     * beta 8.6) evaluated at the source position of each destination sample. Tables are
     * cached by source rate; 44.1kHz has 160 exact phases and 48kHz has one. Source
     * samples outside [0, sourceLength) are zero, matching the window's zero extension.
     */
    fun resampleMonoFloatTo16kCustom(
        source: FloatArray,
        sourceLength: Int,
        sourceSampleRate: Int,
        destination: FloatArray,
        destLength: Int = REQUIRED_MONO_16K_SAMPLES
    ) {
        require(destination.size >= destLength)
        for (i in 0 until destLength) destination[i] = 0f
        if (sourceLength <= 0) return

        if (sourceSampleRate == TARGET_SAMPLE_RATE) {
            val copyLen = minOf(sourceLength, source.size, destLength)
            System.arraycopy(source, 0, destination, 0, copyLen)
            return
        }

        if (sourceSampleRate < TARGET_SAMPLE_RATE) {
            linearResample(source, sourceLength, sourceSampleRate, destination, destLength)
            return
        }

        val sourceAvailable = minOf(sourceLength, source.size)
        val table = firTableFor(sourceSampleRate)
        for (i in 0 until destLength) {
            val positionNumerator = i.toLong() * sourceSampleRate.toLong()
            val center = (positionNumerator / TARGET_SAMPLE_RATE).toInt()
            val phase = phaseIndex(positionNumerator % TARGET_SAMPLE_RATE, table.phaseCount)
            val coefficients = table.coefficients[phase]
            var sum = 0.0
            for (tap in 0 until FIR_TAPS) {
                val sourceIndex = center + tap - FIR_HALF_TAPS + 1
                if (sourceIndex in 0 until sourceAvailable) {
                    sum += coefficients[tap].toDouble() * source[sourceIndex].toDouble()
                }
            }
            destination[i] = sum.toFloat()
        }
    }

    /** Preserve the previous interpolation behavior for the unsupported upsampling case. */
    private fun linearResample(
        source: FloatArray,
        sourceLength: Int,
        sourceSampleRate: Int,
        destination: FloatArray,
        destLength: Int
    ) {
        val sourceAvailable = minOf(sourceLength, source.size)
        val factor = sourceSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        for (i in 0 until destLength) {
            val sourcePosition = i * factor
            val index1 = sourcePosition.toInt()
            val index2 = index1 + 1
            if (index1 >= sourceAvailable) continue
            val alpha = (sourcePosition - index1).toFloat()
            val value1 = source[index1]
            val value2 = if (index2 < sourceAvailable) source[index2] else value1
            destination[i] = (1f - alpha) * value1 + alpha * value2
        }
    }

    @Synchronized
    private fun firTableFor(sourceSampleRate: Int): FirTable {
        require(sourceSampleRate > 0)
        return firTables.getOrPut(sourceSampleRate) {
            val exactPhaseCount = TARGET_SAMPLE_RATE / greatestCommonDivisor(
                sourceSampleRate,
                TARGET_SAMPLE_RATE
            )
            val phaseCount = minOf(exactPhaseCount, MAX_CACHED_PHASES)
            FirTable(
                phaseCount = phaseCount,
                coefficients = Array(phaseCount) { phase ->
                    createFirCoefficients(
                        sourceSampleRate = sourceSampleRate,
                        fractionalPosition = phase.toDouble() / phaseCount.toDouble()
                    )
                }
            )
        }
    }

    private fun phaseIndex(remainder: Long, phaseCount: Int): Int {
        if (phaseCount == 1) return 0
        val rounded = (remainder * phaseCount + TARGET_SAMPLE_RATE / 2) / TARGET_SAMPLE_RATE
        return (rounded % phaseCount).toInt()
    }

    private fun createFirCoefficients(
        sourceSampleRate: Int,
        fractionalPosition: Double
    ): FloatArray {
        val coefficients = FloatArray(FIR_TAPS)
        var sum = 0.0
        for (tap in 0 until FIR_TAPS) {
            val sampleOffset = tap - FIR_HALF_TAPS + 1
            val time = sampleOffset.toDouble() - fractionalPosition
            val normalizedRadius = minOf(1.0, abs(time) / FIR_HALF_TAPS.toDouble())
            val window = besselI0(
                FIR_KAISER_BETA * kotlin.math.sqrt(1.0 - normalizedRadius * normalizedRadius)
            ) / besselI0(FIR_KAISER_BETA)
            val normalizedCutoff = 2.0 * FIR_CUTOFF_HZ / sourceSampleRate.toDouble()
            val value = normalizedCutoff * sinc(normalizedCutoff * time) * window
            coefficients[tap] = value.toFloat()
            sum += value
        }
        for (tap in coefficients.indices) {
            coefficients[tap] = (coefficients[tap] / sum).toFloat()
        }
        return coefficients
    }

    private fun sinc(value: Double): Double {
        return if (abs(value) < 1e-12) 1.0 else sin(Math.PI * value) / (Math.PI * value)
    }

    private fun besselI0(value: Double): Double {
        var term = 1.0
        var sum = 1.0
        var k = 1
        while (true) {
            term *= (value * value / 4.0) / (k.toDouble() * k.toDouble())
            sum += term
            if (term < sum * 1e-15) return sum
            k++
        }
    }

    private fun greatestCommonDivisor(a: Int, b: Int): Int {
        var x = abs(a)
        var y = abs(b)
        while (y != 0) {
            val remainder = x % y
            x = y
            y = remainder
        }
        return x
    }
}

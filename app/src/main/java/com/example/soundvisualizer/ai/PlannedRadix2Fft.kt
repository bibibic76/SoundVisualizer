package com.example.soundvisualizer.ai

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reuses the twiddles of [fftInPlace] across the 96 STFT frames and subsequent ticks.
 * Generate each stage with the original recurrence, rather than cos(j * angle):
 * the latter changes rounding and therefore the model input. Execution preserves
 * the original butterfly order and double-precision arithmetic.
 */
internal class PlannedRadix2Fft(private val size: Int) {
    private val bitReversed = createBitReversedIndices(size)
    private val realTwiddles = DoubleArray(size - 1)
    private val imaginaryTwiddles = DoubleArray(size - 1)

    init {
        var length = 2
        while (length <= size) {
            val half = length shr 1
            val offset = half - 1
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            var real = 1.0
            var imaginary = 0.0
            for (j in 0 until half) {
                realTwiddles[offset + j] = real
                imaginaryTwiddles[offset + j] = imaginary
                val nextReal = real * stepReal - imaginary * stepImaginary
                val nextImaginary = real * stepImaginary + imaginary * stepReal
                real = nextReal
                imaginary = nextImaginary
            }
            length = length shl 1
        }
    }

    fun transform(real: DoubleArray, imaginary: DoubleArray) {
        require(real.size == size && imaginary.size == size)
        for (i in 0 until size) {
            val j = bitReversed[i]
            if (j > i) {
                val r = real[i]
                real[i] = real[j]
                real[j] = r
                val im = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = im
            }
        }
        var length = 2
        while (length <= size) {
            val half = length shr 1
            val offset = half - 1
            var i = 0
            while (i < size) {
                for (j in 0 until half) {
                    val twiddleReal = realTwiddles[offset + j]
                    val twiddleImaginary = imaginaryTwiddles[offset + j]
                    val upper = i + j
                    val lower = upper + half
                    val uReal = real[upper]
                    val uImaginary = imaginary[upper]
                    val vReal = real[lower] * twiddleReal - imaginary[lower] * twiddleImaginary
                    val vImaginary = real[lower] * twiddleImaginary + imaginary[lower] * twiddleReal
                    real[upper] = uReal + vReal
                    imaginary[upper] = uImaginary + vImaginary
                    real[lower] = uReal - vReal
                    imaginary[lower] = uImaginary - vImaginary
                }
                i += length
            }
            length = length shl 1
        }
    }
}

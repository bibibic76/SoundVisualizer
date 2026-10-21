package com.example.soundvisualizer.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** ART must preserve the same arithmetic as the JVM, including signed zero. */
@RunWith(AndroidJUnit4::class)
class PlannedRadix2FftInstrumentedTest {
    @Test
    fun plannedFftIsBitExactOnAndroid() {
        val size = 512
        val random = Random(403)
        val plan = PlannedRadix2Fft(size)
        val permutation = createBitReversedIndices(size)
        repeat(100) { trial ->
            val real = DoubleArray(size) { random.nextDouble(-1.0, 1.0) }
            val imaginary = DoubleArray(size)
            if (trial == 0) real.fill(0.0)
            val originalReal = real.copyOf()
            val originalImaginary = imaginary.copyOf()
            fftInPlace(originalReal, originalImaginary, permutation)
            plan.transform(real, imaginary)
            for (i in 0 until size) {
                assertEquals("trial=$trial real[$i]", originalReal[i].toRawBits(), real[i].toRawBits())
                assertEquals("trial=$trial imaginary[$i]", originalImaginary[i].toRawBits(), imaginary[i].toRawBits())
            }
        }
    }
}

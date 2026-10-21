package com.example.soundvisualizer.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class PlannedRadix2FftTest {
    @Test
    fun reusedPlanIsBitExactToOriginalFft() {
        val random = Random(403)
        for (size in listOf(2, 4, 16, 512)) {
            val plan = PlannedRadix2Fft(size)
            val bitReversed = createBitReversedIndices(size)
            repeat(100) { trial ->
                val real = DoubleArray(size) { random.nextDouble(-1.0, 1.0) }
                val imaginary = DoubleArray(size) { random.nextDouble(-1.0, 1.0) }
                if (trial == 0) { real.fill(0.0); imaginary.fill(0.0) }
                if (trial == 1) { real.fill(0.0); real[0] = 1.0; imaginary.fill(0.0) }
                val expectedReal = real.copyOf()
                val expectedImaginary = imaginary.copyOf()
                fftInPlace(expectedReal, expectedImaginary, bitReversed)
                plan.transform(real, imaginary)
                for (i in 0 until size) {
                    assertEquals("size=$size trial=$trial real[$i]", expectedReal[i].toRawBits(), real[i].toRawBits())
                    assertEquals("size=$size trial=$trial imaginary[$i]", expectedImaginary[i].toRawBits(), imaginary[i].toRawBits())
                }
            }
        }
    }
}

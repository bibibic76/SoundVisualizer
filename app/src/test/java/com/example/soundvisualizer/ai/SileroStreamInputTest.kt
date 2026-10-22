package com.example.soundvisualizer.ai

import org.junit.Assert.*
import org.junit.Test

class SileroStreamInputTest {
    @Test fun sequentialBlocksMatchRegularFirExactlyAtAllSupportedRates() {
        for (rate in listOf(16000, 44100, 48000)) {
            val source = FloatArray(rate / 2) { kotlin.math.sin(it * .07).toFloat() * .3f }
            val expected = FloatArray(2560)
            CaptureAudioMath.resampleMonoFloatTo16kCustom(source, source.size, rate, expected, expected.size)
            val actual = FloatArray(2560)
            for (start in 0 until 2560 step 512) {
                val first = start.toLong() * rate / 16000 - if (rate > 16000) 47 else 0
                val end = (start + 511L) * rate / 16000 + if (rate > 16000) 49 else 1
                val capture = FloatArray((end - first).toInt()) { if (first + it < 0) 0f else source[(first + it).toInt()] }
                val block = FloatArray(512)
                CaptureAudioMath.resampleStreamBlock(capture, first, start.toLong(), rate, block)
                block.copyInto(actual, start)
            }
            assertArrayEquals("rate=$rate", expected, actual, 0f)
        }
    }
    @Test fun ringRangesCannotReadFutureOrPreviousSession() {
        val ring = AiAudioBuffer(48000, 1)
        ring.ingestMono(FloatArray(100) { it.toFloat() })
        val cursor = ring.cursor()
        val dst = FloatArray(10)
        assertTrue(ring.copyRange(cursor.generation, -3, 10, dst))
        assertArrayEquals(floatArrayOf(0f,0f,0f,0f,1f,2f,3f,4f,5f,6f), dst, 0f)
        assertFalse(ring.copyRange(cursor.generation, 95, 10, dst))
        ring.reset()
        ring.ingestMono(FloatArray(100) { 999f })
        assertFalse(ring.copyRange(cursor.generation, 0, 10, dst))
    }
    @Test fun overwrittenPcmCannotBeReplayedAsFresh() {
        val ring = AiAudioBuffer(48000, 1)
        ring.ingestMono(FloatArray(100_000) { it.toFloat() })
        val cursor = ring.cursor()
        val dst = FloatArray(2)
        assertFalse(ring.copyRange(cursor.generation, 0, 2, dst))
        assertTrue(ring.copyRange(cursor.generation, 99998, 2, dst))
        assertArrayEquals(floatArrayOf(99998f, 99999f), dst, 0f)
    }
}

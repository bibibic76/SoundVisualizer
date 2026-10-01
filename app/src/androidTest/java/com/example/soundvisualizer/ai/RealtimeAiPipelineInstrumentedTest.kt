package com.example.soundvisualizer.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/** Production pipeline always uses the pinned Qualcomm-source frontend (#272). */
@RunWith(AndroidJUnit4::class)
class RealtimeAiPipelineInstrumentedTest {
    @Test
    fun realtimePipeline_logMelMatchesQualcommFrontend() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val mono = FloatArray(CaptureAudioMath.REQUIRED_MONO_16K_SAMPLES) { i ->
            kotlin.math.sin(i * .031).toFloat() * .4f
        }
        RealtimeAiPipeline.create(app, captureSampleRate = 16_000, channels = 1).use { pipeline ->
            // Exercise the capture-facing ingest path too: it opens the silence gate
            // before an inference tick is allowed to read the ring buffer.
            pipeline.ingestInterleavedForTest(mono, mono.size)
            val tick = requireNotNull(pipeline.runTickForTest())
            val expected = QualcommSourceAudioPreprocessor().computeLogMelSpectrogram(mono)
            assertNotNull(tick.result)
            assertTrue("pipeline did not use Qualcomm frontend", maxAbs(expected, tick.logMel) < 1e-5f)
        }
    }

    private fun maxAbs(a: FloatArray, b: FloatArray): Float {
        var maxError = 0f
        for (i in a.indices) maxError = max(maxError, abs(a[i] - b[i]))
        return maxError
    }
}

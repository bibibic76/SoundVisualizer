package com.example.soundvisualizer.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiResultNotificationInstrumentedTest {
    @Test fun notificationSeesPublishedResultAndClearsWithoutChangingOnnxOutput() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val mono = FloatArray(CaptureAudioMath.REQUIRED_MONO_16K_SAMPLES) {
            kotlin.math.sin(it * .031).toFloat() * .4f
        }
        val baseline = RealtimeAiPipeline.create(app, 16_000, 1).use {
            it.ingestInterleavedForTest(mono, mono.size)
            requireNotNull(it.runTickForTest())
        }
        val observations = mutableListOf<AiClassificationResult?>()
        lateinit var pipeline: RealtimeAiPipeline
        pipeline = RealtimeAiPipeline.create(app, 16_000, 1,
            onClassificationChanged = { observations.add(pipeline.lastClassification()) })
        pipeline.use {
            pipeline.ingestInterleavedForTest(mono, mono.size)
            val first = requireNotNull(pipeline.runTickForTest())
            assertArrayEquals(baseline.probabilities, first.probabilities, 0f)
            assertEquals(baseline.uiCoarse, first.uiCoarse)
            assertSame(first.result, observations.single())
            pipeline.resetState()
            assertNull(observations.last())
            pipeline.ingestInterleavedForTest(mono, mono.size)
            val repeated = requireNotNull(pipeline.runTickForTest())
            assertArrayEquals(first.probabilities, repeated.probabilities, 0f)
            assertEquals(first.uiCoarse, repeated.uiCoarse)
            assertSame(repeated.result, observations.last())
        }
        assertNull(observations.last())
    }
}

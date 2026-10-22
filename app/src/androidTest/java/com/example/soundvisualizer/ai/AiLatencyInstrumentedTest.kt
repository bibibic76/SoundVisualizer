package com.example.soundvisualizer.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiLatencyInstrumentedTest {
    @Test fun tracingPreservesRealOnnxOutputsAndOrdersBootClockStages() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val mono = FloatArray(CaptureAudioMath.REQUIRED_MONO_16K_SAMPLES) {
            kotlin.math.sin(it * .031).toFloat() * .4f
        }
        RealtimeAiPipeline.create(app, 16_000, 1).use { pipeline ->
            pipeline.ingestInterleavedForTest(mono, mono.size)
            val baseline = requireNotNull(pipeline.runTickForTest())
            assertNull(baseline.result.latency)
            pipeline.resetState()
            val recording = AiLatencyTrace.begin()
            try {
                pipeline.ingestInterleavedForTest(mono, mono.size)
                val traced = requireNotNull(pipeline.runTickForTest())
                assertArrayEquals(baseline.probabilities, traced.probabilities, 0f)
                assertEquals(baseline.uiCoarse, traced.uiCoarse)
                assertEquals(baseline.uiConfidence, traced.uiConfidence, 0f)
                val s = requireNotNull(traced.result.latency)
                assertTrue(s.snapshotBeginNs > 0)
                assertTrue(s.snapshotBeginNs <= s.snapshotEndNs)
                assertTrue(s.snapshotEndNs <= s.preprocessedNs)
                assertTrue(s.preprocessedNs <= s.inferredNs)
                assertTrue(s.inferredNs <= s.confirmedNs)
                val published = recording.queue.single { it.stage == "result" }
                assertTrue(s.confirmedNs <= published.atNs)
                assertEquals(0L, s.readCompletedNs) // test input has no AudioRecord timestamp
            } finally { AiLatencyTrace.end(recording) }
        }
    }
}

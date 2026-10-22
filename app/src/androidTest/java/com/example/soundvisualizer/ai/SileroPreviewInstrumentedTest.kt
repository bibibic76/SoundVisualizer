package com.example.soundvisualizer.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SileroPreviewInstrumentedTest {
    @Test fun pinnedRealModelLoadsAndRecurrentStateResets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SileroInference(context).use { model ->
            val chunk = FloatArray(512) { kotlin.math.sin(it * .07).toFloat() * .4f }
            val first = model.score(chunk)
            assertTrue(first in 0f..1f)
            repeat(4) { model.score(chunk) }
            model.reset()
            assertEquals(first, model.score(chunk), 0f)
        }
    }
    @Test fun enabledWorkerDoesNotChangeRegularOnnxOrMappingInvalidation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val mono = FloatArray(16000) { kotlin.math.sin(it * .031).toFloat() * .4f }
        RealtimeAiPipeline.create(app, 16000, 1, speechPreviewEnabled = false).use { baseline ->
            RealtimeAiPipeline.create(app, 16000, 1, speechPreviewEnabled = true).use { candidate ->
                baseline.ingestInterleavedForTest(mono, mono.size)
                candidate.ingestInterleavedForTest(mono, mono.size)
                repeat(2) {
                    val a = requireNotNull(baseline.runTickForTest())
                    val b = requireNotNull(candidate.runTickForTest())
                    assertArrayEquals(a.probabilities, b.probabilities, 0f)
                    assertEquals(a.uiCoarse, b.uiCoarse)
                    assertFalse(b.result.speechPreview)
                }
                candidate.stop()
                assertFalse(candidate.lastDisplayClassification()?.speechPreview ?: false)
                candidate.resetState()
                assertNull(candidate.lastDisplayClassification())
                assertEquals(0L, baseline.speechVerifierCallsForTest())
            }
        }
    }

    @Test fun stalledOptionalOwnerCannotBlockRegularDangerOrClose() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val names = app.assets.open("ai/yamnet_class_map.csv").use { YamnetCoarseClassifier.loadClassNames(it) }
        val overrides = names.associateWith { "danger" }
        val pipeline = RealtimeAiPipeline.create(app, 16000, 1, mappingOverrides = { overrides }, speechPreviewEnabled = true)
        try {
            pipeline.beforeVerifierScoreForTest { entered.countDown(); release.await() }
            // High-confidence synthetic tone, all labels mapped to Danger solely for
            // this concurrency test. Not evidence of real-world alarm recognition.
            val mono = FloatArray(16000) { kotlin.math.sin(it * (2 * Math.PI * 440 / 16000)).toFloat() * .4f }
            pipeline.ingestInterleavedForTest(mono, mono.size)
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val tick = executor.submit<RealtimeAiPipeline.TickDiagnostics?> { pipeline.runTickForTest() }.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("danger", requireNotNull(tick).uiCoarse)
            assertEquals("danger", pipeline.lastDisplayClassification()?.coarse)
            executor.submit { pipeline.close() }.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pipeline.close()
            executor.shutdownNow()
        }
    }
}

package com.example.soundvisualizer.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Production pipeline always uses the pinned Qualcomm-source frontend (#272). */
@RunWith(AndroidJUnit4::class)
class RealtimeAiPipelineInstrumentedTest {
    @Test
    fun isolatedPreviewDoesNotConsumeRegularConfirmationAndRejectsStaleMapping() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val names = app.assets.open("ai/yamnet_class_map.csv").use { YamnetCoarseClassifier.loadClassNames(it) }
        var overrides = names.associateWith { "speech" }
        // Opposite-phase capture opens the original-channel gate while downmixing to a stable silence tensor.
        val mono = FloatArray(31_200) { i -> if (i % 2 == 0) .1f else -.1f }
        RealtimeAiPipeline.create(
            app, 16_000, 2, { overrides }, responsiveConfirmation = true, elapsedRealtimeMs = { 1000L }
        ).use { pipeline ->
            feedSustainedCapture(pipeline, mono)
            val first = requireNotNull(pipeline.runTickForTest())
            val request = requireNotNull(first.result.pendingSpeechConfirmation)
            assertEquals("ambient", first.uiCoarse)
            awaitPreviewReady(pipeline)
            assertTrue(pipeline.submitPreviewForTest(request, 2000L))
            awaitPreviewIdle(pipeline)
            val confirmed = requireNotNull(pipeline.lastClassification())
            assertEquals("speech", confirmed.coarse)
            assertTrue(confirmed.earlyConfirmation)
            assertEquals(1L, pipeline.inferenceStatsForTest().confirmationAccepted)
            // The original candidate still awaits its original second regular observation.
            val regular = requireNotNull(pipeline.runTickForTest())
            assertEquals("speech", regular.uiCoarse)
            assertTrue(!regular.result.earlyConfirmation)
            assertEquals(2L, pipeline.inferenceStatsForTest().executed)

            pipeline.resetState()
            feedSustainedCapture(pipeline, mono)
            val pending = requireNotNull(pipeline.runTickForTest()).result.pendingSpeechConfirmation!!
            val before = pipeline.inferenceStatsForTest().executed
            overrides = emptyMap()
            assertTrue(pipeline.submitPreviewForTest(pending, 2000L))
            awaitPreviewIdle(pipeline)
            assertEquals(before, pipeline.inferenceStatsForTest().executed)
            assertNull(pipeline.lastClassification())
        }
    }

    @Test
    fun failedSpeculationDoesNotReplaceResultOrEraseNormalCandidate() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val names = app.assets.open("ai/yamnet_class_map.csv").use { YamnetCoarseClassifier.loadClassNames(it) }
        val overrides = names.associateWith { "speech" }
        val mono = FloatArray(31_200) { i -> if (i % 2 == 0) .1f else -.1f }
        RealtimeAiPipeline.create(
            app, 16_000, 2, { overrides }, responsiveConfirmation = true, elapsedRealtimeMs = { 1000L }
        ).use { pipeline ->
            feedSustainedCapture(pipeline, mono)
            val first = requireNotNull(pipeline.runTickForTest()).result
            val request = requireNotNull(first.pendingSpeechConfirmation).copy(confidence = 1.1f)
            awaitPreviewReady(pipeline)
            assertTrue(pipeline.submitPreviewForTest(request, 2000L))
            awaitPreviewIdle(pipeline)
            assertEquals(first, pipeline.lastClassification())
            assertEquals("speech", requireNotNull(pipeline.runTickForTest()).uiCoarse)
            assertEquals(1L, pipeline.inferenceStatsForTest().confirmationAttempts)
            assertEquals(0L, pipeline.inferenceStatsForTest().confirmationAccepted)
        }
    }

    @Test
    fun blockedPreviewCannotBlockRegularDangerOrOverwriteItLater() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val names = app.assets.open("ai/yamnet_class_map.csv").use { YamnetCoarseClassifier.loadClassNames(it) }
        var overrides = names.associateWith { "speech" }
        val pcm = FloatArray(31_200) { if (it % 2 == 0) .1f else -.1f }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        RealtimeAiPipeline.create(
            app, 16_000, 2, { overrides }, responsiveConfirmation = true, elapsedRealtimeMs = { 1000L }
        ).use { pipeline ->
            try {
                feedSustainedCapture(pipeline, pcm)
                val first = requireNotNull(pipeline.runTickForTest()).result
                awaitPreviewReady(pipeline)
                pipeline.beforePreviewInferenceForTest = {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                assertTrue(pipeline.submitPreviewForTest(first.pendingSpeechConfirmation!!, 2000L))
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                overrides = names.associateWith { "danger" }
                // The optional lane remains blocked, yet a real ONNX regular call completes.
                val danger = requireNotNull(pipeline.runTickForTest())
                assertEquals("danger", danger.uiCoarse)
                assertEquals(1L, release.count)
                assertEquals("danger", pipeline.lastClassification()?.coarse)
                release.countDown()
                awaitPreviewIdle(pipeline)
                assertEquals("danger", pipeline.lastClassification()?.coarse)
                assertEquals(0L, pipeline.inferenceStatsForTest().confirmationAccepted)
            } finally {
                release.countDown()
            }
        }
    }

    private fun awaitPreviewReady(pipeline: RealtimeAiPipeline) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!pipeline.previewReadyForTest() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("preview model did not initialize", pipeline.previewReadyForTest())
    }

    private fun awaitPreviewIdle(pipeline: RealtimeAiPipeline) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (pipeline.previewBusyForTest() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("preview did not finish", !pipeline.previewBusyForTest())
    }

    private fun feedSustainedCapture(pipeline: RealtimeAiPipeline, pcm: FloatArray) {
        val now = 1000L
        // Three recent capture publications, without quiet or a >100ms gap.
        pipeline.ingestInterleavedAtForTest(pcm, pcm.size, now - 100)
        pipeline.ingestInterleavedAtForTest(pcm, pcm.size, now - 50)
        pipeline.ingestInterleavedAtForTest(pcm, pcm.size, now)
    }

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

    @Test
    fun runtimeMappingChangeInvalidatesPreviousResultAndResetsConfirmation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val names = app.assets.open("ai/yamnet_class_map.csv").use { YamnetCoarseClassifier.loadClassNames(it) }
        var overrides: Map<String, String> = names.associateWith { "danger" }
        val mono = FloatArray(CaptureAudioMath.REQUIRED_MONO_16K_SAMPLES) { i ->
            kotlin.math.sin(i * .031).toFloat() * .4f
        }
        RealtimeAiPipeline.create(app, 16_000, 1, mappingOverrides = { overrides }).use { pipeline ->
            pipeline.ingestInterleavedForTest(mono, mono.size)
            val danger = requireNotNull(pipeline.runTickForTest())
            assertTrue(danger.dangerScore > 0f)
            assertEquals(0f, danger.ambientScore, 0f)
            assertNotNull(pipeline.lastClassification())

            overrides = names.associateWith { "ambient" }
            assertNull("old mapping result must disappear immediately", pipeline.lastClassification())
            pipeline.ingestInterleavedForTest(mono, mono.size)
            val ambient = requireNotNull(pipeline.runTickForTest())
            assertEquals(0f, ambient.dangerScore, 0f)
            assertEquals("ambient", ambient.uiCoarse)
            assertEquals(YamnetMappingPolicy.from(overrides, names).signature, ambient.result.mappingSignature)

            overrides = emptyMap()
            assertNull(pipeline.lastClassification())
            pipeline.ingestInterleavedForTest(mono, mono.size)
            assertEquals("default", requireNotNull(pipeline.runTickForTest()).result.mappingSignature)
        }
    }

    private fun maxAbs(a: FloatArray, b: FloatArray): Float {
        var maxError = 0f
        for (i in a.indices) maxError = max(maxError, abs(a[i] - b[i]))
        return maxError
    }
}

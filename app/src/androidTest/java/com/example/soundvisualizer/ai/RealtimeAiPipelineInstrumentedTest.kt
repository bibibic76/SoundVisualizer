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

/** Production pipeline always uses the pinned Qualcomm-source frontend (#272). */
@RunWith(AndroidJUnit4::class)
class RealtimeAiPipelineInstrumentedTest {
    @Test
    fun experimentConfirmsSameModelOutputAndRejectsStaleMapping() {
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
            val confirmed = requireNotNull(pipeline.runConfirmationForTest(request))
            assertEquals("speech", confirmed.uiCoarse)
            assertTrue(confirmed.result.earlyConfirmation)
            assertEquals(1L, pipeline.inferenceStatsForTest().confirmationAccepted)

            pipeline.resetState()
            feedSustainedCapture(pipeline, mono)
            val pending = requireNotNull(pipeline.runTickForTest()).result.pendingSpeechConfirmation!!
            val before = pipeline.inferenceStatsForTest().executed
            overrides = emptyMap()
            assertNull(pipeline.runConfirmationForTest(pending))
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
            assertNull(pipeline.runConfirmationForTest(request))
            assertEquals(first, pipeline.lastClassification())
            assertEquals("speech", requireNotNull(pipeline.runTickForTest()).uiCoarse)
            assertEquals(1L, pipeline.inferenceStatsForTest().confirmationAttempts)
            assertEquals(0L, pipeline.inferenceStatsForTest().confirmationAccepted)
        }
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

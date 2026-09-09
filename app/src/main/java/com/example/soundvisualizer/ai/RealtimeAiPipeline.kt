package com.example.soundvisualizer.ai

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureNanoTime

/**
 * Realtime AI orchestration: PCM ingest (capture thread) → 250ms background tick
 * (ring → 16k resample → Log-Mel → YAMNet → 3-class → Booster → PostProcessor).
 *
 * Does not touch Overlay / AudioEngine / C++ DSP.
 */
class RealtimeAiPipeline private constructor(
    private val context: Context,
    private val audioBuffer: AiAudioBuffer,
    private val preprocessor: AudioPreprocessor,
    private val yamnet: YamnetInference,
    private val booster: GunshotBoosterInference,
    private val coarseClassifier: YamnetCoarseClassifier,
    private val classNames: List<String>,
    private val postProcessor: AiPostProcessor,
    private val predictIntervalMs: Long = AI_PREDICT_INTERVAL_MS
) : AutoCloseable {

    data class TickDiagnostics(
        val result: AiClassificationResult,
        val mono16k: FloatArray,
        val logMel: FloatArray,
        val probabilities: FloatArray,
        val preBoosterCoarse: String,
        val postBoosterCoarse: String,
        val gunshotScore: Float,
        val boosterAccepted: Boolean
    )

    companion object {
        const val AI_PREDICT_INTERVAL_MS = 250L
        private const val TAG = "RealtimeAiPipeline"
        private const val LOG_THROTTLE_MS = 2000L

        fun create(
            context: Context,
            captureSampleRate: Int = AiAudioBuffer.DEFAULT_CAPTURE_SAMPLE_RATE,
            channels: Int = 2
        ): RealtimeAiPipeline {
            val names = context.assets.open("ai/yamnet_class_map.csv").use {
                YamnetCoarseClassifier.loadClassNames(it)
            }
            return RealtimeAiPipeline(
                context = context.applicationContext,
                audioBuffer = AiAudioBuffer(captureSampleRate, channels),
                preprocessor = AudioPreprocessor(),
                yamnet = YamnetInference.create(context),
                booster = GunshotBoosterInference.create(context),
                coarseClassifier = YamnetCoarseClassifier(names),
                classNames = names,
                postProcessor = AiPostProcessor()
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var schedulerJob: Job? = null
    private val inferMutex = Mutex()
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lastResult = AtomicReference<AiClassificationResult?>(null)
    private var lastLogMs = 0L

    private val captureNeed =
        CaptureAudioMath.captureSamplesForOneYamnetWindow(audioBuffer.sampleRate)
    private val captureScratch = FloatArray(captureNeed)
    private val mono16kScratch = FloatArray(CaptureAudioMath.REQUIRED_MONO_16K_SAMPLES)

    private val debuggable: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun lastClassification(): AiClassificationResult? = lastResult.get()

    fun start() {
        if (closed.get()) return
        if (!running.compareAndSet(false, true)) return
        audioBuffer.reset()
        postProcessor.reset()
        lastResult.set(null)
        schedulerJob = scope.launch {
            while (isActive && running.get()) {
                try {
                    runTickInternal(observe = true)
                } catch (t: Throwable) {
                    if (debuggable) {
                        Log.w(TAG, "AI tick failed: ${t.message}", t)
                    }
                }
                delay(predictIntervalMs)
            }
        }
    }

    fun stop() {
        running.set(false)
        schedulerJob?.cancel()
        schedulerJob = null
    }

    /**
     * Capture-thread safe: copies PCM into AI ring immediately.
     * Never runs inference.
     */
    fun ingestInterleavedPcm(pcm: FloatArray, floatCount: Int) {
        if (!running.get() || closed.get()) return
        audioBuffer.ingestInterleaved(pcm, floatCount)
    }

    /** Test helper — ingest without requiring [start]. */
    fun ingestInterleavedForTest(pcm: FloatArray, floatCount: Int) {
        audioBuffer.ingestInterleaved(pcm, floatCount)
    }

    fun ingestMonoForTest(mono: FloatArray, length: Int = mono.size) {
        audioBuffer.ingestMono(mono, length)
    }

    /**
     * Synchronous one-shot for instrumentation fixtures (does not require scheduler).
     */
    fun runTickForTest(): TickDiagnostics? {
        return runTickInternal(observe = false)
    }

    fun resetState() {
        audioBuffer.reset()
        postProcessor.reset()
        lastResult.set(null)
    }

    private fun runTickInternal(observe: Boolean): TickDiagnostics? {
        if (closed.get()) return null
        if (!audioBuffer.hasEnoughForYamnetWindow()) return null

        // Skip if previous tick still running (scheduler overlap)
        if (!inferMutex.tryLock()) return null
        try {
            return doInference(observe)
        } finally {
            inferMutex.unlock()
        }
    }

    private fun doInference(observe: Boolean): TickDiagnostics {
        val t0 = System.nanoTime()

        audioBuffer.copyTailRightPadded(captureScratch, captureNeed)

        CaptureAudioMath.resampleMonoFloatTo16kCustom(
            source = captureScratch,
            sourceLength = captureNeed,
            sourceSampleRate = audioBuffer.sampleRate,
            destination = mono16kScratch
        )
        val mono16kCopy = mono16kScratch.copyOf()

        var logMel: FloatArray
        val preprocessNs = measureNanoTime {
            logMel = preprocessor.computeLogMelSpectrogram(mono16kScratch)
        }

        var yamnetResult: YamnetInference.Result
        val yamnetNs = measureNanoTime {
            yamnetResult = yamnet.inferFromLogMelFlat(logMel)
        }

        val pre = coarseClassifier.classify(yamnetResult.probabilities)

        var gunshotScore: Float
        val boosterNs = measureNanoTime {
            gunshotScore = booster.score(yamnetResult.probabilities)
        }

        val decision = GunshotBoosterDecision.decide(
            probabilities = yamnetResult.probabilities,
            classNames = classNames,
            pre = pre,
            gunshotScore = gunshotScore
        )

        val topKSummary = pre.top5.joinToString(separator = " | ") { it.name }
        val hasCritical = AiPostProcessor.isCriticalDangerEvent(
            decision.postBoosterDisplay,
            topKSummary
        )

        val post = postProcessor.process(
            AiPostProcessor.FrameInput(
                coarse = decision.postBoosterCoarse,
                display = decision.postBoosterDisplay,
                confidence = decision.postBoosterConfidence,
                adoptedDangerFromBooster = decision.accepted,
                hasStrongDangerCue = decision.hasStrongDangerCue,
                hasCriticalDangerCue = hasCritical,
                topKSummary = topKSummary
            )
        )

        val totalMs = (System.nanoTime() - t0) / 1e6
        val result = AiClassificationResult(
            coarse = post.uiCoarse,
            display = post.uiDisplay,
            confidence = post.uiConfidence,
            gunshotScore = gunshotScore,
            preBoosterCoarse = decision.preBoosterCoarse,
            boosterAccepted = decision.accepted,
            meetsThreshold = post.meetsThreshold,
            useBoosterDangerPreview = post.useBoosterDangerPreview,
            timestampMs = System.currentTimeMillis(),
            preprocessMs = preprocessNs / 1e6,
            yamnetMs = yamnetNs / 1e6,
            boosterMs = boosterNs / 1e6,
            totalMs = totalMs
        )
        lastResult.set(result)

        if (observe && debuggable) {
            maybeLog(result)
        }

        return TickDiagnostics(
            result = result,
            mono16k = mono16kCopy,
            logMel = logMel.copyOf(),
            probabilities = yamnetResult.probabilities.copyOf(),
            preBoosterCoarse = decision.preBoosterCoarse,
            postBoosterCoarse = decision.postBoosterCoarse,
            gunshotScore = gunshotScore,
            boosterAccepted = decision.accepted
        )
    }

    private fun maybeLog(result: AiClassificationResult) {
        val now = System.currentTimeMillis()
        if (now - lastLogMs < LOG_THROTTLE_MS) return
        lastLogMs = now
        Log.d(
            TAG,
            "AI_RESULT coarse=${result.coarse} display=${result.display} " +
                "confidence=${"%.4f".format(result.confidence)} " +
                "gunshotScore=${"%.4f".format(result.gunshotScore)} " +
                "pre=${result.preBoosterCoarse} boost=${result.boosterAccepted} " +
                "ms[pre=${"%.1f".format(result.preprocessMs)} " +
                "yam=${"%.1f".format(result.yamnetMs)} " +
                "bst=${"%.1f".format(result.boosterMs)} " +
                "tot=${"%.1f".format(result.totalMs)}]"
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        scope.cancel()
        try {
            yamnet.close()
        } catch (_: Throwable) {
        }
        try {
            booster.close()
        } catch (_: Throwable) {
        }
        audioBuffer.reset()
        postProcessor.reset()
        lastResult.set(null)
    }
}

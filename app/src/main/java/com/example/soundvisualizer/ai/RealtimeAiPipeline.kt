package com.example.soundvisualizer.ai

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
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
    private val qualcommSourcePreprocessor: QualcommSourceAudioPreprocessor,
    private val yamnet: YamnetInference,
    private val booster: GunshotBoosterInference?,
    private val coarseClassifier: YamnetCoarseClassifier,
    private val classNames: List<String>,
    private val postProcessor: AiPostProcessor,
    private val diagnosticConfigProvider: () -> AiDiagnosticConfig,
    private val predictIntervalMs: Long = AI_PREDICT_INTERVAL_MS
) : AutoCloseable {

    data class TickDiagnostics(
        val result: AiClassificationResult,
        val frontendMode: AiFrontendMode,
        val boosterEnabled: Boolean,
        val mono16k: FloatArray,
        val logMel: FloatArray,
        val probabilities: FloatArray,
        val inputSampleRate: Int,
        val inputChannels: Int,
        val mono16kRms: Float,
        val mono16kPeak: Float,
        val mono16kMean: Float,
        val logMelMin: Float,
        val logMelMax: Float,
        val logMelMean: Float,
        val logMelStd: Float,
        val top5: List<YamnetCoarseClassifier.TopClassHit>,
        val preBoosterCoarse: String,
        val preBoosterDisplay: String,
        val preBoosterConfidence: Float,
        val ambientScore: Float,
        val speechScore: Float,
        val dangerScore: Float,
        val postBoosterCoarse: String,
        val postBoosterDisplay: String,
        val postBoosterConfidence: Float,
        val gunshotScore: Float,
        val gunshotEvidence: Float,
        val boosterReason: String,
        val boosterAvailable: Boolean,
        val boosterAccepted: Boolean,
        val effectiveThreshold: Float,
        val confirmedCoarse: String,
        val confirmedDisplay: String,
        val confirmedConfidence: Float,
        val uiCoarse: String,
        val uiDisplay: String,
        val uiConfidence: Float
    )

    /** Lightweight counters for verifying inference suppression in device tests. */
    data class InferenceStats(
        val executed: Long,
        val skippedForSilence: Long
    )

    companion object {
        const val AI_PREDICT_INTERVAL_MS = 250L
        private const val TAG = "RealtimeAiPipeline"
        private const val LOG_THROTTLE_MS = 2000L

        /** close() 는 메인 스레드(onDestroy)에서 불린다 — 무한 대기는 ANR. */
        private const val CLOSE_WAIT_MS = 1000L

        fun create(
            context: Context,
            captureSampleRate: Int = AiAudioBuffer.DEFAULT_CAPTURE_SAMPLE_RATE,
            channels: Int = 2,
            diagnosticConfigProvider: () -> AiDiagnosticConfig = { AiDiagnosticConfig.DEFAULT }
        ): RealtimeAiPipeline {
            val names = context.assets.open("ai/yamnet_class_map.csv").use {
                YamnetCoarseClassifier.loadClassNames(it)
            }
            val appContext = context.applicationContext
            val audioBuffer = AiAudioBuffer(captureSampleRate, channels)
            val preprocessor = AudioPreprocessor()
            val qualcommSourcePreprocessor = QualcommSourceAudioPreprocessor()
            val coarseClassifier = YamnetCoarseClassifier(names)
            val postProcessor = AiPostProcessor()

            return AiPipelineInitializer.create(
                createYamnet = { YamnetInference.create(context) },
                createBooster = { GunshotBoosterInference.create(context) },
                onBoosterUnavailable = { failure ->
                    Log.w(TAG, "Gunshot Booster unavailable; continuing with YAMNet only", failure)
                },
                createOwner = { yamnet, booster ->
                    RealtimeAiPipeline(
                        context = appContext,
                        audioBuffer = audioBuffer,
                        preprocessor = preprocessor,
                        qualcommSourcePreprocessor = qualcommSourcePreprocessor,
                        yamnet = yamnet,
                        booster = booster,
                        coarseClassifier = coarseClassifier,
                        classNames = names,
                        postProcessor = postProcessor,
                        diagnosticConfigProvider = diagnosticConfigProvider
                    )
                }
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var schedulerJob: Job? = null
    // ReentrantLock 은 획득한 스레드에서 해제해야 하므로 잠금 구간에는 suspend 호출을 넣지 않는다.
    private val inferLock = ReentrantLock()
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lastResult = AtomicReference<AiClassificationResult?>(null)
    private val captureInferenceGate = AiCaptureInferenceGate(audioBuffer)
    private val inferenceExecutions = java.util.concurrent.atomic.AtomicLong(0)
    private val silenceSkippedTicks = java.util.concurrent.atomic.AtomicLong(0)
    private var lastLogMs = 0L
    private var appliedDiagnosticConfig: AiDiagnosticConfig? = null

    private val captureNeed =
        CaptureAudioMath.captureSamplesForOneYamnetWindow(audioBuffer.sampleRate)
    private val captureScratch = FloatArray(captureNeed)
    private val mono16kScratch = FloatArray(CaptureAudioMath.REQUIRED_MONO_16K_SAMPLES)

    private val debuggable: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun lastClassification(): AiClassificationResult? = lastResult.get()

    fun inferenceStatsForTest(): InferenceStats = InferenceStats(
        executed = inferenceExecutions.get(),
        skippedForSilence = silenceSkippedTicks.get()
    )

    fun start() {
        if (closed.get()) return
        if (!running.compareAndSet(false, true)) return
        audioBuffer.reset()
        captureInferenceGate.reset()
        postProcessor.reset()
        appliedDiagnosticConfig = null
        lastResult.set(null)
        inferenceExecutions.set(0)
        silenceSkippedTicks.set(0)
        schedulerJob = scope.launch {
            while (isActive && running.get()) {
                try {
                    // diagnostics = false: 스케줄러는 반환값을 쓰지 않는다.
                    // true 로 두면 프레임마다 배열 3개(약 88KB)를 복사해 그대로 버린다.
                    runTickInternal(log = true, diagnostics = false)
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
        ingestInterleaved(pcm, floatCount)
    }

    /** Test helper — ingest without requiring [start]. */
    fun ingestInterleavedForTest(pcm: FloatArray, floatCount: Int) {
        ingestInterleaved(pcm, floatCount)
    }

    fun ingestMonoForTest(mono: FloatArray, length: Int = mono.size) {
        audioBuffer.ingestMono(mono, length)
    }

    /**
     * Synchronous one-shot for instrumentation fixtures (does not require scheduler).
     */
    fun runTickForTest(): TickDiagnostics? {
        return runTickInternal(log = false, diagnostics = true)
    }

    fun resetState() {
        audioBuffer.reset()
        captureInferenceGate.reset()
        postProcessor.reset()
        appliedDiagnosticConfig = null
        lastResult.set(null)
        inferenceExecutions.set(0)
        silenceSkippedTicks.set(0)
    }

    private fun ingestInterleaved(pcm: FloatArray, floatCount: Int) {
        // Evaluate the original channels before downmixing, then always retain the
        // samples in the ring so an input that reopens the gate has full context.
        captureInferenceGate.ingestInterleaved(pcm, floatCount, SystemClock.elapsedRealtime())
    }

    /**
     * @param log 결과를 디버그 로그로 남길지 (스케줄러 경로).
     * @param diagnostics 중간 텐서를 복사해 [TickDiagnostics] 로 돌려줄지 (테스트 경로).
     *                    false 면 null 을 반환하고 복사도 하지 않는다.
     */
    private fun runTickInternal(log: Boolean, diagnostics: Boolean): TickDiagnostics? {
        if (closed.get()) return null
        if (!audioBuffer.hasEnoughForYamnetWindow()) return null
        if (!captureInferenceGate.isInferenceOpen(SystemClock.elapsedRealtime())) {
            silenceSkippedTicks.incrementAndGet()
            return null
        }

        // Skip if previous tick still running (scheduler overlap)
        if (!inferLock.tryLock()) return null
        try {
            // close() 가 락을 잡기 직전에 통과했을 수 있으므로 락 안에서 다시 확인한다.
            if (closed.get()) return null
            val snapshotTimeMs = SystemClock.elapsedRealtime()
            val result = try {
                doInference(log, diagnostics)
            } catch (t: Throwable) {
                captureInferenceGate.onInferenceFailed(snapshotTimeMs)
                throw t
            }
            captureInferenceGate.onInferenceCompleted(snapshotTimeMs)
            return result
        } finally {
            inferLock.unlock()
        }
    }

    private fun doInference(log: Boolean, diagnostics: Boolean): TickDiagnostics? {
        inferenceExecutions.incrementAndGet()
        val t0 = System.nanoTime()
        // Read once: a settings change takes effect on the next tick, never halfway through this one.
        val diagnosticConfig = diagnosticConfigProvider()
        val previousDiagnosticConfig = appliedDiagnosticConfig
        if (previousDiagnosticConfig != null && previousDiagnosticConfig != diagnosticConfig) {
            // Candidate/confirmed history from one A/B arm must not leak into the next arm.
            postProcessor.reset()
        }
        appliedDiagnosticConfig = diagnosticConfig

        var logMel: FloatArray
        val preprocessNs = measureNanoTime {
            audioBuffer.copyTailRightPadded(captureScratch, captureNeed)
            CaptureAudioMath.resampleMonoFloatTo16kCustom(
                source = captureScratch,
                sourceLength = captureNeed,
                sourceSampleRate = audioBuffer.sampleRate,
                destination = mono16kScratch
            )
            logMel = when (diagnosticConfig.frontendMode) {
                AiFrontendMode.CURRENT -> preprocessor.computeLogMelSpectrogram(mono16kScratch)
                AiFrontendMode.QUALCOMM_SOURCE ->
                    qualcommSourcePreprocessor.computeLogMelSpectrogram(mono16kScratch)
            }
        }
        val mono16kCopy = if (diagnostics) mono16kScratch.copyOf() else null

        var yamnetResult: YamnetInference.Result
        val yamnetNs = measureNanoTime {
            yamnetResult = yamnet.inferFromLogMelFlat(logMel)
        }

        val pre = coarseClassifier.classify(yamnetResult.probabilities)

        var gunshotScore: Float? = null
        val boosterNs = if (diagnosticConfig.boosterEnabled && booster != null) measureNanoTime {
            gunshotScore = booster.score(yamnetResult.probabilities)
        } else {
            0L
        }

        val availableGunshotScore = gunshotScore
        val decision = if (availableGunshotScore != null) {
            GunshotBoosterDecision.decide(
                probabilities = yamnetResult.probabilities,
                classNames = classNames,
                pre = pre,
                gunshotScore = availableGunshotScore
            )
        } else {
            GunshotBoosterDecision.unavailable(
                probabilities = yamnetResult.probabilities,
                classNames = classNames,
                pre = pre
            )
        }
        val diagnosticBoosterReason = if (diagnosticConfig.boosterEnabled) {
            decision.reason
        } else {
            "booster_disabled"
        }

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
                dangerCuePromoted = decision.dangerCuePromoted,
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
            gunshotScore = decision.gunshotScore,
            top5 = pre.top5,
            gunshotEvidence = decision.gunshotEvidence,
            boosterReason = diagnosticBoosterReason,
            dangerCuePromoted = decision.dangerCuePromoted,
            boosterAvailable = decision.boosterAvailable,
            preBoosterCoarse = decision.preBoosterCoarse,
            boosterAccepted = decision.accepted,
            meetsThreshold = post.meetsThreshold,
            useBoosterDangerPreview = post.useBoosterDangerPreview,
            timestampMs = System.currentTimeMillis(),
            preprocessMs = preprocessNs / 1e6,
            yamnetMs = yamnetNs / 1e6,
            boosterMs = boosterNs / 1e6,
            totalMs = totalMs,
            frontendMode = diagnosticConfig.frontendMode,
            boosterEnabled = diagnosticConfig.boosterEnabled
        )
        lastResult.set(result)

        if (log && debuggable) {
            maybeLog(
                result = result,
                mono16k = mono16kScratch,
                logMel = logMel,
                pre = pre,
                decision = decision,
                diagnosticConfig = diagnosticConfig,
                diagnosticBoosterReason = diagnosticBoosterReason,
                post = post
            )
        }

        if (!diagnostics || mono16kCopy == null) return null
        return TickDiagnostics(
            result = result,
            frontendMode = diagnosticConfig.frontendMode,
            boosterEnabled = diagnosticConfig.boosterEnabled,
            mono16k = mono16kCopy,
            logMel = logMel.copyOf(),
            probabilities = yamnetResult.probabilities.copyOf(),
            inputSampleRate = audioBuffer.sampleRate,
            inputChannels = audioBuffer.channelCount,
            mono16kRms = rms(mono16kCopy),
            mono16kPeak = peak(mono16kCopy),
            mono16kMean = mean(mono16kCopy),
            logMelMin = minValue(logMel),
            logMelMax = maxValue(logMel),
            logMelMean = mean(logMel),
            logMelStd = stddev(logMel),
            top5 = pre.top5,
            preBoosterCoarse = decision.preBoosterCoarse,
            preBoosterDisplay = decision.preBoosterDisplay,
            preBoosterConfidence = decision.preBoosterConfidence,
            ambientScore = pre.ambientScore,
            speechScore = pre.speechScore,
            dangerScore = pre.dangerScore,
            postBoosterCoarse = decision.postBoosterCoarse,
            postBoosterDisplay = decision.postBoosterDisplay,
            postBoosterConfidence = decision.postBoosterConfidence,
            gunshotScore = decision.gunshotScore,
            gunshotEvidence = decision.gunshotEvidence,
            boosterReason = diagnosticBoosterReason,
            boosterAvailable = decision.boosterAvailable,
            boosterAccepted = decision.accepted,
            effectiveThreshold = post.effectiveThreshold,
            confirmedCoarse = post.confirmedCoarse,
            confirmedDisplay = post.confirmedDisplay,
            confirmedConfidence = post.confirmedConfidence,
            uiCoarse = post.uiCoarse,
            uiDisplay = post.uiDisplay,
            uiConfidence = post.uiConfidence
        )
    }

    private fun maybeLog(
        result: AiClassificationResult,
        mono16k: FloatArray,
        logMel: FloatArray,
        pre: YamnetCoarseClassifier.Result,
        decision: GunshotBoosterDecision.Result,
        diagnosticConfig: AiDiagnosticConfig,
        diagnosticBoosterReason: String,
        post: AiPostProcessor.FrameResult
    ) {
        val now = System.currentTimeMillis()
        if (now - lastLogMs < LOG_THROTTLE_MS) return
        lastLogMs = now
        val top5 = pre.top5.joinToString(" | ") {
            "${it.name}:${"%.5f".format(java.util.Locale.US, it.probability)}"
        }
        Log.d(
            TAG,
            "AI_RESULT input[sr=${audioBuffer.sampleRate} ch=${audioBuffer.channelCount}] " +
                "config[frontend=${diagnosticConfig.frontendMode.diagnosticName} " +
                "booster=${if (diagnosticConfig.boosterEnabled) "on" else "disabled"} " +
                "available=${decision.boosterAvailable}] " +
                "mono16k[rms=${"%.5f".format(java.util.Locale.US, rms(mono16k))} " +
                "peak=${"%.5f".format(java.util.Locale.US, peak(mono16k))} " +
                "mean=${"%.5f".format(java.util.Locale.US, mean(mono16k))}] " +
                "mel[min=${"%.4f".format(java.util.Locale.US, minValue(logMel))} " +
                "max=${"%.4f".format(java.util.Locale.US, maxValue(logMel))} " +
                "mean=${"%.4f".format(java.util.Locale.US, mean(logMel))} " +
                "std=${"%.4f".format(java.util.Locale.US, stddev(logMel))}] " +
                "top5=[$top5] " +
                "scores[a=${"%.5f".format(java.util.Locale.US, pre.ambientScore)} " +
                "s=${"%.5f".format(java.util.Locale.US, pre.speechScore)} " +
                "d=${"%.5f".format(java.util.Locale.US, pre.dangerScore)}] " +
                "pre=${decision.preBoosterCoarse}/${decision.preBoosterDisplay} " +
                "preConf=${"%.5f".format(java.util.Locale.US, decision.preBoosterConfidence)} " +
                "booster[score=${"%.5f".format(java.util.Locale.US, decision.gunshotScore)} " +
                "evidence=${"%.5f".format(java.util.Locale.US, decision.gunshotEvidence)} " +
                "accepted=${decision.accepted} reason=$diagnosticBoosterReason] " +
                "post=${decision.postBoosterCoarse}/${decision.postBoosterDisplay} " +
                "postConf=${"%.5f".format(java.util.Locale.US, decision.postBoosterConfidence)} " +
                "threshold=${"%.5f".format(java.util.Locale.US, post.effectiveThreshold)} " +
                "confirmed=${post.confirmedCoarse}/${post.confirmedDisplay} " +
                "streak=${post.candidateCoarse}:${post.candidateStreak} " +
                "ui=${post.uiCoarse}/${post.uiDisplay} " +
                "uiConf=${"%.5f".format(java.util.Locale.US, post.uiConfidence)} " +
                "ms[pre=${"%.1f".format(java.util.Locale.US, result.preprocessMs)} " +
                "yam=${"%.1f".format(java.util.Locale.US, result.yamnetMs)} " +
                "bst=${"%.1f".format(java.util.Locale.US, result.boosterMs)} " +
                "tot=${"%.1f".format(java.util.Locale.US, result.totalMs)}]"
        )
    }

    private fun mean(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        var sum = 0.0
        for (value in values) sum += value.toDouble()
        return (sum / values.size).toFloat()
    }

    private fun rms(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        var sum = 0.0
        for (value in values) sum += value.toDouble() * value.toDouble()
        return kotlin.math.sqrt(sum / values.size).toFloat()
    }

    private fun peak(values: FloatArray): Float {
        var max = 0f
        for (value in values) max = kotlin.math.max(max, kotlin.math.abs(value))
        return max
    }

    private fun minValue(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        var result = values[0]
        for (i in 1 until values.size) result = kotlin.math.min(result, values[i])
        return result
    }

    private fun maxValue(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        var result = values[0]
        for (i in 1 until values.size) result = kotlin.math.max(result, values[i])
        return result
    }

    private fun stddev(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val average = mean(values).toDouble()
        var sum = 0.0
        for (value in values) {
            val delta = value.toDouble() - average
            sum += delta * delta
        }
        return kotlin.math.sqrt(sum / values.size).toFloat()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        scope.cancel()

        // stop()/cancel() 은 다음 틱의 시작만 막는다. 이미 session.run() 안에 들어간 추론이
        // 있으면 그게 끝날 때까지 기다린 뒤에 네이티브 세션을 해제해야 use-after-free 가 없다.
        val acquired = try {
            inferLock.tryLock(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (acquired) {
            try {
                try {
                    yamnet.close()
                } catch (_: Throwable) {
                }
                try {
                    booster?.close()
                } catch (_: Throwable) {
                }
            } finally {
                inferLock.unlock()
            }
        } else {
            // 크래시보다는 누수가 낫다 (AudioCaptureService 의 AudioRecord 처리와 동일 원칙).
            Log.w(TAG, "inference still running; leaking ONNX sessions to avoid use-after-free")
        }

        audioBuffer.reset()
        postProcessor.reset()
        lastResult.set(null)
    }
}

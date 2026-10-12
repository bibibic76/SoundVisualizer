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
 * (ring → 16k resample → Qualcomm-source Log-Mel → YAMNet → 3-class → PostProcessor).
 *
 * Does not touch Overlay / AudioEngine / C++ DSP.
 */
class RealtimeAiPipeline private constructor(
    private val context: Context,
    private val audioBuffer: AiAudioBuffer,
    private val yamnet: YamnetInference,
    private val coarseClassifier: YamnetCoarseClassifier,
    private val classNames: List<String>,
    private val postProcessor: AiPostProcessor,
    private val mappingOverrides: () -> Map<String, String>,
    private val predictIntervalMs: Long = AI_PREDICT_INTERVAL_MS
) : AutoCloseable {

    data class TickDiagnostics(
        val result: AiClassificationResult,
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
        val ambientScore: Float,
        val speechScore: Float,
        val dangerScore: Float,
        val effectiveThreshold: Float,
        val confirmedCoarse: String,
        val confirmedDisplay: String,
        val confirmedConfidence: Float,
        val uiCoarse: String,
        val uiDisplay: String,
        val uiConfidence: Float
    )

    /** Lightweight counters for device tests of inference suppression and lifecycle completion. */
    data class InferenceStats(
        val executed: Long,
        val completed: Long,
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
            mappingOverrides: () -> Map<String, String> = { emptyMap() },
        ): RealtimeAiPipeline {
            val names = context.assets.open("ai/yamnet_class_map.csv").use {
                YamnetCoarseClassifier.loadClassNames(it)
            }
            val appContext = context.applicationContext
            val audioBuffer = AiAudioBuffer(captureSampleRate, channels)
            val coarseClassifier = YamnetCoarseClassifier(names)
            val postProcessor = AiPostProcessor()
            return YamnetInference.create(context).closeOnFailure { yamnet ->
                RealtimeAiPipeline(
                    context = appContext,
                    audioBuffer = audioBuffer,
                    yamnet = yamnet,
                    coarseClassifier = coarseClassifier,
                    classNames = names,
                    postProcessor = postProcessor,
                    mappingOverrides = mappingOverrides
                )
            }
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
    private val inferenceCompletions = java.util.concurrent.atomic.AtomicLong(0)
    private val silenceSkippedTicks = java.util.concurrent.atomic.AtomicLong(0)
    private var lastLogMs = 0L
    private val frontend = QualcommSourceAudioPreprocessor()
    private var previousOverrides: Map<String, String> = emptyMap()
    private var mapping = YamnetMappingPolicy.DEFAULT

    private val captureNeed =
        CaptureAudioMath.captureSamplesForOneYamnetWindow(audioBuffer.sampleRate)
    private val captureScratch = FloatArray(captureNeed)
    private val mono16kScratch = FloatArray(CaptureAudioMath.REQUIRED_MONO_16K_SAMPLES)

    private val debuggable: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun lastClassification(): AiClassificationResult? = lastResult.get()?.takeIf {
        it.mappingInputs == mappingOverrides()
    }

    fun inferenceStatsForTest(): InferenceStats = InferenceStats(
        executed = inferenceExecutions.get(),
        completed = inferenceCompletions.get(),
        skippedForSilence = silenceSkippedTicks.get()
    )

    fun start() {
        if (closed.get()) return
        if (!running.compareAndSet(false, true)) return
        audioBuffer.reset()
        captureInferenceGate.reset()
        postProcessor.reset()
        lastResult.set(null)
        inferenceExecutions.set(0)
        inferenceCompletions.set(0)
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
        lastResult.set(null)
        inferenceExecutions.set(0)
        inferenceCompletions.set(0)
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
        // Skip if previous tick still running (scheduler overlap)
        if (!inferLock.tryLock()) return null
        try {
            // close() 가 락을 잡기 직전에 통과했을 수 있으므로 락 안에서 다시 확인한다.
            if (closed.get()) return null
            // StateFlow publishes immutable maps. Capture once, including during silence, so
            // a previous mapping's confirmed Danger is never carried into the new policy.
            val overrides = mappingOverrides()
            if (overrides != previousOverrides) {
                val next = YamnetMappingPolicy.from(overrides, classNames)
                if (next.signature != mapping.signature) {
                    postProcessor.reset()
                    lastResult.set(null)
                }
                previousOverrides = overrides.toMap()
                mapping = next
            }
            if (!captureInferenceGate.isInferenceOpen(SystemClock.elapsedRealtime())) {
                silenceSkippedTicks.incrementAndGet()
                return null
            }
            val snapshotTimeMs = SystemClock.elapsedRealtime()
            val result = try {
                doInference(log, diagnostics)
            } catch (t: Throwable) {
                captureInferenceGate.onInferenceFailed(snapshotTimeMs)
                throw t
            }
            // doInference() 는 결과를 lastResult 에 넣은 뒤에만 정상 반환한다. close 경쟁 테스트가
            // 일시적인 lastResult 관측 대신 완료 사실 자체를 확인할 수 있게 남긴다.
            inferenceCompletions.incrementAndGet()
            captureInferenceGate.onInferenceCompleted(snapshotTimeMs)
            return result
        } finally {
            inferLock.unlock()
        }
    }

    private fun doInference(log: Boolean, diagnostics: Boolean): TickDiagnostics? {
        inferenceExecutions.incrementAndGet()
        val t0 = System.nanoTime()
        var logMel: FloatArray
        val preprocessNs = measureNanoTime {
            audioBuffer.copyTailRightPadded(captureScratch, captureNeed)
            CaptureAudioMath.resampleMonoFloatTo16kCustom(
                source = captureScratch,
                sourceLength = captureNeed,
                sourceSampleRate = audioBuffer.sampleRate,
                destination = mono16kScratch
            )
            logMel = frontend.computeLogMelSpectrogram(mono16kScratch)
        }
        val mono16kCopy = if (diagnostics) mono16kScratch.copyOf() else null

        var yamnetResult: YamnetInference.Result
        val yamnetNs = measureNanoTime {
            yamnetResult = yamnet.inferFromLogMelFlat(logMel)
        }

        val pre = coarseClassifier.classify(yamnetResult.probabilities, mapping)

        val decision = YamnetSafetyCueDecision.decide(classNames, pre, mapping)

        val topKSummary = pre.top5.joinToString(separator = " | ") { it.name }
        val hasCritical = mapping.hasCriticalDangerCue(decision.postDisplay, pre.top5)

        val post = postProcessor.process(
            AiPostProcessor.FrameInput(
                coarse = decision.postCoarse,
                display = decision.postDisplay,
                confidence = decision.postConfidence,
                dangerCuePromoted = decision.dangerCuePromoted,
                hasStrongDangerCue = decision.hasStrongDangerCue,
                hasCriticalDangerCue = hasCritical,
                topKSummary = topKSummary,
                criticalDangerEvent = hasCritical
            )
        )

        val totalMs = (System.nanoTime() - t0) / 1e6
        val result = AiClassificationResult(
            coarse = post.uiCoarse,
            display = post.uiDisplay,
            confidence = post.uiConfidence,
            top5 = pre.top5,
            dangerCuePromoted = decision.dangerCuePromoted,
            meetsThreshold = post.meetsThreshold,
            timestampMs = System.currentTimeMillis(),
            preprocessMs = preprocessNs / 1e6,
            yamnetMs = yamnetNs / 1e6,
            totalMs = totalMs,
            mappingOverrideCount = mapping.overrideCount,
            mappingSignature = mapping.signature,
            mappingInputs = previousOverrides
        )
        lastResult.set(result)

        if (log && debuggable) {
            maybeLog(
                result = result,
                mono16k = mono16kScratch,
                logMel = logMel,
                pre = pre,
                decision = decision,
                post = post
            )
        }

        if (!diagnostics || mono16kCopy == null) return null
        return TickDiagnostics(
            result = result,
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
            ambientScore = pre.ambientScore,
            speechScore = pre.speechScore,
            dangerScore = pre.dangerScore,
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
        decision: YamnetSafetyCueDecision.Result,
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
                "config[frontend=qualcomm] " +
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
                "pre=${decision.preCoarse}/${decision.preDisplay} " +
                "post=${decision.postCoarse}/${decision.postDisplay} " +
                "cuePromoted=${decision.dangerCuePromoted} " +
                "threshold=${"%.5f".format(java.util.Locale.US, post.effectiveThreshold)} " +
                "confirmed=${post.confirmedCoarse}/${post.confirmedDisplay} " +
                "streak=${post.candidateCoarse}:${post.candidateStreak} " +
                "ui=${post.uiCoarse}/${post.uiDisplay} " +
                "uiConf=${"%.5f".format(java.util.Locale.US, post.uiConfidence)} " +
                "ms[pre=${"%.1f".format(java.util.Locale.US, result.preprocessMs)} " +
                "yam=${"%.1f".format(java.util.Locale.US, result.yamnetMs)} " +
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

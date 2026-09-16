package com.example.soundvisualizer.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * 정지 시 close() 가 진행 중인 ONNX 추론을 기다리는지 확인한다 (#38).
 * 회귀하면 SIGSEGV(프로세스 크래시) 또는
 * "Trying to score a closed OrtSession." 예외로 드러난다.
 *
 * 여기는 무작위 위상으로 경합을 두들기는 쪽이고, 대기·누수·락 안 재확인의 경계 자체는
 * [RealtimeAiPipelineCloseContractInstrumentedTest] 가 순서를 정해 고정한다.
 * 프로덕션 스케줄러 경로는 [RealtimeAiPipelineSchedulerCloseInstrumentedTest] 가 덮는다.
 */
@RunWith(AndroidJUnit4::class)
class RealtimeAiPipelineCloseRaceInstrumentedTest {

    /**
     * 추론 중 close() 가 겹쳐도 락 덕분에 예외도 크래시도 없다.
     *
     * 스케줄러(start)는 예외를 삼키므로, 예외 변종까지 잡기 위해 추론 루프를
     * 테스트 스레드에서 직접 돌린다 — 프로덕션과 같은 runTickInternal 경로다.
     *
     * close() 는 열 번에 한 번 메인 스레드에서 부른다. 프로덕션 호출 자리(onDestroy)가 메인이라,
     * 무작위 경합 중에도 메인 스레드가 오래 붙잡히지 않는지 함께 본다.
     */
    @Test
    fun closeDuringInference_neverCrashesOrThrows() {
        var totalExecuted = 0L
        var maxMainThreadMs = 0L
        repeat(ITERATIONS) { i ->
            val onMainThread = i % 10 == 0
            val result = hammerCloseRace(jitterMs = RNG.nextLong(0, 12), onMainThread = onMainThread)
            assertNull("iteration $i", result.failure)
            // 추론이 한 번도 안 돈 채 close() 했다면 이 테스트는 경합을 만든 적이 없는 것이다.
            assertTrue("iteration $i: close() 전에 추론이 시작되지 않았다", result.executed > 0)
            totalExecuted += result.executed
            if (onMainThread) maxMainThreadMs = maxOf(maxMainThreadMs, result.closeMs)
            if (i % 50 == 0) println("close race iteration $i ok (executed=${result.executed})")
        }
        println("close race: executed=$totalExecuted, main-thread close max=${maxMainThreadMs}ms")
        assertTrue("경합 중 추론이 한 번도 돌지 않았다 — 테스트가 무력화됐다", totalExecuted > 0)
        assertTrue(
            "close() 가 메인 스레드를 ${maxMainThreadMs}ms 붙잡았다 — ANR 위험",
            maxMainThreadMs < CLOSE_WAIT_MS + 200L
        )
    }

    private class RaceResult(val failure: Throwable?, val closeMs: Long, val executed: Long)

    /** 파이프라인 하나를 만들어 추론을 돌리는 중에 close() 를 친다. */
    private fun hammerCloseRace(jitterMs: Long, onMainThread: Boolean = false): RaceResult {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pipeline = RealtimeAiPipeline.create(
            instrumentation.targetContext,
            captureSampleRate = 44100,
            channels = 2
        )
        pipeline.ingestInterleavedForTest(CHUNK, CHUNK.size)

        val failure = AtomicReference<Throwable?>(null)
        val ticker = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    pipeline.runTickForTest()
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        ticker.start()
        // 추론이 실제로 시작된 것을 확인한 뒤에 친다. 그냥 몇 ms 자고 닫으면 스레드가 뜨기도 전에
        // close() 가 끝나 버려, 락이 비어 있는 상태만 반복해서 확인하게 된다 (#49).
        val started = awaitFirstInference(pipeline)
        // 추론 한복판의 여러 지점을 노리도록 위상을 흔든다.
        if (jitterMs > 0) Thread.sleep(jitterMs)

        val closeMs = AtomicReference(0L)
        val doClose = Runnable {
            val t0 = System.nanoTime()
            pipeline.close()
            closeMs.set((System.nanoTime() - t0) / 1_000_000)
        }
        if (onMainThread) instrumentation.runOnMainSync(doClose) else doClose.run()

        ticker.interrupt()
        ticker.join(5_000)
        return RaceResult(failure.get(), closeMs.get(), started)
    }

    /** executed 가 오르면 그 추론은 지금 락 안에서 돌고 있다. 그 순간을 close() 로 덮친다. */
    private fun awaitFirstInference(pipeline: RealtimeAiPipeline): Long {
        val deadline = System.nanoTime() + FIRST_INFERENCE_WAIT_NS
        while (System.nanoTime() < deadline) {
            val executed = pipeline.inferenceStatsForTest().executed
            if (executed > 0) return executed
            Thread.sleep(1)
        }
        return 0
    }

    private companion object {
        const val ITERATIONS = 200

        /** RealtimeAiPipeline 의 close 대기 한도와 같은 값. 바뀌면 여기도 맞춰야 한다. */
        const val CLOSE_WAIT_MS = 1000L
        const val FIRST_INFERENCE_WAIT_NS = 5_000_000_000L

        val RNG = Random(1234)
        val CHUNK = Random(99).let { r -> FloatArray(2 * 44100) { r.nextFloat() * 2f - 1f } }
    }
}

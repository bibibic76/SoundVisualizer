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
 * 프로덕션 스케줄러 경로는 [RealtimeAiPipelineSchedulerCloseInstrumentedTest] 가 덮는다.
 */
@RunWith(AndroidJUnit4::class)
class RealtimeAiPipelineCloseRaceInstrumentedTest {

    /**
     * 추론 중 close() 가 겹쳐도 락 덕분에 예외도 크래시도 없다.
     *
     * 스케줄러(start)는 예외를 삼키므로, 예외 변종까지 잡기 위해 추론 루프를
     * 테스트 스레드에서 직접 돌린다 — 프로덕션과 같은 runTickInternal 경로다.
     */
    @Test
    fun closeDuringInference_neverCrashesOrThrows() {
        repeat(ITERATIONS) { i ->
            val failure = hammerCloseRace(sleepMs = RNG.nextLong(5, 40)).failure
            assertNull("iteration $i", failure)
            if (i % 50 == 0) println("close race iteration $i ok")
        }
    }

    /**
     * close() 는 AudioCaptureService.onDestroy(메인 스레드)에서 불린다.
     * 추론이 돌고 있어도 대기가 타임아웃으로 확실히 끊겨야 ANR 이 안 난다.
     * 한 번만 재면 락을 즉시 잡아 0ms 로 끝날 수 있으니, 반복해서 최댓값을 본다.
     */
    @Test
    fun closeOnMainThread_isBoundedByTimeout() {
        var maxMs = 0L
        repeat(MAIN_THREAD_ITERATIONS) {
            val ms = hammerCloseRace(sleepMs = RNG.nextLong(5, 40), onMainThread = true).closeMs
            maxMs = maxOf(maxMs, ms)
        }
        println("main-thread close() max=${maxMs}ms (limit ${CLOSE_WAIT_MS}ms)")
        // 여유 200ms: 타임아웃 자체는 지켜지되 스케줄링 지터는 허용한다.
        assertTrue(
            "close() 가 메인 스레드를 ${maxMs}ms 붙잡았다 — ANR 위험",
            maxMs < CLOSE_WAIT_MS + 200L
        )
    }

    private class RaceResult(val failure: Throwable?, val closeMs: Long)

    /** 파이프라인 하나를 만들어 추론을 돌리는 중에 close() 를 친다. */
    private fun hammerCloseRace(sleepMs: Long, onMainThread: Boolean = false): RaceResult {
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
        // 추론 한가운데에서 close() 가 걸리도록 어긋난 지연을 준다.
        Thread.sleep(sleepMs)

        val closeMs = AtomicReference(0L)
        val doClose = Runnable {
            val t0 = System.nanoTime()
            pipeline.close()
            closeMs.set((System.nanoTime() - t0) / 1_000_000)
        }
        if (onMainThread) instrumentation.runOnMainSync(doClose) else doClose.run()

        ticker.interrupt()
        ticker.join(5_000)
        return RaceResult(failure.get(), closeMs.get())
    }

    private companion object {
        const val ITERATIONS = 300
        const val MAIN_THREAD_ITERATIONS = 50

        /** RealtimeAiPipeline 의 close 대기 한도와 같은 값. 바뀌면 여기도 맞춰야 한다. */
        const val CLOSE_WAIT_MS = 1000L

        val RNG = Random(1234)
        val CHUNK = Random(99).let { r -> FloatArray(2 * 44100) { r.nextFloat() * 2f - 1f } }
    }
}

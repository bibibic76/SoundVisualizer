package com.example.soundvisualizer.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * 프로덕션과 동일한 경로(start() 의 Dispatchers.Default 스케줄러)에서 정지 경합을 친다 (#38).
 *
 * 틱 주기가 250ms 라, start() 직후 몇 ms 만 기다렸다 닫으면 링이 덜 찬 첫 틱만 잡힌다.
 * 그 틱은 락도 세션도 건드리기 전에 돌아오므로 경합이 아예 만들어지지 않는다.
 * 그래서 추론이 실제로 시작된 순간(executed 가 오르는 순간)을 기다렸다가 닫는다 (#49).
 *
 * 스케줄러는 catch(Throwable) 로 예외를 삼키므로 실패는 두 갈래로 잡는다.
 * 1. logcat 의 "AI tick failed" — debuggable 빌드에서만 남고 링 버퍼가 밀어낼 수 있어 중간중간 읽는다.
 * 2. 로그가 필요 없는 대조 — doInference 는 시작할 때 executed 를 올리고 끝에 결과를 남기므로,
 *    시작한 추론보다 남은 결과가 적으면 그 추론은 도중에 예외로 죽은 것이다.
 * SIGSEGV 변종은 프로세스가 죽어 테스트 실행 자체가 실패한다.
 */
@RunWith(AndroidJUnit4::class)
class RealtimeAiPipelineSchedulerCloseInstrumentedTest {

    @Test
    fun schedulerPath_closeDuringInference_neverLosesATick() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val rng = Random(4321)
        val chunk = Random(7).let { r -> FloatArray(2 * 44100) { r.nextFloat() * 2f - 1f } }

        LogcatReader.clear()

        val tickFailures = linkedSetOf<String>()
        var totalExecuted = 0L
        var iterationsWithInference = 0

        repeat(ITERATIONS) { i ->
            val pipeline = RealtimeAiPipeline.create(app, captureSampleRate = 44100, channels = 2)
            val watcher = ResultWatcher(pipeline)
            watcher.start()
            pipeline.start()
            // start() 이후에만 ingest 가 받아들여진다 (프로덕션 캡처 스레드와 동일).
            pipeline.ingestInterleavedPcm(chunk, chunk.size)

            if (awaitFirstInference(pipeline)) iterationsWithInference++
            // 추론 한복판의 여러 지점을 노리도록 위상을 흔든다.
            val jitterMs = rng.nextLong(0, 4)
            if (jitterMs > 0) Thread.sleep(jitterMs)
            pipeline.close()

            // 시간 초과로 빠진 close() 뒤에도 진행 중이던 추론은 결과를 남기므로 잠깐 더 지켜본다.
            val executed = pipeline.inferenceStatsForTest().executed
            awaitResults(watcher, executed)
            watcher.close()
            assertEquals(
                "iteration $i: 시작한 추론 ${executed}건 중 ${watcher.completed()}건만 결과를 남겼다 — " +
                    "close() 가 기다리지 않아 틱이 도중에 죽었다 (스케줄러가 예외를 삼킨다)",
                executed,
                watcher.completed().toLong()
            )
            totalExecuted += executed

            // 로그는 자주 읽어 둔다. 한 번에 몰아 읽으면 링 버퍼가 그 줄을 이미 밀어냈을 수 있다.
            if (i % LOG_POLL_EVERY == LOG_POLL_EVERY - 1) tickFailures += tickFailureLines()
            if (i % 20 == 0) println("scheduler close iteration $i ok (executed=$executed)")
        }

        // 틱이 250ms 간격이므로 마지막 백그라운드 로그까지 여유를 둔다.
        Thread.sleep(1000)
        tickFailures += tickFailureLines()

        println("scheduler close: executed=$totalExecuted in $iterationsWithInference/$ITERATIONS iterations")
        assertEquals("스케줄러가 삼킨 틱 예외:\n${tickFailures.joinToString("\n")}", 0, tickFailures.size)
        // 추론이 한 번도 안 돈 채 close() 했다면 이 테스트는 경합을 만든 적이 없는 것이다.
        assertTrue("경합 중 추론이 한 번도 돌지 않았다 — 테스트가 무력화됐다", totalExecuted > 0)
        assertTrue(
            "$ITERATIONS 번 중 ${iterationsWithInference}번만 추론에 닿았다 — 틱 타이밍이 바뀐 것 같다",
            iterationsWithInference >= ITERATIONS / 2
        )
    }

    private fun tickFailureLines(): List<String> =
        LogcatReader.linesFor("RealtimeAiPipeline").filter { it.contains("AI tick failed") }

    /** 스케줄러가 추론을 시작하면(executed 가 오르면) 그 추론은 지금 락 안에서 돌고 있다. */
    private fun awaitFirstInference(pipeline: RealtimeAiPipeline): Boolean {
        val deadline = System.nanoTime() + FIRST_INFERENCE_WAIT_NS
        while (System.nanoTime() < deadline) {
            if (pipeline.inferenceStatsForTest().executed > 0) return true
            Thread.sleep(1)
        }
        return false
    }

    private fun awaitResults(watcher: ResultWatcher, expected: Long) {
        val deadline = System.nanoTime() + SETTLE_WAIT_NS
        while (System.nanoTime() < deadline && watcher.completed() < expected) {
            Thread.sleep(1)
        }
    }

    /**
     * 끝까지 돈 추론의 수를 센다 — 로그캣에 기대지 않는 두 번째 탐지기.
     *
     * close() 가 마지막에 lastResult 를 비우므로, 추론이 끝난 직후의 짧은 순간을 놓치면 안 된다.
     * 그래서 추론이 시작된 뒤에는 쉬지 않고 돌며 본다.
     */
    private class ResultWatcher(private val pipeline: RealtimeAiPipeline) {
        private val seen =
            Collections.newSetFromMap(IdentityHashMap<AiClassificationResult, Boolean>())
        private val distinct = AtomicInteger(0)
        private val running = AtomicBoolean(true)
        private val thread = Thread({
            var inferenceStarted = false
            while (running.get()) {
                val result = pipeline.lastClassification()
                if (result != null && seen.add(result)) distinct.incrementAndGet()
                if (!inferenceStarted) {
                    // 추론 전에는 쉬어 준다. 뜨겁게 도는 구간에서는 stats 를 읽지 않는다 — 읽을 때마다
                    // 객체가 하나씩 생겨 GC 가 추론 스레드까지 흔든다.
                    if (pipeline.inferenceStatsForTest().executed > 0L) {
                        inferenceStarted = true
                    } else {
                        Thread.sleep(1)
                    }
                }
            }
        }, "test-result-watcher").apply { isDaemon = true }

        fun start() = thread.start()

        fun completed(): Int = distinct.get()

        fun close() {
            running.set(false)
            thread.join(5_000)
        }
    }

    private companion object {
        const val ITERATIONS = 50
        const val LOG_POLL_EVERY = 10
        const val FIRST_INFERENCE_WAIT_NS = 3_000_000_000L
        const val SETTLE_WAIT_NS = 2_000_000_000L
    }
}

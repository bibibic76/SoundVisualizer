package com.example.soundvisualizer.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
 * 2. 로그가 필요 없는 대조 — doInference 는 결과를 남긴 뒤에만 completed 를 올린다. 시작한
 *    추론보다 완료 수가 적으면 그 추론은 도중에 예외로 죽었거나 끝나지 않은 것이다.
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
            pipeline.start()
            // start() 이후에만 ingest 가 받아들여진다 (프로덕션 캡처 스레드와 동일).
            pipeline.ingestInterleavedPcm(chunk, chunk.size)

            if (awaitFirstInference(pipeline)) iterationsWithInference++
            // 추론 한복판의 여러 지점을 노리도록 위상을 흔든다.
            val jitterMs = rng.nextLong(0, 4)
            if (jitterMs > 0) Thread.sleep(jitterMs)
            pipeline.close()

            // 시간 초과로 빠진 close() 뒤에도 진행 중이던 추론은 세션을 건드리지 않은 채 끝날 수 있다.
            // 그 완료만 제한 시간 동안 기다린다.
            val executed = pipeline.inferenceStatsForTest().executed
            awaitCompletedInferences(pipeline, executed)
            val completed = pipeline.inferenceStatsForTest().completed
            assertEquals(
                "iteration $i: 시작한 추론 ${executed}건 중 ${completed}건만 완료했다 — " +
                    "close() 뒤 틱이 도중에 죽었거나 끝나지 않았다 (스케줄러가 예외를 삼킨다)",
                executed,
                completed
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

    private fun awaitCompletedInferences(pipeline: RealtimeAiPipeline, expected: Long) {
        val deadline = System.nanoTime() + SETTLE_WAIT_NS
        while (System.nanoTime() < deadline && pipeline.inferenceStatsForTest().completed < expected) {
            Thread.sleep(1)
        }
    }

    private companion object {
        const val ITERATIONS = 50
        const val LOG_POLL_EVERY = 10
        const val FIRST_INFERENCE_WAIT_NS = 3_000_000_000L
        const val SETTLE_WAIT_NS = 2_000_000_000L
    }
}

package com.example.soundvisualizer.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * #38 수정(정지 시 진행 중인 추론을 기다렸다 ONNX 세션을 닫는다)의 세 갈래를, 타이밍 운에 기대지 않고
 * 순서를 정해 고정한다 (#49). 무작위로 두들기는 쪽은 [RealtimeAiPipelineCloseRaceInstrumentedTest] 가 맡는다.
 *
 * 1. 추론이 도는 중이면 close() 는 기다린다 — 그러나 한도까지만 (메인 스레드 = ANR).
 * 2. 한도를 넘기면 세션을 닫지 않는다 — 크래시보다 누수가 낫다.
 * 3. closed 가 선 뒤 락을 잡은 틱은 추론을 돌리지 않는다 — 락 안 재확인.
 *
 * 세 가지 모두 파이프라인 내부 상태를 봐야 해서 [PipelineInternals] 의 리플렉션을 쓴다.
 */
@RunWith(AndroidJUnit4::class)
class RealtimeAiPipelineCloseContractInstrumentedTest {

    /**
     * 추론이 한도보다 오래 걸리는 상황 = 락이 한도보다 오래 잡혀 있는 상황.
     * 아래로는 "기다리지 않고 닫아 버리는" 회귀를, 위로는 "무제한 대기(ANR)"를 막는다.
     * 덤으로 시간 초과 뒤 세션이 살아 있는지(누수 분기)까지 이 자리에서 확인한다.
     */
    @Test
    fun closeOnMainThread_waitsForRunningInference_butGivesUpAtTimeout() {
        val pipeline = newWarmedPipeline()
        val lock = PipelineInternals.inferLock(pipeline)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread({
            lock.lock()
            try {
                holding.countDown()
                // close() 가 무제한 대기로 바뀌면 여기서 풀어 줘야 테스트가 멈추지 않고 "너무 오래 걸렸다"로 실패한다.
                release.await(HOLD_MS, TimeUnit.MILLISECONDS)
            } finally {
                lock.unlock()
            }
        }, "test-infer-holder")
        holder.isDaemon = true
        holder.start()
        assertTrue("테스트가 추론 락을 잡지 못했다", holding.await(5, TimeUnit.SECONDS))

        val elapsedMs = closeOnMainThread(pipeline)
        release.countDown()
        holder.join(5_000)
        println("close() while a tick holds the lock: ${elapsedMs}ms (limit ${CLOSE_WAIT_MS}ms)")

        assertTrue(
            "close() 가 진행 중인 추론을 기다리지 않고 ${elapsedMs}ms 만에 끝났다 — 세션을 먼저 닫으면 use-after-free",
            elapsedMs >= CLOSE_WAIT_MS - TIMER_SLACK_MS
        )
        assertTrue(
            "close() 가 메인 스레드를 ${elapsedMs}ms 붙잡았다 — 한도 없는 대기는 ANR",
            elapsedMs < CLOSE_WAIT_MS + UPPER_SLACK_MS
        )
        assertNull(
            "시간 초과로 빠졌는데도 ONNX 세션을 닫았다 — 진행 중이던 추론이 use-after-free 로 죽는다",
            PipelineInternals.sessionFailure(pipeline)
        )

        // 일부러 누수시킨 세션이라 close() 로는 못 닫는다. 남은 테스트에 15MB 를 물려주지 않도록 여기서 정리한다.
        PipelineInternals.releaseLeakedSessions(pipeline)
    }

    /** 반대쪽 고정: 기다릴 것이 없으면 close() 는 바로 세션을 닫는다 (누수 분기가 기본이 되면 안 된다). */
    @Test
    fun closeWhileIdle_closesSessionsRightAway() {
        val pipeline = newWarmedPipeline()

        val elapsedMs = closeOnMainThread(pipeline)
        println("idle close(): ${elapsedMs}ms")

        assertTrue("한가한 close() 가 ${elapsedMs}ms 나 걸렸다", elapsedMs < IDLE_CLOSE_LIMIT_MS)
        val failure = PipelineInternals.sessionFailure(pipeline)
        assertNotNull("close() 가 ONNX 세션을 닫지 않았다 — 정상 종료인데도 누수", failure)
        println("closed-session probe: ${failure?.javaClass?.simpleName}: ${failure?.message}")
    }

    /**
     * 락 안 closed 재확인 (#38 수정의 나머지 절반).
     *
     * closed 검사를 이미 통과한 틱을 링 버퍼 모니터 앞에 세워 두고, 그 사이에 closed 를 세운다.
     * 세션은 살려 두므로, 재확인이 사라지면 크래시가 아니라 "추론이 한 번 더 돌았다"로 조용히 드러난다.
     * (프로덕션에서는 이때 세션이 이미 닫혀 있어 use-after-free 가 된다.)
     */
    @Test
    fun tickEnteredBeforeClose_doesNotInferOnceClosedIsSet() {
        val pipeline = newWarmedPipeline()
        val before = pipeline.inferenceStatsForTest().executed
        assertTrue("준비 틱이 추론을 돌리지 못했다", before > 0)

        val tick = AtomicReference<RealtimeAiPipeline.TickDiagnostics?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val ticker = Thread({
            try {
                tick.set(pipeline.runTickForTest())
            } catch (t: Throwable) {
                failure.set(t)
            }
        }, "test-tick")
        ticker.isDaemon = true

        synchronized(PipelineInternals.ringMonitor(pipeline)) {
            ticker.start()
            // 링 검사에서 막혔다 = closed 검사는 이미 통과했고 아직 락은 건드리지 않았다.
            awaitBlocked(ticker)
            PipelineInternals.setClosedFlag(pipeline, true)
        }

        ticker.join(10_000)
        assertFalse("틱 스레드가 끝나지 않았다", ticker.isAlive)
        assertNull("틱이 예외를 던졌다", failure.get())
        // 진단 문구에 TickDiagnostics 를 통째로 찍지 않는다 — 배열 세 개가 그대로 찍혔다.
        assertTrue("closed 인데도 틱이 추론 결과를 만들었다", tick.get() == null)
        assertEquals(
            "closed 가 선 뒤에 락을 잡은 틱이 추론을 돌렸다 — 락 안 재확인이 사라졌다",
            before,
            pipeline.inferenceStatsForTest().executed
        )

        // 리플렉션으로 세운 플래그라 되돌려 놔야 close() 가 실제로 세션을 닫는다.
        PipelineInternals.setClosedFlag(pipeline, false)
        pipeline.close()
    }

    /** 세션·게이트·JIT 을 모두 데워 둔 파이프라인. 첫 틱이 링 부족으로 헛도는 일을 없앤다. */
    private fun newWarmedPipeline(): RealtimeAiPipeline {
        val pipeline = RealtimeAiPipeline.create(
            InstrumentationRegistry.getInstrumentation().targetContext,
            captureSampleRate = 44100,
            channels = 2
        )
        pipeline.ingestInterleavedForTest(CHUNK, CHUNK.size)
        assertNotNull("준비 틱이 돌지 않았다", pipeline.runTickForTest())
        return pipeline
    }

    /** close() 는 AudioCaptureService.onDestroy(메인 스레드)에서 불린다 — 재는 자리도 같아야 의미가 있다. */
    private fun closeOnMainThread(pipeline: RealtimeAiPipeline): Long {
        val elapsedMs = AtomicLong(0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val t0 = System.nanoTime()
            pipeline.close()
            elapsedMs.set((System.nanoTime() - t0) / 1_000_000)
        }
        return elapsedMs.get()
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + BLOCK_WAIT_NS
        while (System.nanoTime() < deadline) {
            if (thread.state == Thread.State.BLOCKED) return
            Thread.sleep(1)
        }
        throw AssertionError("틱 스레드가 링 버퍼 모니터에서 멈추지 않았다 (state=${thread.state})")
    }

    private companion object {
        /** RealtimeAiPipeline 의 close 대기 한도와 같은 값. 바뀌면 여기도 맞춰야 한다. */
        const val CLOSE_WAIT_MS = 1000L

        /** 한도를 넘겨도 테스트가 멈추지 않도록, 락은 한도보다 1초만 더 잡는다. */
        const val HOLD_MS = CLOSE_WAIT_MS + 1000L
        const val TIMER_SLACK_MS = 50L
        const val UPPER_SLACK_MS = 400L
        const val IDLE_CLOSE_LIMIT_MS = 500L
        const val BLOCK_WAIT_NS = 5_000_000_000L

        val CHUNK = Random(99).let { r -> FloatArray(2 * 44100) { r.nextFloat() * 2f - 1f } }
    }
}

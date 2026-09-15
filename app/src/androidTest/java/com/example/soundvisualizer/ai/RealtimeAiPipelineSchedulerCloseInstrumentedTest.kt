package com.example.soundvisualizer.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * 프로덕션과 동일한 경로(start() 의 Dispatchers.Default 스케줄러)에서 정지 경합을 친다 (#38).
 *
 * 스케줄러는 catch(Throwable) 로 예외를 삼키고 debuggable 일 때만 로그를 남기므로,
 * 예외 변종은 logcat 의 "AI tick failed" 로 잡는다. SIGSEGV 변종은 프로세스가 죽어
 * 테스트 실행 자체가 실패한다.
 */
@RunWith(AndroidJUnit4::class)
class RealtimeAiPipelineSchedulerCloseInstrumentedTest {

    @Test
    fun schedulerPath_startStopRepeatedly_logsNoTickFailure() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val rng = Random(4321)
        val chunk = Random(7).let { r -> FloatArray(2 * 44100) { r.nextFloat() * 2f - 1f } }

        LogcatReader.clear()

        repeat(ITERATIONS) { i ->
            val pipeline = RealtimeAiPipeline.create(app, captureSampleRate = 44100, channels = 2)
            pipeline.start()
            // start() 이후에만 ingest 가 받아들여진다 (프로덕션 캡처 스레드와 동일).
            pipeline.ingestInterleavedPcm(chunk, chunk.size)
            // 틱 간격이 250ms 라, 추론에 들어간 순간을 노리려면 그 주변에서 흔들어야 한다.
            Thread.sleep(rng.nextLong(1, 60))
            pipeline.close()
            if (i % 20 == 0) println("scheduler close iteration $i ok")
        }

        // 틱이 250ms 간격이므로 마지막 백그라운드 로그까지 여유를 둔다.
        Thread.sleep(1000)
        val failures = LogcatReader.linesFor("RealtimeAiPipeline")
            .filter { it.contains("AI tick failed") }

        assertEquals("스케줄러가 삼킨 틱 예외:\n${failures.joinToString("\n")}", 0, failures.size)
    }

    private companion object {
        const val ITERATIONS = 100
    }
}

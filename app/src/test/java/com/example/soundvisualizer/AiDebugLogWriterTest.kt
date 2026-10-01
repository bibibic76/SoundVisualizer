package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 개발자 모드 기록의 쌓는 규칙 (#193).
 *
 * 세 가지를 고정한다. 머리글이 한 번만 나가는 것, 같은 결과를 두 번 적지 않는 것(HUD 는 100ms 마다
 * 읽지만 추론은 250ms 주기다), 상한에 닿으면 멈추는 것.
 */
class AiDebugLogWriterTest {

    private fun result(timestampMs: Long, coarse: String = "ambient") = AiClassificationResult(
        coarse = coarse,
        display = "Speech",
        confidence = 0.5f,
        top5 = emptyList(),
        dangerCuePromoted = false,
        meetsThreshold = true,
        timestampMs = timestampMs,
        preprocessMs = 1.0,
        yamnetMs = 2.0,
        totalMs = 3.0
    )

    @Test
    fun `머리글은 첫 줄과 함께 한 번만 나간다`() {
        val sink = StringBuilder()
        val writer = AiDebugLogWriter(sink)
        assertTrue(writer.write(result(1_000L), 1_000L, CaptureSource.InternalPlayback, 0.1f, true))
        assertTrue(writer.write(result(1_250L), 1_250L, CaptureSource.InternalPlayback, 0.1f, true))

        val lines = sink.toString().trim().lines()
        assertEquals(3, lines.size)
        assertEquals(AiDebugCsv.HEADER, lines[0])
        assertEquals(1, lines.count { it == AiDebugCsv.HEADER })
        assertEquals(2, writer.rows)
    }

    @Test
    fun `결과가 없으면 아무것도 쓰지 않는다`() {
        // 기록만 켜고 소리가 없으면 빈 파일이어야 한다. 머리글만 남은 파일은 "돌았는데 결과가 없었다" 와
        // "켜지지 않았다" 를 헷갈리게 한다.
        val sink = StringBuilder()
        AiDebugLogWriter(sink)
        assertEquals("", sink.toString())
    }

    @Test
    fun `같은 결과를 두 번 읽으면 한 번만 적는다`() {
        val sink = StringBuilder()
        val writer = AiDebugLogWriter(sink)
        val same = result(2_000L)
        assertTrue(writer.write(same, 2_000L, CaptureSource.InternalPlayback, 0.1f, true))
        assertFalse("같은 추론 결과가 두 줄이 됐다", writer.write(same, 2_100L, CaptureSource.InternalPlayback, 0.1f, true))
        assertFalse(writer.write(result(2_000L), 2_200L, CaptureSource.InternalPlayback, 0.1f, true))
        assertTrue(writer.write(result(2_250L), 2_250L, CaptureSource.InternalPlayback, 0.1f, true))
        assertEquals(2, writer.rows)
    }

    @Test
    fun `상한에 닿으면 멈추고 더 쓰지 않는다`() {
        val sink = StringBuilder()
        // 머리글 + 한 줄이면 이미 넘는 아주 작은 상한.
        val writer = AiDebugLogWriter(sink, maxBytes = 10L)
        assertTrue(writer.write(result(1_000L), 1_000L, CaptureSource.InternalPlayback, 0f, false))
        assertTrue("상한에 닿았는데 멈추지 않았다", writer.stopped)
        assertFalse(writer.write(result(1_250L), 1_250L, CaptureSource.InternalPlayback, 0f, false))
        assertEquals(1, writer.rows)
        assertEquals(2, sink.toString().trim().lines().size)
    }

    @Test
    fun `쓴 줄에 그때의 값이 들어간다`() {
        val sink = StringBuilder()
        val writer = AiDebugLogWriter(sink)
        writer.write(result(3_000L, coarse = "danger"), 3_120L, CaptureSource.Microphone, 0.42f, false)

        val row = sink.toString().trim().lines()[1]
        assertEquals(
            AiDebugCsv.row(result(3_000L, coarse = "danger"), 3_120L, CaptureSource.Microphone, 0.42f, false),
            row
        )
        assertTrue("마이크 source가 빠졌다", row.contains("Microphone"))
        assertTrue("소리 크기가 빠졌다", row.contains("0.42000"))
        assertTrue("표시 여부가 빠졌다", row.contains("false"))
    }
}

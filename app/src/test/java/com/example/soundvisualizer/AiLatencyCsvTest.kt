package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiLatencyTrace
import org.junit.Assert.*
import org.junit.Test

class AiLatencyCsvTest {
    @Test fun lifecycleEventsHaveSameColumnsAndEscapeDetails() {
        val row = AiLatencyCsv.row(AiLatencyTrace.Event("capture_start", 123, sessionId = 2,
            detail = "source=\"microphone\",rate=48000"))
        assertTrue(row.startsWith("\"1\",\"capture_start\",\"123\",\"2\""))
        assertTrue(row.contains("source=\"\"microphone\"\",rate=48000"))
        assertEquals(AiLatencyCsv.HEADER.split(',').size, row.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()).size)
    }
}

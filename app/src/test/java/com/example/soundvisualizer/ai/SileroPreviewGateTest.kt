package com.example.soundvisualizer.ai

import org.junit.Assert.*
import org.junit.Test

class SileroPreviewGateTest {
    private fun gate() = SileroPreviewGate<String>().apply {
        regular(SileroPreviewGate.Candidate("parent", 100, 1000, 1300, 7))
    }
    @Test fun needsThreeContiguousFreshBlocks() {
        val gate = gate()
        assertNull(gate.block(7, 0, 100, 1f, 100))
        assertNull(gate.block(7, 100, 200, 1f, 200))
        assertNull(gate.block(7, 200, 300, 1f, 300))
        assertNotNull(gate.block(7, 300, 400, 1f, 400))
        assertNull(gate.block(7, 400, 500, 1f, 500))
    }
    @Test fun lowNonfiniteOrMissingBlockResetsEvidence() {
        for (p in listOf(.89f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val gate = gate()
            gate.block(7, 100, 200, 1f, 200)
            gate.block(7, 200, 300, p, 300)
            assertNull(gate.block(7, 300, 400, 1f, 400))
        }
        val gate = gate()
        gate.block(7, 100, 200, 1f, 200)
        gate.block(7, 200, 300, 1f, 300)
        assertNull(gate.block(7, 400, 500, 1f, 500))
    }
    @Test fun deadlineAndGenerationRejectLateWork() {
        val gate = gate()
        gate.block(7, 100, 200, 1f, 200)
        gate.block(7, 200, 300, 1f, 300)
        assertNull(gate.block(6, 300, 400, 1f, 400))
        assertNull(gate.block(7, 300, 400, 1f, 1000))
        assertNull(gate.view(1001))
    }
    @Test fun nextRegularStartCannotAcceptMoreButDoesNotPrematurelyRetract() {
        val gate = gate()
        for (i in 1..3) gate.block(7, i * 100L, (i + 1) * 100L, 1f, i * 100L)
        gate.regularStarted()
        assertEquals("parent", gate.view(1100)?.parent)
        assertNull(gate.view(1300))
        gate.regular(null) // new regular result / Danger / stop / reset
        assertNull(gate.view(1100))
    }
    @Test fun newParentDoesNotInheritOldEvidence() {
        val gate = gate()
        gate.block(7, 100, 200, 1f, 200)
        gate.block(7, 200, 300, 1f, 300)
        gate.regular(SileroPreviewGate.Candidate("new", 300, 1000, 1300, 7))
        assertNull(gate.block(7, 300, 400, 1f, 400))
    }
}

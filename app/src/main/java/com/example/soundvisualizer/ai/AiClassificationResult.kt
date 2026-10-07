package com.example.soundvisualizer.ai

/**
 * Lightweight AI classification snapshot for observation / future Overlay wiring.
 * Not a StateFlow architecture — plain immutable result.
 */
data class AiClassificationResult(
    val coarse: String,
    val display: String,
    val confidence: Float,
    val top5: List<YamnetCoarseClassifier.TopClassHit> = emptyList(),
    val dangerCuePromoted: Boolean = false,
    val meetsThreshold: Boolean,
    val timestampMs: Long,
    val preprocessMs: Double = 0.0,
    val yamnetMs: Double = 0.0,
    val totalMs: Double = 0.0,
    val mappingOverrideCount: Int = 0,
    val mappingSignature: String = "default",
    /** Immutable settings snapshot used to suppress stale results immediately on edits. */
    internal val mappingInputs: Map<String, String> = emptyMap()
)

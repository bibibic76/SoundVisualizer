package com.example.soundvisualizer.ai

/**
 * Lightweight AI classification snapshot for observation / future Overlay wiring.
 * Not a StateFlow architecture — plain immutable result.
 */
data class AiClassificationResult(
    val coarse: String,
    val display: String,
    val confidence: Float,
    val gunshotScore: Float,
    val preBoosterCoarse: String,
    val boosterAccepted: Boolean,
    val meetsThreshold: Boolean,
    val useBoosterDangerPreview: Boolean,
    val timestampMs: Long,
    val preprocessMs: Double = 0.0,
    val yamnetMs: Double = 0.0,
    val boosterMs: Double = 0.0,
    val totalMs: Double = 0.0
)

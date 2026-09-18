package com.example.soundvisualizer.ai

/**
 * Lightweight AI classification snapshot for observation / future Overlay wiring.
 * Not a StateFlow architecture — plain immutable result.
 */
data class AiClassificationResult(
    val coarse: String,
    val display: String,
    val confidence: Float,
    /** Valid only when [boosterAvailable] is true; otherwise [Float.NaN]. */
    val gunshotScore: Float,
    val top5: List<YamnetCoarseClassifier.TopClassHit> = emptyList(),
    val gunshotEvidence: Float = 0f,
    val boosterReason: String = "",
    val dangerCuePromoted: Boolean = false,
    val boosterAvailable: Boolean,
    val preBoosterCoarse: String,
    val boosterAccepted: Boolean,
    val meetsThreshold: Boolean,
    val useBoosterDangerPreview: Boolean,
    val timestampMs: Long,
    val preprocessMs: Double = 0.0,
    val yamnetMs: Double = 0.0,
    val boosterMs: Double = 0.0,
    val totalMs: Double = 0.0,
    /** Frontend actually used for this result, not the setting currently shown by the UI. */
    val frontendMode: AiFrontendMode = AiFrontendMode.CURRENT,
    /** Whether Booster inference was requested for this result. It may still be unavailable. */
    val boosterEnabled: Boolean = true
)

package com.example.soundvisualizer.ai

/** YAMNet frontend that a single realtime inference tick uses. */
enum class AiFrontendMode(val diagnosticName: String) {
    CURRENT("current"),
    QUALCOMM_SOURCE("qualcomm")
}

/**
 * Developer-only A/B selection for the realtime AI path.
 *
 * Production behavior stays in [DEFAULT]. The pipeline reads one immutable copy
 * at the start of a tick so frontend and Booster choices cannot change halfway
 * through that inference.
 */
data class AiDiagnosticConfig(
    val frontendMode: AiFrontendMode = AiFrontendMode.CURRENT,
    val boosterEnabled: Boolean = true
) {
    companion object {
        val DEFAULT = AiDiagnosticConfig()
    }
}

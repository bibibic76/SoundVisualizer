package com.example.soundvisualizer.ai

/**
 * Threshold + ApplyCoarseHysteresis + booster UI preview.
 * Pure Kotlin — no Android framework, no capture/overlay coupling.
 */
class AiPostProcessor(
    private val baseConfidenceThreshold: Float = DEFAULT_BASE_THRESHOLD
) {

    data class FrameInput(
        val coarse: String,
        val display: String,
        val confidence: Float,
        val adoptedDangerFromBooster: Boolean = false,
        /** Top-5에 strong danger keyword 존재 (threshold 완화용). */
        val hasStrongDangerCue: Boolean = false,
        /** Top-5에 critical danger keyword 존재 (threshold 완화용). */
        val hasCriticalDangerCue: Boolean = false,
        /** FormatTop5 요약 문자열 — IsCriticalDangerEvent용 (optional). */
        val topKSummary: String = ""
    )

    data class FrameResult(
        val effectiveThreshold: Float,
        val meetsThreshold: Boolean,
        val candidateCoarse: String,
        val candidateStreak: Int,
        val confirmedCoarse: String,
        val confirmedDisplay: String,
        val confirmedConfidence: Float,
        val useBoosterDangerPreview: Boolean,
        val uiCoarse: String,
        val uiDisplay: String,
        val uiConfidence: Float
    )

    companion object {
        const val DEFAULT_BASE_THRESHOLD = 0.25f
        const val COARSE_HYSTERESIS_THRESHOLD = 2
        const val DANGER_HYSTERESIS_THRESHOLD = 1
        const val DANGER_IMMEDIATE_SWITCH_CONFIDENCE = 0.28f
        const val DANGER_EXIT_RELAXED_CONFIDENCE = 0.27f

        fun computeEffectiveThreshold(
            coarse: String,
            baseThreshold: Float,
            adoptedDangerFromBooster: Boolean,
            hasStrongDangerCue: Boolean,
            hasCriticalDangerCue: Boolean
        ): Float {
            var effective = baseThreshold
            if (coarse == "danger" &&
                (hasStrongDangerCue || hasCriticalDangerCue || adoptedDangerFromBooster)
            ) {
                effective = minOf(effective, if (adoptedDangerFromBooster) 0.18f else 0.20f)
            } else if (coarse == "speech") {
                effective = maxOf(effective, 0.25f)
            }
            return effective
        }

        fun isCriticalDangerKeyword(name: String?): Boolean {
            if (name.isNullOrEmpty()) return false
            if (GunshotBoosterDecision.isGunshotKeyword(name)) return true
            val s = name.lowercase()
            return "explosion" in s || "fireworks" in s || "firecracker" in s
        }

        fun isCriticalDangerEvent(display: String?, topKSummary: String?): Boolean {
            return isCriticalDangerKeyword(display) || isCriticalDangerKeyword(topKSummary)
        }
    }

    private var confirmedCoarse: String = "ambient"
    private var confirmedDisplay: String = ""
    private var confirmedConfidence: Float = 0f
    private var candidateCoarse: String = ""
    private var candidateStreak: Int = 0

    fun reset() {
        confirmedCoarse = "ambient"
        confirmedDisplay = ""
        confirmedConfidence = 0f
        candidateCoarse = ""
        candidateStreak = 0
    }

    fun process(frame: FrameInput): FrameResult {
        val effective = computeEffectiveThreshold(
            coarse = frame.coarse,
            baseThreshold = baseConfidenceThreshold,
            adoptedDangerFromBooster = frame.adoptedDangerFromBooster,
            hasStrongDangerCue = frame.hasStrongDangerCue,
            hasCriticalDangerCue = frame.hasCriticalDangerCue
        )
        val meets = frame.confidence >= effective

        // Snapshot for InferenceResult-equivalent used by hysteresis
        val rCoarse = frame.coarse
        val rDisplay = frame.display
        val rConf = frame.confidence
        val rAdopted = frame.adoptedDangerFromBooster
        val rMeets = meets
        val rCritical = isCriticalDangerEvent(frame.display, frame.topKSummary)

        applyCoarseHysteresis(
            meetsThreshold = rMeets,
            newCoarse = rCoarse,
            display = rDisplay,
            confidence = rConf,
            adoptedDangerFromBooster = rAdopted,
            criticalDangerEvent = rCritical
        )

        val usePreview =
            confirmedCoarse != "danger" &&
                frame.adoptedDangerFromBooster &&
                frame.coarse == "danger" &&
                meets

        val uiCoarse = if (usePreview) "danger" else confirmedCoarse
        val uiDisplay = if (usePreview) frame.display else confirmedDisplay
        val uiConfidence = if (usePreview) frame.confidence else confirmedConfidence

        return FrameResult(
            effectiveThreshold = effective,
            meetsThreshold = meets,
            candidateCoarse = candidateCoarse,
            candidateStreak = candidateStreak,
            confirmedCoarse = confirmedCoarse,
            confirmedDisplay = confirmedDisplay,
            confirmedConfidence = confirmedConfidence,
            useBoosterDangerPreview = usePreview,
            uiCoarse = uiCoarse,
            uiDisplay = uiDisplay,
            uiConfidence = uiConfidence
        )
    }

    private fun applyCoarseHysteresis(
        meetsThreshold: Boolean,
        newCoarse: String,
        display: String,
        confidence: Float,
        adoptedDangerFromBooster: Boolean,
        criticalDangerEvent: Boolean
    ) {
        if (!meetsThreshold) return

        if (newCoarse == confirmedCoarse) {
            candidateStreak = 0
            candidateCoarse = ""
            confirmedDisplay = display
            confirmedConfidence = confidence
            return
        }

        if (newCoarse == "danger" &&
            (confidence >= DANGER_IMMEDIATE_SWITCH_CONFIDENCE || criticalDangerEvent)
        ) {
            confirmedCoarse = newCoarse
            confirmedDisplay = display
            confirmedConfidence = confidence
            candidateStreak = 0
            candidateCoarse = ""
            return
        }

        if (newCoarse == candidateCoarse) {
            candidateStreak++
        } else {
            candidateCoarse = newCoarse
            candidateStreak = 1
        }

        var required =
            if (newCoarse == "danger") DANGER_HYSTERESIS_THRESHOLD else COARSE_HYSTERESIS_THRESHOLD
        if (confirmedCoarse == "danger" &&
            newCoarse != "danger" &&
            !adoptedDangerFromBooster &&
            confidence >= DANGER_EXIT_RELAXED_CONFIDENCE
        ) {
            required = 1
        }

        if (candidateStreak >= required) {
            confirmedCoarse = newCoarse
            confirmedDisplay = display
            confirmedConfidence = confidence
            candidateStreak = 0
            candidateCoarse = ""
        }
    }
}

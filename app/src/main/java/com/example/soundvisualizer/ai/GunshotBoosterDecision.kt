package com.example.soundvisualizer.ai

import kotlin.math.max

/**
 * Gunshot-booster adopt/reject rules (no threshold/hysteresis).
 * Inference score is supplied externally ([GunshotBoosterInference]).
 */
object GunshotBoosterDecision {

    data class Result(
        val gunshotScore: Float,
        val gunshotEvidence: Float,
        val accepted: Boolean,
        val reason: String,
        val preBoosterCoarse: String,
        val postBoosterCoarse: String,
        val preBoosterDisplay: String,
        val postBoosterDisplay: String,
        val preBoosterClassIndex: Int,
        val postBoosterClassIndex: Int,
        val preBoosterConfidence: Float,
        val postBoosterConfidence: Float,
        val hasGunshotCue: Boolean,
        val hasStrongDangerCue: Boolean
    )

    /**
     * @param pre [YamnetCoarseClassifier] result (PreferDanger + Vote already applied)
     * @param gunshotScore raw booster ONNX output
     */
    fun decide(
        probabilities: FloatArray,
        classNames: List<String>,
        pre: YamnetCoarseClassifier.Result,
        gunshotScore: Float
    ): Result {
        require(probabilities.size == 521)
        require(classNames.size == 521)

        val topIdx = IntArray(5) { -1 }
        val topProbs = FloatArray(5) { -1f }
        for (i in pre.top5.indices) {
            if (i >= 5) break
            topIdx[i] = pre.top5[i].index
            topProbs[i] = pre.top5[i].probability
        }

        val evidence = sumGunshotProbabilityFromTop5(topIdx, topProbs, classNames, 5)
        val hasGunshotCue = hasGunshotCueInTop5(topIdx, classNames, 5)
        val hasStrongDangerCue = hasStrongDangerCueInTop5(topIdx, classNames, 5)

        val yamnetCoarse = pre.coarse
        val display = pre.displayName
        val conf = pre.confidence

        val blockBooster =
            yamnetCoarse == "speech" ||
                isSpeechLikeDisplay(display) ||
                isSilenceLikeDisplay(display) ||
                conf < 0.12f

        var adopt = false
        val reason: String
        if (blockBooster) {
            reason = "blocked_speech_silence_or_low_conf"
        } else if (hasGunshotCue) {
            adopt = gunshotScore >= 0.20f && evidence >= 0.05f
            reason =
                "gunshot_cue score=${fmt(gunshotScore)} evidence=${fmt(evidence)} adopt=${pyBool(adopt)}"
        } else if (isGameMixMaskDisplay(display) || hasStrongDangerCue) {
            adopt = gunshotScore >= 0.50f || (gunshotScore >= 0.40f && evidence >= 0.04f)
            reason =
                "game_mix_or_strong_danger score=${fmt(gunshotScore)} evidence=${fmt(evidence)} adopt=${pyBool(adopt)}"
        } else {
            adopt = gunshotScore >= 0.45f && evidence >= 0.10f
            reason =
                "default score=${fmt(gunshotScore)} evidence=${fmt(evidence)} adopt=${pyBool(adopt)}"
        }

        var postCoarse = yamnetCoarse
        var postDisplay = display
        var postIndex = pre.yamnetClassIndex
        var postConf = conf

        if (adopt) {
            postCoarse = "danger"
            postConf = max(postConf, max(gunshotScore, evidence))
            val (gunIdx, gunProb) = tryPickBestGunshotDisplay(probabilities, classNames)
            if (gunIdx >= 0) {
                postIndex = gunIdx
                postDisplay = classNames[gunIdx]
                postConf = max(postConf, gunProb)
            }
        }

        return Result(
            gunshotScore = gunshotScore,
            gunshotEvidence = evidence,
            accepted = adopt,
            reason = reason,
            preBoosterCoarse = yamnetCoarse,
            postBoosterCoarse = postCoarse,
            preBoosterDisplay = display,
            postBoosterDisplay = postDisplay,
            preBoosterClassIndex = pre.yamnetClassIndex,
            postBoosterClassIndex = postIndex,
            preBoosterConfidence = conf,
            postBoosterConfidence = postConf,
            hasGunshotCue = hasGunshotCue,
            hasStrongDangerCue = hasStrongDangerCue
        )
    }

    /** Format like Python f"{x:.4f}" for reason-string parity in tests. */
    private fun fmt(x: Float): String = String.format(java.util.Locale.US, "%.4f", x)

    /** Python bool str() in f-strings: True/False. */
    private fun pyBool(v: Boolean): String = if (v) "True" else "False"

    fun isGunshotKeyword(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val s = name.lowercase()
        return "gunshot" in s || "gunfire" in s || "machine gun" in s ||
            "artillery" in s || "fusillade" in s || "cap gun" in s
    }

    fun isSpeechLikeDisplay(display: String?): Boolean {
        if (display.isNullOrEmpty()) return false
        val s = display.lowercase()
        return "speech" in s || "conversation" in s || "narration" in s ||
            "speaking" in s || "babbling" in s || "whisper" in s ||
            "singing" in s || "choir" in s || "laughter" in s ||
            "crying" in s || "sobbing" in s || "shout" in s
    }

    fun isSilenceLikeDisplay(display: String?): Boolean {
        if (display.isNullOrEmpty()) return false
        val s = display.lowercase()
        return "silence" in s || "quiet" in s || "background noise" in s
    }

    fun isGameMixMaskDisplay(display: String?): Boolean {
        if (display.isNullOrEmpty()) return false
        if (isSpeechLikeDisplay(display) || isSilenceLikeDisplay(display)) return false
        if (YamnetThreeClassMapper.isGenericSoundEffectLabel(display)) return true
        val s = display.lowercase()
        return "music" in s || "video game" in s
    }

    fun isStrongDangerKeyword(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        if (isGunshotKeyword(name)) return true
        val s = name.lowercase()
        return "explosion" in s || "fireworks" in s || "firecracker" in s ||
            "siren" in s || "alarm" in s
    }

    fun sumGunshotProbabilityFromTop5(
        topIndices: IntArray,
        topProbs: FloatArray,
        classNames: List<String>,
        k: Int
    ): Float {
        var sum = 0f
        val limit = minOf(5, k)
        for (idx in 0 until limit) {
            val i = topIndices[idx]
            if (i < 0) continue
            if (isGunshotKeyword(classNames[i])) sum += topProbs[idx]
        }
        return sum
    }

    fun hasGunshotCueInTop5(topIndices: IntArray, classNames: List<String>, k: Int): Boolean {
        val limit = minOf(5, k)
        for (idx in 0 until limit) {
            val i = topIndices[idx]
            if (i >= 0 && isGunshotKeyword(classNames[i])) return true
        }
        return false
    }

    fun hasStrongDangerCueInTop5(topIndices: IntArray, classNames: List<String>, k: Int): Boolean {
        val limit = minOf(5, k)
        for (idx in 0 until limit) {
            val i = topIndices[idx]
            if (i >= 0 && isStrongDangerKeyword(classNames[i])) return true
        }
        return false
    }

    fun tryPickBestGunshotDisplay(
        probs: FloatArray,
        classNames: List<String>
    ): Pair<Int, Float> {
        var bestI = -1
        var bestP = 0f
        val n = minOf(probs.size, classNames.size)
        for (i in 0 until n) {
            if (!isGunshotKeyword(classNames[i])) continue
            if (probs[i] > bestP) {
                bestP = probs[i]
                bestI = i
            }
        }
        return bestI to bestP
    }
}

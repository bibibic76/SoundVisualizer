package com.example.soundvisualizer.ai

/**
 * Preserves non-gunshot YAMNet danger cues independently of any auxiliary model.
 *
 * The former Gunshot Booster path mixed two concerns: optional model-score adoption
 * and the safety policy that keeps a clearly present Alarm/Siren/Explosion cue as
 * Danger. The model-score path is intentionally gone; this object retains only the
 * latter policy so removing the Booster does not silently change Alarm/Siren UI
 * semantics.
 */
object YamnetSafetyCueDecision {

    data class Result(
        val preCoarse: String,
        val preDisplay: String,
        val preClassIndex: Int,
        val preConfidence: Float,
        val postCoarse: String,
        val postDisplay: String,
        val postClassIndex: Int,
        val postConfidence: Float,
        val hasGunshotCue: Boolean,
        val hasStrongDangerCue: Boolean,
        val dangerCuePromoted: Boolean
    )

    fun decide(
        classNames: List<String>,
        pre: YamnetCoarseClassifier.Result
    ): Result {
        val topIndices = IntArray(5) { -1 }
        val topProbabilities = FloatArray(5) { -1f }
        for (i in pre.top5.indices) {
            if (i >= 5) break
            topIndices[i] = pre.top5[i].index
            topProbabilities[i] = pre.top5[i].probability
        }

        val hasGunshotCue = hasGunshotCueInTop5(topIndices, classNames, 5)
        val hasStrongDangerCue = hasStrongDangerCueInTop5(
            topIndices,
            classNames,
            5,
            topProbabilities
        )
        val blockPromotion =
            pre.coarse == "speech" ||
                isSpeechLikeDisplay(pre.displayName) ||
                isSilenceLikeDisplay(pre.displayName) ||
                pre.confidence < 0.12f
        val (cueIndex, _) = tryPickBestStrongDangerDisplay(topIndices, topProbabilities, classNames)
        val promote = !blockPromotion && hasStrongDangerCue && !hasGunshotCue && cueIndex >= 0

        return Result(
            preCoarse = pre.coarse,
            preDisplay = pre.displayName,
            preClassIndex = pre.yamnetClassIndex,
            preConfidence = pre.confidence,
            postCoarse = if (promote) "danger" else pre.coarse,
            postDisplay = if (promote) classNames[cueIndex] else pre.displayName,
            postClassIndex = if (promote) cueIndex else pre.yamnetClassIndex,
            postConfidence = pre.confidence,
            hasGunshotCue = hasGunshotCue,
            hasStrongDangerCue = hasStrongDangerCue,
            dangerCuePromoted = promote
        )
    }

    fun isGunshotKeyword(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val s = name.lowercase()
        return "gunshot" in s || "gunfire" in s || "machine gun" in s ||
            "artillery" in s || "fusillade" in s || "cap gun" in s
    }

    fun isStrongDangerKeyword(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        if (isGunshotKeyword(name)) return true
        val s = name.lowercase()
        if ("alarm clock" in s) return false
        return "explosion" in s || "fireworks" in s || "firecracker" in s ||
            "siren" in s || "alarm" in s
    }

    private fun isSpeechLikeDisplay(display: String?): Boolean {
        if (display.isNullOrEmpty()) return false
        val s = display.lowercase()
        return "speech" in s || "conversation" in s || "narration" in s ||
            "speaking" in s || "babbling" in s || "whisper" in s ||
            "singing" in s || "choir" in s || "laughter" in s ||
            "crying" in s || "sobbing" in s || "shout" in s
    }

    private fun isSilenceLikeDisplay(display: String?): Boolean {
        if (display.isNullOrEmpty()) return false
        val s = display.lowercase()
        return "silence" in s || "quiet" in s || "background noise" in s
    }

    private fun hasGunshotCueInTop5(topIndices: IntArray, classNames: List<String>, k: Int): Boolean {
        for (position in 0 until minOf(5, k)) {
            val index = topIndices[position]
            if (index >= 0 && isGunshotKeyword(classNames[index])) return true
        }
        return false
    }

    private fun hasStrongDangerCueInTop5(
        topIndices: IntArray,
        classNames: List<String>,
        k: Int,
        topProbabilities: FloatArray
    ): Boolean {
        for (position in 0 until minOf(5, k)) {
            val index = topIndices[position]
            if (index >= 0 && topProbabilities[position] >= STRONG_DANGER_CUE_MIN_PROBABILITY &&
                isStrongDangerKeyword(classNames[index])
            ) return true
        }
        return false
    }

    private fun tryPickBestStrongDangerDisplay(
        topIndices: IntArray,
        topProbabilities: FloatArray,
        classNames: List<String>
    ): Pair<Int, Float> {
        var bestIndex = -1
        var bestProbability = 0f
        for (position in 0 until minOf(5, topIndices.size)) {
            val index = topIndices[position]
            if (index < 0 || isGunshotKeyword(classNames[index])) continue
            if (isStrongDangerKeyword(classNames[index]) && topProbabilities[position] > bestProbability) {
                bestIndex = index
                bestProbability = topProbabilities[position]
            }
        }
        return bestIndex to bestProbability
    }

    private const val STRONG_DANGER_CUE_MIN_PROBABILITY = 0.05f
}

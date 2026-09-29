package com.example.soundvisualizer.ai

import kotlin.math.max

/**
 * Gunshot-booster adopt/reject rules (no threshold/hysteresis).
 * Inference score is supplied externally ([GunshotBoosterInference]).
 */
object GunshotBoosterDecision {

    data class Result(
        val boosterAvailable: Boolean,
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
        val hasStrongDangerCue: Boolean,
        /** A non-gunshot danger cue promoted the coarse class without booster adoption. */
        val dangerCuePromoted: Boolean
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
        val hasStrongDangerCue = hasStrongDangerCueInTop5(topIdx, classNames, 5, topProbs)

        val yamnetCoarse = pre.coarse
        val display = pre.displayName
        val conf = pre.confidence

        val blockBooster =
            isSpeechByKeywordVote(pre) ||
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
            // A score-only adoption is unsafe: the current booster can emit ~0.505 for
            // unrelated audio. Strong non-gunshot cues still promote danger below.
            adopt = gunshotScore >= 0.40f && evidence >= 0.04f
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
        var dangerCuePromoted = false

        if (adopt) {
            postCoarse = "danger"
            postConf = max(postConf, max(gunshotScore, evidence))
            val (gunIdx, gunProb) = tryPickBestGunshotDisplay(probabilities, classNames)
            if (gunIdx >= 0) {
                postIndex = gunIdx
                postDisplay = classNames[gunIdx]
                postConf = max(postConf, gunProb)
            }
        } else if (!blockBooster && hasStrongDangerCue && !hasGunshotCue) {
            val (cueIdx, _) = tryPickBestStrongDangerDisplay(topIdx, topProbs, classNames)
            if (cueIdx >= 0) {
                postCoarse = "danger"
                postIndex = cueIdx
                postDisplay = classNames[cueIdx]
                // Preserve YAMNet confidence; the booster score is not evidence here.
                dangerCuePromoted = true
            }
        }

        return Result(
            boosterAvailable = true,
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
            hasStrongDangerCue = hasStrongDangerCue,
            dangerCuePromoted = dangerCuePromoted
        )
    }

    /**
     * Preserve the YAMNet result when the optional booster could not be loaded.
     * NaN is an explicit unavailable marker, not a score fed through booster thresholds.
     */
    fun unavailable(
        probabilities: FloatArray,
        classNames: List<String>,
        pre: YamnetCoarseClassifier.Result
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
        val hasStrongDangerCue = hasStrongDangerCueInTop5(topIdx, classNames, 5, topProbs)
        val blockPromotion =
            isSpeechByKeywordVote(pre) ||
                isSpeechLikeDisplay(pre.displayName) ||
                isSilenceLikeDisplay(pre.displayName) ||
                pre.confidence < 0.12f
        val (cueIdx, _) = tryPickBestStrongDangerDisplay(topIdx, topProbs, classNames)
        val promote = !blockPromotion && hasStrongDangerCue && !hasGunshotCue && cueIdx >= 0

        return Result(
            boosterAvailable = false,
            gunshotScore = Float.NaN,
            gunshotEvidence = evidence,
            accepted = false,
            reason = "booster_unavailable",
            preBoosterCoarse = pre.coarse,
            postBoosterCoarse = if (promote) "danger" else pre.coarse,
            preBoosterDisplay = pre.displayName,
            postBoosterDisplay = if (promote) classNames[cueIdx] else pre.displayName,
            preBoosterClassIndex = pre.yamnetClassIndex,
            postBoosterClassIndex = if (promote) cueIdx else pre.yamnetClassIndex,
            preBoosterConfidence = pre.confidence,
            postBoosterConfidence = pre.confidence,
            hasGunshotCue = hasGunshotCue,
            hasStrongDangerCue = hasStrongDangerCue,
            dangerCuePromoted = promote
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
        if ("alarm clock" in s) return false
        return "explosion" in s || "fireworks" in s || "firecracker" in s ||
            "siren" in s || "alarm" in s
    }

    /**
     * 총소리·강한 위협음 키워드에 걸려도, 지금 종류가 위협음인 소리만 단서로 센다.
     * 사용자가 분류 탭에서 위협음에서 뺀 소리로 Booster 나 강한 단서가 다시 위협음을 올리면 그 선택이 먹지 않는다.
     * 아무것도 바꾸지 않았으면 키워드에 걸리는 소리는 모두 기본 종류가 위협음이라 판정이 전과 같다.
     */
    /**
     * Booster 를 막는 "말소리 프레임" 판단. 사용자가 고른 종류가 아니라 키워드 규칙으로 다시 투표한다.
     * 이 막음은 "말소리 위에서는 Booster 점수를 믿을 수 없다" 는 소리의 성질이라, 사용자가 음악·TV 를 대화음으로
     * 옮겼다고 그 밑의 총소리·사이렌까지 못 올리면 안 된다. 사용자 선택이 없으면 [YamnetCoarseClassifier] 의
     * 투표와 같은 소리·같은 합산 순서·같은 동점 규칙이라 결과가 전과 같다.
     */
    private fun isSpeechByKeywordVote(pre: YamnetCoarseClassifier.Result): Boolean {
        var danger = 0f
        var speech = 0f
        var ambient = 0f
        for (hit in pre.top5.take(YamnetCoarseClassifier.VOTE_K)) {
            when (YamnetThreeClassMapper.defaultCoarse(hit.name)) {
                "danger" -> danger += hit.probability
                "speech" -> speech += hit.probability
                else -> ambient += hit.probability
            }
        }
        if (danger >= speech && danger >= ambient) return false
        return speech >= ambient
    }

    private fun countsAsGunshotCue(name: String?): Boolean =
        isGunshotKeyword(name) && YamnetThreeClassMapper.mapDisplayNameToCoarse(name) == "danger"

    private fun countsAsStrongDangerCue(name: String?): Boolean =
        isStrongDangerKeyword(name) && YamnetThreeClassMapper.mapDisplayNameToCoarse(name) == "danger"

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
            if (countsAsGunshotCue(classNames[i])) sum += topProbs[idx]
        }
        return sum
    }

    fun hasGunshotCueInTop5(topIndices: IntArray, classNames: List<String>, k: Int): Boolean {
        val limit = minOf(5, k)
        for (idx in 0 until limit) {
            val i = topIndices[idx]
            if (i >= 0 && countsAsGunshotCue(classNames[i])) return true
        }
        return false
    }

    fun hasStrongDangerCueInTop5(
        topIndices: IntArray,
        classNames: List<String>,
        k: Int,
        topProbs: FloatArray? = null
    ): Boolean {
        val limit = minOf(5, k)
        for (idx in 0 until limit) {
            val i = topIndices[idx]
            val probability = topProbs?.getOrNull(idx) ?: 1f
            if (i >= 0 && countsAsStrongDangerCue(classNames[i]) && probability >= STRONG_DANGER_CUE_MIN_PROBABILITY) return true
        }
        return false
    }

    private const val STRONG_DANGER_CUE_MIN_PROBABILITY = 0.05f

    fun tryPickBestGunshotDisplay(
        probs: FloatArray,
        classNames: List<String>
    ): Pair<Int, Float> {
        var bestI = -1
        var bestP = 0f
        val n = minOf(probs.size, classNames.size)
        for (i in 0 until n) {
            if (!countsAsGunshotCue(classNames[i])) continue
            if (probs[i] > bestP) {
                bestP = probs[i]
                bestI = i
            }
        }
        return bestI to bestP
    }

    private fun tryPickBestStrongDangerDisplay(
        topIndices: IntArray,
        topProbs: FloatArray,
        classNames: List<String>
    ): Pair<Int, Float> {
        var bestI = -1
        var bestP = 0f
        for (i in 0 until minOf(5, topIndices.size)) {
            val index = topIndices[i]
            if (index < 0 || isGunshotKeyword(classNames[index])) continue
            if (countsAsStrongDangerCue(classNames[index]) && topProbs[i] > bestP) {
                bestI = index
                bestP = topProbs[i]
            }
        }
        return bestI to bestP
    }
}

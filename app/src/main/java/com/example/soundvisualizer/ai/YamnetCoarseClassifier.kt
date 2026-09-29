package com.example.soundvisualizer.ai

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.max

/**
 * YAMNet Softmax → top5 / VoteCoarse(k=3) /
 * PreferDangerWhenTopIsMaskedByGameMix 경로 (Booster·threshold·hysteresis 제외).
 */
class YamnetCoarseClassifier(
    private val classNames: List<String>
) {
    init {
        require(classNames.size == NUM_CLASSES) {
            "Expected $NUM_CLASSES class names, got ${classNames.size}"
        }
    }

    data class TopClassHit(
        val index: Int,
        val name: String,
        val probability: Float
    )

    data class Result(
        val coarse: String,
        val displayName: String,
        val confidence: Float,
        val yamnetClassIndex: Int,
        val top5: List<TopClassHit>,
        val ambientScore: Float,
        val speechScore: Float,
        val dangerScore: Float,
        val gameMixPreferenceApplied: Boolean
    )

    companion object {
        const val NUM_CLASSES = 521
        const val TOP_K = 5
        const val VOTE_K = 3

        /** CSV loader: index, mid, display_name (quoted fields OK). */
        fun loadClassNames(csv: InputStream): List<String> {
            val names = MutableList(NUM_CLASSES) { i -> "class_$i" }
            BufferedReader(InputStreamReader(csv, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = parseCsvLine(line!!)
                    if (parts.isEmpty()) continue
                    val index = parts[0].trim().trim('"').toIntOrNull() ?: continue
                    if (index < 0 || index >= NUM_CLASSES) continue
                    var name = when {
                        parts.size >= 3 -> parts[2].trim()
                        parts.size >= 2 -> parts[1].trim()
                        else -> continue
                    }
                    if (name.length >= 2 && name.first() == '"' && name.last() == '"') {
                        name = name.substring(1, name.length - 1).replace("\"\"", "\"")
                    }
                    if (name.isNotEmpty()) names[index] = name
                }
            }
            return names
        }

        /** Minimal RFC4180-ish split for yamnet_class_map.csv. */
        internal fun parseCsvLine(line: String): List<String> {
            val out = ArrayList<String>(3)
            val sb = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' -> {
                        if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                            sb.append('"')
                            i++
                        } else {
                            inQuotes = !inQuotes
                        }
                    }
                    c == ',' && !inQuotes -> {
                        out.add(sb.toString())
                        sb.setLength(0)
                    }
                    else -> sb.append(c)
                }
                i++
            }
            out.add(sb.toString())
            return out
        }
    }

    fun classify(probabilities: FloatArray): Result {
        require(probabilities.size == NUM_CLASSES) {
            "Expected $NUM_CLASSES probabilities, got ${probabilities.size}"
        }

        val topIdx = IntArray(TOP_K) { -1 }
        val topProbs = FloatArray(TOP_K) { -1f }
        computeTop5(probabilities, topIdx, topProbs)

        var maxIndex = topIdx[0]
        var conf = if (maxIndex >= 0) topProbs[0] else 0f
        var display = if (maxIndex >= 0) classNames[maxIndex] else ""

        val beforeIndex = maxIndex
        val beforeConf = conf
        val beforeDisplay = display
        preferDangerWhenTopIsMaskedByGameMix(probabilities, maxIndex, conf, display).also {
            maxIndex = it.first
            conf = it.second
            display = it.third
        }
        val gameMixApplied =
            maxIndex != beforeIndex || conf != beforeConf || display != beforeDisplay

        val scores = coarseVoteScores(topIdx, topProbs, VOTE_K)
        val coarse = voteCoarseFromScores(scores)

        val top5 = ArrayList<TopClassHit>(TOP_K)
        for (i in 0 until TOP_K) {
            val idx = topIdx[i]
            if (idx < 0) continue
            top5.add(TopClassHit(idx, classNames[idx], topProbs[i]))
        }

        return Result(
            coarse = coarse,
            displayName = display,
            confidence = conf,
            yamnetClassIndex = maxIndex,
            top5 = top5,
            ambientScore = scores.ambient,
            speechScore = scores.speech,
            dangerScore = scores.danger,
            gameMixPreferenceApplied = gameMixApplied
        )
    }

    /** ComputeTop5: strict > (ties keep earlier / lower index). */
    fun computeTop5(
        probs: FloatArray,
        outIndices: IntArray,
        outProbs: FloatArray
    ) {
        require(outIndices.size >= TOP_K && outProbs.size >= TOP_K)
        for (i in 0 until TOP_K) {
            outIndices[i] = -1
            outProbs[i] = -1f
        }
        val n = minOf(probs.size, classNames.size)
        for (i in 0 until n) {
            val p = probs[i]
            for (j in 0 until TOP_K) {
                if (p > outProbs[j]) {
                    for (k in (TOP_K - 1) downTo (j + 1)) {
                        outProbs[k] = outProbs[k - 1]
                        outIndices[k] = outIndices[k - 1]
                    }
                    outProbs[j] = p
                    outIndices[j] = i
                    break
                }
            }
        }
    }

    data class VoteScores(val ambient: Float, val speech: Float, val danger: Float)

    fun coarseVoteScores(topIndices: IntArray, topProbs: FloatArray, k: Int): VoteScores {
        var danger = 0f
        var speech = 0f
        var ambient = 0f
        val limit = minOf(TOP_K, k)
        for (idx in 0 until limit) {
            val i = topIndices[idx]
            if (i < 0) continue
            val c = YamnetThreeClassMapper.mapDisplayNameToCoarse(classNames[i])
            val p = topProbs[idx]
            when (c) {
                "danger" -> danger += p
                "speech" -> speech += p
                else -> ambient += p
            }
        }
        return VoteScores(ambient = ambient, speech = speech, danger = danger)
    }

    fun voteCoarseFromTop5(topIndices: IntArray, topProbs: FloatArray, k: Int): String {
        return voteCoarseFromScores(coarseVoteScores(topIndices, topProbs, k))
    }

    fun voteCoarseFromScores(scores: VoteScores): String {
        // danger > speech > ambient (ties: danger wins over speech/ambient; speech over ambient)
        if (scores.danger >= scores.speech && scores.danger >= scores.ambient) return "danger"
        if (scores.speech >= scores.ambient) return "speech"
        return "ambient"
    }

    fun isGameMixMaskDisplay(display: String?): Boolean {
        if (display.isNullOrEmpty()) return false
        if (isSpeechLikeDisplay(display) || isSilenceLikeDisplay(display)) return false
        if (YamnetThreeClassMapper.isGenericSoundEffectLabel(display)) return true
        val s = display.lowercase()
        return "music" in s || "video game" in s
    }

    private fun isSpeechLikeDisplay(display: String): Boolean {
        val s = display.lowercase()
        return "speech" in s || "conversation" in s || "narration" in s ||
            "speaking" in s || "babbling" in s || "whisper" in s ||
            "singing" in s || "choir" in s || "laughter" in s ||
            "crying" in s || "sobbing" in s || "shout" in s
    }

    private fun isSilenceLikeDisplay(display: String): Boolean {
        val s = display.lowercase()
        return "silence" in s || "quiet" in s || "background noise" in s
    }

    /**
     * @return Triple(maxIndex, conf, display) after possible remapping.
     */
    fun preferDangerWhenTopIsMaskedByGameMix(
        probs: FloatArray,
        maxIndex: Int,
        conf: Float,
        display: String
    ): Triple<Int, Float, String> {
        if (maxIndex < 0 || maxIndex >= classNames.size || probs.size != classNames.size) {
            return Triple(maxIndex, conf, display)
        }
        if (!isGameMixMaskDisplay(display)) {
            return Triple(maxIndex, conf, display)
        }

        var bestDangerIdx = -1
        var bestDangerProb = 0f
        val n = minOf(probs.size, classNames.size)
        for (i in 0 until n) {
            if (YamnetThreeClassMapper.mapDisplayNameToCoarse(classNames[i]) != "danger") continue
            if (probs[i] > bestDangerProb) {
                bestDangerProb = probs[i]
                bestDangerIdx = i
            }
        }
        if (bestDangerIdx < 0) return Triple(maxIndex, conf, display)

        val topProb = probs[maxIndex]
        val bar = max(0.06f, topProb * 0.22f)
        if (bestDangerProb < bar) return Triple(maxIndex, conf, display)

        return Triple(bestDangerIdx, bestDangerProb, classNames[bestDangerIdx])
    }
}

import com.example.soundvisualizer.ai.*
import java.io.File

// Diagnostic only. `baseline` deliberately reconstructs the pre-#351 policy so
// the committed comparison remains reproducible after the approved policy ships.
// Default mappings only: #283 B does not change the baseline vote here.
private val variants = listOf("baseline", "no_firearm_veto", "no_speech_vote", "no_speech_name",
    "no_silence_name", "no_confidence_gate", "no_speech_guards", "no_block_promotion",
    "no_firearm_veto_cue_floor", "firearm_promotion_05")

private fun speech(s: String): Boolean = listOf("speech", "conversation", "narration", "speaking",
    "babbling", "whisper", "singing", "choir", "laughter", "crying", "sobbing", "shout").any { it in s.lowercase() }
private fun silence(s: String): Boolean = listOf("silence", "quiet", "background noise").any { it in s.lowercase() }

private fun candidate(pre: YamnetCoarseClassifier.Result, original: YamnetSafetyCueDecision.Result, variant: String): YamnetSafetyCueDecision.Result {
    val removeAll = variant == "no_block_promotion"
    val bothSpeech = variant == "no_speech_guards"
    val block = (!removeAll && !bothSpeech && variant != "no_speech_vote" && pre.coarse == "speech") ||
        (!removeAll && !bothSpeech && variant != "no_speech_name" && speech(pre.displayName)) ||
        (!removeAll && variant != "no_silence_name" && silence(pre.displayName)) ||
        (!removeAll && variant != "no_confidence_gate" && pre.confidence < .12f)
    val firearms = variant == "firearm_promotion_05"
    val floor = firearms || variant == "no_firearm_veto_cue_floor"
    val noVeto = floor || variant == "no_firearm_veto"
    val best = pre.top5.filter { YamnetSafetyCueDecision.isStrongDangerKeyword(it.name) &&
        (firearms || !YamnetSafetyCueDecision.isGunshotKeyword(it.name)) && (!floor || it.probability >= .05f)
    }.maxByOrNull { it.probability }
    val promote = !block && original.hasStrongDangerCue && (noVeto || !original.hasGunshotCue) && best != null
    return original.copy(postCoarse=if(promote) "danger" else pre.coarse,
        postDisplay=if(promote) best!!.name else pre.displayName,
        postClassIndex=if(promote) best!!.index else pre.yamnetClassIndex, dangerCuePromoted=promote)
}

private fun verifySyntheticCases(names: List<String>, classifier: YamnetCoarseClassifier) {
    fun frame(vararg values: Pair<String, Float>): YamnetCoarseClassifier.Result {
        val p = FloatArray(521)
        for ((name, value) in values) {
            val index = names.indexOf(name)
            check(index >= 0) { "Missing label: $name" }
            p[index] = value
        }
        return classifier.classify(p)
    }
    val weak = frame("Television" to .5f, "Cap gun" to .2f, "Music" to .08f, "Wind" to .05f, "Siren" to .01f)
    val weakBase = YamnetSafetyCueDecision.decide(names, weak)
    check(!weakBase.dangerCuePromoted)
    check(candidate(weak, weakBase, "no_firearm_veto").dangerCuePromoted)
    check(!candidate(weak, weakBase, "no_firearm_veto_cue_floor").dangerCuePromoted)
    val voice = frame("Speech" to .45f, "Siren" to .4f, "Music" to .08f, "Wind" to .04f, "Walk, footsteps" to .03f)
    val voiceBase = YamnetSafetyCueDecision.decide(names, voice)
    check(!candidate(voice, voiceBase, "no_speech_vote").dangerCuePromoted)
    check(!candidate(voice, voiceBase, "no_speech_name").dangerCuePromoted)
    check(candidate(voice, voiceBase, "no_speech_guards").dangerCuePromoted)
    val gun = frame("Television" to .6f, "Gunshot, gunfire" to .1f, "Music" to .08f, "Wind" to .04f, "Walk, footsteps" to .03f)
    val gunBase = YamnetSafetyCueDecision.decide(names, gun)
    check(!gunBase.dangerCuePromoted)
    check(!candidate(gun, gunBase, "no_firearm_veto_cue_floor").dangerCuePromoted)
    check(candidate(gun, gunBase, "firearm_promotion_05").dangerCuePromoted)
    System.err.println("Synthetic guard-isolation checks passed")
}

fun main(args: Array<String>) {
    val names = File(args[0]).inputStream().use { YamnetCoarseClassifier.loadClassNames(it) }
    val classifier = YamnetCoarseClassifier(names)
    verifySyntheticCases(names, classifier)
    val processors = variants.associateWith { AiPostProcessor() }
    var file = -1
    var frame = 0
    val out = System.out.bufferedWriter()
    out.appendLine("file\ttime\tvariant\tui\tpost\tpromoted\tdisplay\tconfidence\tpre\tpython_ui\ttop5")
    File(args[1]).forEachLine { line ->
        val parts = line.split('\t')
        val index = parts[0].toInt()
        if (file != index) { processors.values.forEach { it.reset() }; file = index }
        val pre = classifier.classify(parts[3].split(',').map { it.toFloat() }.toFloatArray())
        val production = YamnetSafetyCueDecision.decide(names, pre)
        check(candidate(pre, production, "no_firearm_veto_cue_floor") == production) {
            "Diagnostic production mismatch at $index frame $frame"
        }
        val top5 = pre.top5.joinToString(";") { "${it.name}=${it.probability}" }
        for (variant in variants) {
            val decision = candidate(pre, production, variant)
            val critical = YamnetMappingPolicy.DEFAULT.hasCriticalDangerCue(decision.postDisplay, pre.top5)
            val result = processors.getValue(variant).process(AiPostProcessor.FrameInput(
                coarse=decision.postCoarse, display=decision.postDisplay, confidence=decision.postConfidence,
                dangerCuePromoted=decision.dangerCuePromoted, hasStrongDangerCue=decision.hasStrongDangerCue,
                hasCriticalDangerCue=critical, criticalDangerEvent=critical))
            out.appendLine("$index\t${parts[1]}\t$variant\t${result.uiCoarse}\t${decision.postCoarse}\t${decision.dangerCuePromoted}\t${decision.postDisplay}\t${decision.postConfidence}\t${pre.coarse}\t${parts[2]}\t$top5")
        }
        frame++
    }
    out.flush()
    System.err.println("Validated $frame production-candidate frames against Kotlin; ${variants.size} variants")
}

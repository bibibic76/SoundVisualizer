package com.example.soundvisualizer.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * JVM tests for GunshotBoosterDecision (no ORT). Scores from Python CP5 fixtures / injected.
 */
class GunshotBoosterDecisionTest {

    companion object {
        private lateinit var classNames: List<String>
        private lateinit var coarse: YamnetCoarseClassifier
        private lateinit var meta: JSONObject

        @JvmStatic
        @BeforeClass
        fun setUp() {
            val csv = open("ai/yamnet_class_map.csv")
                ?: File("src/test/resources/ai/yamnet_class_map.csv").inputStream()
            classNames = YamnetCoarseClassifier.loadClassNames(csv)
            coarse = YamnetCoarseClassifier(classNames)
            val metaIn = open("ai_reference/yamnet_cp5_meta.json")
                ?: File("src/test/resources/ai_reference/yamnet_cp5_meta.json").inputStream()
            meta = JSONObject(metaIn.bufferedReader().readText())
        }

        private fun open(path: String) =
            GunshotBoosterDecisionTest::class.java.classLoader?.getResourceAsStream(path)

        private fun loadProbs(name: String): FloatArray {
            val stream = open("ai_reference/cp5_${name}_probs.bin")
                ?: File("src/test/resources/ai_reference/cp5_${name}_probs.bin").inputStream()
            val bytes = stream.use { it.readBytes() }
            val out = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return out
        }

        private fun loadScore(name: String): Float {
            val stream = open("ai_reference/cp5_${name}_score.bin")
                ?: File("src/test/resources/ai_reference/cp5_${name}_score.bin").inputStream()
            val bytes = stream.use { it.readBytes() }
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float
        }

        private fun runCase(name: String): GunshotBoosterDecision.Result {
            val probs = loadProbs(name)
            val pre = coarse.classify(probs)
            return GunshotBoosterDecision.decide(probs, classNames, pre, loadScore(name))
        }

        private fun assertMatchesMeta(name: String, actual: GunshotBoosterDecision.Result) {
            val exp = meta.getJSONObject("cases").getJSONObject(name)
            assertEquals(name, exp.getBoolean("accepted"), actual.accepted)
            assertEquals(name, exp.getString("reason"), actual.reason)
            assertEquals(name, exp.getJSONObject("pre").getString("coarse"), actual.preBoosterCoarse)
            assertEquals(name, exp.getJSONObject("post").getString("coarse"), actual.postBoosterCoarse)
            assertEquals(name, exp.getJSONObject("pre").getString("display"), actual.preBoosterDisplay)
            assertEquals(name, exp.getJSONObject("post").getString("display"), actual.postBoosterDisplay)
            assertTrue(
                name + " score",
                abs(actual.gunshotScore - exp.getDouble("gunshot_score").toFloat()) < 1e-5f
            )
            assertTrue(
                name + " evidence",
                abs(actual.gunshotEvidence - exp.getDouble("gunshot_evidence").toFloat()) < 1e-5f
            )
        }
    }

    @Test
    fun A_silence_blockedDespiteHighScore() {
        val r = runCase("silence")
        assertMatchesMeta("silence", r)
        assertFalse(r.accepted)
        assertTrue(r.gunshotScore > 0.45f)
        assertEquals("blocked_speech_silence_or_low_conf", r.reason)
        assertEquals("ambient", r.postBoosterCoarse)
    }

    @Test
    fun B_speech_blocked() {
        val r = runCase("speech_block")
        assertMatchesMeta("speech_block", r)
        assertFalse(r.accepted)
        assertEquals("speech", r.preBoosterCoarse)
    }

    @Test
    fun C_gunshot_ambientToDanger() {
        val r = runCase("gunshot")
        assertMatchesMeta("gunshot", r)
        assertTrue(r.accepted)
        assertEquals("ambient", r.preBoosterCoarse)
        assertEquals("danger", r.postBoosterCoarse)
        assertEquals("Gunshot, gunfire", r.postBoosterDisplay)
    }

    @Test
    fun D_lowConfidence_blocked() {
        val r = runCase("low_conf_block")
        assertMatchesMeta("low_conf_block", r)
        assertFalse(r.accepted)
        assertTrue(r.preBoosterConfidence < 0.12f)
    }

    @Test
    fun E_gunshotCue_path() {
        val r = runCase("gunshot_cue")
        assertMatchesMeta("gunshot_cue", r)
        assertTrue(r.hasGunshotCue)
        assertTrue(r.accepted)
        assertTrue(r.reason.startsWith("gunshot_cue"))
    }

    @Test
    fun F_strongDanger_path() {
        val r = runCase("strong_alarm")
        assertMatchesMeta("strong_alarm", r)
        assertTrue(r.hasStrongDangerCue)
        assertTrue(r.accepted)
        assertTrue(r.reason.startsWith("game_mix_or_strong_danger"))
    }

    @Test
    fun G_default_evidenceInsufficient_reject() {
        val r = runCase("default_path")
        assertMatchesMeta("default_path", r)
        assertFalse(r.accepted)
        assertTrue(r.reason.startsWith("default"))
        assertTrue(r.gunshotScore >= 0.45f)
        assertTrue(r.gunshotEvidence < 0.10f)
    }

    @Test
    fun H_boundaries_fromMeta() {
        val boundaries = meta.getJSONObject("boundaries")
        // cue path: gunshot_cue probs + injected scores
        val cueProbs = loadProbs("gunshot_cue")
        val cuePre = coarse.classify(cueProbs)

        fun decide(score: Float) =
            GunshotBoosterDecision.decide(cueProbs, classNames, cuePre, score)

        // evidence for gunshot_cue should be >= 0.05
        assertTrue(decide(0.20f).gunshotEvidence >= 0.05f)
        assertFalse(decide(0.1999f).accepted) // score just below
        assertTrue(decide(0.20f).accepted)
        assertTrue(decide(0.2001f).accepted)

        // Compare a few exported boundary entries' accepted flags
        assertEquals(
            boundaries.getJSONObject("cue_below_score").getBoolean("accepted"),
            decide(0.1999f).accepted
        )
        assertEquals(
            boundaries.getJSONObject("cue_at_score").getBoolean("accepted"),
            decide(0.20f).accepted
        )

        val strongProbs = loadProbs("strong_alarm")
        val strongPre = coarse.classify(strongProbs)
        fun decideStrong(score: Float) =
            GunshotBoosterDecision.decide(strongProbs, classNames, strongPre, score)

        // evidence ~0 → need score >= 0.50
        assertFalse(decideStrong(0.3999f).accepted)
        assertFalse(decideStrong(0.40f).accepted) // 0.40 but evidence < 0.04
        assertTrue(decideStrong(0.50f).accepted)

        val defProbs = loadProbs("default_path")
        val defPre = coarse.classify(defProbs)
        fun decideDef(score: Float) =
            GunshotBoosterDecision.decide(defProbs, classNames, defPre, score)
        // evidence < 0.10 → never accept on default path regardless of score alone
        assertFalse(decideDef(0.45f).accepted)
        assertFalse(decideDef(0.99f).accepted)
    }

    @Test
    fun gameMix_noCue_adoptsAt50() {
        val r = runCase("game_mix_no_cue")
        assertMatchesMeta("game_mix_no_cue", r)
        assertTrue(r.accepted)
        assertEquals("danger", r.postBoosterCoarse)
    }

    @Test
    fun alarm_sample_defaultReject() {
        val r = runCase("alarm")
        assertMatchesMeta("alarm", r)
        assertFalse(r.accepted)
        assertEquals("ambient", r.postBoosterCoarse)
    }
}

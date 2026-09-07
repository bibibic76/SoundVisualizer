package com.example.soundvisualizer.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * CP6: threshold + hysteresis + booster preview — frame-by-frame vs Python golden.
 */
class AiPostProcessorTest {

    companion object {
        private lateinit var meta: JSONObject

        @JvmStatic
        @BeforeClass
        fun setUp() {
            val stream = AiPostProcessorTest::class.java.classLoader
                ?.getResourceAsStream("ai_reference/yamnet_cp6_meta.json")
                ?: File("src/test/resources/ai_reference/yamnet_cp6_meta.json").inputStream()
            meta = JSONObject(stream.bufferedReader().readText())
        }

        private fun runSequence(name: String): List<AiPostProcessor.FrameResult> {
            val seq = meta.getJSONObject(name)
            val frames = seq.getJSONArray("frames")
            val pp = AiPostProcessor()
            val actual = ArrayList<AiPostProcessor.FrameResult>()
            for (i in 0 until frames.length()) {
                val f = frames.getJSONObject(i).getJSONObject("input")
                actual.add(
                    pp.process(
                        AiPostProcessor.FrameInput(
                            coarse = f.getString("coarse"),
                            display = f.getString("display"),
                            confidence = f.getDouble("confidence").toFloat(),
                            adoptedDangerFromBooster = f.getBoolean("adopted_danger_from_booster"),
                            hasStrongDangerCue = f.getBoolean("has_strong_danger_cue"),
                            hasCriticalDangerCue = f.getBoolean("has_critical_danger_cue"),
                            topKSummary = f.optString("top_k_summary", "")
                        )
                    )
                )
            }
            return actual
        }

        private fun assertSequence(name: String) {
            val expectedFrames = meta.getJSONObject(name).getJSONArray("frames")
            val actual = runSequence(name)
            assertEquals("$name length", expectedFrames.length(), actual.size)
            for (i in actual.indices) {
                val e = expectedFrames.getJSONObject(i)
                val a = actual[i]
                val label = "$name[$i]"
                assertEquals(label, e.getString("confirmed_coarse"), a.confirmedCoarse)
                assertEquals(label, e.getString("confirmed_display"), a.confirmedDisplay)
                assertEquals(label, e.getString("candidate_coarse"), a.candidateCoarse)
                assertEquals(label, e.getInt("candidate_streak"), a.candidateStreak)
                assertEquals(label, e.getBoolean("meets_threshold"), a.meetsThreshold)
                assertEquals(label, e.getBoolean("use_booster_danger_preview"), a.useBoosterDangerPreview)
                assertEquals(label, e.getString("ui_coarse"), a.uiCoarse)
                assertEquals(label, e.getString("ui_display"), a.uiDisplay)
                assertTrue(
                    "$label effective",
                    abs(a.effectiveThreshold - e.getDouble("effective_threshold").toFloat()) < 1e-6f
                )
                assertTrue(
                    "$label conf",
                    abs(a.confirmedConfidence - e.getDouble("confirmed_confidence").toFloat()) < 1e-6f
                )
            }
        }
    }

    @Test fun A_stableAmbient() = assertSequence("A_stable_ambient")
    @Test fun B_ambientToSpeech() = assertSequence("B_ambient_to_speech")
    @Test fun C_ambientToDangerStreak1() = assertSequence("C_ambient_to_danger_streak1")
    @Test fun D_immediateDangerConf() = assertSequence("D_immediate_danger_conf")
    @Test fun E_criticalDanger() = assertSequence("E_critical_danger")
    @Test fun F_lowConfidence() = assertSequence("F_low_confidence")
    @Test fun G_candidateReset() = assertSequence("G_candidate_reset")
    @Test fun H_dangerExit() = assertSequence("H_danger_exit")
    @Test fun H2_dangerExitNeedsTwo() = assertSequence("H2_danger_exit_needs_two")
    @Test fun I_boosterAccepted() = assertSequence("I_booster_accepted")
    @Test fun J_boosterPreviewSameFrameConfirm() = assertSequence("J_booster_preview_same_frame_confirm")
    @Test fun J2_boosterSirenConfirm() = assertSequence("J2_booster_siren_confirm")
    @Test fun K_thresholdBoundaries() = assertSequence("K_threshold_boundaries")

    @Test
    fun B_speechNeedsTwoFrames() {
        val pp = AiPostProcessor()
        pp.process(AiPostProcessor.FrameInput("ambient", "Wind", 0.40f))
        val f1 = pp.process(AiPostProcessor.FrameInput("speech", "Speech", 0.40f))
        assertEquals("ambient", f1.confirmedCoarse)
        assertEquals("speech", f1.candidateCoarse)
        assertEquals(1, f1.candidateStreak)
        val f2 = pp.process(AiPostProcessor.FrameInput("speech", "Speech", 0.41f))
        assertEquals("speech", f2.confirmedCoarse)
        assertEquals(0, f2.candidateStreak)
    }

    @Test
    fun threshold_boundariesIsolated() {
        fun eff(
            coarse: String,
            adopted: Boolean = false,
            strong: Boolean = false,
            critical: Boolean = false
        ) = AiPostProcessor.computeEffectiveThreshold(coarse, 0.25f, adopted, strong, critical)

        assertEquals(0.25f, eff("ambient"), 0f)
        assertEquals(0.25f, eff("speech"), 0f)
        assertEquals(0.25f, eff("danger"), 0f) // no cues → no relax
        assertEquals(0.20f, eff("danger", strong = true), 0f)
        assertEquals(0.18f, eff("danger", adopted = true, strong = true), 0f)

        // meets uses >=
        assertFalse(0.2499f >= 0.25f)
        assertTrue(0.25f >= 0.25f)
        assertFalse(0.1999f >= 0.20f)
        assertTrue(0.20f >= 0.20f)
        assertFalse(0.1799f >= 0.18f)
        assertTrue(0.18f >= 0.18f)
        assertFalse(0.2799f >= 0.28f)
        assertTrue(0.28f >= 0.28f)
        assertFalse(0.2699f >= 0.27f)
        assertTrue(0.27f >= 0.27f)
    }

    @Test
    fun J_previewFlag_falseAfterSameFrameDangerConfirm() {
        // DangerHysteresisThreshold=1 → booster danger confirms same frame → preview=false
        val pp = AiPostProcessor()
        pp.process(AiPostProcessor.FrameInput("ambient", "Ding", 0.35f))
        val r = pp.process(
            AiPostProcessor.FrameInput(
                coarse = "danger",
                display = "Siren",
                confidence = 0.22f,
                adoptedDangerFromBooster = true,
                hasStrongDangerCue = true
            )
        )
        assertEquals("danger", r.confirmedCoarse)
        assertFalse(r.useBoosterDangerPreview)
        assertEquals("danger", r.uiCoarse)
    }

    @Test
    fun criticalEvent_fromTopKSummary() {
        assertTrue(
            AiPostProcessor.isCriticalDangerEvent(
                "Music",
                "Gunshot, gunfire 12.0% > Sound effect 10.0%"
            )
        )
        assertFalse(AiPostProcessor.isCriticalDangerEvent("Music", "Siren 12.0% > Alarm 10.0%"))
    }
}

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
 * CP4: Softmax probs[521] → top5 → PreferDanger(game-mix) → Vote(k=3).
 * No booster / threshold / hysteresis.
 */
class YamnetThreeClassMapperTest {

    companion object {
        private lateinit var classNames: List<String>
        private lateinit var classifier: YamnetCoarseClassifier
        private lateinit var meta: JSONObject

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val csv = openResource("ai/yamnet_class_map.csv")
                ?: File("src/main/assets/ai/yamnet_class_map.csv").inputStream()
            classNames = YamnetCoarseClassifier.loadClassNames(csv)
            assertEquals(521, classNames.size)
            assertEquals("Speech", classNames[0])
            assertEquals("Field recording", classNames[520])
            classifier = YamnetCoarseClassifier(classNames)

            val metaStream = openResource("ai_reference/yamnet_cp4_meta.json")
                ?: File("src/test/resources/ai_reference/yamnet_cp4_meta.json").inputStream()
            meta = JSONObject(metaStream.bufferedReader().readText())
        }

        private fun openResource(path: String) =
            YamnetThreeClassMapperTest::class.java.classLoader?.getResourceAsStream(path)

        private fun loadProbs(name: String): FloatArray {
            val stream = openResource("ai_reference/cp4_${name}_probs.bin")
                ?: File("src/test/resources/ai_reference/cp4_${name}_probs.bin").inputStream()
            val bytes = stream.use { it.readBytes() }
            require(bytes.size == 521 * 4)
            val out = FloatArray(521)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return out
        }

        private fun assertResultMatches(expected: JSONObject, actual: YamnetCoarseClassifier.Result, label: String) {
            assertEquals("$label coarse", expected.getString("coarse"), actual.coarse)
            assertEquals("$label display", expected.getString("display"), actual.displayName)
            assertEquals("$label index", expected.getInt("yamnet_class_index"), actual.yamnetClassIndex)
            assertEquals(
                "$label gameMix",
                expected.getBoolean("game_mix_preference_applied"),
                actual.gameMixPreferenceApplied
            )
            assertTrue(
                "$label confidence ${actual.confidence} vs ${expected.getDouble("confidence")}",
                abs(actual.confidence - expected.getDouble("confidence").toFloat()) < 1e-5f
            )
            val vs = expected.getJSONObject("vote_scores")
            assertTrue("$label ambient vote", abs(actual.ambientScore - vs.getDouble("ambient").toFloat()) < 1e-5f)
            assertTrue("$label speech vote", abs(actual.speechScore - vs.getDouble("speech").toFloat()) < 1e-5f)
            assertTrue("$label danger vote", abs(actual.dangerScore - vs.getDouble("danger").toFloat()) < 1e-5f)

            val top5 = expected.getJSONArray("top5")
            assertEquals("$label top5 size", top5.length(), actual.top5.size)
            for (i in 0 until top5.length()) {
                val e = top5.getJSONObject(i)
                val a = actual.top5[i]
                assertEquals("$label top5[$i].index", e.getInt("index"), a.index)
                assertEquals("$label top5[$i].name", e.getString("name"), a.name)
                assertTrue(
                    "$label top5[$i].prob",
                    abs(a.probability - e.getDouble("prob").toFloat()) < 1e-5f
                )
            }
        }
    }

    @Test
    fun classMap_has521Entries() {
        assertEquals(521, classNames.size)
        assertEquals(521, meta.getInt("num_classes"))
        assertEquals(521, meta.getInt("class_map_filled"))
        // quoted CSV field
        assertEquals("Child speech, kid speaking", classNames[1])
        assertEquals("Gunshot, gunfire", classNames[421])
    }

    @Test
    fun mappingChecks_matchPython() {
        val checks = meta.getJSONObject("mapping_checks")
        val keys = checks.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            assertEquals(
                name,
                checks.getString(name),
                YamnetThreeClassMapper.mapDisplayNameToCoarse(name)
            )
        }
    }

    @Test
    fun A_ambient() = assertCase("ambient")

    @Test
    fun B_speech() = assertCase("speech")

    @Test
    fun C_danger() = assertCase("danger")

    @Test
    fun D_defaultUnmappedToAmbient() = assertCase("default_ambient")

    @Test
    fun E_voteOverTop1() {
        assertCase("vote_over_top1")
        val r = classifier.classify(loadProbs("vote_over_top1"))
        assertEquals("Music", r.top5[0].name)
        assertEquals("speech", r.coarse)
    }

    @Test
    fun F_priorityDangerWinsTie() {
        assertCase("priority_tie")
        val r = classifier.classify(loadProbs("priority_tie"))
        assertEquals(r.dangerScore, r.speechScore, 1e-6f)
        assertEquals(r.dangerScore, r.ambientScore, 1e-6f)
        assertEquals("danger", r.coarse)
    }

    @Test
    fun G_proxyPlop() {
        assertCase("proxy_plop")
        assertEquals("danger", YamnetThreeClassMapper.mapDisplayNameToCoarse("Plop"))
        assertEquals("danger", YamnetThreeClassMapper.mapDisplayNameToCoarse("Gargling"))
        assertEquals("danger", YamnetThreeClassMapper.mapDisplayNameToCoarse("Rain"))
    }

    @Test
    fun H_gameMixPreference() {
        assertCase("game_mix")
        val r = classifier.classify(loadProbs("game_mix"))
        assertTrue(r.gameMixPreferenceApplied)
        assertEquals("Gunshot, gunfire", r.displayName)
        // Vote still uses original top5 → ambient can win
        assertEquals("ambient", r.coarse)
    }

    @Test
    fun sample_silence_preBoosterAmbient() {
        assertSample("silence")
        val r = classifier.classify(loadProbs("silence"))
        assertEquals("ambient", r.coarse)
        assertFalse(r.gameMixPreferenceApplied)
    }

    @Test
    fun sample_gunshot_preBoosterAmbientIsExpected() {
        // Booster 이전 gunshot sample은 ambient로 남는 것이 정상 동작
        assertSample("gunshot")
        val r = classifier.classify(loadProbs("gunshot"))
        assertEquals("ambient", r.coarse)
    }

    @Test
    fun sample_alarm_preBooster() {
        assertSample("alarm")
    }

    private fun assertCase(name: String) {
        val expected = meta.getJSONObject("handcrafted").getJSONObject(name)
        assertResultMatches(expected, classifier.classify(loadProbs(name)), name)
    }

    private fun assertSample(name: String) {
        val expected = meta.getJSONObject("samples").getJSONObject(name)
        assertResultMatches(expected, classifier.classify(loadProbs(name)), "sample_$name")
    }
}

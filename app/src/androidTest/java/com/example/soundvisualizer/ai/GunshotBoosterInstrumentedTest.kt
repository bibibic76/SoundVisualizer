package com.example.soundvisualizer.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * ORT Android gunshot_booster.onnx vs Python CP5 scores + decision parity.
 */
@RunWith(AndroidJUnit4::class)
class GunshotBoosterInstrumentedTest {

    @Test
    fun booster_matchesPythonCp5() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context

        val classNames = YamnetCoarseClassifier.loadClassNames(
            appContext.assets.open("ai/yamnet_class_map.csv")
        )
        val coarse = YamnetCoarseClassifier(classNames)
        val meta = JSONObject(
            testContext.assets.open("ai_reference/yamnet_cp5_meta.json").bufferedReader().readText()
        )

        GunshotBoosterInference.create(appContext).use { booster ->
            println("Booster session: ${booster.inputOutputSummary()}")

            for (name in listOf("silence", "gunshot", "alarm")) {
                val probs = loadF32(testContext.assets.open("ai_reference/cp5_${name}_probs.bin"))
                val expectedScore = loadF32(testContext.assets.open("ai_reference/cp5_${name}_score.bin"))[0]
                val exp = meta.getJSONObject("cases").getJSONObject(name)

                val score1 = booster.score(probs)
                val score2 = booster.score(probs)
                assertEquals("deterministic $name", score1, score2, 0f)

                val err = abs(score1 - expectedScore)
                println("=== CP5 $name === scoreAndroid=$score1 scorePy=$expectedScore absErr=$err")

                val pre = coarse.classify(probs)
                val decision = GunshotBoosterDecision.decide(probs, classNames, pre, score1)

                assertEquals("$name accepted", exp.getBoolean("accepted"), decision.accepted)
                assertEquals("$name pre coarse", exp.getJSONObject("pre").getString("coarse"), decision.preBoosterCoarse)
                assertEquals("$name post coarse", exp.getJSONObject("post").getString("coarse"), decision.postBoosterCoarse)
                assertEquals("$name post display", exp.getJSONObject("post").getString("display"), decision.postBoosterDisplay)

                // Score tolerance: same model, platform float noise
                assertTrue("$name score maxAbs $err", err < 1e-3f)
            }

            // Aggregate errors over cases present as bins
            val scoresA = ArrayList<Float>()
            val scoresB = ArrayList<Float>()
            for (name in listOf("silence", "gunshot", "alarm")) {
                val probs = loadF32(testContext.assets.open("ai_reference/cp5_${name}_probs.bin"))
                val expectedScore = loadF32(testContext.assets.open("ai_reference/cp5_${name}_score.bin"))[0]
                scoresA.add(booster.score(probs))
                scoresB.add(expectedScore)
            }
            var maxAbs = 0.0
            var sumAbs = 0.0
            var sumSq = 0.0
            for (i in scoresA.indices) {
                val d = abs(scoresA[i].toDouble() - scoresB[i].toDouble())
                maxAbs = max(maxAbs, d)
                sumAbs += d
                sumSq += d * d
            }
            val n = scoresA.size.toDouble()
            println("score maxAbs=$maxAbs MAE=${sumAbs / n} RMSE=${sqrt(sumSq / n)}")
        }
    }

    private fun loadF32(input: java.io.InputStream): FloatArray {
        val bytes = input.use { it.readBytes() }
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }
}

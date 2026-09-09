package com.example.soundvisualizer.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Full realtime input path on device:
 * 44.1k stereo float → AiAudioBuffer → resample → CP2..CP6 vs Python E2E goldens.
 */
@RunWith(AndroidJUnit4::class)
class RealtimeAiPipelineInstrumentedTest {

    @Test
    fun e2e_stereo44100_matchesPythonReference() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val test = InstrumentationRegistry.getInstrumentation().context
        val meta = JSONObject(
            test.assets.open("ai_reference/yamnet_e2e_meta.json").bufferedReader().readText()
        )
        val cases = meta.getJSONObject("cases")

        RealtimeAiPipeline.create(app, captureSampleRate = 44100, channels = 2).use { pipeline ->
            for (name in listOf("silence", "gunshot", "alarm")) {
                pipeline.resetState()
                val stereo = loadF32(test.assets.open("ai_reference/e2e_${name}_stereo44100.bin"))
                val expectedMono16 = loadF32(test.assets.open("ai_reference/e2e_${name}_mono16k.bin"))
                val expectedLogMel = loadF32(test.assets.open("ai_reference/e2e_${name}_logmel.bin"))
                val expectedProbs = loadF32(test.assets.open("ai_reference/e2e_${name}_probs.bin"))
                val exp = cases.getJSONObject(name)

                var offset = 0
                while (offset < stereo.size) {
                    val n = minOf(1024, stereo.size - offset)
                    val chunk = stereo.copyOfRange(offset, offset + n)
                    pipeline.ingestInterleavedForTest(chunk, n)
                    offset += n
                }

                val tick = pipeline.runTickForTest()
                assertNotNull("$name tick", tick)
                val d = tick!!

                val monoErr = maxAbs(d.mono16k, expectedMono16)
                val melErr = maxAbs(d.logMel, expectedLogMel)
                val probErr = maxAbs(d.probabilities, expectedProbs)
                val scoreErr = abs(d.gunshotScore - exp.getDouble("gunshot_score").toFloat())

                println("=== E2E $name ===")
                println("mono16k maxAbs=$monoErr logmel maxAbs=$melErr probs maxAbs=$probErr")
                println(
                    "pre=${d.preBoosterCoarse} boost=${d.boosterAccepted} " +
                        "score=${d.gunshotScore} ui=${d.result.coarse}/${d.result.display}"
                )
                println(
                    "timing ms pre=${d.result.preprocessMs} yam=${d.result.yamnetMs} " +
                        "bst=${d.result.boosterMs} tot=${d.result.totalMs}"
                )

                assertTrue("$name mono16k", monoErr < 1e-4f)
                assertTrue("$name logmel", melErr < 1e-3f)
                assertTrue("$name probs", probErr < 1e-3f)
                assertTrue("$name gunshotScore", scoreErr < 1e-3f)

                assertEquals(
                    "$name pre-booster coarse",
                    exp.getString("pre_booster_coarse"),
                    d.preBoosterCoarse
                )
                assertEquals(
                    "$name booster accepted",
                    exp.getBoolean("booster_accepted"),
                    d.boosterAccepted
                )
                assertEquals("$name ui coarse", exp.getString("ui_coarse"), d.result.coarse)
                assertEquals("$name ui display", exp.getString("ui_display"), d.result.display)
            }
        }
    }

    private fun loadF32(input: java.io.InputStream): FloatArray {
        val bytes = input.use { it.readBytes() }
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    private fun maxAbs(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        for (i in a.indices) m = max(m, abs(a[i] - b[i]))
        return m
    }
}

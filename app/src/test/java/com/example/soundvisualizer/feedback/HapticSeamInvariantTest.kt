package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.feedback.HapticSimulator.Cue
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/**
 * 소리 따라가 모든 흉내 소리 × 세기 × 버퍼 전달 방식 × 옛·새 안드로이드에서 지키는 약속.
 *
 * 가장 중요한 것은 이음매 규칙이다. 새 계획을 보내면 안드로이드가 모터를 껐다 다시 켠다. 그 틈이 "징징 끊기는" 느낌이다.
 * 그래서 울리는 도중에 보내는 계획의 첫 칸은 지금 울리는 세기와 [HapticTuning.LEVEL_STEP_DB] 이상 달라야 한다.
 * 그러면 틈이 어차피 느껴지는 변화(친 곳 앞의 눌림, 뚝 떨어지는 곳, 크게 오르는 곳) 속에 숨는다.
 */
class HapticSeamInvariantTest {

    private data class Run(val name: String, val scene: HapticScene, val sdk: Int, val cues: List<Cue>)

    private val runs: List<Run> by lazy {
        val out = ArrayList<Run>()
        for (scene in HapticScenes.all) {
            for (strength in HapticStrength.values()) {
                for (delivery in Delivery.values()) {
                    for (sdk in intArrayOf(29, 36)) {
                        val cues = HapticSimulator.run(scene, strength, sdk = sdk, delivery = delivery, aiLagMs = 300)
                        out.add(Run("${scene.id}/$strength/$delivery/api$sdk", scene, sdk, cues))
                    }
                }
            }
        }
        out
    }

    private fun playingAt(prev: Cue, t: Long): Int = prev.plan.amplitudeAt(t - prev.atMs)

    @Test
    fun `다시 보낼 때 첫 칸은 울리던 세기에서 4dB 이상 떨어져 있다`() {
        val bad = ArrayList<String>()
        for (r in runs) {
            for (i in 1 until r.cues.size) {
                val c = r.cues[i]
                val p = playingAt(r.cues[i - 1], c.atMs)
                if (p <= 2 * HapticTuning.KEEP_ALIVE) continue
                val a0 = c.plan.amplitudes.first()
                val db = abs(20.0 * log10(a0.toDouble() / p))
                if (db < HapticTuning.LEVEL_STEP_DB) bad.add("${r.name} t=${c.atMs} ${c.plan.reason} 울리던 $p → 첫 칸 $a0 (${"%.1f".format(db)}dB)")
            }
        }
        if (bad.isNotEmpty()) fail("이음매 규칙 위반 ${bad.size}건:\n" + bad.take(20).joinToString("\n"))
    }

    @Test
    fun `계획 안에 0 이 없다`() {
        for (r in runs) for (c in r.cues) {
            assertTrue("${r.name} t=${c.atMs} ${c.plan}", c.plan.amplitudes.all { it > 0 })
        }
    }

    @Test
    fun `모든 계획은 작게 끝난다`() {
        for (r in runs) for (c in r.cues) {
            val max = c.plan.amplitudes.maxOrNull()!!
            val last = c.plan.amplitudes.last()
            assertTrue("${r.name} t=${c.atMs} 끝이 $last (최대 $max)", last <= maxOf(8, (0.15 * max).toInt() + 1) || c.plan.durationMs <= 40)
        }
    }

    @Test
    fun `계획은 칸수와 3초 반 한도를 넘지 않는다`() {
        for (r in runs) {
            val limits = HapticTuning.limitsFor(r.sdk)
            for (c in r.cues) {
                assertTrue("${r.name} t=${c.atMs} ${c.plan.timings.size}칸", c.plan.timings.size <= limits.maxSteps)
                assertTrue("${r.name} t=${c.atMs} ${c.plan.durationMs}ms", c.plan.durationMs <= 3500)
                if (r.sdk <= 30) {
                    assertTrue("${r.name} 옛 기기 칸이 짧다", c.plan.timings.all { it >= 15 })
                }
            }
        }
    }

    @Test
    fun `이어지는 소리에 구멍이 없다`() {
        val continuous = setOf("wail", "yelp", "hilo", "rain", "steady", "music")
        for (r in runs) {
            if (r.scene.id !in continuous || r.cues.isEmpty()) continue
            val felt = HapticSimulator.felt(r.cues, r.scene.durationMs)
            val from = r.cues.first().atMs.toInt()
            for (t in from until r.scene.durationMs.toInt()) {
                if (felt[t] == 0) fail("${r.name} ${t}ms 에 진동이 끊겼다")
            }
        }
    }

    @Test
    fun `보내는 간격을 지킨다`() {
        for (r in runs) {
            for (i in 1 until r.cues.size) {
                val gap = r.cues[i].atMs - r.cues[i - 1].atMs
                val reason = r.cues[i].plan.reason
                val min = when (reason) {
                    PlanReason.UP -> HapticTuning.UP_MIN_GAP_MS
                    PlanReason.DOWN, PlanReason.RESUME -> HapticTuning.DOWN_MIN_GAP_MS
                    else -> HapticTuning.FOLLOW_TICK_MS
                }
                assertTrue("${r.name} t=${r.cues[i].atMs} $reason 간격 $gap < $min", gap >= min)
            }
            val accents = r.cues.filter { it.plan.reason == PlanReason.ONSET || it.plan.reason == PlanReason.REMINDER }
            for (i in 1 until accents.size) {
                val gap = accents[i].atMs - accents[i - 1].atMs
                assertTrue("${r.name} 친 간격 $gap", gap >= HapticTuning.ACCENT_REFRACTORY_MS)
            }
        }
    }

    @Test
    fun `소리 따라에는 꺼짐 알림 같은 긴 센 진동이 없다`() {
        for (r in runs) {
            val felt = HapticSimulator.felt(r.cues, r.scene.durationMs + 3000)
            var run = 0
            for (v in felt) {
                run = if (v >= 200) run + 1 else 0
                assertTrue("${r.name} 200 이상이 ${run}ms 이어졌다", run < 200)
            }
        }
    }
}

package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.feedback.FollowAnalyzer.PitchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class FollowAnalyzerTest {

    private fun rms(db: Float) = 10f.pow(db / 20f)

    /** 장면을 빠른 틱(20ms)으로 흘리며 [probe] 로 들여다본다. */
    private fun feed(scene: HapticScene, untilMs: Long, probe: (Long, FollowAnalyzer) -> Unit = { _, _ -> }): FollowAnalyzer {
        var now = 0L
        val input = SyntheticHapticInput(scene, { now })
        val a = FollowAnalyzer()
        val f = FloatArray(HapticInput.FRAME_SIZE)
        input.takeFrame(f)
        while (now <= untilMs) {
            input.takeFrame(f)
            a.onFrame(now, f[HapticInput.RMS], f[HapticInput.TONE], f[HapticInput.BUFFERS].toInt(), fastTick = now > 0)
            probe(now, a)
            now += 20
        }
        return a
    }

    @Test
    fun `천장은 1초 붙잡고 초당 6dB 씩 내려가며 -45 아래로는 안 간다`() {
        val a = FollowAnalyzer()
        a.onFrame(0, rms(-10f), 0f, 2, true)
        a.onFrame(900, rms(-40f), 0f, 2, true)
        assertEquals(-10f, a.ceil, 0.01f)
        a.onFrame(2000, rms(-40f), 0f, 2, true)
        assertTrue("천장이 내려가지 않았다: ${a.ceil}", a.ceil < -10f && a.ceil > -18f)
        for (t in 2100L..20_000L step 100) a.onFrame(t, 0f, 0f, 2, true)
        assertEquals(HapticTuning.CEIL_FLOOR_DBFS, a.ceil, 0.01f)
    }

    @Test
    fun `버퍼가 잠깐 없는 틱은 조용함이 아니다`() {
        val a = FollowAnalyzer()
        a.onFrame(0, rms(-10f), 0f, 2, true)
        a.onFrame(20, 0f, 0f, 0, true)
        assertFalse(a.dataThisTick)
        assertFalse("버퍼가 몰려 오는 사이를 조용함으로 봤다", a.silentNow)
    }

    @Test
    fun `200ms 동안 버퍼가 없으면 조용함이다`() {
        val a = FollowAnalyzer()
        a.onFrame(0, rms(-10f), 0f, 2, true)
        a.onFrame(220, 0f, 0f, 0, true)
        assertTrue(a.dataThisTick)
        assertTrue(a.silentNow)
    }

    @Test
    fun `데이터 한 틱이면 조용함을 안다`() {
        val a = FollowAnalyzer()
        a.onFrame(0, rms(-10f), 0f, 2, true)
        a.onFrame(20, rms(-80f), 0f, 2, true)
        assertTrue(a.silentNow)
    }

    @Test
    fun `크게 오르면 오른 폭을 잰다`() {
        val a = FollowAnalyzer()
        for (t in 0L..400L step 20) a.onFrame(t, rms(-30f), 0f, 2, true)
        a.onFrame(420, rms(-12f), 0f, 2, true)
        assertEquals(18f, a.riseDb, 0.5f)
    }

    @Test
    fun `울부짖음은 SLOW, 하이로는 STEP, 옐프는 FAST`() {
        assertEquals(PitchMode.SLOW, dominantMode(HapticScenes.wail))
        assertEquals(PitchMode.STEP, dominantMode(HapticScenes.hilo))
        assertEquals(PitchMode.FAST, dominantMode(HapticScenes.yelp))
    }

    private fun dominantMode(scene: HapticScene): PitchMode {
        val counts = HashMap<PitchMode, Int>()
        feed(scene, scene.durationMs - 100) { t, a -> if (t >= 2500) counts.merge(a.pitchMode, 1, Int::plus) }
        return counts.maxByOrNull { it.value }!!.key
    }

    @Test
    fun `옐프 두근거림 주기는 333ms 쯤이다`() {
        val a = feed(HapticScenes.yelp, 5000)
        assertEquals(PitchMode.FAST, a.pitchMode)
        assertTrue("주기 ${a.throbPeriodMs}", a.throbPeriodMs in 283L..383L)
    }

    @Test
    fun `비와 음악과 말은 음높이 모드가 되지 않는다`() {
        for (scene in listOf(HapticScenes.rain, HapticScenes.music, HapticScenes.speech, HapticScenes.steady)) {
            var pitched = 0
            var total = 0
            feed(scene, minOf(scene.durationMs, 8000) - 100) { t, a ->
                if (t >= 1500) {
                    total++
                    if (a.pitchMode != PitchMode.NONE) pitched++
                }
            }
            assertTrue("${scene.id} 가 ${pitched}/${total} 틱 음높이 모드였다", pitched <= total / 20)
        }
    }

    @Test
    fun `느린 틱의 음높이는 창에 넣지 않는다`() {
        var now = 0L
        val input = SyntheticHapticInput(HapticScenes.yelp, { now })
        val a = FollowAnalyzer()
        val f = FloatArray(HapticInput.FRAME_SIZE)
        while (now <= 5000) {
            input.takeFrame(f)
            a.onFrame(now, f[HapticInput.RMS], f[HapticInput.TONE], f[HapticInput.BUFFERS].toInt(), fastTick = false)
            assertEquals(PitchMode.NONE, a.pitchMode)
            now += 100
        }
    }

    @Test
    fun `한결같음은 500ms 뒤에 인정되고 조용해지면 다시 센다`() {
        val a = FollowAnalyzer()
        for (t in 0L..700L step 20) a.onFrame(t, rms(-12f), 0f, 2, true)
        assertTrue(a.steadyFor(700) >= HapticTuning.STEADY_MIN_MS)
        a.onFrame(720, 0f, 0f, 2, true)
        assertEquals(0L, a.steadyFor(720))
    }
}

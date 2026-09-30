package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 틱 하나의 흐름: 분석 → 판단 → 한 번 모양 또는 소리 따라. */
class HapticLoopTest {

    private val loud = 0.3f

    private fun cfg(pattern: HapticPattern, enabled: Boolean = true, strength: HapticStrength = HapticStrength.Medium):
        (String) -> HapticPolicy.ClassConfig = { HapticPolicy.ClassConfig(true, HapticSettings(enabled, strength, pattern)) }

    private fun HapticLoop.tick(t: Long, rms: Float, c: (String) -> HapticPolicy.ClassConfig, label: String? = AiClassification.DANGER) =
        onTick(t, label, if (rms > 0f) loud else 0f, rms, 0.02f, 2, c)

    @Test
    fun `AI 를 못 쓰는 실행이면 라벨 없는 큰 소리에 한 번 모양을 보낸다`() {
        val c = cfg(HapticPattern.DoubleTap)
        assertNull("AI 를 쓸 수 있으면 라벨을 기다린다", HapticLoop(36, true).onTick(0, null, 0.5f, 0.2f, 0.02f, 2, c))

        val plan = HapticLoop(36, true).onTick(0, null, 0.5f, 0.2f, 0.02f, 2, c, unlabeledAlerts = true)
        assertNotNull(plan)
        assertEquals(PlanReason.ONE_SHOT, plan!!.reason)
    }

    @Test
    fun `한 번 패턴만 켜져 있으면 빠른 틱을 쓰지 않는다`() {
        val loop = HapticLoop(36, true)
        val c = cfg(HapticPattern.DoubleTap)
        var t = 0L
        val shots = ArrayList<HapticPlan>()
        while (t <= 3000) {
            loop.tick(t, 0.2f, c)?.let { shots.add(it) }
            assertFalse("$t 에 빠른 틱을 원했다", loop.wantsFastTick(t))
            t += 100
        }
        assertEquals(1, shots.size)
        assertEquals(PlanReason.ONE_SHOT, shots.single().reason)
    }

    @Test
    fun `소리가 없으면 느린 틱, 세션과 계획이 울리는 동안만 빠른 틱`() {
        val loop = HapticLoop(36, true)
        val c = cfg(HapticPattern.Repeat)
        loop.tick(0, 0f, c)
        assertFalse(loop.wantsFastTick(0))
        loop.tick(100, 0.2f, c)
        assertTrue(loop.wantsFastTick(100))
        // 소리가 끊기고 2초 넘게 지나면 세션도, 끝 페이드도 끝나 다시 느린 틱.
        var t = 120L
        while (t <= 5000) {
            loop.tick(t, 0f, c)
            t += if (loop.wantsFastTick(t)) 20 else 100
        }
        assertNull(loop.follow)
        assertFalse(loop.wantsFastTick(t))
    }

    @Test
    fun `소리가 끊기면 모터는 바로 쉬고 세션만 2초 남는다`() {
        val loop = HapticLoop(36, true)
        val c = cfg(HapticPattern.Repeat)
        var t = 0L
        while (t <= 1000) { loop.tick(t, 0.2f, c); t += 20 }
        var released: HapticPlan? = null
        while (t <= 1100) { loop.tick(t, 0f, c)?.let { if (released == null) released = it }; t += 20 }
        assertNotNull("소리가 끊겼는데 모터를 쉬게 하지 않았다", released)
        assertEquals(PlanReason.RELEASE, released!!.reason)
        assertNotNull("세션이 너무 일찍 끝났다", loop.follow)
    }

    @Test
    fun `세션이 끝나면 부드럽게 끝낸다`() {
        val loop = HapticLoop(36, true)
        var t = 0L
        while (t <= 1000) { loop.tick(t, 0.2f, cfg(HapticPattern.Repeat)); t += 20 }
        val end = loop.tick(t, 0.2f, cfg(HapticPattern.Repeat, enabled = false))
        assertNotNull(end)
        assertEquals(PlanReason.END, end!!.reason)
        assertTrue(end.durationMs in 100L..200L)
        assertNull(loop.follow)
    }

    @Test
    fun `한 번 패턴이 끼어들면 그 모양을 바로 내고 끝날 때까지 소리 따라를 보내지 않는다`() {
        val loop = HapticLoop(36, true)
        val speechFollow: (String) -> HapticPolicy.ClassConfig = { label ->
            val pattern = if (label == AiClassification.SPEECH) HapticPattern.Repeat else HapticPattern.Hold
            HapticPolicy.ClassConfig(true, HapticSettings(true, HapticStrength.Medium, pattern))
        }
        var t = 0L
        while (t <= 1000) { loop.tick(t, 0.2f, speechFollow, AiClassification.SPEECH); t += 20 }
        val shot = loop.tick(t, 0.2f, speechFollow, AiClassification.DANGER)
        assertEquals(PlanReason.ONE_SHOT, shot!!.reason)
        val shotEnd = t + shot.durationMs
        t += 20
        while (t < shotEnd) {
            assertNull("한 번 모양이 울리는 중에 계획을 보냈다 ($t)", loop.tick(t, 0.2f, speechFollow, AiClassification.DANGER))
            t += 20
        }
    }

    @Test
    fun `위협음 세션은 톡톡으로 시작한다`() {
        val loop = HapticLoop(36, true)
        val start = loop.tick(0, 0.2f, cfg(HapticPattern.Repeat))!!
        assertEquals(PlanReason.START, start.reason)
        val peak = HapticTuning.followLevels(HapticStrength.Medium).peak
        assertEquals(listOf(peak, HapticTuning.KEEP_ALIVE, peak), start.amplitudes.take(3))
    }

    @Test
    fun `미리보기 때문에 건너뛴 계획은 곧 다시 낸다`() {
        val loop = HapticLoop(36, true)
        val c = cfg(HapticPattern.Repeat)
        var t = 0L
        while (t <= 600) { loop.tick(t, 0.2f, c); t += 20 }
        loop.onIssueSkipped(t)
        var resent: HapticPlan? = null
        repeat(5) {
            t += 20
            if (resent == null) resent = loop.tick(t, 0.2f, c)
        }
        assertNotNull("건너뛴 뒤 다시 내지 않았다", resent)
    }

    @Test
    fun `미리보기 때문에 건너뛴 옐프 떨림 계획도 곧 다시 낸다`() {
        // 떨림 계획이 남긴 표시가 남으면 음높이 갱신이 "아직 울리는 중" 으로 보고, 그 계획이 끝날 때까지 다시 내지 않았다(#232).
        var now = 0L
        val scene = HapticScenes.yelp
        val input = SyntheticHapticInput(scene, { now })
        val loop = HapticLoop(36, true)
        val frame = FloatArray(HapticInput.FRAME_SIZE)
        val c = cfg(HapticPattern.Repeat)
        input.takeFrame(frame)
        var skippedAt = -1L
        var resentAt = -1L
        while (now <= scene.durationMs && resentAt < 0) {
            input.takeFrame(frame)
            val plan = loop.onTick(
                now, scene.label, frame[HapticInput.PEAK], frame[HapticInput.RMS], frame[HapticInput.TONE],
                frame[HapticInput.BUFFERS].toInt(), c
            )
            if (plan != null) {
                val throb = plan.reason == PlanReason.PITCH && plan.timings.size >= 20
                if (skippedAt < 0 && throb) {
                    loop.onIssueSkipped(now)
                    skippedAt = now
                } else if (skippedAt >= 0) {
                    resentAt = now
                }
            }
            now += HapticTuning.tickPeriodMs(loop.wantsFastTick(now))
        }
        assertTrue("옐프에서 떨림 계획이 나오지 않았다", skippedAt >= 0)
        assertTrue("건너뛴 뒤 ${now - skippedAt}ms 동안 다시 내지 않았다", resentAt >= 0 && resentAt - skippedAt <= 700)
    }

    @Test
    fun `세기를 바꾸면 다음 계획부터 새 세기다`() {
        val loop = HapticLoop(36, true)
        var t = 0L
        while (t <= 400) { loop.tick(t, 0.2f, cfg(HapticPattern.Repeat)); t += 20 }
        assertEquals(HapticStrength.Medium, loop.follow!!.strength)
        loop.tick(t, 0.2f, cfg(HapticPattern.Repeat, strength = HapticStrength.Weak))
        assertEquals(HapticStrength.Weak, loop.follow!!.strength)
        // 세기가 4dB 넘게 바뀌면 곧 새 세기의 계획이 나간다(중 129 → 약 79 는 -4.3dB).
        var next: HapticPlan? = null
        repeat(20) {
            t += 20
            val p = loop.tick(t, 0.2f, cfg(HapticPattern.Repeat, strength = HapticStrength.Weak))
            if (next == null && p != null) next = p
        }
        assertNotNull(next)
        assertTrue(next!!.amplitudes.maxOrNull()!! <= HapticTuning.followLevels(HapticStrength.Weak).sustain)
    }
}

package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.feedback.HapticSimulator.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 흉내 소리마다 소리 따라가 어떻게 느껴지는지. AI 가 처음 판정하기까지 300ms 늦는 것으로 돌린다.
 * 숫자가 아니라 느낌의 약속(끊기지 않는다, 친다, 쉰다, 멈추지 않는다)을 확인한다.
 */
class FollowEngineTest {

    private val medium = HapticTuning.followLevels(HapticStrength.Medium)

    private fun run(scene: HapticScene, strength: HapticStrength = HapticStrength.Medium, sdk: Int = 36, amp: Boolean = true) =
        HapticSimulator.run(scene, strength, sdk = sdk, amplitudeControl = amp, aiLagMs = 300)

    private fun sendsPerSecond(cues: List<Cue>, fromMs: Long, toMs: Long): Double =
        cues.count { it.atMs in fromMs until toMs } * 1000.0 / (toMs - fromMs)

    @Test
    fun `세 사이렌은 서로 다른 몸짓이다`() {
        val wail = run(HapticScenes.wail)
        val hilo = run(HapticScenes.hilo)
        val yelp = run(HapticScenes.yelp)

        // 웨일: 음높이 단계로 부풀었다 가라앉는다. 초당 1.5번 넘게 보내지 않는다.
        val wailLevels = wail.filter { it.plan.reason == PlanReason.PITCH }.map { it.plan.bodyLevelAt(it.plan.headMs) }.toSet()
        assertTrue("웨일이 단계로 움직이지 않는다: $wailLevels", wailLevels.size >= 2)
        assertTrue("웨일을 너무 자주 보낸다", sendsPerSecond(wail, 2000, 8000) <= 1.5)

        // 하이로: 음이 바뀔 때마다 톡 친다.
        val hiloTicks = hilo.count { it.plan.reason == PlanReason.PITCH && it.plan.headMs > 0 }
        assertTrue("하이로가 바뀔 때 톡이 없다: $hiloTicks", hiloTicks >= 4)

        // 옐프: 한 계획 안에서 두근거린다.
        val throbs = yelp.filter { it.plan.reason == PlanReason.PITCH && it.plan.timings.size >= 20 }
        assertTrue("옐프가 두근거리지 않는다", throbs.isNotEmpty())
        val firstThrob = throbs.first().atMs
        assertTrue("옐프를 너무 자주 보낸다", sendsPerSecond(yelp, firstThrob, 6000) <= 1.2)
        val amps = throbs.first().plan.amplitudes
        assertTrue("두근거림이 얕다", 20 * kotlin.math.log10(amps.maxOrNull()!!.toDouble() / amps.filter { it > HapticTuning.KEEP_ALIVE }.minOrNull()!!) >= 6.0)
    }

    @Test
    fun `5Hz 삐소리는 틈마다 40ms 이상 쉰다`() {
        val cues = run(HapticScenes.beeps)
        val felt = HapticSimulator.felt(cues, 5000)
        // 삐 사이 틈: 0.1초 켜짐, 0.1초 꺼짐. 세션이 열린 뒤의 틈만 본다.
        var gapStart = 500
        var checked = 0
        while (gapStart + 100 < 4900) {
            val rest = (gapStart until gapStart + 100).count { felt[it] <= 2 * HapticTuning.KEEP_ALIVE }
            assertTrue("${gapStart}ms 틈에 ${rest}ms 만 쉰다", rest >= 40)
            checked++
            gapStart += 200
        }
        assertTrue(checked > 10)
    }

    @Test
    fun `총소리는 곧바로 치고 사그라든다`() {
        val cues = run(HapticScenes.gunshots)
        val felt = HapticSimulator.felt(cues, 4100)
        for (shot in longArrayOf(900, 1100, 1300, 2600)) {
            val hit = (shot.toInt() until shot.toInt() + 45).maxOf { felt[it] }
            assertTrue("${shot}ms 총소리를 치지 않았다 ($hit)", hit >= (0.8 * medium.peak).toInt())
        }
        // 마지막 총소리 뒤 0.5초 안에 바닥 아래로 잦아든다.
        val tail = (3100 until 3300).maxOf { felt[it] }
        assertTrue("총소리 꼬리가 남는다 ($tail)", tail < medium.floor)
    }

    @Test
    fun `노크 사이 틈에서 쉰다`() {
        val cues = run(HapticScenes.knock)
        val felt = HapticSimulator.felt(cues, 2550)
        for (k in 2..4) {
            val start = (300 + k * 250 - 120)
            val rest = (start until start + 100).count { felt[it] <= 2 * HapticTuning.KEEP_ALIVE }
            assertTrue("노크 ${k} 앞 틈에 ${rest}ms 만 쉰다", rest >= 60)
        }
    }

    @Test
    fun `음악 박을 거의 모두 바로 치고 알림 톡은 없다`() {
        val cues = run(HapticScenes.music)
        val felt = HapticSimulator.felt(cues, 8000)
        var hits = 0
        var beats = 0
        var t = 1000
        while (t < 7900) {
            beats++
            val m = (t until t + 45).maxOf { felt[it] }
            if (m >= (0.7 * medium.peak).toInt()) hits++
            t += 500
        }
        assertTrue("박 $beats 중 $hits 만 쳤다", hits >= 0.9 * beats)
        assertTrue("음악에 알림 톡이 나왔다", cues.none { it.plan.reason == PlanReason.REMINDER })
        assertTrue("킥 사이에 올리기·내리기를 보냈다", cues.none { it.plan.reason == PlanReason.UP || it.plan.reason == PlanReason.DOWN })
    }

    @Test
    fun `비는 멈추지 않고 몇 초마다 가볍게 친다`() {
        val cues = run(HapticScenes.rain)
        val felt = HapticSimulator.felt(cues, 14_000)
        for (t in cues.first().atMs.toInt() until 14_000) assertTrue("${t}ms 에 끊겼다", felt[t] > 0)
        val reminders = cues.filter { it.plan.reason == PlanReason.REMINDER }.map { it.atMs }
        assertTrue("알림 톡이 없다", reminders.size >= 3)
        for (i in 1 until reminders.size) {
            val gap = reminders[i] - reminders[i - 1]
            assertTrue("알림 톡 간격 $gap", gap in HapticTuning.REMINDER_MS - 100..HapticTuning.REMINDER_LONG_MS + 200)
        }
    }

    @Test
    fun `한결같은 위협음은 2초 동안 온 세기이고 차츰 바닥까지 잔잔해지지만 멈추지 않는다`() {
        val cues = run(HapticScenes.steady)
        val felt = HapticSimulator.felt(cues, 15_000)
        val start = cues.first().atMs.toInt()
        // 첫 2초(친 곳 지나서): 이어지는 가장 센 세기
        assertTrue("처음 2초가 약하다", (start + 300 until start + 1900).all { felt[it] >= (0.9 * medium.sustain).toInt() })
        // 6초 뒤: 0.55배 이하(알림 톡 자리는 뺀다)
        val later = (start + 6000 until start + 6200).filter { felt[it] < medium.peak / 2 }
        assertTrue("6초 뒤에도 세다", later.isNotEmpty() && later.all { felt[it] <= (0.6 * medium.sustain).toInt() })
        // 온 세기 2초 + 한결같음 10초 + 바닥으로 2초 = 14초 뒤: 바닥
        val end = (start + 14_100 until start + 14_600).filter { felt[it] < medium.peak / 2 }
        assertTrue("14초 뒤 바닥이 아니다", end.isNotEmpty() && end.all { felt[it] <= medium.floor + 3 })
        for (t in start until 15_000) assertTrue("${t}ms 에 멈췄다", felt[t] > 0)
    }

    @Test
    fun `작은 소리와 큰 소리 모두 따라 오른다`() {
        val cues = run(HapticScenes.quietLoud)
        val felt = HapticSimulator.felt(cues, 6000)
        val quietMax = (1000 until 2900).maxOf { felt[it] }
        val loudMax = (3500 until 5900).maxOf { felt[it] }
        assertTrue("작은 소리를 따라가지 않는다 ($quietMax)", quietMax >= (0.9 * medium.sustain).toInt())
        assertTrue("큰 소리를 따라가지 않는다 ($loudMax)", loudMax >= (0.9 * medium.sustain).toInt())
        // 커지는 순간을 친다.
        val jump = (3000 until 3060).maxOf { felt[it] }
        assertTrue("커지는 순간을 치지 않았다 ($jump)", jump >= (0.8 * medium.peak).toInt())
    }

    @Test
    fun `위협음 세션은 톡톡으로 시작한다`() {
        val start = run(HapticScenes.steady).first()
        assertEquals(PlanReason.START, start.plan.reason)
        val strikes = start.plan.amplitudes.take(3)
        assertEquals(listOf(medium.peak, HapticTuning.KEEP_ALIVE, medium.peak), strikes)
    }

    @Test
    fun `버퍼가 몰려와도 치는 수가 거의 같다`() {
        for (scene in listOf(HapticScenes.beeps, HapticScenes.knock, HapticScenes.gunshots, HapticScenes.music)) {
            val smooth = HapticSimulator.run(scene, aiLagMs = 300).count { it.plan.reason == PlanReason.ONSET }
            val bursty = HapticSimulator.run(scene, aiLagMs = 300, delivery = Delivery.BURSTY).count { it.plan.reason == PlanReason.ONSET }
            assertTrue("${scene.id}: 고르게 $smooth, 몰려서 $bursty", kotlin.math.abs(smooth - bursty) <= 1 + smooth / 10)
        }
    }

    @Test
    fun `같은 입력이면 같은 계획이 나온다`() {
        val a = run(HapticScenes.hilo).map { it.atMs to it.plan.amplitudes.toList() }
        val b = run(HapticScenes.hilo).map { it.atMs to it.plan.amplitudes.toList() }
        assertEquals(a, b)
    }

    @Test
    fun `세기 조절이 없으면 짧은 펄스만 낸다`() {
        for (scene in HapticScenes.all) {
            for (c in run(scene, amp = false)) {
                assertTrue("${scene.id} ${c.plan}", c.plan.binary)
                val on = c.plan.toOnOffTimings().filterIndexed { i, _ -> i % 2 == 1 }
                assertTrue("${scene.id} 긴 울림 $on", on.all { it <= HapticTuning.BINARY_PULSE_MS })
            }
        }
    }

    @Test
    fun `세기가 셀수록 세게 울린다`() {
        fun maxFelt(s: HapticStrength) = HapticSimulator.felt(run(HapticScenes.steady, s), 4000).maxOrNull()!!
        assertTrue(maxFelt(HapticStrength.Weak) < maxFelt(HapticStrength.Medium))
        assertTrue(maxFelt(HapticStrength.Medium) < maxFelt(HapticStrength.Strong))
    }
}

package com.example.soundvisualizer.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Play = HapticDriver.Command.Play

private fun vibe(mode: HapticMode, level: Int = 60) = HapticPolicy.Vibe(mode, level)

/** [atMs] 에 보낸 명령. */
private data class Sent(val atMs: Long, val command: HapticDriver.Command)

/**
 * 실제 진동 알림처럼 driver 가 고른 시각([HapticDriver.nextWakeMs])에 깨어나 [wantAt] 을 주고, 보낸 명령을 모은다.
 * [lateBy] 는 그 시각에 늦게 깨어난 만큼이다(스레드가 밀릴 때).
 */
private fun HapticDriver.run(
    fromMs: Long,
    untilMs: Long,
    lateBy: (Long) -> Long = { 0L },
    wantAt: (Long) -> HapticPolicy.Vibe?
): List<Sent> {
    val out = ArrayList<Sent>()
    var t = fromMs
    while (t <= untilMs) {
        val c = onTick(t, wantAt(t))
        if (c != HapticDriver.Command.None) out.add(Sent(t, c))
        val next = nextWakeMs(t)
        assertTrue("t=$t 다음 깨어날 시각 $next 이 지금보다 앞이 아니다", next > t)
        assertTrue("t=$t 판단 주기보다 늦게 깨어난다", next <= t + HapticTuning.IDLE_TICK_MS)
        t = next + lateBy(next)
    }
    return out
}

private fun List<Sent>.plays() = filter { it.command is Play }

class HapticDriverTest {

    // ---------------------------------------------------------------
    // 박자
    // ---------------------------------------------------------------

    @Test
    fun `중간은 0_5초마다 0_2초씩 같은 세기로 울린다`() {
        val sent = HapticDriver(amplitudeControl = true).run(0, 3000) { vibe(HapticMode.Medium, 70) }

        assertEquals(listOf(0L, 500L, 1000L, 1500L, 2000L, 2500L, 3000L), sent.map { it.atMs })
        for (s in sent) {
            val plan = (s.command as Play).plan
            assertEquals(1, plan.timings.size)
            assertEquals(200L, plan.durationMs)
            assertEquals(HapticTuning.amplitude(70), plan.amplitudes.single())
        }
    }

    @Test
    fun `느림은 중간의 절반 빠르기이고 빠름은 두 배 빠르기다`() {
        fun beats(mode: HapticMode) = HapticDriver(true).run(0, 4000) { vibe(mode) }
        val medium = beats(HapticMode.Medium)
        val slow = beats(HapticMode.Slow)
        val fast = beats(HapticMode.Fast)

        fun gaps(s: List<Sent>) = s.zipWithNext { a, b -> b.atMs - a.atMs }.toSet()
        fun onMs(s: List<Sent>) = s.map { (it.command as Play).plan.durationMs }.toSet()
        assertEquals(setOf(HapticTuning.MEDIUM_PERIOD_MS), gaps(medium))
        assertEquals(setOf(HapticTuning.MEDIUM_PERIOD_MS * 2), gaps(slow))
        assertEquals(setOf(HapticTuning.MEDIUM_PERIOD_MS / 2), gaps(fast))
        // 울림 길이도 박자에 맞춰 늘고 준다.
        assertEquals(setOf(onMs(medium).single() * 2), onMs(slow))
        assertEquals(setOf(onMs(medium).single() / 2), onMs(fast))
    }

    @Test
    fun `박자 방식은 쉼이 울림보다 길다`() {
        // 쉼이 짧으면 박자가 흐려져 연속과 구별되지 않는다.
        for (mode in listOf(HapticMode.Slow, HapticMode.Medium, HapticMode.Fast)) {
            val on = HapticTuning.onMs(mode)
            assertTrue("$mode", on > 0 && on < HapticTuning.periodMs(mode) - on)
        }
    }

    @Test
    fun `박자 방식은 울리는 도중에 다시 보내지 않는다`() {
        // 새로 보내면 모터가 멈췄다 다시 돈다. 울림 하나가 끝나기 전에 다음 울림을 보내면 안 된다.
        for (mode in listOf(HapticMode.Slow, HapticMode.Medium, HapticMode.Fast)) {
            val plays = HapticDriver(true).run(0, 5000) { vibe(mode) }.plays()
            plays.zipWithNext { a, b ->
                assertTrue("$mode ${a.atMs}→${b.atMs}", b.atMs >= a.atMs + (a.command as Play).plan.durationMs)
            }
        }
    }

    @Test
    fun `방식을 바꾸면 지금부터 새로 센다`() {
        val sent = HapticDriver(true).run(0, 1200) { t -> vibe(if (t < 300) HapticMode.Medium else HapticMode.Fast) }
        assertEquals(listOf(0L, 300L, 550L, 800L, 1050L), sent.map { it.atMs })
    }

    @Test
    fun `세기만 바꾸면 박자는 그대로 두고 다음 울림부터 새 세기로 울린다`() {
        val sent = HapticDriver(true).run(0, 1500) { t -> vibe(HapticMode.Medium, if (t < 700) 60 else 90) }
        assertEquals(listOf(0L, 500L, 1000L, 1500L), sent.map { it.atMs })
        assertEquals(HapticTuning.amplitude(60), (sent[1].command as Play).plan.amplitudes.single())
        assertEquals(HapticTuning.amplitude(90), (sent[2].command as Play).plan.amplitudes.single())
    }

    @Test
    fun `조금 늦게 깨어나면 그 박자를 늦게라도 울린다`() {
        // 울림 시간(0.2초) 안이면 늦게라도 울리고, 다음 박자는 원래 자리에 둔다.
        val sent = HapticDriver(true).run(0, 1500, lateBy = { if (it == 500L) 60L else 0L }) { vibe(HapticMode.Medium) }
        assertEquals(listOf(0L, 560L, 1000L, 1500L), sent.map { it.atMs })
    }

    @Test
    fun `울림 시간을 통째로 놓친 박자는 건너뛴다`() {
        // 몰아서 울리면 두 울림이 붙어 한 번의 긴 울림이 된다.
        val sent = HapticDriver(true).run(0, 2000, lateBy = { if (it == 500L) 250L else 0L }) { vibe(HapticMode.Medium) }
        assertEquals(listOf(0L, 1000L, 1500L, 2000L), sent.map { it.atMs })
    }

    // ---------------------------------------------------------------
    // 연속
    // ---------------------------------------------------------------

    @Test
    fun `연속은 끝나기 전에 이어 보내 끊기지 않는다`() {
        val until = 20_000L
        val plays = HapticDriver(true).run(0, until) { vibe(HapticMode.Continuous, 50) }.plays()

        var covered = 0L
        for (s in plays) {
            val plan = (s.command as Play).plan
            assertTrue("${s.atMs} 에 틈이 있다 (앞 울림이 $covered 에 끝남)", s.atMs <= covered)
            assertEquals(HapticTuning.amplitude(50), plan.amplitudes.single())
            covered = s.atMs + plan.durationMs
        }
        assertTrue(covered > until)
        // 다시 보낼 때마다 모터가 다시 돌므로 드물게 보낸다.
        val expected = until / (HapticTuning.CONTINUOUS_CHUNK_MS - HapticTuning.CONTINUOUS_REFILL_MS) + 1
        assertTrue("${plays.size} 번 보냈다", plays.size <= expected)
    }

    @Test
    fun `연속의 세기를 바꾸면 바로 새 세기로 보낸다`() {
        val sent = HapticDriver(true).run(0, 1000) { t -> vibe(HapticMode.Continuous, if (t < 500) 40 else 100) }
        assertEquals(listOf(0L, 500L), sent.map { it.atMs })
        assertEquals(255, (sent[1].command as Play).plan.amplitudes.single())
    }

    @Test
    fun `연속에서 박자 방식으로 바꾸면 첫 울림으로 바로 덮는다`() {
        val sent = HapticDriver(true).run(0, 1000) { t -> vibe(if (t < 400) HapticMode.Continuous else HapticMode.Medium) }
        assertEquals(listOf(0L, 400L, 900L), sent.map { it.atMs })
        assertTrue(sent.all { it.command is Play })
        assertEquals(HapticTuning.onMs(HapticMode.Medium), (sent[1].command as Play).plan.durationMs)
    }

    // ---------------------------------------------------------------
    // 멈출 때
    // ---------------------------------------------------------------

    @Test
    fun `소리가 끝나면 연속은 바로 끊는다`() {
        val sent = HapticDriver(true).run(0, 2000) { t -> if (t < 1000) vibe(HapticMode.Continuous) else null }
        assertEquals(listOf(0L, 1000L), sent.map { it.atMs })
        assertEquals(HapticDriver.Command.Cancel, sent[1].command)
    }

    @Test
    fun `울리지 않아야 하면 울리던 박자 울림도 바로 끊는다`() {
        // 끝까지 두면 느림은 0.4초까지 앞 종류가 더 울린다(#244).
        val sent = HapticDriver(true).run(0, 3000) { t -> if (t < 1100) vibe(HapticMode.Medium) else null }
        assertEquals(listOf(0L, 500L, 1000L, 1100L), sent.map { it.atMs })
        assertEquals(HapticDriver.Command.Cancel, sent.last().command)
    }

    @Test
    fun `이미 끝난 박자 울림은 끊지 않는다`() {
        // 1000ms 에 보낸 0.2초 울림은 1200ms 에 끝났다. 쉬는 중에 소리가 끝나면 보낼 것이 없다.
        val sent = HapticDriver(true).run(0, 3000) { t -> if (t < 1300) vibe(HapticMode.Medium) else null }
        assertEquals(listOf(0L, 500L, 1000L), sent.map { it.atMs })
        assertTrue(sent.all { it.command is Play })
    }

    @Test
    fun `이미 끝난 연속은 끊지 않는다`() {
        val driver = HapticDriver(true)
        driver.onTick(0, vibe(HapticMode.Continuous))
        assertEquals(HapticDriver.Command.None, driver.onTick(HapticTuning.CONTINUOUS_CHUNK_MS, null))
    }

    @Test
    fun `꺼짐 진동은 울리지 않는 것과 같다`() {
        val driver = HapticDriver(true)
        assertTrue(driver.onTick(0, vibe(HapticMode.Continuous)) is Play)
        assertEquals(HapticDriver.Command.Cancel, driver.onTick(100, vibe(HapticMode.Off)))
        assertEquals(HapticDriver.Command.None, driver.onTick(200, vibe(HapticMode.Off)))
        assertEquals(300L + HapticTuning.IDLE_TICK_MS, driver.nextWakeMs(300))
    }

    @Test
    fun `아무것도 울리지 않으면 판단 주기마다 깨어나 아무것도 보내지 않는다`() {
        val driver = HapticDriver(true)
        assertTrue(driver.run(0, 2000) { null }.isEmpty())
        assertEquals(1234L + HapticTuning.IDLE_TICK_MS, driver.nextWakeMs(1234L))
    }

    // ---------------------------------------------------------------
    // 미리보기, 기기
    // ---------------------------------------------------------------

    @Test
    fun `미리보기에 끊긴 연속은 다음 틱에 다시 보낸다`() {
        val driver = HapticDriver(true)
        assertTrue(driver.onTick(0, vibe(HapticMode.Continuous)) is Play)
        assertEquals(HapticDriver.Command.None, driver.onTick(100, vibe(HapticMode.Continuous)))
        driver.onPreempted()
        assertTrue("미리보기가 끊은 울림을 아직 울리는 중으로 안다", driver.onTick(200, vibe(HapticMode.Continuous)) is Play)
    }

    @Test
    fun `미리보기에 막힌 박자는 다시 보내지 않고 다음 박자부터 잇는다`() {
        val driver = HapticDriver(true)
        driver.onTick(0, vibe(HapticMode.Medium))
        driver.onPreempted()
        assertEquals(HapticDriver.Command.None, driver.onTick(100, vibe(HapticMode.Medium)))
        assertTrue(driver.onTick(500, vibe(HapticMode.Medium)) is Play)
    }

    @Test
    fun `세기 조절이 없는 기기는 기본 세기로 켰다 끈다`() {
        for (mode in listOf(HapticMode.Medium, HapticMode.Continuous)) {
            val plan = (HapticDriver(amplitudeControl = false).onTick(0, vibe(mode, 10)) as Play).plan
            assertTrue("$mode", plan.binary)
            assertEquals(255, plan.amplitudes.single())
        }
    }
}

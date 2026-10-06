package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 잠금 화면 위협음 알림을 언제 띄우는지(#310). 확인 틱은 500ms 마다 돈다. */
class DangerAlertPolicyTest {

    private val danger = AiClassification.DANGER
    private val loud = 0.2f
    private val quiet = 0f

    /** [from] 부터 [toMs] 까지 500ms 틱을 돌려 알린 시각을 모은다. */
    private fun run(
        policy: DangerAlertPolicy,
        from: Long,
        toMs: Long,
        label: (Long) -> String = { danger },
        peak: (Long) -> Float = { loud },
        enabled: Boolean = true,
        locked: (Long) -> Boolean = { true }
    ): List<Long> {
        val fired = mutableListOf<Long>()
        var t = from
        while (t <= toMs) {
            val now = t
            if (policy.onTick(now, label(now), peak(now), enabled) { locked(now) }) fired += now
            t += 500
        }
        return fired
    }

    @Test
    fun `잠겨 있을 때 위협음이 이어지면 한 번만 알린다`() {
        assertEquals(listOf(0L), run(DangerAlertPolicy(), 0, 20_000))
    }

    @Test
    fun `쓰는 중에는 알리지 않는다`() {
        assertEquals(emptyList<Long>(), run(DangerAlertPolicy(), 0, 10_000, locked = { false }))
    }

    @Test
    fun `위협음이 이어지는 중에 잠그면 그때 알린다`() {
        assertEquals(listOf(4_000L), run(DangerAlertPolicy(), 0, 10_000, locked = { it >= 4_000 }))
    }

    @Test
    fun `소리가 없는데 위협음 라벨만 남아 있으면 알리지 않는다`() {
        assertEquals(emptyList<Long>(), run(DangerAlertPolicy(), 0, 10_000, peak = { quiet }))
    }

    @Test
    fun `위협음이 아니면 알리지 않는다`() {
        assertEquals(emptyList<Long>(), run(DangerAlertPolicy(), 0, 10_000, label = { AiClassification.SPEECH }))
    }

    @Test
    fun `위협음 표시를 꺼 두었거나 AI 가 동작하지 않으면 알리지 않는다`() {
        assertEquals(emptyList<Long>(), run(DangerAlertPolicy(), 0, 10_000, enabled = false))
    }

    @Test
    fun `알린 뒤 30초 안의 새 사건은 알리지 않고 그 뒤에는 알린다`() {
        // 0~2초 위협음, 10~12초 위협음(쿨다운 안), 40~42초 위협음(쿨다운 뒤)
        val on = { t: Long -> t in 0..2_000 || t in 10_000..12_000 || t in 40_000..42_000 }
        val fired = run(DangerAlertPolicy(), 0, 45_000, peak = { if (on(it)) loud else quiet })
        assertEquals(listOf(0L, 40_000L), fired)
    }

    @Test
    fun `짧게 끊겼다 이어지는 위협음은 같은 사건이다`() {
        val policy = DangerAlertPolicy(cooldownMs = 0)
        // 2초 끊김은 사건 간격(3초)보다 짧다.
        val on = { t: Long -> t !in 3_000..4_500 }
        assertEquals(listOf(0L), run(policy, 0, 10_000, peak = { if (on(it)) loud else quiet }))
    }

    @Test
    fun `잠금 여부는 알릴 차례일 때만 묻는다`() {
        val policy = DangerAlertPolicy()
        var asked = 0
        policy.onTick(0, AiClassification.SPEECH, loud, true) { asked++; true }
        policy.onTick(500, danger, quiet, true) { asked++; true }
        assertEquals("위협음이 아니거나 조용하면 묻지 않는다", 0, asked)
        assertTrue(policy.onTick(1_000, danger, loud, true) { asked++; true })
        policy.onTick(1_500, danger, loud, true) { asked++; true }
        assertEquals("알린 사건 동안에는 다시 묻지 않는다", 1, asked)
    }

    @Test
    fun `다시 시작하면 지난 실행의 쿨다운을 잊는다`() {
        val policy = DangerAlertPolicy()
        assertTrue(policy.onTick(0, danger, loud, true) { true })
        policy.reset()
        assertTrue(policy.onTick(1_000, danger, loud, true) { true })
        assertFalse(policy.onTick(1_500, danger, loud, true) { true })
    }
}

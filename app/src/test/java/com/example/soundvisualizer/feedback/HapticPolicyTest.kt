package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실제 알림 루프의 주기와 같게 흘린다. */
private const val TICK = 100L
private const val LOUD = 0.5f
private const val QUIET = 0f

private const val DANGER = AiClassification.DANGER
private const val SPEECH = AiClassification.SPEECH

private fun config(
    shown: Boolean = true,
    enabled: Boolean = true,
    pattern: HapticPattern = HapticPattern.Tap,
    strength: HapticStrength = HapticStrength.Medium
): (String) -> HapticPolicy.ClassConfig = {
    HapticPolicy.ClassConfig(shown, HapticSettings(enabled, strength, pattern))
}

/** [fromMs] 부터 [toMs] 까지(포함) [TICK] 간격으로 흘리고, 울린 시각과 결정을 모은다. */
private fun HapticPolicy.feed(
    fromMs: Long,
    toMs: Long,
    label: String?,
    level: Float,
    cfg: (String) -> HapticPolicy.ClassConfig
): List<Pair<Long, HapticPolicy.Decision>> {
    val fired = ArrayList<Pair<Long, HapticPolicy.Decision>>()
    var t = fromMs
    while (t <= toMs) {
        onTick(t, label, level, cfg)?.let { fired.add(t to it) }
        t += TICK
    }
    return fired
}

class HapticPolicyTest {

    // ---------------------------------------------------------------
    // 라벨이 남아 있는 무음 (이 설계가 막으려는 문제)
    // ---------------------------------------------------------------

    @Test
    fun `무음에서는 라벨이 위협음으로 남아 있어도 울리지 않는다`() {
        val fired = HapticPolicy().feed(0, 3000, DANGER, QUIET, config(pattern = HapticPattern.Repeat))
        assertTrue("무음인데 울렸다: $fired", fired.isEmpty())
    }

    @Test
    fun `조용해졌다가 다시 소리가 나면 라벨이 그대로여도 다시 울린다`() {
        val policy = HapticPolicy()
        val cfg = config()
        val first = policy.feed(0, 500, DANGER, LOUD, cfg)
        policy.feed(600, 3000, DANGER, QUIET, cfg)
        val second = policy.feed(3100, 3500, DANGER, LOUD, cfg)

        assertEquals("첫 총소리", listOf(0L), first.map { it.first })
        assertEquals("조용한 뒤 두 번째 총소리", listOf(3100L), second.map { it.first })
    }

    // ---------------------------------------------------------------
    // 기본 동작
    // ---------------------------------------------------------------

    @Test
    fun `소리와 함께 위협음이 시작되면 울린다`() {
        assertNotNull(HapticPolicy().onTick(0, DANGER, LOUD, config()))
    }

    @Test
    fun `한 번 패턴은 이어지는 동안 다시 울리지 않는다`() {
        val fired = HapticPolicy().feed(0, 5000, DANGER, LOUD, config(pattern = HapticPattern.Tap))
        assertEquals(listOf(0L), fired.map { it.first })
    }

    @Test
    fun `반복 패턴은 간격마다 다시 울린다`() {
        val fired = HapticPolicy().feed(0, 5000, DANGER, LOUD, config(pattern = HapticPattern.Repeat))
        assertEquals(listOf(0L, 1500L, 3000L, 4500L), fired.map { it.first })
    }

    @Test
    fun `짧은 끊김은 같은 사건으로 본다`() {
        val policy = HapticPolicy()
        val cfg = config()
        policy.feed(0, 500, SPEECH, LOUD, cfg)
        // 마지막 큰 소리(500ms) 뒤 300ms 끊김 → 해제 시간(400ms) 안이라 같은 사건
        val gap = policy.feed(600, 800, SPEECH, QUIET, cfg)
        val resumed = policy.feed(900, 3000, SPEECH, LOUD, cfg)
        assertTrue("말소리 사이 틈에서 다시 울렸다", gap.isEmpty() && resumed.isEmpty())
    }

    @Test
    fun `쿨다운 안에 다시 시작된 사건은 울리지 않는다`() {
        val policy = HapticPolicy()
        val cfg = config()
        policy.feed(0, 200, DANGER, LOUD, cfg)          // 0ms 에 울림
        policy.feed(300, 900, DANGER, QUIET, cfg)       // 사건 끝
        val again = policy.feed(1000, 1900, DANGER, LOUD, cfg)  // 쿨다운(2초) 안
        assertTrue("연발 총소리에 연타했다: $again", again.isEmpty())
    }

    @Test
    fun `쿨다운에 막힌 사건은 쿨다운이 끝나고 소리가 이어지면 울린다`() {
        // 총소리 한 발 뒤 1.5초 만에 시작된 경보음. 예전에는 이 사건을 들고 있지 않아 영영 울리지 않았다(#174).
        val policy = HapticPolicy()
        val cfg = config()
        policy.feed(0, 200, DANGER, LOUD, cfg)          // 0ms 에 울림
        policy.feed(300, 900, DANGER, QUIET, cfg)       // 사건 끝
        val fired = policy.feed(1500, 3000, DANGER, LOUD, cfg)  // 쿨다운 안에서 시작해 계속 나는 소리
        assertEquals("쿨다운이 끝나는 2000ms 에 한 번 울려야 한다", listOf(2000L), fired.map { it.first })
    }

    @Test
    fun `쿨다운 안에서 시작해 쿨다운 전에 끝난 사건은 울리지 않는다`() {
        val policy = HapticPolicy()
        val cfg = config()
        policy.feed(0, 200, DANGER, LOUD, cfg)
        policy.feed(300, 900, DANGER, QUIET, cfg)
        policy.feed(1000, 1200, DANGER, LOUD, cfg)      // 쿨다운 안에서 잠깐
        val after = policy.feed(1300, 3000, DANGER, QUIET, cfg)
        assertTrue("이미 끝난 소리에 뒤늦게 울렸다: $after", after.isEmpty())
    }

    @Test
    fun `쿨다운이 지난 뒤 다시 시작된 사건은 울린다`() {
        val policy = HapticPolicy()
        val cfg = config()
        policy.feed(0, 200, DANGER, LOUD, cfg)
        policy.feed(300, 1900, DANGER, QUIET, cfg)
        val again = policy.feed(2000, 2200, DANGER, LOUD, cfg)
        assertEquals(listOf(2000L), again.map { it.first })
    }

    // ---------------------------------------------------------------
    // 설정
    // ---------------------------------------------------------------

    @Test
    fun `표시가 꺼진 종류는 울리지 않는다`() {
        val fired = HapticPolicy().feed(0, 2000, DANGER, LOUD, config(shown = false))
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `진동이 꺼진 종류는 울리지 않는다`() {
        val fired = HapticPolicy().feed(0, 2000, DANGER, LOUD, config(enabled = false))
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `종류가 바뀌면 새 종류의 패턴과 세기로 울린다`() {
        val cfg: (String) -> HapticPolicy.ClassConfig = { label ->
            when (label) {
                DANGER -> HapticPolicy.ClassConfig(true, HapticSettings(true, HapticStrength.Strong, HapticPattern.DoubleTap))
                else -> HapticPolicy.ClassConfig(true, HapticSettings(true, HapticStrength.Weak, HapticPattern.Tap))
            }
        }
        val policy = HapticPolicy()
        val speech = policy.feed(0, 400, SPEECH, LOUD, cfg)
        val danger = policy.feed(500, 900, DANGER, LOUD, cfg)

        assertEquals(listOf(HapticPolicy.Decision(HapticPattern.Tap, HapticStrength.Weak)), speech.map { it.second })
        assertEquals(listOf(HapticPolicy.Decision(HapticPattern.DoubleTap, HapticStrength.Strong)), danger.map { it.second })
    }

    @Test
    fun `진동을 켠 채 사건 중간에 끄면 그 뒤로 울리지 않는다`() {
        val policy = HapticPolicy()
        policy.feed(0, 300, DANGER, LOUD, config(pattern = HapticPattern.Repeat))
        val afterOff = policy.feed(400, 5000, DANGER, LOUD, config(enabled = false, pattern = HapticPattern.Repeat))
        assertTrue(afterOff.isEmpty())
    }

    @Test
    fun `분류 결과가 없으면 울리지 않는다`() {
        val fired = HapticPolicy().feed(0, 2000, null, LOUD, config())
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `reset 하면 쿨다운도 지워진다`() {
        val policy = HapticPolicy()
        assertNotNull(policy.onTick(0, DANGER, LOUD, config()))
        policy.reset()
        assertNotNull("reset 뒤 새 사건인데 쿨다운에 막혔다", policy.onTick(100, DANGER, LOUD, config()))
    }

    @Test
    fun `기준 이하의 작은 소리는 소리로 보지 않는다`() {
        val fired = HapticPolicy().feed(0, 2000, DANGER, HapticPolicy.LEVEL_THRESHOLD, config())
        assertTrue("기준값과 같은 크기에서 울렸다", fired.isEmpty())
        assertNull(HapticPolicy().onTick(0, DANGER, 0.005f, config()))
    }

    // ---------------------------------------------------------------
    // 기본값
    // ---------------------------------------------------------------

    @Test
    fun `기본값은 위협음만 켜져 있다`() {
        val danger = HapticSettings.defaultFor(AiClassification.DANGER)
        assertTrue(danger.enabled)
        assertEquals(HapticStrength.Strong, danger.strength)
        assertEquals(HapticPattern.DoubleTap, danger.pattern)

        assertFalse(HapticSettings.defaultFor(AiClassification.SPEECH).enabled)
        assertFalse(HapticSettings.defaultFor(AiClassification.AMBIENT).enabled)
    }

    @Test
    fun `모르는 라벨은 환경음 기본값을 쓴다`() {
        assertEquals(
            HapticSettings.defaultFor(AiClassification.AMBIENT),
            HapticSettings.defaultFor("unknown")
        )
    }

    @Test
    fun `세기는 약할수록 진폭이 작다`() {
        assertTrue(HapticStrength.Weak.amplitude < HapticStrength.Medium.amplitude)
        assertTrue(HapticStrength.Medium.amplitude < HapticStrength.Strong.amplitude)
        HapticStrength.values().forEach { assertTrue("${it.name} 진폭 범위", it.amplitude in 1..255) }
    }
}

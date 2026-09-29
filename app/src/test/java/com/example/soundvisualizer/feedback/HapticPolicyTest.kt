package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실제 알림 루프의 조용할 때 주기와 같게 흘린다. */
private const val TICK = HapticTuning.IDLE_TICK_MS
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
    cfg: (String) -> HapticPolicy.ClassConfig,
    unlabeledAlerts: Boolean = false
): List<Pair<Long, HapticPolicy.Decision>> {
    val fired = ArrayList<Pair<Long, HapticPolicy.Decision>>()
    var t = fromMs
    while (t <= toMs) {
        onTick(t, label, level, cfg, unlabeledAlerts)?.let { fired.add(t to it) }
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

    // ---------------------------------------------------------------
    // 소리 따라 (HapticPattern.Repeat)
    // ---------------------------------------------------------------

    @Test
    fun `소리 따라는 소리가 나는 동안 세션을 이어가고 한 번 패턴을 내지 않는다`() {
        val policy = HapticPolicy()
        val cfg = config(pattern = HapticPattern.Repeat)
        val ids = HashSet<Int>()
        var t = 0L
        while (t <= 5000) {
            assertNull("소리 따라가 한 번 패턴을 냈다 ($t)", policy.onTick(t, DANGER, LOUD, cfg))
            val f = policy.follow
            assertNotNull("$t 에 세션이 없다", f)
            assertEquals(DANGER, f!!.label)
            assertEquals(HapticStrength.Medium, f.strength)
            ids.add(f.id)
            t += TICK
        }
        assertEquals("세션이 중간에 새로 시작됐다", 1, ids.size)
    }

    @Test
    fun `무음에서는 라벨이 남아 있어도 소리 따라를 시작하지 않는다`() {
        val policy = HapticPolicy()
        policy.feed(0, 3000, DANGER, QUIET, config(pattern = HapticPattern.Repeat))
        assertNull(policy.follow)
    }

    @Test
    fun `소리 따라 세션은 소리가 2초 끊기면 끝난다`() {
        val policy = HapticPolicy()
        val cfg = config(pattern = HapticPattern.Repeat)
        policy.feed(0, 500, DANGER, LOUD, cfg)
        val id = policy.follow!!.id
        policy.feed(600, 2500, DANGER, QUIET, cfg)
        assertEquals("2초 안의 쉼은 같은 세션", id, policy.follow?.id)
        policy.onTick(2600, DANGER, QUIET, cfg)
        assertNull("2초 넘게 끊겼는데 세션이 남았다", policy.follow)
    }

    @Test
    fun `T3 의 쉼 동안 라벨이 바뀌어도 세션은 이어진다`() {
        val policy = HapticPolicy()
        val cfg = mixed(danger = HapticPattern.Repeat, other = HapticPattern.Tap)
        policy.feed(0, 500, DANGER, LOUD, cfg)
        val id = policy.follow!!.id
        val gap = policy.feed(600, 1400, AiClassification.AMBIENT, QUIET, cfg)
        val back = policy.feed(1500, 2000, DANGER, LOUD, cfg)
        assertTrue("쉼에서 다른 종류가 울렸다: $gap $back", gap.isEmpty() && back.isEmpty())
        assertEquals(id, policy.follow?.id)
    }

    @Test
    fun `600ms 안에 돌아온 라벨 흔들림은 세션을 끊지 않는다`() {
        val policy = HapticPolicy()
        val cfg = mixed(danger = HapticPattern.Repeat, other = HapticPattern.Tap)
        policy.feed(0, 1000, DANGER, LOUD, cfg)
        val id = policy.follow!!.id
        val flicker = policy.feed(1100, 1500, SPEECH, LOUD, cfg)
        policy.feed(1600, 2000, DANGER, LOUD, cfg)
        assertTrue("흔들림에 말소리 진동이 울렸다: $flicker", flicker.isEmpty())
        assertEquals("흔들림에 세션이 새로 시작됐다", id, policy.follow?.id)
    }

    @Test
    fun `소리와 함께 다른 종류가 600ms 넘게 이어지면 세션이 끝나고 그 종류가 울린다`() {
        val policy = HapticPolicy()
        val cfg = mixed(danger = HapticPattern.Repeat, other = HapticPattern.Tap)
        policy.feed(0, 1000, DANGER, LOUD, cfg)
        val fired = policy.feed(1100, 2500, SPEECH, LOUD, cfg)
        assertEquals("600ms 째에 말소리가 울려야 한다", listOf(1600L), fired.map { it.first })
        assertNull(policy.follow)
    }

    @Test
    fun `위협음은 기다리지 않고 소리 따라를 끊고 제 패턴으로 울린다`() {
        val policy = HapticPolicy()
        val cfg: (String) -> HapticPolicy.ClassConfig = { label ->
            when (label) {
                SPEECH -> HapticPolicy.ClassConfig(true, HapticSettings(true, HapticStrength.Medium, HapticPattern.Repeat))
                else -> HapticPolicy.ClassConfig(true, HapticSettings(true, HapticStrength.Strong, HapticPattern.DoubleTap))
            }
        }
        policy.feed(0, 1000, SPEECH, LOUD, cfg)
        val fired = policy.feed(1100, 1500, DANGER, LOUD, cfg)
        assertEquals(listOf(1100L), fired.map { it.first })
        assertEquals(HapticPattern.DoubleTap, fired.single().second.pattern)
        assertNull(policy.follow)
    }

    @Test
    fun `위협음도 소리 따라면 바로 위협음 세션으로 바뀐다`() {
        val policy = HapticPolicy()
        val cfg = config(pattern = HapticPattern.Repeat)
        policy.feed(0, 1000, SPEECH, LOUD, cfg)
        val speechId = policy.follow!!.id
        policy.onTick(1100, DANGER, LOUD, cfg)
        val f = policy.follow!!
        assertEquals(DANGER, f.label)
        assertTrue(f.id != speechId)
    }

    @Test
    fun `소리 따라는 쿨다운을 읽지도 쓰지도 않는다`() {
        val policy = HapticPolicy()
        val follow = config(pattern = HapticPattern.Repeat)
        policy.onTick(0, DANGER, LOUD, follow)
        val first = policy.follow!!.id
        policy.onTick(100, DANGER, LOUD, config(enabled = false, pattern = HapticPattern.Repeat))
        assertNull(policy.follow)
        policy.onTick(200, DANGER, LOUD, follow)
        assertTrue("2초 안에 다시 켠 세션이 막혔다", policy.follow != null && policy.follow!!.id != first)

        // 소리 따라가 쿨다운을 남기지 않았으니, 끝난 바로 뒤 같은 종류의 한 번 패턴도 막히지 않는다.
        val other = HapticPolicy()
        other.onTick(0, DANGER, LOUD, config(pattern = HapticPattern.Repeat))
        other.onTick(100, DANGER, LOUD, config(enabled = false, pattern = HapticPattern.Repeat))
        assertNotNull("소리 따라 뒤의 한 번이 쿨다운에 막혔다", other.onTick(200, DANGER, LOUD, config(pattern = HapticPattern.Tap)))
    }

    @Test
    fun `사건 중간에 소리 따라를 한 번으로 바꾸면 세션이 끝나고 그 사건에는 다시 울리지 않는다`() {
        val policy = HapticPolicy()
        policy.feed(0, 300, DANGER, LOUD, config(pattern = HapticPattern.Repeat))
        val after = policy.feed(400, 3000, DANGER, LOUD, config(pattern = HapticPattern.Tap))
        assertNull(policy.follow)
        assertTrue("이어지는 같은 소리에 한 번이 울렸다: $after", after.isEmpty())
    }

    @Test
    fun `표시를 끄면 세션이 끝난다`() {
        val policy = HapticPolicy()
        policy.feed(0, 300, DANGER, LOUD, config(pattern = HapticPattern.Repeat))
        policy.onTick(400, DANGER, LOUD, config(shown = false, pattern = HapticPattern.Repeat))
        assertNull(policy.follow)
    }

    @Test
    fun `세기를 바꾸면 세션 세기가 바로 바뀐다`() {
        val policy = HapticPolicy()
        policy.feed(0, 300, DANGER, LOUD, config(pattern = HapticPattern.Repeat))
        val id = policy.follow!!.id
        policy.onTick(400, DANGER, LOUD, config(pattern = HapticPattern.Repeat, strength = HapticStrength.Strong))
        assertEquals(id, policy.follow!!.id)
        assertEquals(HapticStrength.Strong, policy.follow!!.strength)
    }

    @Test
    fun `reset 하면 세션도 지워진다`() {
        val policy = HapticPolicy()
        policy.onTick(0, DANGER, LOUD, config(pattern = HapticPattern.Repeat))
        policy.reset()
        assertNull(policy.follow)
    }

    private fun mixed(danger: HapticPattern, other: HapticPattern): (String) -> HapticPolicy.ClassConfig = { label ->
        HapticPolicy.ClassConfig(
            true,
            HapticSettings(true, HapticStrength.Medium, if (label == DANGER) danger else other)
        )
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
    fun `사건 중간에 진동을 끄면 같은 틱에 세션이 끝난다`() {
        val policy = HapticPolicy()
        policy.feed(0, 300, DANGER, LOUD, config(pattern = HapticPattern.Repeat))
        assertNotNull(policy.follow)
        val off = config(enabled = false, pattern = HapticPattern.Repeat)
        assertNull(policy.onTick(400, DANGER, LOUD, off))
        assertNull("끈 틱에 세션이 남았다", policy.follow)
        val afterOff = policy.feed(500, 5000, DANGER, LOUD, off)
        assertTrue(afterOff.isEmpty())
        assertNull(policy.follow)
    }

    @Test
    fun `분류 결과가 없으면 울리지 않는다`() {
        // AI 가 로딩 중이거나 화면을 켠 직후라 라벨이 곧 온다. 먼저 울리면 곧 올 라벨과 겹쳐 두 번 울린다.
        val fired = HapticPolicy().feed(0, 2000, null, LOUD, config())
        assertTrue(fired.isEmpty())
    }

    // ---------------------------------------------------------------
    // AI 를 쓸 수 없는 실행 (#225)
    // ---------------------------------------------------------------

    @Test
    fun `AI 를 못 쓰면 라벨 없이도 큰 소리에 위협음 설정으로 한 번 울린다`() {
        val cfg: (String) -> HapticPolicy.ClassConfig = { label ->
            when (label) {
                DANGER -> HapticPolicy.ClassConfig(true, HapticSettings(true, HapticStrength.Strong, HapticPattern.Hold))
                else -> HapticPolicy.ClassConfig(true, HapticSettings(true, HapticStrength.Weak, HapticPattern.Tap))
            }
        }
        val fired = HapticPolicy().feed(0, 3000, null, LOUD, cfg, unlabeledAlerts = true)

        assertEquals("이어지는 큰 소리 하나에 한 번만 울린다", listOf(0L), fired.map { it.first })
        assertEquals(HapticPolicy.Decision(HapticPattern.Hold, HapticStrength.Strong), fired.single().second)
    }

    @Test
    fun `AI 를 못 써도 큰 소리가 아니면 울리지 않는다`() {
        // 소리는 나지만(0.01 초과) 큰 소리는 아니다. 게임·영상이 켜져 있는 내내 울리면 안 된다.
        val fired = HapticPolicy().feed(0, 3000, null, HapticTuning.UNLABELED_LOUD_LEVEL / 2, config(), unlabeledAlerts = true)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `AI 를 못 쓸 때 조용해졌다가 다시 큰 소리가 나면 다시 울린다`() {
        val policy = HapticPolicy()
        val first = policy.feed(0, 500, null, LOUD, config(), unlabeledAlerts = true)
        policy.feed(600, 2400, null, QUIET, config(), unlabeledAlerts = true)
        val second = policy.feed(2500, 3000, null, LOUD, config(), unlabeledAlerts = true)

        assertEquals(listOf(0L), first.map { it.first })
        assertEquals(listOf(2500L), second.map { it.first })
    }

    @Test
    fun `AI 를 못 쓸 때 쿨다운 안에 시작된 큰 소리는 쿨다운이 끝나고 이어지면 울린다`() {
        val policy = HapticPolicy()
        val first = policy.feed(0, 200, null, LOUD, config(), unlabeledAlerts = true)
        policy.feed(300, 900, null, QUIET, config(), unlabeledAlerts = true)
        val second = policy.feed(1000, 4000, null, LOUD, config(), unlabeledAlerts = true)

        assertEquals(listOf(0L), first.map { it.first })
        assertEquals(listOf(HapticPolicy.COOLDOWN_MS), second.map { it.first })
    }

    @Test
    fun `위협음의 표시나 진동을 꺼 두면 종류를 몰라도 울리지 않는다`() {
        assertTrue(HapticPolicy().feed(0, 2000, null, LOUD, config(enabled = false), unlabeledAlerts = true).isEmpty())
        assertTrue(HapticPolicy().feed(0, 2000, null, LOUD, config(shown = false), unlabeledAlerts = true).isEmpty())
    }

    @Test
    fun `위협음이 소리 따라여도 종류를 모를 때는 두 번으로 울리고 따라가지 않는다`() {
        val policy = HapticPolicy()
        val fired = policy.feed(0, 3000, null, LOUD, config(pattern = HapticPattern.Repeat, strength = HapticStrength.Weak), unlabeledAlerts = true)

        assertEquals(listOf(HapticPolicy.Decision(HapticPattern.DoubleTap, HapticStrength.Weak)), fired.map { it.second })
        assertNull("모든 소리를 따라 울리면 안 된다", policy.follow)
    }

    @Test
    fun `라벨이 오면 AI 를 못 쓴다는 표시와 상관없이 라벨로 판단한다`() {
        val fired = HapticPolicy().feed(0, 2000, SPEECH, LOUD, config(pattern = HapticPattern.Tap), unlabeledAlerts = true)
        assertEquals(listOf(HapticPolicy.Decision(HapticPattern.Tap, HapticStrength.Medium)), fired.map { it.second })
    }

    // ---------------------------------------------------------------
    // 마이크로 들을 때 (#226)
    // ---------------------------------------------------------------

    @Test
    fun `마이크로 들을 때는 소리 따라만 두 번으로 바꾸고 세기는 그대로 둔다`() {
        val follow = HapticPolicy.ClassConfig(true, HapticSettings(true, HapticStrength.Weak, HapticPattern.Repeat))
        assertEquals(HapticSettings(true, HapticStrength.Weak, HapticPattern.DoubleTap), follow.withoutFollow().haptic)

        for (pattern in listOf(HapticPattern.Tap, HapticPattern.DoubleTap, HapticPattern.Hold)) {
            val other = HapticPolicy.ClassConfig(true, HapticSettings(true, HapticStrength.Strong, pattern))
            assertEquals(other, other.withoutFollow())
        }
    }

    @Test
    fun `소리 따라를 두 번으로 바꾸면 이어지는 소리에 세션을 열지 않고 한 번만 울린다`() {
        // 폰이 자기 진동을 다시 들어도 사건은 하나로 이어질 뿐이라 다시 울리지 않는다.
        val follow = config(pattern = HapticPattern.Repeat)
        val policy = HapticPolicy()
        val fired = policy.feed(0, 5000, DANGER, LOUD, { follow(it).withoutFollow() })

        assertEquals(listOf(0L), fired.map { it.first })
        assertNull(policy.follow)
    }

    @Test
    fun `reset 하면 종류를 모를 때의 쿨다운도 지워진다`() {
        val policy = HapticPolicy()
        assertNotNull(policy.onTick(0, null, LOUD, config(), unlabeledAlerts = true))
        policy.reset()
        assertNotNull(policy.onTick(100, null, LOUD, config(), unlabeledAlerts = true))
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

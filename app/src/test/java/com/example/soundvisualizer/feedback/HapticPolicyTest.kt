package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실제 알림 루프의 판단 주기와 같게 흘린다. */
private const val TICK = HapticTuning.IDLE_TICK_MS
private const val LOUD = 0.5f
private const val QUIET = 0f

private const val DANGER = AiClassification.DANGER
private const val SPEECH = AiClassification.SPEECH
private const val AMBIENT = AiClassification.AMBIENT

/** 모든 종류가 같은 설정. */
private fun config(
    shown: Boolean = true,
    mode: HapticMode = HapticMode.Medium,
    level: Int = 60
): (String) -> HapticPolicy.ClassConfig = { HapticPolicy.ClassConfig(shown, HapticSettings(mode, level)) }

/** 종류마다 다른 설정. 적지 않은 종류는 꺼짐이다. */
private fun perLabel(vararg settings: Pair<String, HapticSettings>): (String) -> HapticPolicy.ClassConfig {
    val map = settings.toMap()
    return { label -> HapticPolicy.ClassConfig(true, map[label] ?: HapticSettings(HapticMode.Off, 60)) }
}

private fun vibe(mode: HapticMode, level: Int = 60) = HapticPolicy.Vibe(mode, level)

/** [fromMs] 부터 [toMs] 까지(포함) [TICK] 간격으로 흘리고, 틱마다 낸 진동을 모은다. */
private fun HapticPolicy.feed(
    fromMs: Long,
    toMs: Long,
    label: String?,
    level: Float,
    cfg: (String) -> HapticPolicy.ClassConfig,
    unlabeledAlerts: Boolean = false
): List<Pair<Long, HapticPolicy.Vibe?>> {
    val out = ArrayList<Pair<Long, HapticPolicy.Vibe?>>()
    var t = fromMs
    while (t <= toMs) {
        out.add(t to onTick(t, label, level, cfg, unlabeledAlerts))
        t += TICK
    }
    return out
}

class HapticPolicyTest {

    // ---------------------------------------------------------------
    // 소리가 이어지는 동안
    // ---------------------------------------------------------------

    @Test
    fun `무음에서는 라벨이 위협음으로 남아 있어도 울리지 않는다`() {
        val out = HapticPolicy().feed(0, 3000, DANGER, QUIET, config())
        assertTrue("무음인데 울렸다: $out", out.all { it.second == null })
    }

    @Test
    fun `그 종류의 소리가 이어지는 동안 그 종류의 방식과 세기로 계속 울린다`() {
        val out = HapticPolicy().feed(0, 5000, DANGER, LOUD, config(mode = HapticMode.Fast, level = 80))
        assertTrue(out.all { it.second == vibe(HapticMode.Fast, 80) })
    }

    @Test
    fun `소리가 끝나면 0_4초 뒤에 멈춘다`() {
        val policy = HapticPolicy()
        policy.feed(0, 1000, SPEECH, LOUD, config())
        val after = policy.feed(1100, 2000, SPEECH, QUIET, config())

        val lastOn = after.last { it.second != null }.first
        assertEquals("말 사이의 쉼(0.4초)까지는 이어진다", 1000L + HapticPolicy.RELEASE_MS, lastOn)
        assertTrue("멈춘 뒤 다시 울렸다", after.filter { it.first > lastOn }.all { it.second == null })
    }

    @Test
    fun `짧은 쉼이 있는 소리는 끊기지 않는다`() {
        // 말소리처럼 0.3초 소리, 0.3초 쉼이 되풀이된다.
        val policy = HapticPolicy()
        var t = 0L
        while (t <= 5000) {
            val level = if (t % 600 < 300) LOUD else QUIET
            assertEquals("t=$t", vibe(HapticMode.Medium), policy.onTick(t, SPEECH, level, config()))
            t += TICK
        }
    }

    @Test
    fun `조용해졌다가 다시 소리가 나면 라벨이 그대로여도 다시 울린다`() {
        val policy = HapticPolicy()
        policy.feed(0, 500, DANGER, LOUD, config())
        val quiet = policy.feed(1000, 2000, DANGER, QUIET, config())
        val again = policy.feed(2100, 2500, DANGER, LOUD, config())

        assertTrue(quiet.all { it.second == null })
        assertTrue(again.all { it.second == vibe(HapticMode.Medium) })
    }

    @Test
    fun `기준 이하의 작은 소리는 소리로 보지 않는다`() {
        val out = HapticPolicy().feed(0, 2000, DANGER, HapticPolicy.LEVEL_THRESHOLD, config())
        assertTrue("기준값과 같은 크기에서 울렸다", out.all { it.second == null })
        assertNull(HapticPolicy().onTick(0, DANGER, 0.005f, config()))
    }

    @Test
    fun `분류 결과가 없으면 울리지 않는다`() {
        val out = HapticPolicy().feed(0, 2000, null, LOUD, config())
        assertTrue(out.all { it.second == null })
    }

    // ---------------------------------------------------------------
    // 설정
    // ---------------------------------------------------------------

    @Test
    fun `진동이 꺼진 종류는 울리지 않는다`() {
        assertTrue(HapticPolicy().feed(0, 2000, SPEECH, LOUD, config(mode = HapticMode.Off)).all { it.second == null })
    }

    @Test
    fun `표시가 꺼진 종류는 울리지 않는다`() {
        assertTrue(HapticPolicy().feed(0, 2000, DANGER, LOUD, config(shown = false)).all { it.second == null })
    }

    @Test
    fun `울리는 중에 진동이나 표시를 끄면 같은 틱에 멈춘다`() {
        val policy = HapticPolicy()
        assertNotNull(policy.onTick(0, DANGER, LOUD, config()))
        assertNull(policy.onTick(100, DANGER, LOUD, config(mode = HapticMode.Off)))
        assertNotNull(policy.onTick(200, DANGER, LOUD, config()))
        assertNull(policy.onTick(300, DANGER, LOUD, config(shown = false)))
    }

    @Test
    fun `한 틱에 설정을 한 번만 읽는다`() {
        // 설정 화면(메인 스레드)이 틱 도중에 바꿀 수 있다. 두 번 읽으면 켜짐으로 고른 뒤 꺼짐 설정을 돌려줄 수 있다.
        var reads = 0
        val counting: (String) -> HapticPolicy.ClassConfig = {
            reads++
            HapticPolicy.ClassConfig(true, HapticSettings(HapticMode.Medium, 60))
        }
        assertNotNull(HapticPolicy().onTick(0, DANGER, LOUD, counting))
        assertEquals(1, reads)
    }

    @Test
    fun `울리는 중에 방식이나 세기를 바꾸면 바로 따른다`() {
        val policy = HapticPolicy()
        assertEquals(vibe(HapticMode.Slow, 30), policy.onTick(0, DANGER, LOUD, config(mode = HapticMode.Slow, level = 30)))
        assertEquals(vibe(HapticMode.Continuous, 30), policy.onTick(100, DANGER, LOUD, config(mode = HapticMode.Continuous, level = 30)))
        assertEquals(vibe(HapticMode.Continuous, 90), policy.onTick(200, DANGER, LOUD, config(mode = HapticMode.Continuous, level = 90)))
    }

    // ---------------------------------------------------------------
    // 종류가 바뀔 때
    // ---------------------------------------------------------------

    private val speechSlowAmbientFast = perLabel(
        SPEECH to HapticSettings(HapticMode.Slow, 40),
        AMBIENT to HapticSettings(HapticMode.Fast, 70),
        DANGER to HapticSettings(HapticMode.Continuous, 100)
    )

    @Test
    fun `라벨이 바뀌면 같은 틱에 새 종류로 바뀐다`() {
        // 화면 색도 같은 라벨을 곧바로 따른다. 진동만 기다리면 앞 종류가 화면보다 늦게까지 남는다(#244).
        val policy = HapticPolicy()
        assertEquals(vibe(HapticMode.Slow, 40), policy.onTick(0, SPEECH, LOUD, speechSlowAmbientFast))
        assertEquals(vibe(HapticMode.Fast, 70), policy.onTick(100, AMBIENT, LOUD, speechSlowAmbientFast))
        assertEquals(vibe(HapticMode.Continuous, 100), policy.onTick(200, DANGER, LOUD, speechSlowAmbientFast))
        assertEquals(vibe(HapticMode.Slow, 40), policy.onTick(300, SPEECH, LOUD, speechSlowAmbientFast))
    }

    @Test
    fun `꺼진 종류로 바뀌면 같은 틱에 멈추고 다시 바뀌면 바로 울린다`() {
        val cfg = perLabel(DANGER to HapticSettings(HapticMode.Medium, 100))
        val policy = HapticPolicy()
        assertEquals(vibe(HapticMode.Medium, 100), policy.onTick(0, DANGER, LOUD, cfg))
        assertNull(policy.onTick(100, SPEECH, LOUD, cfg))
        assertEquals(vibe(HapticMode.Medium, 100), policy.onTick(200, DANGER, LOUD, cfg))
    }

    // ---------------------------------------------------------------
    // AI 를 쓸 수 없을 때 (#225)
    // ---------------------------------------------------------------

    private val dangerSlow = perLabel(
        DANGER to HapticSettings(HapticMode.Slow, 30),
        SPEECH to HapticSettings(HapticMode.Fast, 90)
    )

    @Test
    fun `AI 를 못 쓰면 큰 소리가 이어지는 동안 위협음 설정으로 울린다`() {
        val out = HapticPolicy().feed(0, 3000, null, LOUD, dangerSlow, unlabeledAlerts = true)
        assertTrue(out.all { it.second == vibe(HapticMode.Slow, 30) })
    }

    @Test
    fun `AI 를 못 써도 큰 소리가 아니면 울리지 않는다`() {
        // 소리는 나지만(0.01 초과) 큰 소리는 아니다. 게임·영상이 켜져 있는 내내 울리면 안 된다.
        val out = HapticPolicy().feed(0, 3000, null, HapticTuning.UNLABELED_LOUD_LEVEL / 3, dangerSlow, unlabeledAlerts = true)
        assertTrue(out.all { it.second == null })
    }

    @Test
    fun `AI 를 못 쓸 때 배경음 사이의 큰 소리에만 울린다`() {
        // 게임 음악처럼 끊기지 않는 배경음(0.01 초과) 동안은 멈추고, 폭발음마다 울려야 한다(#232).
        val policy = HapticPolicy()
        val background = HapticTuning.UNLABELED_LOUD_LEVEL * HapticTuning.UNLABELED_RELEASE_RATIO / 2
        val first = policy.feed(0, 500, null, LOUD, dangerSlow, unlabeledAlerts = true)
        val between = policy.feed(600, 3000, null, background, dangerSlow, unlabeledAlerts = true)
        val second = policy.feed(3100, 3500, null, LOUD, dangerSlow, unlabeledAlerts = true)

        assertTrue(first.all { it.second != null })
        assertTrue("배경음 내내 울린다", between.filter { it.first > 500 + HapticPolicy.RELEASE_MS }.all { it.second == null })
        assertTrue(second.all { it.second != null })
    }

    @Test
    fun `AI 를 못 쓸 때 큰 소리 기준 근처를 오르내리는 소리는 끊기지 않는다`() {
        // 기준 바로 위아래를 오가는 음악에 켰다 껐다 하면 안 된다. 기준의 절반 아래로 내려가야 끝난다.
        val policy = HapticPolicy()
        val over = HapticTuning.UNLABELED_LOUD_LEVEL * 1.2f
        val under = HapticTuning.UNLABELED_LOUD_LEVEL * (1f + HapticTuning.UNLABELED_RELEASE_RATIO) / 2
        var t = 0L
        while (t < 8000) {
            val level = if (t % 1200 < 200) over else under
            assertNotNull("t=$t", policy.onTick(t, null, level, dangerSlow, unlabeledAlerts = true))
            t += TICK
        }
    }

    @Test
    fun `AI 를 못 쓸 때 큰 소리가 잠깐 약해진 것은 이어진 것으로 본다`() {
        // 폭발음의 꼬리나 사이렌의 흔들림처럼 0.4초 안에 다시 커지면 끊지 않는다.
        val policy = HapticPolicy()
        var t = 0L
        while (t < 5000) {
            val level = if (t % 700 < 400) LOUD else 0.02f
            assertNotNull("t=$t", policy.onTick(t, null, level, dangerSlow, unlabeledAlerts = true))
            t += TICK
        }
    }

    @Test
    fun `위협음의 표시나 진동을 꺼 두면 종류를 몰라도 울리지 않는다`() {
        assertTrue(HapticPolicy().feed(0, 2000, null, LOUD, config(mode = HapticMode.Off), unlabeledAlerts = true).all { it.second == null })
        assertTrue(HapticPolicy().feed(0, 2000, null, LOUD, config(shown = false), unlabeledAlerts = true).all { it.second == null })
    }

    @Test
    fun `라벨이 오면 AI 를 못 쓴다는 표시와 상관없이 라벨로 판단한다`() {
        val out = HapticPolicy().feed(0, 2000, SPEECH, LOUD, dangerSlow, unlabeledAlerts = true)
        assertTrue(out.all { it.second == vibe(HapticMode.Fast, 90) })
    }

    @Test
    fun `reset 하면 이어지던 소리를 잊는다`() {
        val policy = HapticPolicy()
        assertNotNull(policy.onTick(0, DANGER, LOUD, config()))
        policy.reset()
        assertNull("reset 뒤에도 앞 소리가 이어진 것으로 본다", policy.onTick(100, DANGER, QUIET, config()))
        assertNotNull(policy.onTick(200, null, LOUD, config(), unlabeledAlerts = true))
        policy.reset()
        assertNull(policy.onTick(300, null, QUIET, config(), unlabeledAlerts = true))
    }

    // ---------------------------------------------------------------
    // 외부 사운드 모드: 자기 진동이 섞인 동안 기다리기 (#290)
    // ---------------------------------------------------------------

    @Test
    fun `기다리는 동안은 마지막 깨끗한 판단대로 계속 울린다`() {
        val policy = HapticPolicy()
        assertNotNull(policy.onTick(0, DANGER, LOUD, config()))
        // 문은 섞인 값을 0 으로 넘기고 기다리게 한다. 0.4초 여유가 지나도 판단을 바꾸지 않는다.
        var t = TICK
        while (t <= 600) {
            assertEquals("t=$t", vibe(HapticMode.Medium), policy.onTick(t, DANGER, 0f, config(), holding = true))
            t += TICK
        }
        assertNull("깨끗한 값을 다시 읽으면 0.4초 여유대로 멈춘다", policy.onTick(t, DANGER, QUIET, config()))
    }

    @Test
    fun `기다리는 동안 새로 울리기 시작하지 않는다`() {
        val policy = HapticPolicy()
        assertNull(policy.onTick(0, DANGER, QUIET, config()))
        assertNull("조용하던 중에 섞인 값으로 울리기 시작했다", policy.onTick(100, DANGER, 0f, config(), holding = true))
        assertNull(policy.onTick(200, DANGER, 0f, config(), holding = true))
    }

    @Test
    fun `기다리는 동안에도 꺼진 종류로 바뀌면 같은 틱에 멈춘다`() {
        val cfg = perLabel(DANGER to HapticSettings(HapticMode.Fast, 80))
        val policy = HapticPolicy()
        assertNotNull(policy.onTick(0, DANGER, LOUD, cfg))
        assertNotNull(policy.onTick(100, DANGER, 0f, cfg, holding = true))
        assertNull("꺼진 종류(대화음)로 바뀌었는데 울린다(#244)", policy.onTick(200, SPEECH, 0f, cfg, holding = true))
    }

    @Test
    fun `기다리는 동안은 종류를 모르는 큰 소리가 시작되지도 끝나지도 않는다`() {
        val policy = HapticPolicy()
        assertNull("섞인 값으로 큰 소리가 시작됐다", policy.onTick(0, null, 0.9f, config(), unlabeledAlerts = true, holding = true))
        assertNotNull(policy.onTick(100, null, LOUD, config(), unlabeledAlerts = true))
        var t = 200L
        while (t <= 800) {
            assertNotNull("t=$t", policy.onTick(t, null, 0f, config(), unlabeledAlerts = true, holding = true))
            t += TICK
        }
        assertNull(policy.onTick(t, null, QUIET, config(), unlabeledAlerts = true))
    }

    @Test
    fun `reset 하면 기다리던 판단도 잊는다`() {
        val policy = HapticPolicy()
        assertNotNull(policy.onTick(0, DANGER, LOUD, config()))
        policy.reset()
        assertNull(policy.onTick(100, DANGER, 0f, config(), holding = true))
    }

    @Test
    fun `이어 준 크기는 그 크기가 들린 시각부터 여유를 센다`() {
        val policy = HapticPolicy()
        assertNotNull(policy.onTick(0, DANGER, LOUD, config()))
        // 문은 섞인 값 자리에 앞 쉼의 크기를 돌려주되, 그 구간이 시작된 시각(100)의 소리로 친다.
        assertNotNull(policy.onTick(500, DANGER, LOUD, config(), holding = true, levelAtMs = 100))
        assertNull("듣지 못한 구간 끝까지 소리가 이어졌다고 쳤다", policy.onTick(550, DANGER, QUIET, config()))
    }

    @Test
    fun `종류를 모를 때도 이어 준 크기로 큰 소리가 이어진 시각을 늘린다`() {
        val policy = HapticPolicy()
        assertNotNull(policy.onTick(0, null, LOUD, config(), unlabeledAlerts = true))
        // 듣지 않는 동안 앞 쉼의 큰 소리를 이어 받는다. 0.4초가 지나도 쉼에서 처음 읽은 값이 잠깐 작다고 끝나지 않는다.
        assertNotNull(policy.onTick(300, null, LOUD, config(), unlabeledAlerts = true, holding = true, levelAtMs = 300))
        assertNotNull(policy.onTick(600, null, 0.1f, config(), unlabeledAlerts = true))
    }
}

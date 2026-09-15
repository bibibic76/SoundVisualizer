package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 60fps 한 프레임. 엔진의 시간 정규화 기준값과 같다. */
private const val FRAME_60 = 16_666_667L
private const val FRAME_120 = 8_333_333L
private const val DENSITY = 1f
private const val W = 1080f
private const val H = 1920f

/** maxBase = min(w,h)/2 - EDGE_MARGIN_DP*density = 540 - 10 */
private const val MAX_BASE = 530f

private class FakeInputs(
    var mode: VisualMode = VisualMode.Wave,
    var settings: ModeSettings = ModeSettings(),
    var label: String = AiClassification.AMBIENT,
    var color: Int = 0xFFFFFF,
    var shown: Boolean = true,
    /** 모드별로 다른 설정을 주고 싶을 때만 쓴다. null 이면 [settings] 를 그대로 돌려준다. */
    var settingsProvider: ((VisualMode) -> ModeSettings)? = null
) : VisualizerInputs {

    /** 다음 readPeaks 가 돌려줄 값. */
    var left = 0f
    var right = 0f

    /** 마지막 읽기 이후 도착한 버퍼 수. 0 이면 엔진이 감쇠 경로를 탄다. */
    var buffers = 1

    /** 프레임 캡이 실제로 틱을 걸러내는지 세는 용도. */
    var readCount = 0
        private set

    override fun readPeaks(out: FloatArray) {
        readCount++
        out[0] = left
        out[1] = right
        out[2] = buffers.toFloat()
    }

    override fun currentMode(): VisualMode = mode
    override fun settingsFor(mode: VisualMode): ModeSettings =
        settingsProvider?.invoke(mode) ?: settings
    override fun coarseLabel(): String = label
    override fun colorFor(label: String): Int = color
    override fun isShown(label: String): Boolean = shown
}

private fun newEngine(fake: FakeInputs): VisualizerEngine =
    VisualizerEngine(DENSITY, fake).also { it.setSurfaceSize(W, H) }

/** [frames] 프레임을 [stepNs] 간격으로 흘리고 마지막 타임스탬프를 돌려준다. */
private fun VisualizerEngine.advance(
    frames: Int,
    stepNs: Long = FRAME_60,
    startNs: Long = FRAME_60
): Long {
    var t = startNs
    repeat(frames) {
        tick(t)
        t += stepNs
    }
    return t - stepNs
}

/**
 * 대기 상태가 될 때까지 60fps 로 틱을 돌리고 다음 타임스탬프를 돌려준다.
 * 실제 렌더 루프처럼 대기에 들어가면 틱을 멈춘다.
 */
private fun VisualizerEngine.tickUntilIdle(startNs: Long = FRAME_60, maxFrames: Int = 600): Long {
    var t = startNs
    var frames = 0
    while (!debugState().idle && frames < maxFrames) {
        tick(t)
        t += FRAME_60
        frames++
    }
    assertTrue("대기 상태로 내려가지 않았다 (frames=$frames)", debugState().idle)
    return t
}

/** 대기 중 33ms 폴링을 [times] 번. */
private fun VisualizerEngine.poll(times: Int) = repeat(times) { pollWake() }

/** 분류기가 위협음으로 판정했고, 위협음은 표시가 켜져 있다. */
private fun FakeInputs.becomeDanger() {
    label = AiClassification.DANGER
    shown = true
}

/** 환경음을 숨기고 위협음만 보이게 한 채, 조용해서 대기로 내려간 엔진. */
private fun hiddenAmbientIdle(): Pair<VisualizerEngine, FakeInputs> {
    val fake = FakeInputs(label = AiClassification.AMBIENT, shown = false)
    val engine = newEngine(fake)
    engine.tickUntilIdle()
    return engine to fake
}

class VisualizerEngineTest {

    // ---------------------------------------------------------------
    // 프레임 캡
    // ---------------------------------------------------------------

    @Test
    fun `60Hz 화면에서는 모든 프레임을 처리한다`() {
        val fake = FakeInputs()
        newEngine(fake).advance(frames = 60, stepNs = FRAME_60)
        assertEquals(60, fake.readCount)
    }

    @Test
    fun `120Hz 화면에서는 프레임을 절반만 처리한다`() {
        val fake = FakeInputs()
        newEngine(fake).advance(frames = 120, stepNs = FRAME_120)
        // 캡이 없으면 120. 캡이 걸리면 60 근처로 떨어진다.
        assertTrue("120Hz 처리 횟수=${fake.readCount}", fake.readCount in 57..64)
    }

    @Test
    fun `첫 프레임은 캡에 걸리지 않는다`() {
        val fake = FakeInputs()
        newEngine(fake).tick(FRAME_60)
        assertEquals(1, fake.readCount)
    }

    // ---------------------------------------------------------------
    // 피크 유지와 감쇠
    // ---------------------------------------------------------------

    @Test
    fun `새 버퍼가 오면 목표값이 즉시 갱신된다`() {
        val fake = FakeInputs()
        val engine = newEngine(fake)

        fake.left = 0.9f
        fake.right = 0.9f
        engine.advance(frames = 30)

        assertTrue("소리를 받으면 smoothTotal 이 올라야 한다", engine.debugState().smoothTotal > 0f)
    }

    @Test
    fun `버퍼가 끊기면 목표값이 감쇠한다`() {
        val fake = FakeInputs()
        val engine = newEngine(fake)

        fake.left = 0.9f
        fake.right = 0.9f
        var t = engine.advance(frames = 30) + FRAME_60
        val loud = engine.debugState().smoothTotal
        assertTrue(loud > 0f)

        // 버퍼 도착이 멈추고 피크도 0 → 감쇠 경로
        fake.buffers = 0
        fake.left = 0f
        fake.right = 0f
        repeat(60) {
            engine.tick(t)
            t += FRAME_60
        }

        assertTrue(
            "감쇠 후 smoothTotal(${engine.debugState().smoothTotal}) 이 최대치($loud) 보다 작아야 한다",
            engine.debugState().smoothTotal < loud
        )
    }

    // ---------------------------------------------------------------
    // 깊이 계산
    // ---------------------------------------------------------------

    @Test
    fun `크기 100이면 baseDepth 가 화면 중앙 한계선까지 닿는다`() {
        val fake = FakeInputs(settings = ModeSettings(intensity = 100f))
        val engine = newEngine(fake)
        engine.advance(frames = 1)
        assertEquals(MAX_BASE, engine.debugState().baseDepth, 0.01f)
    }

    @Test
    fun `baseDepth 는 크기 설정에 비례한다`() {
        val half = FakeInputs(settings = ModeSettings(intensity = 50f))
        newEngine(half).also { it.advance(frames = 1) }.let {
            assertEquals(MAX_BASE / 2f, it.debugState().baseDepth, 0.01f)
        }
    }

    @Test
    fun `깊이는 baseDepth 를 넘지 않는다`() {
        // 아주 큰 소리를 길게 넣어 스무딩이 포화되게 만든다.
        val fake = FakeInputs(settings = ModeSettings(intensity = 100f, sensitivity = 100f))
        val engine = newEngine(fake)
        fake.left = 1f
        fake.right = 1f
        engine.advance(frames = 600)

        val state = engine.debugState()
        state.depths.forEachIndexed { i, d ->
            assertTrue("채널 $i 깊이 $d > baseDepth ${state.baseDepth}", d <= state.baseDepth + 0.001f)
            assertTrue("채널 $i 깊이 $d 가 음수", d >= 0f)
        }
    }

    // ---------------------------------------------------------------
    // 진하기 (예전에 슬라이더 방향이 뒤집혀 있었다)
    // ---------------------------------------------------------------

    @Test
    fun `진하기 설정이 알파에 그대로 반영된다`() {
        val fake = FakeInputs(settings = ModeSettings(opacity = 50f))
        val engine = newEngine(fake)
        engine.advance(frames = 1)
        // 값이 클수록 진해야 한다. 1 - opacity/100 으로 뒤집히면 이 테스트가 깨진다.
        assertEquals(0.5f, engine.debugState().alpha, 0.001f)
    }

    @Test
    fun `진하기 100이면 완전히 불투명하다`() {
        val fake = FakeInputs(settings = ModeSettings(opacity = 100f))
        val engine = newEngine(fake)
        engine.advance(frames = 1)
        assertEquals(1f, engine.debugState().alpha, 0.001f)
    }

    @Test
    fun `크기 고정 모드는 고정 크기와 최대 진하기를 쓴다`() {
        val fake = FakeInputs(
            settings = ModeSettings(
                intensityAsOpacity = true,
                opacityFixedSize = 30f,
                opacityFixedMaxOpacity = 100f,
                intensity = 100f,
                opacity = 10f
            )
        )
        val engine = newEngine(fake)
        engine.advance(frames = 1)

        // baseDepth 는 intensity 가 아니라 opacityFixedSize/2 를 쓴다 → 530 * 15/100
        assertEquals(MAX_BASE * 0.15f, engine.debugState().baseDepth, 0.01f)
        // 무음이므로 알파는 0 (최대 진하기 x 볼륨 비율)
        assertEquals(0f, engine.debugState().alpha, 0.001f)
    }

    @Test
    fun `크기 고정 모드의 알파는 소리 크기를 따라 올라간다`() {
        val fake = FakeInputs(
            settings = ModeSettings(
                intensityAsOpacity = true,
                opacityFixedMaxOpacity = 100f,
                sensitivity = 100f
            )
        )
        val engine = newEngine(fake)
        fake.left = 1f
        fake.right = 1f
        engine.advance(frames = 300)
        assertTrue("소리가 크면 알파가 올라야 한다", engine.debugState().alpha > 0.5f)
    }

    // ---------------------------------------------------------------
    // 분류 라벨 연동
    // ---------------------------------------------------------------

    @Test
    fun `표시 꺼진 라벨은 보이지 않는다`() {
        val fake = FakeInputs(settings = ModeSettings(sensitivity = 100f))
        val engine = newEngine(fake)
        fake.left = 1f
        fake.right = 1f
        engine.advance(frames = 120)
        assertTrue("소리가 크면 보여야 한다", engine.debugState().visible)

        fake.shown = false
        engine.advance(frames = 2, startNs = FRAME_60 * 200)
        assertFalse("표시를 끄면 안 보여야 한다", engine.debugState().visible)
    }

    @Test
    fun `라벨 색에서 알파 채널은 제거된다`() {
        val fake = FakeInputs(color = 0xFF123456.toInt())
        val engine = newEngine(fake)
        engine.advance(frames = 1)
        assertEquals(0x123456, engine.debugState().colorRgb)
    }

    // ---------------------------------------------------------------
    // 무효화와 idle
    // ---------------------------------------------------------------

    @Test
    fun `보이지 않게 된 프레임은 한 번 더 그린다`() {
        val fake = FakeInputs(settings = ModeSettings(sensitivity = 100f))
        val engine = newEngine(fake)
        fake.left = 1f
        fake.right = 1f
        var t = engine.advance(frames = 120) + FRAME_60
        assertTrue(engine.debugState().visible)

        // 표시를 끄면 이번 프레임은 지워야 하므로 다시 그려야 한다.
        fake.shown = false
        assertTrue("사라지는 프레임은 무효화해야 화면이 지워진다", engine.tick(t))
        t += FRAME_60
        // 그 다음 프레임부터는 그릴 것이 없다.
        assertFalse("계속 보이지 않으면 무효화하지 않는다", engine.tick(t))
    }

    @Test
    fun `무음이 이어지면 idle 로 내려간다`() {
        val fake = FakeInputs()
        val engine = newEngine(fake)
        var t = FRAME_60
        var frames = 0
        while (!engine.debugState().idle && frames < 300) {
            engine.tick(t)
            t += FRAME_60
            frames++
        }
        assertTrue("무음 1초 뒤에는 idle 이어야 한다 (frames=$frames)", engine.debugState().idle)
        // 1초 = 60 프레임. 그보다 훨씬 이르게 내려가면 안 된다.
        assertTrue("너무 빨리 idle 로 갔다 (frames=$frames)", frames >= 60)
    }

    @Test
    fun `idle 중 소리가 감지되면 깨어난다`() {
        val fake = FakeInputs()
        val engine = newEngine(fake)
        var t = FRAME_60
        repeat(300) {
            engine.tick(t)
            t += FRAME_60
        }
        assertTrue(engine.debugState().idle)

        fake.left = 0.5f
        engine.pollWake()
        assertFalse("소리가 오면 프레임 클럭으로 복귀해야 한다", engine.debugState().idle)
    }

    @Test
    fun `idle 폴링은 조용하면 그대로 머문다`() {
        val fake = FakeInputs()
        val engine = newEngine(fake)
        var t = FRAME_60
        repeat(300) {
            engine.tick(t)
            t += FRAME_60
        }
        assertTrue(engine.debugState().idle)

        engine.pollWake()
        assertTrue("여전히 조용하면 idle 을 유지한다", engine.debugState().idle)
    }

    @Test
    fun `표시가 꺼진 종류의 소리가 계속 나면 대기 상태로 내려간다`() {
        val fake = FakeInputs(shown = false)
        val engine = newEngine(fake)
        fake.left = 0.8f
        fake.right = 0.8f
        engine.advance(frames = 120)
        assertTrue("그릴 것이 없는데 화면 주사율로 계속 돈다", engine.debugState().idle)
    }

    @Test
    fun `진하기가 0이면 소리가 나도 대기 상태로 내려간다`() {
        val fake = FakeInputs(settings = ModeSettings(opacity = 0f))
        val engine = newEngine(fake)
        fake.left = 0.8f
        fake.right = 0.8f
        engine.advance(frames = 120)
        assertTrue(engine.debugState().idle)
    }

    @Test
    fun `대기 중 표시 대상이 아닌 소리로는 깨어나지 않는다`() {
        val fake = FakeInputs(shown = false)
        val engine = newEngine(fake)
        fake.left = 0.8f
        fake.right = 0.8f
        engine.advance(frames = 120)
        assertTrue(engine.debugState().idle)

        engine.pollWake()
        assertTrue("표시가 꺼진 소리에 깨어났다", engine.debugState().idle)
    }

    @Test
    fun `대기 중 표시 대상이 되면 깨어난다`() {
        val fake = FakeInputs(shown = false)
        val engine = newEngine(fake)
        fake.left = 0.8f
        fake.right = 0.8f
        engine.advance(frames = 120)
        assertTrue(engine.debugState().idle)

        fake.shown = true
        engine.pollWake()
        assertFalse("표시를 켰는데 깨어나지 않았다", engine.debugState().idle)
    }

    // ---------------------------------------------------------------
    // 늦게 온 위협음 판정: 숨긴 라벨로 먼저 들어온 짧은 소리
    // ---------------------------------------------------------------

    @Test
    fun `숨긴 라벨로 지나간 짧은 소리는 늦게 온 위협음 판정에 그 크기로 보인다`() {
        val (engine, fake) = hiddenAmbientIdle()

        // 총성 한 번(33ms) 뒤로는 약한 잔향만 남는다. 아직 라벨은 숨긴 환경음이다.
        fake.left = 0.9f
        fake.right = 0.9f
        engine.pollWake()
        fake.left = 0.03f
        fake.right = 0.03f
        engine.poll(10)
        assertTrue("숨긴 라벨의 소리에 깨어났다", engine.debugState().idle)

        // 약 0.36초 뒤 위협음 판정이 도착한다.
        fake.becomeDanger()
        engine.pollWake()
        assertFalse("위협음 판정이 왔는데 깨어나지 않았다", engine.debugState().idle)

        engine.advance(frames = 3, startNs = FRAME_60 * 1000)
        val state = engine.debugState()
        assertTrue("잔향만으로 그려서 보이지 않는다 (smoothTotal=${state.smoothTotal})", state.visible)
        // 잔향(0.03)으로 0 에서 자라면 0.01 도 안 된다. 보관한 0.9 의 크기(3.42)에서 시작해야 한다.
        assertTrue("보관한 피크 크기로 시작하지 않았다 (${state.smoothTotal})", state.smoothTotal > 2f)
    }

    @Test
    fun `위협음 판정이 소리가 끝난 뒤에 와도 보관한 방향으로 보인다`() {
        val (engine, fake) = hiddenAmbientIdle()

        // 왼쪽에서 난 짧은 소리. 판정이 올 때는 완전히 조용하다.
        fake.left = 0.6f
        fake.right = 0.2f
        engine.pollWake()
        fake.left = 0f
        fake.right = 0f
        engine.poll(15)

        fake.becomeDanger()
        engine.pollWake()
        assertFalse("소리가 끝났다고 위협음 판정을 무시했다", engine.debugState().idle)

        engine.advance(frames = 3, startNs = FRAME_60 * 1000)
        val state = engine.debugState()
        assertTrue(state.visible)
        // 인덱스 6 = SL(좌측 중앙), 2 = SR(우측 중앙)
        assertTrue(
            "좌측(${state.depths[6]}) 이 우측(${state.depths[2]}) 보다 깊어야 한다",
            state.depths[6] > state.depths[2]
        )
    }

    @Test
    fun `위협음 라벨이어도 숨겨서 놓친 소리가 없으면 보통처럼 서서히 커진다`() {
        // 위협음을 보이는 중에 조용해서 대기로 내려갔다가 소리가 난다. 보통의 깨어남이다.
        val fake = FakeInputs(label = AiClassification.DANGER, shown = true)
        val engine = newEngine(fake)
        engine.tickUntilIdle()
        fake.left = 0.9f
        fake.right = 0.9f
        engine.pollWake()
        assertFalse(engine.debugState().idle)
        engine.tick(FRAME_60 * 1000)
        val woken = engine.debugState().smoothTotal

        // 처음 켠 엔진이 같은 소리를 첫 프레임에 받았을 때와 같아야 한다.
        val freshFake = FakeInputs(label = AiClassification.DANGER, shown = true)
        val fresh = newEngine(freshFake)
        freshFake.left = 0.9f
        freshFake.right = 0.9f
        fresh.tick(FRAME_60)

        assertEquals(fresh.debugState().smoothTotal, woken, 0.0001f)
        assertTrue("보통의 깨어남이 한 번에 커졌다 ($woken)", woken < 0.5f)
    }

    @Test
    fun `숨긴 소리가 끝나고 위협음이 아닌 라벨로 돌아오면 보관한 피크를 쓰지 않는다`() {
        // 대화음을 숨긴 채 말소리가 지나가고, 끝난 뒤 라벨이 표시 중인 환경음으로 돌아온다.
        val fake = FakeInputs(label = AiClassification.SPEECH, shown = false)
        val engine = newEngine(fake)
        engine.tickUntilIdle()
        fake.left = 0.7f
        fake.right = 0.7f
        engine.poll(5)
        fake.left = 0f
        fake.right = 0f
        engine.poll(5)

        fake.label = AiClassification.AMBIENT
        fake.shown = true
        engine.pollWake()
        assertTrue("지나간 말소리가 환경음으로 그려졌다", engine.debugState().idle)
    }

    @Test
    fun `보관한 피크는 약 1초 뒤 사라진다`() {
        fun wakesAfter(quietPolls: Int): Boolean {
            val (engine, fake) = hiddenAmbientIdle()
            fake.left = 0.9f
            fake.right = 0.9f
            engine.pollWake()
            fake.left = 0f
            fake.right = 0f
            engine.poll(quietPolls)
            fake.becomeDanger()
            engine.pollWake()
            return !engine.debugState().idle
        }
        // 폴링 한 번이 33ms 다.
        assertTrue("0.8초 전 소리를 벌써 잊었다", wakesAfter(24))
        assertFalse("1.2초 전 소리로 깨어났다", wakesAfter(36))
    }

    @Test
    fun `대기로 내려가기 직전에 숨긴 라벨로 받은 소리도 위협음 판정에 쓴다`() {
        val fake = FakeInputs(label = AiClassification.AMBIENT, shown = false)
        val engine = newEngine(fake)
        // 보이지 않은 지 1초가 안 되어 아직 프레임 루프다.
        var t = engine.advance(frames = 50) + FRAME_60
        assertFalse(engine.debugState().idle)

        fake.left = 0.9f
        fake.right = 0.9f
        engine.tick(t)
        t += FRAME_60
        fake.left = 0f
        fake.right = 0f
        t = engine.tickUntilIdle(startNs = t)

        fake.becomeDanger()
        engine.pollWake()
        assertFalse("대기로 내려가며 직전 소리를 잊었다", engine.debugState().idle)
        engine.advance(frames = 3, startNs = t)
        assertTrue(engine.debugState().visible)
    }

    @Test
    fun `깨어 있는 중 위협음 판정이 오면 보관한 크기로 커지고 같은 소리로 두 번 번쩍이지 않는다`() {
        val fake = FakeInputs(label = AiClassification.AMBIENT, shown = false)
        val engine = newEngine(fake)
        var t = engine.advance(frames = 10) + FRAME_60

        fake.left = 0.9f
        fake.right = 0.9f
        engine.tick(t)
        t += FRAME_60
        fake.left = 0f
        fake.right = 0f
        t = engine.advance(frames = 10, startNs = t) + FRAME_60
        val beforeLabel = engine.debugState().smoothTotal

        fake.becomeDanger()
        engine.tick(t)
        t += FRAME_60
        val state = engine.debugState()
        assertTrue(state.visible)
        assertTrue(
            "보관한 크기로 커지지 않았다 ($beforeLabel -> ${state.smoothTotal})",
            state.smoothTotal > 2f
        )

        // 다 사라지고 대기로 내려간 뒤, 라벨이 그대로여도 이미 그린 소리로 다시 깨어나면 안 된다.
        engine.tickUntilIdle(startNs = t)
        engine.pollWake()
        assertTrue("이미 그린 소리로 다시 깨어났다", engine.debugState().idle)
    }

    // ---------------------------------------------------------------
    // 시간 정규화: 프레임 간격이 달라도 같은 시간에 같은 결과가 나와야 한다
    // ---------------------------------------------------------------

    @Test
    fun `프레임 간격이 두 배여도 같은 시간 뒤 스무딩 결과가 같다`() {
        // 공간 리플은 틱 수 기반 히스토리라 간격이 다르면 값이 달라진다. 여기서는 끈다.
        val settings = ModeSettings(useRippleDelay = false, sensitivity = 40f, speed = 30f)

        val fastFake = FakeInputs(settings = settings)
        val fast = newEngine(fastFake)
        fastFake.left = 0.8f
        fastFake.right = 0.4f
        // 첫 틱은 양쪽 모두 k=1 이다. 그 뒤로 fast 는 1배 20번, slow 는 2배 10번 →
        // 둘 다 60fps 기준 21 프레임 분량이 되어 정확히 같은 값이어야 한다.
        fast.advance(frames = 21, stepNs = FRAME_60)

        val slowFake = FakeInputs(settings = settings)
        val slow = newEngine(slowFake)
        slowFake.left = 0.8f
        slowFake.right = 0.4f
        slow.advance(frames = 11, stepNs = FRAME_60 * 2)

        val a = fast.debugState().smoothTotal
        val b = slow.debugState().smoothTotal
        assertTrue("스무딩이 진행되지 않았다", a > 0f)
        assertEquals("프레임 간격에 따라 결과가 달라진다: $a vs $b", a, b, a * 0.005f)
    }

    @Test
    fun `민감도가 높으면 더 빨리 반응한다`() {
        fun smoothAfter(sensitivity: Float): Float {
            val fake = FakeInputs(settings = ModeSettings(sensitivity = sensitivity))
            val engine = newEngine(fake)
            fake.left = 1f
            fake.right = 1f
            engine.advance(frames = 10)
            return engine.debugState().smoothTotal
        }
        val slow = smoothAfter(5f)
        val quick = smoothAfter(80f)
        assertTrue("민감도 80($quick) 이 5($slow) 보다 빨라야 한다", quick > slow)
    }

    // ---------------------------------------------------------------
    // 모드
    // ---------------------------------------------------------------

    @Test
    fun `네 모드 모두 큰 소리에서 보인다`() {
        VisualMode.values().forEach { mode ->
            val fake = FakeInputs(mode = mode, settings = ModeSettings(sensitivity = 100f))
            val engine = newEngine(fake)
            fake.left = 1f
            fake.right = 0.2f
            engine.advance(frames = 200)
            assertTrue("$mode 가 큰 소리에서 보이지 않는다", engine.debugState().visible)
        }
    }

    @Test
    fun `모드마다 다른 설정을 읽는다`() {
        val fake = FakeInputs(
            settingsProvider = { mode ->
                if (mode == VisualMode.Wave) ModeSettings(intensity = 100f)
                else ModeSettings(intensity = 20f)
            }
        )
        val engine = newEngine(fake)

        fake.mode = VisualMode.Wave
        engine.advance(frames = 1)
        val wave = engine.debugState().baseDepth

        fake.mode = VisualMode.Pad
        engine.advance(frames = 2, startNs = FRAME_60 * 100)
        val pad = engine.debugState().baseDepth

        assertTrue("모드를 바꿨는데 baseDepth 가 같다 ($wave)", wave != pad)
        assertEquals(MAX_BASE, wave, 0.01f)
        assertEquals(MAX_BASE * 0.2f, pad, 0.01f)
    }

    @Test
    fun `무음에서는 보이지 않는다`() {
        val fake = FakeInputs()
        val engine = newEngine(fake)
        engine.advance(frames = 10)
        assertFalse(engine.debugState().visible)
    }

    // ---------------------------------------------------------------
    // 방향
    // ---------------------------------------------------------------

    @Test
    fun `왼쪽에서 소리가 나면 왼쪽 채널이 더 깊다`() {
        val fake = FakeInputs(settings = ModeSettings(sensitivity = 60f, useRippleDelay = false))
        val engine = newEngine(fake)
        fake.left = 1f
        fake.right = 0.05f
        engine.advance(frames = 300)

        val depths = engine.debugState().depths
        // 인덱스 6 = SL(좌측 중앙), 2 = SR(우측 중앙)
        assertTrue(
            "좌측(${depths[6]}) 이 우측(${depths[2]}) 보다 깊어야 한다",
            depths[6] > depths[2]
        )
    }

    @Test
    fun `좌우가 같으면 좌우 사이드 채널이 대칭이다`() {
        val fake = FakeInputs(settings = ModeSettings(sensitivity = 60f, useRippleDelay = false))
        val engine = newEngine(fake)
        fake.left = 0.7f
        fake.right = 0.7f
        engine.advance(frames = 300)

        val depths = engine.debugState().depths
        assertEquals("좌우 사이드가 비대칭", depths[6], depths[2], 0.001f)
        assertEquals("좌우 프론트가 비대칭", depths[7], depths[1], 0.001f)
    }
}

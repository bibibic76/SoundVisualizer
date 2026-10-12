package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

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

/**
 * 위협음을 표시 중인 엔진이 같은 소리를 제때 받았을 때 평소 경로가 도달하는 가장 큰 전체 크기.
 * [loudFrames] 프레임 동안 [loud], 이어서 [tailFrames] 프레임 동안 [tail] 을 좌우에 같게 넣는다.
 */
private fun onTimePeakSmoothTotal(loud: Float, loudFrames: Int, tail: Float, tailFrames: Int): Float {
    val fake = FakeInputs(label = AiClassification.DANGER, shown = true)
    val engine = newEngine(fake)
    var t = FRAME_60
    var peak = 0f
    repeat(loudFrames + tailFrames) { n ->
        val v = if (n < loudFrames) loud else tail
        fake.left = v
        fake.right = v
        engine.tick(t)
        t += FRAME_60
        peak = maxOf(peak, engine.debugState().smoothTotal)
    }
    return peak
}

/** 가장자리 띠 시험의 화면: 너비, 높이, 배율. */
private data class BandSurface(val width: Float, val height: Float, val density: Float) {
    override fun toString() = "${width.toInt()}x${height.toInt()}(배율 $density)"
}

/**
 * 가장자리 띠 시험의 화면: 배율 1 의 세로·가로와 흔한 폰(1080×2400, 420dpi)의 세로·가로.
 * 1080×1920 은 파도 점이 네 모서리에서 똑같이 반 칸씩 떨어지고, 1080×2400 은 모서리마다 양쪽 점까지의 거리가 달라
 * 모서리를 가로지르는 조각이 한쪽 변에 붙는다.
 */
private val BAND_SURFACES = listOf(
    BandSurface(W, H, DENSITY), BandSurface(H, W, DENSITY),
    BandSurface(1080f, 2400f, 2.625f), BandSurface(2400f, 1080f, 2.625f)
)

/** 외곽선 두께(dp). 엔진의 4dp 와 같다. */
private const val OUTLINE_STROKE_DP = 4f

/** 띠 시험에서 켜는 발광 세기. */
private const val BAND_GLOW_INTENSITY = 50f

/** 띠의 변 이름. [VisualizerEngine.SIDE_TOP] 등의 번호 순서다. */
private val SIDE_NAMES = listOf("위", "오른쪽", "아래", "왼쪽")

/** 변 [side] 에서 안쪽으로 잰 거리. 엔진의 식을 빌리지 않고 여기에 따로 적는다. */
private fun sideDistance(side: Int, x: Float, y: Float, width: Float, height: Float): Float = when (side) {
    VisualizerEngine.SIDE_TOP -> y
    VisualizerEngine.SIDE_RIGHT -> width - x
    VisualizerEngine.SIDE_BOTTOM -> height - y
    VisualizerEngine.SIDE_LEFT -> x
    else -> error("변 번호가 아니다: $side")
}

/** 띠를 재며 짚은 점을 변마다 모은다: 그 변에 맡긴 점이 그 변에서 들어온 가장 깊은 거리. 맡긴 점이 없는 변은 -1. */
private class SideSamples(private val width: Float, private val height: Float) {
    val deepest = FloatArray(4) { -1f }
    fun add(side: Int, x: Float, y: Float) {
        val d = sideDistance(side, x, y, width, height)
        if (d > deepest[side]) deepest[side] = d
    }
}

/** 띠 네 개의 넓이(px²). 위·아래 띠는 너비 전체, 왼쪽·오른쪽 띠는 그 사이 높이다. 매 프레임 래스터화하는 마스크의 크기다. */
private fun bandArea(thickness: FloatArray, width: Float, height: Float): Float {
    val top = thickness[VisualizerEngine.SIDE_TOP]
    val bottom = thickness[VisualizerEngine.SIDE_BOTTOM]
    val sides = thickness[VisualizerEngine.SIDE_LEFT] + thickness[VisualizerEngine.SIDE_RIGHT]
    return width * (top + bottom) + sides * (height - top - bottom)
}

/** #365 전처럼 네 띠가 가장 두꺼운 변의 두께 하나를 쓸 때의 넓이. */
private fun sharedBandArea(thickness: FloatArray, width: Float, height: Float): Float {
    val t = thickness.max()
    return bandArea(floatArrayOf(t, t, t, t), width, height)
}

/** 발광 세기 [glowIntensity] 의 블러 반경(px). 세기 × 0.5dp 이고 1dp 보다 작아지지 않는다. */
private fun glowRadiusPx(glowIntensity: Float, density: Float): Float = maxOf(1f, glowIntensity * 0.5f) * density

/** 가장자리 띠 시험에 넣는 소리: [frames] 프레임 동안 [level] 이 프레임마다 돌려주는 (좌, 우) 크기. */
private class BandSound(val name: String, val frames: Int, val level: (frame: Int) -> Pair<Float, Float>)

/**
 * 정해 둔 소리 아홉 가지와 무작위 소리 [randomCount] 개.
 * 뒤쪽만 들리는 모양은 소리를 끊은 직후에 나온다. 앞 채널은 바로 0 이 되고 뒤 채널은 몇 틱 늦게 따라오기 때문이다.
 */
private fun bandSounds(rnd: java.util.Random, randomCount: Int = 20): List<BandSound> {
    val fixed = listOf(
        // 모든 채널이 가장 깊이 들어와 둥근 모서리가 가장 깊다.
        BandSound("양쪽 최대", 300) { 1f to 1f },
        BandSound("가운데 작게", 300) { 0.05f to 0.05f },
        BandSound("가운데 보통", 300) { 0.15f to 0.15f },
        BandSound("왼쪽으로 치우침", 300) { 0.3f to 0.1f },
        BandSound("오른쪽으로 치우침", 300) { 0.1f to 0.3f },
        BandSound("왼쪽만", 300) { 1f to 0f },
        BandSound("오른쪽만", 300) { 0f to 1f },
        BandSound("뒤쪽만", 122) { f -> if (f < 120) 0.8f to 0.8f else 0f to 0f },
        BandSound("뒤쪽 왼쪽만", 122) { f -> if (f < 120) 1f to 0.3f else 0f to 0f }
    )
    val random = List(randomCount) { n ->
        val frames = 30 + rnd.nextInt(90)
        val levels = FloatArray(frames * 2) { rnd.nextFloat() }
        BandSound("무작위 $n", frames) { f -> levels[2 * f] to levels[2 * f + 1] }
    }
    return fixed + random
}

/**
 * 가장자리 띠 시험의 설정. 소리를 바로 따라가게 해 정해 둔 소리의 모양이 그대로 나오게 한다.
 * 발광 세기 [glowIntensity] 의 반경은 [glowRadiusPx] 다.
 */
private fun bandSettings(intensity: Float, glow: Boolean, glowIntensity: Float = BAND_GLOW_INTENSITY) = ModeSettings(
    intensity = intensity,
    sensitivity = 100f,
    speed = 100f,
    isGlowMode = glow,
    glowIntensity = if (glow) glowIntensity else 0f
)

/**
 * 반경 [radiusPx] 인 블러의 커널이 도형 밖으로 닿는 거리(px). Android 는 반경을 시그마 = 0.57735 × 반경 + 0.5 로
 * 바꾸고, Skia 의 커널은 3시그마를 올림한 거리까지 닿는다. 엔진의 식을 빌리지 않고 여기에 따로 적는다.
 */
private fun blurKernelReach(radiusPx: Float): Float = ceil(3f * (0.57735f * radiusPx + 0.5f))

/** [sound] 를 끝까지 넣은 엔진. */
private fun engineAfter(
    sound: BandSound,
    mode: VisualMode,
    settings: ModeSettings,
    width: Float,
    height: Float,
    density: Float = DENSITY
): VisualizerEngine {
    val fake = FakeInputs(mode = mode, settings = settings)
    val engine = VisualizerEngine(density, fake).also { it.setSurfaceSize(width, height) }
    var t = FRAME_60
    repeat(sound.frames) { f ->
        val (left, right) = sound.level(f)
        fake.left = left
        fake.right = right
        engine.tick(t)
        t += FRAME_60
    }
    return engine
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

    // ---------------------------------------------------------------
    // 가장자리 띠로 잘라 그리기 (#172, #350, #365): 잘라 낸 곳에 그릴 것이 없어야 한다
    // ---------------------------------------------------------------

    /**
     * [band] 를 잴 때 짚은 점([samples])이 저마다 맡긴 변의 띠 안인지 본다. 점마다 [margin] 만큼 더 번지는 몫까지 띠 안이어야 한다.
     * 띠 네 개를 합친 곳은 "어느 한 변에서 그 변의 두께 안"인 점 전체와 같으므로, 맡긴 변의 띠 안이면 잘리지 않는다.
     * 맡긴 변에서 잰 깊이는 엔진이 그 변의 경계로 잡은 값 이하여야 한다(볼록 껍질). 차이는 부동소수 오차만 둔다.
     */
    private fun assertInSideBands(where: String, band: VisualizerEngine.DebugBand, samples: SideSamples, margin: Float) {
        for (side in 0 until 4) {
            val deepest = samples.deepest[side]
            if (deepest < 0f) continue // 이 변에 맡긴 점이 없다.
            val name = SIDE_NAMES[side]
            assertTrue(
                "$where: $name 변에 맡긴 도형이 ${deepest}px 들어왔는데 그 변의 경계는 ${band.bound[side]}px",
                deepest <= band.bound[side] + 0.01f
            )
            if (!band.clipped) continue
            assertTrue(
                "$where: $name 변에 맡긴 도형이 ${deepest}px 들어오고 ${margin}px 더 번지는데 그 변의 띠는 ${band.thickness[side]}px",
                deepest + margin <= band.thickness[side] + 0.01f
            )
        }
    }

    @Test
    fun `파도와 외곽선 곡선은 조각을 맡긴 변의 띠 밖으로 나가지 않는다`() {
        // 화면 방향·크기·발광·소리를 바꿔 가며, 그릴 때와 같은 곡선을 점 사이까지 촘촘히 짚는다.
        // 띠를 좁게 잡으면 파도 안쪽이나 둥근 모서리가 잘려 보인다. 띠는 변마다 두께가 달라서(#365), 점마다 그 조각을 맡긴 변의 띠를 본다.
        val sounds = bandSounds(java.util.Random(172))
        var checked = 0
        for (surface in BAND_SURFACES) for (mode in listOf(VisualMode.Wave, VisualMode.Outline))
            for (intensity in listOf(10f, 50f, 100f)) for (glow in listOf(false, true)) for (sound in sounds) {
                val (width, height, density) = surface
                val engine = engineAfter(sound, mode, bandSettings(intensity, glow), width, height, density)
                val samples = SideSamples(width, height)
                val band = engine.debugWaveBand(samplesPerSegment = 16, onSample = samples::add)
                if (band.clipped && band.deepestSample > 0f) checked++
                // 안티앨리어싱으로 번지는 1px 까지 띠 안에 있어야 한다. 외곽선은 선이 곡선 양쪽으로 두께의 절반씩 나가고,
                // 발광은 블러가 번지는 폭만큼 더 나간다.
                val halfStroke = if (mode == VisualMode.Outline) OUTLINE_STROKE_DP * density / 2f else 0f
                val glowSpread = if (glow) VisualizerEngine.glowSpread(glowRadiusPx(BAND_GLOW_INTENSITY, density)) else 0f
                val where = "$mode $surface 크기 $intensity 발광 $glow ${sound.name}"
                assertInSideBands(where, band, samples, margin = 1f + halfStroke + glowSpread)
            }
        assertTrue("잘라 그린 파도가 거의 없어 확인한 것이 적다 ($checked)", checked > 1100)
    }

    @Test
    fun `파도 점만으로 띠를 재면 점 사이의 곡선이 띠 밖으로 나간다`() {
        // 조절점으로 띠를 재는 이유(#350). 점으로만 재고 안티앨리어싱 1px 만 더하면 곡선이 잘린다.
        // 예전에는 점 사이의 몫을 8dp 로 어림해 더했다.
        val cases = listOf(
            // 곡선이 가장 깊은 점보다 3px 넘게 더 부푼다.
            BandSound("왼쪽만", 300) { 1f to 0f } to 50f,
            // 소리가 없는 모서리에서는 점이 모서리 양쪽 변에 놓이고, 그 사이 곡선이 모서리를 가로질러
            // 가장 깊은 점(약 3px)보다 깊이(약 9px) 들어온다.
            BandSound("가운데 작게", 300) { 0.05f to 0.05f } to 10f
        )
        for ((sound, intensity) in cases) {
            val samples = SideSamples(W, H)
            val band = engineAfter(sound, VisualMode.Wave, bandSettings(intensity, glow = false), W, H)
                .debugWaveBand(samplesPerSegment = 16, onSample = samples::add)
            val pointsOnly = ceil(band.deepestPoint + 1f)
            assertTrue(
                "${sound.name}: 곡선(${band.deepestSample}px)이 점으로만 잰 띠(${pointsOnly}px) 안에 든다",
                band.deepestSample + 1f > pointsOnly
            )
            assertTrue("${sound.name}: 자르지 않고 그린다", band.clipped)
            assertInSideBands(sound.name, band, samples, margin = 1f)
        }
    }

    @Test
    fun `패드 막대는 놓인 변의 띠 밖으로 나가지 않는다`() {
        // 막대는 직선이고 안쪽 점은 그 막대의 두께만큼만 들어오므로, 막대가 놓인 변의 띠는 그 변의 가장 두꺼운 막대에
        // 안티앨리어싱 1px 만 더하면 된다(#365). 모서리 막대는 앞 절반과 뒤 절반이 이웃한 두 변에 놓인다.
        // 막대 둘레를 선마다 짚어, 모서리를 가로질러 더 깊이 들어오는 선이 없는지도 본다.
        val sounds = bandSounds(java.util.Random(173))
        var checked = 0
        for (surface in BAND_SURFACES) for (intensity in listOf(10f, 50f, 100f))
            for (glow in listOf(false, true)) for (sound in sounds) {
                val (width, height, density) = surface
                val engine = engineAfter(sound, VisualMode.Pad, bandSettings(intensity, glow), width, height, density)
                val samples = SideSamples(width, height)
                val band = engine.debugPadBand(samplesPerEdge = 8, onSample = samples::add)
                if (band.deepestPoint <= 0f) continue // 보이는 막대가 없다.
                if (band.clipped) checked++
                val glowSpread = if (glow) VisualizerEngine.glowSpread(glowRadiusPx(BAND_GLOW_INTENSITY, density)) else 0f
                assertInSideBands("$surface 크기 $intensity 발광 $glow ${sound.name}", band, samples, margin = 1f + glowSpread)
            }
        assertTrue("잘라 그린 패드가 거의 없어 확인한 것이 적다 ($checked)", checked > 600)
    }

    @Test
    fun `세로가 가로의 4배를 넘는 화면에서는 패드를 자르지 않는다`() {
        // 위·아래 가운데 막대(길이 h/4)가 변을 넘어 모서리를 돌면, 막대의 바깥 선이 모서리를 가로질러
        // 가장 두꺼운 막대보다 깊이 들어온다. 그런 화면은 없지만, 있으면 막대 두께로 잘라서는 안 된다.
        val fake = FakeInputs(mode = VisualMode.Pad, settings = ModeSettings(intensity = 10f, sensitivity = 100f))
        val engine = VisualizerEngine(DENSITY, fake).also { it.setSurfaceSize(300f, 1500f) }
        fake.left = 0.3f
        fake.right = 0.3f
        engine.advance(frames = 120)

        val band = engine.debugPadBand(samplesPerEdge = 8)
        assertTrue("보이는 막대가 없어 확인한 것이 없다", band.deepestPoint > 0f)
        assertTrue(
            "모서리를 가로지르는 선(${band.deepestSample}px)이 막대 두께(${band.deepestPoint}px)보다 깊지 않다",
            band.deepestSample > band.deepestPoint + 1f
        )
        assertFalse("자르지 않고 그려야 한다 (${band.thickness.toList()})", band.clipped)
    }

    @Test
    fun `발광이 번지는 폭은 반경이 작으면 블러 커널이 닿는 거리에 1px 을 더하고 크면 반경의 3배다`() {
        // 반경 0.5·1·2px 의 블러 커널은 3·4·5px 까지 닿는다. 예전처럼 반경의 3배(1.5·3·6px)만 더하면 앞의 둘은 모자란다.
        assertEquals(4f, VisualizerEngine.glowSpread(0.5f), 0f)
        assertEquals(5f, VisualizerEngine.glowSpread(1f), 0f)
        assertEquals(6f, VisualizerEngine.glowSpread(2f), 0f)
        // 420dpi(배율 2.625) 화면의 가장 작은 반경(발광 세기 2 이하, 1dp)이다. 커널(7px) + 1px 이 3배(7.875px)보다 조금 넓다.
        assertEquals(8f, VisualizerEngine.glowSpread(2.625f), 0f)
        // 반경이 크면 반경의 3배 그대로다. 65.625·131.25px 은 배율 2.625 에서 발광 세기 50·100 의 반경이다.
        assertEquals(24f, VisualizerEngine.glowSpread(8f), 0f)
        assertEquals(196.875f, VisualizerEngine.glowSpread(65.625f), 0f)
        assertEquals(393.75f, VisualizerEngine.glowSpread(131.25f), 0f)
    }

    @Test
    fun `발광이 번지는 폭은 블러가 늦게 바뀌어도 커널을 덮고 예전의 반경 3배보다 좁지 않다`() {
        // 엔진은 반경이 0.5px 넘게 바뀔 때만 블러를 새로 만든다. 그 사이 그리는 블러의 반경은 0.5px 까지 크다.
        // 반경은 발광 세기 × 0.5dp 라 배율 4 화면의 세기 100 이면 200px 이다.
        for (centiPx in 25..20_000) {
            val r = centiPx / 100f
            val spread = VisualizerEngine.glowSpread(r)
            val reach = blurKernelReach(r + 0.5f)
            assertTrue("반경 ${r}px: 번지는 폭 ${spread}px 이 늦게 바뀐 커널이 닿는 거리 ${reach}px 보다 좁다", spread >= reach)
            assertTrue("반경 ${r}px: 번지는 폭 ${spread}px 이 반경의 3배보다 좁다", spread >= 3f * r)
        }
    }

    @Test
    fun `반경이 작은 발광도 블러가 번지는 곳까지 잘라 그릴 띠 안에 든다`() {
        // 이 시험의 화면은 배율 1 이라 발광 세기 1 의 반경은 1dp = 1px 이다. 블러 커널은 4px, 블러가 늦게 바뀌어
        // 반경이 1.5px 이면 5px 까지 닿는다. 예전처럼 반경의 3배(3px)만 더하면 발광 끝이 띠 밖에서 잘린다.
        // 패드와 큰 소리의 파도는 조절점으로 잰 깊이가 도형에 꼭 맞아, 띠가 모자라면 바로 드러난다.
        val settings = bandSettings(intensity = 50f, glow = true, glowIntensity = 1f)
        val reach = blurKernelReach(1f + 0.5f)
        val sounds = bandSounds(java.util.Random(350), randomCount = 5)
        var checked = 0
        for (surface in BAND_SURFACES.filter { it.density == 1f })
            for (mode in listOf(VisualMode.Wave, VisualMode.Outline, VisualMode.Pad)) for (sound in sounds) {
                val (width, height) = surface
                val engine = engineAfter(sound, mode, settings, width, height)
                val samples = SideSamples(width, height)
                val band = if (mode == VisualMode.Pad) engine.debugPadBand(samplesPerEdge = 8, onSample = samples::add)
                else engine.debugWaveBand(samplesPerSegment = 16, onSample = samples::add)
                if (!band.clipped || band.deepestSample <= 0f) continue // 자르지 않거나 보이는 도형이 없다.
                checked++
                // 도형 끝이 걸친 픽셀(안티앨리어싱 1px)에서 커널이 닿는 곳까지, 도형을 맡긴 변의 띠 안이어야 한다.
                // 외곽선은 선 두께의 절반이 더 나간다.
                val halfStroke = if (mode == VisualMode.Outline) OUTLINE_STROKE_DP / 2f else 0f
                assertInSideBands("$mode $surface ${sound.name}", band, samples, margin = halfStroke + 1f + reach)
            }
        assertTrue("잘라 그린 경우가 적어 확인한 것이 적다 ($checked)", checked > 70)
    }

    @Test
    fun `소리가 한쪽에서만 크면 조용한 변의 띠가 얇아져 띠 넓이가 한 두께로 그릴 때보다 준다`() {
        // #365 전에는 네 띠가 가장 깊은 변의 두께 하나를 썼다. 그 두께로 돌아가면 조용한 변도 깊은 변만큼 두꺼워져 여기서 실패한다.
        // 흔한 폰 화면에서 띠 네 개의 넓이, 곧 매 프레임 래스터화하는 마스크의 크기를 한 두께로 그릴 때와 견준다.
        // 기준은 잰 값보다 넉넉히 낮게 잡았다. 세로 화면에서 파도는 왼쪽만 41%, 왼쪽으로 치우침 29%, 뒤쪽만 80% 줄었다.
        class Case(val sound: BandSound, val quiet: Int, val loud: Int, val minSaving: Float)
        val cases = listOf(
            Case(BandSound("왼쪽만", 300) { 0.3f to 0f }, VisualizerEngine.SIDE_RIGHT, VisualizerEngine.SIDE_LEFT, 0.2f),
            Case(BandSound("오른쪽만", 300) { 0f to 0.3f }, VisualizerEngine.SIDE_LEFT, VisualizerEngine.SIDE_RIGHT, 0.2f),
            Case(BandSound("왼쪽으로 치우침", 300) { 0.3f to 0.1f }, VisualizerEngine.SIDE_RIGHT, VisualizerEngine.SIDE_LEFT, 0.1f),
            Case(BandSound("뒤쪽만", 303) { f -> if (f < 300) 0.4f to 0.4f else 0f to 0f }, VisualizerEngine.SIDE_TOP, VisualizerEngine.SIDE_BOTTOM, 0.4f)
        )
        for (surface in BAND_SURFACES.filter { it.density > 1f }) for (mode in listOf(VisualMode.Wave, VisualMode.Outline, VisualMode.Pad))
            for (case in cases) {
                val (width, height, density) = surface
                val engine = engineAfter(case.sound, mode, bandSettings(intensity = 50f, glow = false), width, height, density)
                val band = if (mode == VisualMode.Pad) engine.debugPadBand(samplesPerEdge = 1) else engine.debugWaveBand(samplesPerSegment = 1)
                val where = "$mode $surface ${case.sound.name} (위·오른쪽·아래·왼쪽 ${band.thickness.toList()})"
                assertTrue("$where: 자르지 않고 그린다", band.clipped)
                assertTrue(
                    "$where: 조용한 ${SIDE_NAMES[case.quiet]} 띠가 ${SIDE_NAMES[case.loud]} 띠보다 얇지 않다",
                    band.thickness[case.quiet] < band.thickness[case.loud]
                )
                val saving = 1f - bandArea(band.thickness, width, height) / sharedBandArea(band.thickness, width, height)
                assertTrue(
                    "$where: 띠 넓이가 한 두께로 그릴 때보다 ${"%.1f".format(saving * 100f)}% 줄었다 (기준 ${case.minSaving * 100f}%)",
                    saving >= case.minSaving
                )
            }
    }

    @Test
    fun `소리가 없는 모서리를 가로지르는 조각은 조용한 변의 띠를 두껍게 하지 않는다`() {
        // 뒤쪽만 들리면 위·왼쪽·오른쪽 변의 깊이는 0 이고, 네 모서리에서는 모서리 양쪽 변의 파도 점을 잇는 조각만 모서리를
        // 가로질러 들어온다. 그 조각의 껍질은 두 변 쪽으로 모두 걸치고, 모서리에서 가까운 점이 놓인 변 쪽으로 얕다.
        // - 1080×2400 은 네 모서리 모두 왼쪽·오른쪽 변의 점이 모서리에서 16.8px, 위·아래 변의 점이 29.6px 떨어져 있어,
        //   모서리 조각의 껍질은 위·아래 변에 붙는다(16.8px). 이 조각을 시작점이 놓인 변이나 껍질이 먼 변에 맡기면
        //   조용한 왼쪽·오른쪽 띠가 31px 로 두꺼워진다.
        // - 1080×1920 은 모서리마다 양쪽 점이 똑같이 20px 떨어져, 껍질이 두 변에 똑같이 붙는다. 껍질만 보고 맡기면 아래 오른쪽
        //   모서리 조각이 오른쪽에 가서 조용한 오른쪽 띠가 21px 이 된다. 아래 띠가 이미 덮으므로 그쪽에 맡겨야 한다.
        // 어느 쪽에 맡기든 그 변에서 잰 껍질 값을 쓰면 경계는 맞다. 그래서 띠 안인지와 함께 띠 두께도 본다.
        val rearOnly = BandSound("뒤쪽만", 303) { f -> if (f < 300) 0.4f to 0.4f else 0f to 0f }
        val surfaces = BAND_SURFACES.filter { it.density > 1f } + BandSurface(W, H, DENSITY)
        for (surface in surfaces) for (mode in listOf(VisualMode.Wave, VisualMode.Outline)) {
            val (width, height, density) = surface
            val engine = engineAfter(rearOnly, mode, bandSettings(intensity = 50f, glow = false), width, height, density)
            val samples = SideSamples(width, height)
            val band = engine.debugWaveBand(samplesPerSegment = 16, onSample = samples::add)
            val stroke = if (mode == VisualMode.Outline) OUTLINE_STROKE_DP * density else 0f
            val where = "$mode $surface (위·오른쪽·아래·왼쪽 ${band.thickness.toList()})"
            assertTrue("$where: 자르지 않고 그린다", band.clipped)
            assertInSideBands(where, band, samples, margin = 1f + stroke / 2f)
            // 깊이가 0 인 변에는 안티앨리어싱 1px 과 선 두께만 남는다.
            val quiet = ceil(1f + stroke)
            assertEquals("$where: 조용한 왼쪽 띠", quiet, band.thickness[VisualizerEngine.SIDE_LEFT])
            assertEquals("$where: 조용한 오른쪽 띠", quiet, band.thickness[VisualizerEngine.SIDE_RIGHT])
            // 위 띠에는 모서리 조각만 든다. 껍질이 붙은 쪽은 모서리에서 가까운 점이 놓인 변이라 점 간격(둘레의 1/150)의 절반을
            // 넘지 않는다. 1px 은 간격이 정수로 떨어질 때의 부동소수 오차 몫이다.
            val halfSpacing = (width + height) / 150f
            assertTrue(
                "$where: 위 띠가 모서리 조각의 가까운 쪽(점 간격의 절반 ${halfSpacing}px)보다 두껍다",
                band.thickness[VisualizerEngine.SIDE_TOP] <= halfSpacing + 1f + stroke + 1f
            )
        }
    }

    @Test
    fun `패드는 막대가 하나도 놓이지 않은 변을 자르지도 그리지도 않는다`() {
        // 왼쪽만 들리면 오른쪽 위·오른쪽 가운데·오른쪽 아래 막대가 모두 보이지 않는다. 그 변은 띠 두께가 0 이라 건너뛴다(#365).
        // 예전에는 그 변도 가장 두꺼운 막대의 두께로 그렸고, 발광을 켜면 번지는 폭까지 더했다.
        // 모서리 막대는 이웃한 두 변에 모두 들어가므로, 왼쪽 위·왼쪽 아래 막대가 있는 위·아래 변은 그린다.
        val leftOnly = BandSound("왼쪽만", 300) { 0.3f to 0f }
        for (surface in BAND_SURFACES) for (glow in listOf(false, true)) {
            val (width, height, density) = surface
            val band = engineAfter(leftOnly, VisualMode.Pad, bandSettings(intensity = 50f, glow), width, height, density)
                .debugPadBand(samplesPerEdge = 1)
            val where = "$surface 발광 $glow (위·오른쪽·아래·왼쪽 ${band.thickness.toList()})"
            assertTrue("$where: 자르지 않고 그린다", band.clipped)
            assertEquals("$where: 막대가 없는 오른쪽 변을 그린다", 0f, band.thickness[VisualizerEngine.SIDE_RIGHT])
            for (side in listOf(VisualizerEngine.SIDE_TOP, VisualizerEngine.SIDE_BOTTOM, VisualizerEngine.SIDE_LEFT)) {
                assertTrue("$where: 막대가 놓인 ${SIDE_NAMES[side]} 변을 그리지 않는다", band.thickness[side] > 0f)
            }
        }
    }

    // ---------------------------------------------------------------
    // 대기 중 소리 신호로 잠들어도 되는지 (#170)
    // ---------------------------------------------------------------

    @Test
    fun `조용한 대기는 한 번 확인한 뒤부터 소리가 날 때까지 잠들어도 된다`() {
        val engine = newEngine(FakeInputs())
        engine.tickUntilIdle()
        assertFalse("대기로 내려오자마자 확인도 없이 잠든다", engine.canSleepUntilLoud)

        engine.pollWake()
        assertTrue(engine.canSleepUntilLoud)
    }

    @Test
    fun `표시를 꺼 둔 소리가 나는 동안에는 잠들지 않고 33ms 확인을 이어 간다`() {
        // 잠들면 소리 신호가 버퍼마다 깨워서 확인보다 오히려 잦아진다.
        val (engine, fake) = hiddenAmbientIdle()
        fake.left = 0.8f
        fake.right = 0.8f
        engine.pollWake()
        assertTrue(engine.debugState().idle)
        assertFalse(engine.canSleepUntilLoud)
    }

    @Test
    fun `소리를 보관하는 동안에는 잠들지 않고 보관이 끝나면 잠든다`() {
        // 늦게 올 위협음 판정을 보려면 소리가 끝난 뒤에도 약 1초 동안 확인해야 한다.
        val (engine, fake) = hiddenAmbientIdle()
        fake.left = 0.9f
        fake.right = 0.9f
        engine.pollWake()
        fake.left = 0f
        fake.right = 0f
        engine.poll(24) // 0.8초
        assertFalse("보관한 소리가 남았는데 잠든다", engine.canSleepUntilLoud)

        engine.poll(12) // 1.2초
        assertTrue("보관이 끝났는데 계속 확인한다", engine.canSleepUntilLoud)
    }

    @Test
    fun `그릴 수 없는 설정에서 소리가 나면 잠들지 않는다`() {
        val fake = FakeInputs(settings = ModeSettings(opacity = 0f))
        val engine = newEngine(fake)
        fake.left = 0.8f
        fake.right = 0.8f
        engine.advance(frames = 120)
        assertTrue(engine.debugState().idle)

        engine.pollWake()
        assertFalse(engine.canSleepUntilLoud)
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
    fun `숨긴 라벨로 지나간 짧은 소리는 늦게 온 위협음 판정에 제때 표시했을 크기로 보인다`() {
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

        // 위협음을 제때 표시했다면 그렸을 크기로 시작한다. 폴링 한 번(33ms)은 60fps 두 프레임이다.
        // 보관한 0.9 를 그대로 전체 크기(3.42)로 쓰면 소리가 1초 넘게 이어질 때의 크기라 짧은 총성마다 크기 한도까지 뛴다.
        val seeded = engine.debugState().smoothTotal
        val onTime = onTimePeakSmoothTotal(loud = 0.9f, loudFrames = 2, tail = 0.03f, tailFrames = 20)
        assertEquals("제때 표시했을 때의 크기($onTime)와 다르다 ($seeded)", onTime, seeded, onTime * 0.2f)

        engine.advance(frames = 3, startNs = FRAME_60 * 1000)
        val state = engine.debugState()
        // 잔향(0.03)으로 0 에서 자라면 0.01 도 안 되어 보이지 않는다.
        assertTrue("잔향만으로 그려서 보이지 않는다 (smoothTotal=${state.smoothTotal})", state.visible)
    }

    @Test
    fun `제때 표시해도 안 보일 작은 위협음도 늦게 온 판정에서는 보이게 그린다`() {
        // 전제: 0.1 짜리 짧은 소리는 위협음을 표시 중이어도 파도가 보이는 3dp 에 못 미친다.
        val onTimeFake = FakeInputs(label = AiClassification.DANGER, shown = true)
        val onTime = newEngine(onTimeFake)
        var t = FRAME_60
        var onTimeVisible = false
        repeat(32) { n ->
            val v = if (n < 2) 0.1f else 0f
            onTimeFake.left = v
            onTimeFake.right = v
            onTime.tick(t)
            t += FRAME_60
            onTimeVisible = onTimeVisible || onTime.debugState().visible
        }
        assertFalse("전제가 틀렸다: 제때 표시해도 보이는 크기다", onTimeVisible)

        // 판정까지 난 위협음은 안 보이면 안 되므로, 늦게 온 판정에서는 보이는 깊이까지 올린다.
        val (engine, fake) = hiddenAmbientIdle()
        fake.left = 0.1f
        fake.right = 0.1f
        engine.pollWake()
        fake.left = 0f
        fake.right = 0f
        engine.poll(10)

        fake.becomeDanger()
        engine.pollWake()
        assertFalse(engine.debugState().idle)
        engine.advance(frames = 3, startNs = FRAME_60 * 1000)
        val state = engine.debugState()
        assertTrue("판정까지 난 작은 위협음이 보이지 않는다 (depths=${state.depths})", state.visible)
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
        // 깨어 있는 동안 스무딩은 숨긴 소리도 따라가지만 판정이 올 즈음엔 이미 줄어 있다.
        // 표시 중이었다면 도달했을 크기로 다시 커져야 한다.
        assertTrue(
            "보관한 크기로 커지지 않았다 ($beforeLabel -> ${state.smoothTotal})",
            state.smoothTotal > beforeLabel * 2f
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

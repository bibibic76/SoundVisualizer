package com.example.soundvisualizer

import android.graphics.BlurMaskFilter
import android.graphics.Canvas as NativeCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.withClip
import android.os.SystemClock
import com.example.soundvisualizer.ai.AiLatencyStamp
import com.example.soundvisualizer.ai.AiLatencyTrace
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 오디오 피크 → 8채널 깊이 → 도형 → 캔버스까지 담당하는 렌더 엔진.
 *
 * 스무딩 파이프라인과 네 가지 모드의 기하 수식을 구현하되,
 *  - 프레임당 힙 할당이 0 이 되도록 모든 버퍼/Path/Paint 를 미리 잡아두고
 *  - 스무딩 계수는 60fps 기준으로 시간 정규화해서 90/120Hz 화면에서도 같은 느낌을 내고
 *  - 소리가 없으면 그리기 무효화를 멈추고(GPU 휴식), 1초 뒤엔 저빈도 폴링으로 내려간다.
 *  - 표시가 꺼진 라벨이라 그리지 못한 피크는 약 1초 보관해, 위협음 판정이 늦게 와도 표시 중이었을 때의 크기로 그린다.
 *
 * 메인 스레드에서만 접근한다.
 *
 * 채널 인덱스(화면 둘레 기준): 0:FC(상단중앙) 1:FR(우상단) 2:SR(우측중앙) 3:BR(우하단)
 *                          4:BC(하단중앙) 5:BL(좌하단) 6:SL(좌측중앙) 7:FL(좌상단)
 */
class VisualizerEngine(
    private val density: Float,
    private val inputs: VisualizerInputs = LiveVisualizerInputs
) {

    companion object {
        private const val CH = 8
        private const val WAVE_N = 150
        private const val PAD_N = 16
        private const val CIRCLE_N = 64

        /** 렌더 프레임마다 target *= 0.87 로 감쇠 */
        private const val TARGET_DECAY = 0.87f
        /** Pad / CircleRipple 모드의 고정 lerp 계수 */
        private const val PAD_LERP = 0.3f
        private const val CIRCLE_LERP = 0.25f
        /** 민감도는 내부 계수 3.75 를 x4 한 15 로 표시한다. 슬라이더 값이 곧 표시값. */
        private const val SENSITIVITY_UI_SCALE = 0.25f

        /** 외곽선 두께 4dp, 광원 블러 반경 = GlowIntensity * 0.5 */
        private const val OUTLINE_STROKE_DP = 4f
        private const val EDGE_MARGIN_DP = 10f
        private const val WAVE_ACTIVE_DP = 3f
        private const val PAD_MAX_DP = 55f
        private const val PAD_MIN_DP = 0.5f
        private const val CIRCLE_MIN_DP = 1f

        /** 스테레오 → 8채널 가상 서라운드 근사. 후면 채널 지연 히스토리 길이(틱). */
        private const val HISTORY = 5

        /** 시간 정규화 기준 프레임(60fps). 정지 후 복귀 시 한 번에 최대 4프레임까지만 진행. */
        private const val REF_FRAME_NS = 16_666_667L
        private const val MIN_FRAME_STEP = 0.25f
        private const val MAX_FRAME_STEP = 4f

        /** 오버레이 최대 프레임. 120Hz 화면에서 GPU/배터리를 아낀다. 0 이면 vsync 그대로. */
        const val FULL_FPS = 60

        /**
         * 배터리 설정을 켰을 때의 프레임 수 ([SettingsManager.reducedFrameRate]).
         *
         * 같은 기기에서 재니 한 코어 기준 22.9% -> 17.2% 로 줄었다. 절반이 안 되는 이유는
         * AI·캡처처럼 프레임 수와 무관한 비용이 섞여 있기 때문이다. 대신 그만큼 덜 부드럽다.
         */
        const val REDUCED_FPS = 30
        /** 이 시간 동안 아무것도 안 보이면 저빈도 폴링(idle)으로 전환 */
        private const val IDLE_AFTER_NS = 1_000_000_000L
        /** 대기 중 확인 간격. 조용하고 보관한 소리도 없을 때는 이 간격 대신 소리 신호를 기다린다([canSleepUntilLoud]). */
        const val IDLE_POLL_MS = 33L
        /** maxVolume < 0.01 이면 비활성으로 본다. 대기 중인 오버레이를 깨우는 소리 신호([OverlayWake])도 같은 값을 쓴다. */
        const val WAKE_THRESHOLD = 0.01f
        private const val MIN_VISIBLE_ALPHA = 0.002f

        /**
         * 가장자리 띠에 안티앨리어싱 몫으로 더하는 폭(px). 안티앨리어싱은 도형의 경계가 지나는 픽셀까지만 칠하므로 1px 이면 된다.
         * 도형이 들어오는 깊이는 [waveBand]·[padBand] 가 보장된 경계로 재므로 어림한 여유는 두지 않는다(#350).
         */
        private const val EDGE_BAND_AA_PX = 1f

        /**
         * 가장자리 띠의 변 번호(#365). 띠 두께를 변마다 따로 재서 [sideBand]·[sideThickness] 를 이 번호로 읽는다.
         * 둘레를 도는 방향(시계 방향)으로 위에서부터 센다. 채널도 같은 방향이라, 패드 막대 c 는 변 c/2 와 (c+1)/2 에 놓인다
         * (가운데 막대는 둘이 같고, 모서리 막대는 이웃한 두 변이다).
         */
        internal const val SIDE_TOP = 0
        internal const val SIDE_RIGHT = 1
        internal const val SIDE_BOTTOM = 2
        internal const val SIDE_LEFT = 3
        private const val SIDES = 4

        /**
         * 발광 블러가 번지는 폭으로 잡는 블러 반경의 배수. 반경이 크면 블러 커널보다 넉넉하지만 반경이 작으면 모자라서,
         * 띠에는 이 값과 커널이 닿는 거리를 견준 [glowSpread] 를 더한다.
         */
        private const val GLOW_SPREAD = 3f

        /**
         * Android 가 `BlurMaskFilter` 의 반경(px)을 Skia 의 시그마로 바꾸는 식: 시그마 = 0.57735 × 반경 + 0.5.
         * hwui `MaskFilter.cpp` 의 `convertRadiusToSigma` 와 같다.
         */
        private const val BLUR_SIGMA_PER_RADIUS = 0.57735f
        private const val BLUR_SIGMA_OFFSET = 0.5f

        /**
         * 반경 [radiusPx] 인 발광 블러가 도형 밖으로 번지는 폭(px). 가장자리 띠에 이만큼 더한다(#350).
         *
         * 블러 커널은 도형에서 3시그마를 올림한 거리까지 닿는다. 반경이 크면 반경의 3배([GLOW_SPREAD])가 그보다 넓다
         * (반경 65px 이면 5.1시그마). 반경이 작으면 시그마의 + 0.5 몫이 커서 3배로는 모자란다. 반경 1px 이면 3px 은
         * 2.8시그마뿐이고 커널은 4px 까지 닿는다. 그래서 커널이 닿는 거리 + 1px 과 반경의 3배 가운데 큰 쪽을 쓴다.
         * 반경 0.5·1·2px 이면 4·5·6px 이고, 반경이 8/3px(약 2.67px) 이상이면 늘 반경의 3배다. 예전 값보다 좁아지는 일은 없다.
         *
         * 1px 을 더 두는 것은 [drawGlow] 가 반경이 0.5px 넘게 바뀔 때만 블러를 새로 만들기 때문이다. 그 사이 실제 블러의
         * 반경은 0.5px 까지 커서 3시그마가 0.87px 늘 수 있지만, 올림한 거리는 1px 넘게 늘지 않는다.
         * 곱셈과 올림뿐이라 프레임마다 불러도 할당이 없다.
         */
        internal fun glowSpread(radiusPx: Float): Float {
            val kernelReach = ceil(3f * (BLUR_SIGMA_PER_RADIUS * radiusPx + BLUR_SIGMA_OFFSET))
            return max(radiusPx * GLOW_SPREAD, kernelReach + 1f)
        }

        /**
         * 표시가 꺼진 라벨이라 그리지 못한 피크를 보관하는 칸 수와 칸 하나의 길이(60fps 프레임 단위).
         * 칸 하나 = 대기 폴링 한 번(33ms ≈ 2프레임)이라 30칸이면 약 1초다.
         * 분류기는 최근 약 1초 창을 250ms 마다 판정하므로, 판정이 몇 번 늦어져도 그 판정을 부른 소리는 이 안에 있다.
         */
        private const val HOLD_SLOTS = 30
        private const val HOLD_SLOT_FRAMES = 2f

        /**
         * 늦게 온 위협음 판정으로 그릴 때 가장 센 방향이 적어도 닿는 깊이.
         * 파도·외곽선이 보이기 시작하는 3dp 의 두 배 넘게 잡아 민감도 기본값에서 0.4초쯤 보이고,
         * 패드(깊이 x0.25, 0.5dp)와 원형(깊이 x0.35, 1dp)도 보이는 문턱을 넘는다.
         */
        private const val LATE_DANGER_MIN_DP = 8f
    }

    // ---------------- 오디오 입력 ----------------
    private val peaks = FloatArray(3)
    private var targetL = 0f
    private var targetR = 0f
    private val historyL = FloatArray(HISTORY)
    private val historyR = FloatArray(HISTORY)
    private var historyIndex = 0

    // ---------------- 스무딩 상태 ----------------
    private val targets = FloatArray(CH)
    private val dist = FloatArray(CH)
    private var smoothTotal = 0f
    private val depths = FloatArray(CH)
    private var baseDepth = 0f

    // ---------------- 늦게 온 위협음 판정용 피크 보관 ----------------
    // 라벨이 숨김이라 읽고 버리던 피크를 칸마다 가장 큰 좌우 한 쌍으로 남기는 링 버퍼. 할당은 없다.
    private val holdL = FloatArray(HOLD_SLOTS)
    private val holdR = FloatArray(HOLD_SLOTS)
    private var holdIndex = 0       // 다음에 덮어쓸(가장 오래된) 칸
    private var holdCurL = 0f       // 채우는 중인 칸
    private var holdCurR = 0f
    private var holdFrames = 0f     // 채우는 중인 칸에 쌓인 시간(60fps 프레임 단위)
    /** 마지막으로 큰 피크를 넣은 뒤 그 피크가 밀려나기까지 남은 칸 수. 0 이면 보관한 게 없어 링을 훑지 않는다. */
    private var holdLiveSlots = 0

    // ---------------- 모드별 애니메이션 상태 ----------------
    private val padThickness = FloatArray(CH)
    private val circleTargets = FloatArray(CH)

    // ---------------- 프레임 결과 ----------------
    private var mode = VisualMode.Wave
    private var settings: ModeSettings = ModeSettings()
    private var alpha = 0f
    private var glowAlpha = 0f
    private var glowRadiusPx = 0f
    private var colorRgb = 0xFFFFFF
    private var colorLatency: AiLatencyStamp? = null
    private var visible = false
    private var wasVisible = false
    private var lastTickNanos = 0L      // 마지막으로 처리한 틱 (시간 정규화용)
    private var lastVsyncNanos = 0L     // 마지막으로 관측한 vsync (프레임 캡 누산용)
    private var invisibleSinceNanos = -1L
    private var frameAccNanos = 0L
    var isIdle = false
        private set

    /**
     * 직전 [pollWake] 가 조용했고 보관한 소리도 없었는지.
     * 깨어나는 길은 조용하지 않은 [pollWake] 뿐이라, 대기로 다시 내려온 직후에는 늘 false 다. 그래서 내려오기 직전에
     * 보관한 소리가 있어도 한 번은 확인하고 잠든다.
     */
    private var quietAtLastPoll = false

    /**
     * 대기 중 다음 확인을 소리가 날 때까지 미뤄도 되는지(#170). 직전 [pollWake] 가 조용했고 보관한 소리도 없을 때만 true.
     *
     * 표시를 꺼 둔 종류의 소리가 계속 나는 동안에는 false 다. 그때 소리 신호로 깨우면 버퍼마다(약 12ms) 깨어나
     * 33ms 확인보다 오히려 잦아진다. 늦게 올 위협음 판정을 위해 소리를 보관하는 동안에도 false 다. 보관 시간은
     * 확인 횟수로 흐르고(한 번 = 한 칸), 판정이 오면 소리가 끝났어도 깨어나야 하기 때문이다.
     */
    val canSleepUntilLoud: Boolean
        get() = isIdle && quietAtLastPoll

    // ---------------- 화면 크기 ----------------
    private var w = 0f
    private var h = 0f

    /**
     * 그릴 면의 크기를 알려준다. [draw] 가 매 프레임 호출하므로 보통 따로 부를 일은 없지만,
     * 캔버스 없이 [tick] 만 돌릴 때는 이걸로 크기를 먼저 정해야 깊이가 화면에 맞게 나온다.
     */
    fun setSurfaceSize(width: Float, height: Float) {
        w = width
        h = height
    }

    // ---------------- 기하 버퍼 ----------------
    private val channelPos = FloatArray(CH)
    private val waveX = FloatArray(WAVE_N)
    private val waveY = FloatArray(WAVE_N)
    /** [waveSegment] 의 결과: 시작점, 제어점 둘, 끝점 (x, y 순). */
    private val seg = FloatArray(8)
    private val centerDists = FloatArray(CH)
    private val padOuterX = FloatArray(PAD_N + 1)
    private val padOuterY = FloatArray(PAD_N + 1)
    private val padInnerX = FloatArray(PAD_N + 1)
    private val padInnerY = FloatArray(PAD_N + 1)
    private val padEase = FloatArray(PAD_N + 1)
    private val cosT = FloatArray(CIRCLE_N)
    private val sinT = FloatArray(CIRCLE_N)
    private val circleI0 = IntArray(CIRCLE_N)
    private val circleI1 = IntArray(CIRCLE_N)
    private val circleFt = FloatArray(CIRCLE_N)
    private var px = 0f
    private var py = 0f

    // ---------------- 가장자리 띠 (#350, #365) ----------------
    // 변 번호([SIDE_TOP] 등)로 읽는다. 프레임마다 다시 채우기만 하고 새로 만들지 않는다.
    /** 변마다 그 변에 맡긴 도형이 그 변에서 들어올 수 있는 가장 깊은 거리(px). 맡긴 것이 없으면 -1. [waveBand]·[padBand] 가 채운다. */
    private val sideBand = FloatArray(SIDES)
    /** 변마다 잘라 그릴 띠의 두께(px, 정수). 0 이면 그 변은 그리지 않는다. [edgeBandThickness] 가 채운다. */
    private val sideThickness = FloatArray(SIDES)
    /** 곡선 조각마다, 네 변 각각에서 조각의 네 점 가운데 가장 먼 거리. 조각 i 의 변 s 는 [i * SIDES + s] 다. [hullSide] 가 채운다. */
    private val segHull = FloatArray(WAVE_N * SIDES)
    /** 곡선 조각마다 맡긴 변. [waveBand] 가 채우고, 테스트([debugWaveBand])가 읽는다. */
    private val segSide = IntArray(WAVE_N)
    /** [waveBand] 가 조각을 껍질이 붙은 변에만 맡겼을 때의 변마다의 경계. 조각을 다시 맡길 때의 기준이다. */
    private val hugBand = FloatArray(SIDES)

    // ---------------- 그리기 객체 ----------------
    // 그릴 때만 필요하다. 미리 만들면 엔진을 JVM 에서 생성할 수 없어 계산부를 테스트하지 못한다.
    // draw 는 메인 스레드 전용이라 동기화 없는 lazy 로 충분하다.
    private val path by lazy(LazyThreadSafetyMode.NONE) { Path() }
    private val fillPaint by lazy(LazyThreadSafetyMode.NONE) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val glowPaint by lazy(LazyThreadSafetyMode.NONE) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val shaderMatrix by lazy(LazyThreadSafetyMode.NONE) { Matrix() }
    private var waveShader: RadialGradient? = null
    private var shaderW = -1f
    private var shaderH = -1f
    private var shaderRgb = -1
    private val shaderColors = IntArray(3)
    private val shaderStops = floatArrayOf(0f, 0.7f, 1f)
    private var blurFilter: BlurMaskFilter? = null
    private var blurRadius = -1f

    init {
        for (i in 0..PAD_N) {
            val t = i.toDouble() / PAD_N
            padEase[i] = sin(t * PI).pow(0.6).toFloat()
        }
        for (i in 0 until CIRCLE_N) {
            val angle = 2.0 * PI * i / CIRCLE_N
            cosT[i] = cos(angle).toFloat()
            sinT[i] = sin(angle).toFloat()
            // angle=0(3시) → 2번(SR), pi/2(6시) → 4번(BC)
            var mapped = (angle / (2.0 * PI)) * 8.0 + 2.0
            if (mapped >= 8.0) mapped -= 8.0
            val i0 = floor(mapped).toInt() % CH
            circleI0[i] = i0
            circleI1[i] = (i0 + 1) % CH
            val t = mapped - floor(mapped)
            circleFt[i] = ((1.0 - cos(t * PI)) / 2.0).toFloat()
        }
    }

    // =====================================================================
    // 프레임 틱
    // =====================================================================

    /**
     * vsync 마다 호출. 오디오를 읽고 깊이/가시성을 갱신한다.
     * @return 이번 프레임에 다시 그려야 하면 true
     */
    fun tick(frameTimeNanos: Long): Boolean {
        // 프레임 캡: vsync 간격을 누적해서 1/maxFps 마다 한 번만 처리 (90Hz → 2/3, 120Hz → 1/2)
        val maxFps = inputs.framesPerSecond()
        if (maxFps > 0) {
            val interval = 1_000_000_000L / maxFps - 200_000L
            if (lastVsyncNanos != 0L) {
                frameAccNanos += frameTimeNanos - lastVsyncNanos
                lastVsyncNanos = frameTimeNanos
                if (frameAccNanos < interval) return false
                frameAccNanos = min(frameAccNanos - interval, interval)
            } else {
                lastVsyncNanos = frameTimeNanos
                frameAccNanos = 0L
            }
        }

        val k = if (lastTickNanos == 0L) 1f
        else ((frameTimeNanos - lastTickNanos).toFloat() / REF_FRAME_NS).coerceIn(MIN_FRAME_STEP, MAX_FRAME_STEP)
        lastTickNanos = frameTimeNanos

        mode = inputs.currentMode()
        settings = inputs.settingsFor(mode)
        val s = settings

        // 1. 피크 읽기. 오디오 버퍼가 도착하면 target = 피크, 렌더 프레임마다 target *= 0.87
        inputs.readPeaks(peaks)
        val decay = TARGET_DECAY.pow(k)
        if (peaks[2] > 0f) {
            targetL = peaks[0]
            targetR = peaks[1]
        } else {
            targetL = max(targetL * decay, peaks[0])
            targetR = max(targetR * decay, peaks[1])
        }

        // 1-1. 라벨. 숨김이면 이번 피크를 보관하고, 위협음 판정이 새로 왔으면 보관한 크기로 시작한다 (pollWake 참고).
        // 깨어 있는 동안 받은 소리도 판정이 오기 전에 대기로 내려가면 사라지므로 여기서도 똑같이 모은다.
        val coarse = inputs.coarseLabel()
        colorLatency = inputs.latencyStamp()
        val shown = inputs.isShown(coarse)
        val drawable = canDraw(s)
        if (colorLatency != null && AiLatencyTrace.recordingId != 0L)
            AiLatencyTrace.observed(colorLatency,
                AiLatencyTrace.Stage.COLOR_READ, SystemClock.elapsedRealtimeNanos(),
                if (shown && drawable) "eligible" else "hidden_or_disabled")
        advanceHold(k)
        if (drawable) {
            if (!shown) holdHidden(peaks[0], peaks[1])
            else if (coarse == AiClassification.DANGER) releaseHold(s)
        }

        // 2. 스테레오 → 8채널 가상 서라운드 (2채널만 받으므로 Mid/Side 로 방향을 합성)
        historyL[historyIndex] = targetL
        historyR[historyIndex] = targetR
        val delayedIndex = (historyIndex + 1) % HISTORY // 가장 오래된 값 (HISTORY-1 틱 전)
        historyIndex = delayedIndex
        val rearL = if (s.useRippleDelay) historyL[delayedIndex] else targetL
        val rearR = if (s.useRippleDelay) historyR[delayedIndex] else targetR
        upmix(targetL, targetR, rearL, rearR)

        // 3. 스무딩: 전체 크기(민감도)와 방향 분포(속도)를 따로 추종
        var total = 0f
        for (i in 0 until CH) total += targets[i]

        val sfTremor = norm(tremorCoef(s), k)
        smoothTotal += (total - smoothTotal) * sfTremor

        val sfPosition = norm(min(1f, max(0.1f, s.speed) / 100f), k)
        if (total > 0.0001f) {
            for (i in 0 until CH) dist[i] += (targets[i] / total - dist[i]) * sfPosition
        }

        // 4. 깊이(px). Intensity 100% 가 화면 중앙 한계선에 닿도록 매핑
        val useOpacity = s.intensityAsOpacity
        baseDepth = baseDepthFor(s)
        for (i in 0 until CH) {
            val v = if (useOpacity) dist[i] * 4f else smoothTotal * dist[i]
            depths[i] = min(baseDepth, baseDepth * v)
        }

        // 5. 진하기 / 색 / 광원
        alpha = if (useOpacity) {
            val maxOpacity = max(0f, s.opacityFixedMaxOpacity) / 100f
            maxOpacity * (smoothTotal / 2.5f).coerceIn(0f, 1f)
        } else {
            (max(0f, s.opacity) / 100f).coerceAtMost(1f)
        }
        // 소리 종류에 따라 색과 표시 여부를 고른다. 분류기가 없으면 항상 환경음이다.
        // 색은 보간하지 않고 즉시 바꾼다. 위협음은 경고라서 서서히 물드는 것보다
        // 바로 뜨는 편이 낫고, 프레임당 셰이더 재생성(=할당)도 생기지 않는다.
        colorRgb = inputs.colorFor(coarse) and 0xFFFFFF
        if (s.isGlowMode && s.glowIntensity > 0f) {
            glowAlpha = min(1f, s.glowIntensity / 100f * 1.6f)
            glowRadiusPx = max(1f, s.glowIntensity * 0.5f) * density
        } else {
            glowAlpha = 0f
        }

        // 6. 모드별 가시성 판정 (Pad/Circle 은 여기서 자체 lerp 도 진행)
        val anyShape = when (mode) {
            VisualMode.Wave, VisualMode.Outline -> anyDepthAbove(WAVE_ACTIVE_DP * density)
            VisualMode.Pad -> stepPad(k)
            VisualMode.CircleRipple -> stepCircle(k)
        }
        visible = shown && alpha > MIN_VISIBLE_ALPHA && anyShape

        // 7. 무효화 / idle 판단
        val needsRedraw = visible || wasVisible
        wasVisible = visible
        if (visible) {
            invisibleSinceNanos = -1L
        } else if (invisibleSinceNanos < 0L) {
            invisibleSinceNanos = frameTimeNanos
        } else if (frameTimeNanos - invisibleSinceNanos > IDLE_AFTER_NS &&
            ((targetL < WAKE_THRESHOLD && targetR < WAKE_THRESHOLD) || !(shown && drawable))
        ) {
            // 조용하거나, 소리는 나도 지금 설정으로는 그릴 수 없으면 쉰다.
            // 소리 조건만 보면 표시를 끈 종류의 음악이 계속 나올 때 그릴 것도 없이 화면 주사율로 돈다.
            enterIdle()
        }
        return needsRedraw
    }

    /**
     * 소리 크기와 라벨에 상관없이, 지금 설정으로 무언가 보일 수 있는지.
     * 진하기·크기가 0이면 소리가 아무리 커도 그릴 것이 없다. 라벨의 표시 여부는 따로 본다.
     */
    private fun canDraw(s: ModeSettings): Boolean {
        val potentialAlpha = (if (s.intensityAsOpacity) s.opacityFixedMaxOpacity else s.opacity) / 100f
        val size = if (s.intensityAsOpacity) s.opacityFixedSize else s.intensity
        return potentialAlpha > MIN_VISIBLE_ALPHA && size > 0f
    }

    /** 민감도 → 60fps 한 프레임에 전체 크기가 소리를 따라붙는 비율. 틱과 늦은 위협음 판정이 같은 값을 써야 크기가 맞는다. */
    private fun tremorCoef(s: ModeSettings): Float =
        min(1f, max(0.1f, s.sensitivity * SENSITIVITY_UI_SCALE) / 100f)

    /** 크기 100% 가 화면 중앙 한계선에 닿는 깊이(px). 크기 고정 모드는 고정 크기의 절반을 쓴다. */
    private fun baseDepthFor(s: ModeSettings): Float {
        var maxBase = min(w, h) / 2f - EDGE_MARGIN_DP * density
        if (maxBase < EDGE_MARGIN_DP * density) maxBase = EDGE_MARGIN_DP * density
        val size = if (s.intensityAsOpacity) s.opacityFixedSize / 2f else s.intensity
        return maxBase * (max(0f, size) / 100f)
    }

    /**
     * 한 프레임의 계산 결과. 테스트에서 확인용으로만 읽는다. 렌더 경로는 쓰지 않는다.
     */
    internal data class DebugState(
        val visible: Boolean,
        val alpha: Float,
        val smoothTotal: Float,
        val baseDepth: Float,
        val colorRgb: Int,
        val depths: List<Float>,
        val idle: Boolean
    )

    /**
     * 테스트에서 잰 가장자리 띠. 변마다의 값은 [SIDE_TOP]·[SIDE_RIGHT]·[SIDE_BOTTOM]·[SIDE_LEFT] 번째 칸에 있고,
     * 거리는 모두 가장자리에서 안쪽으로 잰 px 이다.
     */
    internal class DebugBand(
        /** 변마다 그릴 때 쓰는 띠 두께([edgeBandThickness]). 0 이면 그 변은 그리지 않는다. 자르지 않고 그리면 모두 -1. */
        val thickness: FloatArray,
        /**
         * 변마다 그 변에 맡긴 도형이 그 변에서 들어올 수 있다고 본 깊이([waveBand]·[padBand]).
         * 막대가 하나도 놓이지 않은 패드의 변은 -1 이고, 패드를 자르지 않는 화면(h/4 > w)이면 모두 -1 이다.
         */
        val bound: FloatArray,
        /** 도형의 점(파도 점, 막대의 꼭짓점)만 가장 가까운 변에서 잰 가장 깊은 거리. */
        val deepestPoint: Float,
        /** 그릴 도형을 점 사이까지 촘촘히 짚어 가장 가까운 변에서 잰 가장 깊은 거리. */
        val deepestSample: Float
    ) {
        /** 띠로 잘라 그리는지. */
        val clipped: Boolean get() = thickness[SIDE_TOP] >= 0f
    }

    /**
     * 테스트용: 지금 상태로 파도 점을 만들어 그릴 때와 같은 띠를 잰다. 외곽선 모드면 선 두께까지 넣은 띠다.
     * 곡선은 그릴 때와 같은 [waveSegment] 로 점 사이마다 [samplesPerSegment] 번 짚고, 짚은 점마다 그 조각을 맡긴 변과
     * 좌표를 [onSample] 에 넘긴다. 맡긴 변은 그릴 때 [waveBand] 가 남긴 것이므로, 테스트는 점마다 그 변의 띠 안인지 볼 수 있다.
     */
    internal fun debugWaveBand(
        samplesPerSegment: Int,
        onSample: (side: Int, x: Float, y: Float) -> Unit = { _, _, _ -> }
    ): DebugBand {
        buildWavePoints()
        waveBand()
        var points = 0f
        var curve = 0f
        for (i in 0 until WAVE_N) {
            points = max(points, edgeDistance(waveX[i], waveY[i]))
            waveSegment(i)
            val side = segSide[i]
            for (k in 0..samplesPerSegment) {
                val t = k.toFloat() / samplesPerSegment
                val u = 1f - t
                val a = u * u * u; val b = 3f * u * u * t; val c = 3f * u * t * t; val d = t * t * t
                val x = a * seg[0] + b * seg[2] + c * seg[4] + d * seg[6]
                val y = a * seg[1] + b * seg[3] + c * seg[5] + d * seg[7]
                val e = edgeDistance(x, y)
                if (e > curve) curve = e
                onSample(side, x, y)
            }
        }
        return debugBand(clip = true, waveStrokeWidth(isWave = mode != VisualMode.Outline), points, curve)
    }

    /**
     * 테스트용: 지금 상태로 패드 막대를 만들어 그릴 때와 같은 띠를 잰다.
     * 막대마다 그릴 때와 같은 순서로 둘레(바깥 점, 안쪽 점, 다시 첫 바깥 점)를 돌며 선마다 [samplesPerEdge] 번 짚고,
     * 짚은 점마다 그 선이 놓인 변과 좌표를 [onSample] 에 넘긴다. 모서리 막대는 앞 절반(막대의 점 0..PAD_N/2)을 잇는 선이
     * 변 c/2 에, 나머지 선이 다음 변에 놓인다. 모서리의 점은 두 변 모두에서 막대 두께 안이라 어느 쪽 선에도 낄 수 있다([padBand]).
     */
    internal fun debugPadBand(
        samplesPerEdge: Int,
        onSample: (side: Int, x: Float, y: Float) -> Unit = { _, _, _ -> }
    ): DebugBand {
        setPadCenters()
        var points = 0f
        var sampled = 0f
        for (c in 0 until CH) {
            if (!buildPadBar(c)) continue
            val vertices = 2 * PAD_N + 2
            for (j in 0 until vertices) {
                val next = (j + 1) % vertices
                val ax = padVertexX(j); val ay = padVertexY(j)
                val bx = padVertexX(next); val by = padVertexY(next)
                val side = if (max(padPointIndex(j), padPointIndex(next)) <= PAD_N / 2) c / 2 else (c + 1) / 2 % SIDES
                points = max(points, edgeDistance(ax, ay))
                for (k in 0..samplesPerEdge) {
                    val t = k.toFloat() / samplesPerEdge
                    val x = ax + (bx - ax) * t
                    val y = ay + (by - ay) * t
                    sampled = max(sampled, edgeDistance(x, y))
                    onSample(side, x, y)
                }
            }
        }
        return debugBand(clip = padBand(), strokeWidth = 0f, points, sampled)
    }

    /** [debugWaveBand]·[debugPadBand] 의 결과. [sideBand] 를 채운 뒤에 부른다. [clip] 이 false 면 자르지 않고 그리는 경우다. */
    private fun debugBand(clip: Boolean, strokeWidth: Float, points: Float, sampled: Float): DebugBand {
        val clipped = clip && edgeBandThickness(strokeWidth)
        return DebugBand(
            thickness = if (clipped) sideThickness.copyOf() else FloatArray(SIDES) { -1f },
            bound = if (clip) sideBand.copyOf() else FloatArray(SIDES) { -1f },
            deepestPoint = points,
            deepestSample = sampled
        )
    }

    /** 패드 막대 둘레의 [j] 번째 꼭짓점이 막대의 몇 번째 점인지: 바깥 점 0..PAD_N 다음에 안쪽 점 PAD_N..0. */
    private fun padPointIndex(j: Int): Int = if (j <= PAD_N) j else 2 * PAD_N + 1 - j
    private fun padVertexX(j: Int): Float = if (j <= PAD_N) padOuterX[j] else padInnerX[padPointIndex(j)]
    private fun padVertexY(j: Int): Float = if (j <= PAD_N) padOuterY[j] else padInnerY[padPointIndex(j)]

    internal fun debugState(): DebugState = DebugState(
        visible = visible,
        alpha = alpha,
        smoothTotal = smoothTotal,
        baseDepth = baseDepth,
        colorRgb = colorRgb,
        depths = depths.toList(),
        idle = isIdle
    )

    /**
     * idle 중 저빈도 폴링. 소리가 나고 지금 설정으로 그릴 수 있을 때만 프레임 클럭으로 복귀한다.
     * 표시를 끈 종류의 소리가 계속 나는 동안에는 깨어나지 않는다.
     *
     * 다만 그 피크는 버리지 않고 약 1초 보관한다. 위협음만 보이게 해 두면 총성 같은 짧은 소리는 먼저
     * 숨긴 라벨(환경음 등)로 들어오고, 위협음 판정은 다음 분류(250ms 주기)와 추론 시간만큼 늦게 온다.
     * 피크를 이미 버렸다면 그때 남은 잔향으로 깨어나 민감도 속도로 0 에서 자라므로 거의 보이지 않는다.
     */
    fun pollWake() {
        inputs.readPeaks(peaks)
        advanceHold(HOLD_SLOT_FRAMES) // 폴링 한 번 = 보관 한 칸
        val loud = peaks[0] > WAKE_THRESHOLD || peaks[1] > WAKE_THRESHOLD
        // 조용하고 보관한 소리도 없으면 설정·라벨을 읽을 필요가 없다. 대기 중 폴링은 대부분 여기서 끝나고,
        // 다음 확인은 소리가 날 때까지 미룰 수 있다.
        quietAtLastPoll = !loud && holdLiveSlots == 0
        if (quietAtLastPoll) return
        val s = inputs.settingsFor(inputs.currentMode())
        if (!canDraw(s)) return
        val coarse = inputs.coarseLabel()
        if (!inputs.isShown(coarse)) {
            holdHidden(peaks[0], peaks[1])
            return
        }
        // 위협음 판정이 새로 왔으면 보관한 크기로 시작한다. 소리가 이미 끝났어도 깨어난다.
        // 그 밖의 깨어남은 0 에서 민감도 속도로 자란다.
        val released = coarse == AiClassification.DANGER && releaseHold(s)
        if (!loud && !released) return

        targetL = peaks[0]
        targetR = peaks[1]
        isIdle = false
        invisibleSinceNanos = -1L
        lastTickNanos = 0L
        lastVsyncNanos = 0L
        frameAccNanos = 0L
    }

    private fun enterIdle() {
        isIdle = true
        targetL = 0f
        targetR = 0f
        smoothTotal = 0f
        for (i in 0 until CH) {
            targets[i] = 0f
            padThickness[i] = 0f
            circleTargets[i] = 0f
        }
        historyL.fill(0f)
        historyR.fill(0f)
        lastTickNanos = 0L
        lastVsyncNanos = 0L
        frameAccNanos = 0L
        // 보관한 피크는 비우지 않는다. 대기로 내려간 뒤에 위협음 판정이 와도 쓸 수 있어야 한다.
    }

    /**
     * 지금 바로 쉰다. 오버레이 창이 가려진 동안(화면 꺼짐·잠금 화면, [OverlayVisibility]) 부른다([VisualizerOverlay]).
     *
     * 외부 사운드 모드처럼 화면이 꺼져도 소리를 받는 동안에는 엔진이 쉬지 않는다. 그동안 새 프레임을 요청하면, 화면이 꺼진
     * 동안에도 프레임 신호를 보내는 버전(Android 10)에서는 보이지 않는 그림을 초당 60번 계산했고(#400), Galaxy S25+(Android 17)
     * 에서는 잠금 화면이 숨긴 창의 요청이 잠금을 풀어도 끝나지 않아 오버레이가 멈춘 채 남았다(#401).
     *
     * 그리던 것은 지운다. 다시 보일 때 가려지기 전 모양이 남지 않고, 깨어나면 평소처럼 0 에서 자란다.
     * 보관한 피크도 비운다. 가려지기 전 소리를 다시 보인 뒤의 위협음 판정으로 꺼내 그리면 지난 소리가 번쩍인다.
     * @return 그리던 것이 남아 있어 한 번 다시 그려 비워야 하면 true
     */
    fun sleepNow(): Boolean {
        val drawn = visible || wasVisible
        visible = false
        wasVisible = false
        alpha = 0f
        baseDepth = 0f
        depths.fill(0f)
        // 다시 보이면 소리 신호를 기다리지 않고 한 번은 확인한다. 가려진 동안 들어온 소리를 그때 본다.
        quietAtLastPoll = false
        clearHold()
        if (!isIdle) enterIdle()
        return drawn
    }

    // ---------------- 피크 보관 ----------------

    /**
     * 라벨이 숨김이라 그리지 못한 피크를 지금 칸에 모은다.
     * 문턱 이하는 깨울 일도 없으니 넣지 않고, 방향이 섞이지 않게 좌우를 한 쌍으로 다룬다.
     */
    private fun holdHidden(l: Float, r: Float) {
        if (l <= WAKE_THRESHOLD && r <= WAKE_THRESHOLD) return
        if (max(l, r) > max(holdCurL, holdCurR)) {
            holdCurL = l
            holdCurR = r
        }
        holdLiveSlots = HOLD_SLOTS + 1 // 지금 칸 + 링 한 바퀴
    }

    /**
     * 보관 시간을 [frames](60fps 프레임 단위)만큼 흘린다. 칸이 차면 링에 넣고 가장 오래된 칸을 덮어쓴다.
     * 큰 피크가 모두 밀려나면 링은 전부 0 이 되므로 그다음부터는 아무것도 하지 않는다.
     */
    private fun advanceHold(frames: Float) {
        if (holdLiveSlots == 0) return
        holdFrames += frames
        while (holdFrames >= HOLD_SLOT_FRAMES) {
            holdFrames -= HOLD_SLOT_FRAMES
            holdL[holdIndex] = holdCurL
            holdR[holdIndex] = holdCurR
            holdIndex = (holdIndex + 1) % HOLD_SLOTS
            holdCurL = 0f
            holdCurR = 0f
            holdLiveSlots--
            if (holdLiveSlots == 0) {
                holdFrames = 0f
                return
            }
        }
    }

    /** 보관한 피크를 모두 비운다. 링이 전부 0 이 되므로 다음에 덮어쓸 칸([holdIndex])은 그대로 두어도 된다. */
    private fun clearHold() {
        holdL.fill(0f)
        holdR.fill(0f)
        holdCurL = 0f
        holdCurR = 0f
        holdFrames = 0f
        holdLiveSlots = 0
    }

    /**
     * 보관한 소리로 전체 크기와 방향을 바로 잡고 보관을 비운다.
     * 판정이 늦게 온 소리를 민감도 스무딩으로 0 에서 키우면 이미 끝난 뒤라 거의 보이지 않으므로 미리 키워 두고,
     * 줄어드는 것은 이후 프레임에서 평소처럼 민감도 속도를 따른다.
     * 한 번 쓰면 비우므로 같은 소리로 두 번 번쩍이지 않는다.
     *
     * 크기는 그 종류를 표시 중이었다면 평소 경로가 그렸을 크기다. 보관한 피크를 그대로 전체 크기로 쓰면
     * 소리가 1초 넘게 이어질 때의 크기라서, 짧은 총성마다 크기 한도까지 뛰고 같은 소리가 직전 라벨에 따라
     * 열 배 넘게 다르게 그려진다.
     * @return 보관한 피크가 있었으면 true
     */
    private fun releaseHold(s: ModeSettings): Boolean {
        if (holdLiveSlots == 0) return false

        // 1) 오래된 칸부터 칸 하나(2프레임)씩 평소 스무딩에 다시 흘려, 도달했을 가장 큰 전체 크기를 구한다.
        //    칸마다 가장 큰 피크만 남아 있고 후면 지연도 없으니 실제보다 조금 크게 나온다. 방향은 가장 큰 쌍으로 잡는다.
        //    targets 는 틱의 2단계 upmix 가 곧 현재 소리로 다시 채우므로 여기서 덮어써도 된다.
        val sf = norm(tremorCoef(s), HOLD_SLOT_FRAMES)
        var ramp = 0f
        var rampPeak = 0f
        var l = 0f
        var r = 0f
        for (n in 0..HOLD_SLOTS) {
            val slot = (holdIndex + n) % HOLD_SLOTS
            val sl = if (n < HOLD_SLOTS) holdL[slot] else holdCurL // 마지막은 채우는 중인 칸
            val sr = if (n < HOLD_SLOTS) holdR[slot] else holdCurR
            ramp += (upmixTotal(sl, sr) - ramp) * sf
            if (ramp > rampPeak) rampPeak = ramp
            if (max(sl, sr) > max(l, r)) {
                l = sl
                r = sr
            }
        }
        clearHold()

        val full = upmixTotal(l, r) // 이 소리가 계속 날 때 다다르는 크기. targets 에 방향이 남는다.
        if (full <= 0f) return true
        var seed = rampPeak
        // 2) 그래도 가장 센 방향이 LATE_DANGER_MIN_DP 에 못 미치면 거기까지 올린다. 판정까지 난 위협음이 안 보이면 안 된다.
        //    크기 고정 모드는 전체 크기가 깊이가 아니라 진하기라서 올리지 않는다.
        val depth = baseDepthFor(s)
        if (!s.intensityAsOpacity && depth > 0f) {
            var maxShare = 0f
            for (i in 0 until CH) maxShare = max(maxShare, targets[i] / full)
            seed = max(seed, LATE_DANGER_MIN_DP * density / (depth * maxShare))
        }
        // 같은 소리가 계속 날 때보다 크게 그리지는 않는다.
        seed = min(seed, full)

        // 이미 더 크게 그리는 중이면(지금도 큰 소리가 이어짐) 건드리지 않는다.
        if (seed > smoothTotal) {
            smoothTotal = seed
            for (i in 0 until CH) dist[i] = targets[i] / full
        }
        return true
    }

    /** 앞뒤에 같은 좌우 값을 넣었을 때의 8채널 합. 판정이 올 즈음엔 후면 지연도 따라잡았다고 본다. [targets] 를 덮어쓴다. */
    private fun upmixTotal(l: Float, r: Float): Float {
        upmix(l, r, l, r)
        var total = 0f
        for (i in 0 until CH) total += targets[i]
        return total
    }

    /** 프레임당 계수 a 를 k 프레임 분량으로 환산: 1-(1-a)^k */
    private fun norm(a: Float, k: Float): Float = if (k == 1f) a else 1f - (1f - a).pow(k)

    private fun upmix(fl: Float, fr: Float, rearL: Float, rearR: Float) {
        val mid = min(fl, fr)
        val sideL = max(0f, fl - fr)
        val sideR = max(0f, fr - fl)
        targets[0] = mid * 1.2f    // FC
        targets[1] = fr * 0.9f     // FR
        targets[7] = fl * 0.9f     // FL
        targets[2] = sideR * 1.5f  // SR
        targets[6] = sideL * 1.5f  // SL

        val rearMid = min(rearL, rearR)
        val rearSideL = max(0f, rearL - rearR)
        val rearSideR = max(0f, rearR - rearL)
        targets[3] = rearSideR * 1.2f // BR
        targets[4] = rearMid * 0.8f   // BC
        targets[5] = rearSideL * 1.2f // BL
    }

    private fun anyDepthAbove(threshold: Float): Boolean {
        for (i in 0 until CH) if (depths[i] > threshold) return true
        return false
    }

    private fun stepPad(k: Float): Boolean {
        val lerp = norm(PAD_LERP, k)
        val cap = PAD_MAX_DP * density
        val minVisible = PAD_MIN_DP * density
        var any = false
        for (c in 0 until CH) {
            var target = depths[c] * 0.25f
            if (target > cap) target = cap
            padThickness[c] += (target - padThickness[c]) * lerp
            if (padThickness[c] >= minVisible) any = true
        }
        return any
    }

    private fun stepCircle(k: Float): Boolean {
        val lerp = norm(CIRCLE_LERP, k)
        val cap = h * 0.4f
        val minVisible = CIRCLE_MIN_DP * density
        var any = false
        for (i in 0 until CH) {
            var target = depths[i] * 0.35f
            if (target > cap) target = cap
            circleTargets[i] += (target - circleTargets[i]) * lerp
            if (circleTargets[i] > minVisible) any = true
        }
        return any
    }

    // =====================================================================
    // 그리기
    // =====================================================================

    /**
     * Compose draw 단계에서 호출. [frameSerial] 은 값 자체는 쓰지 않고, 읽는 행위로
     * 그리기 무효화를 프레임 틱에 묶는 용도다.
     */
    fun draw(canvas: NativeCanvas, width: Float, height: Float, @Suppress("UNUSED_PARAMETER") frameSerial: Long) {
        setSurfaceSize(width, height)
        if (!visible || w <= 0f || h <= 0f) return

        when (mode) {
            VisualMode.Wave -> drawWaveOrOutline(canvas, isWave = true)
            VisualMode.Outline -> drawWaveOrOutline(canvas, isWave = false)
            VisualMode.Pad -> drawPad(canvas)
            VisualMode.CircleRipple -> drawCircle(canvas)
        }
        if (colorLatency != null && AiLatencyTrace.recordingId != 0L)
            AiLatencyTrace.observed(colorLatency,
                AiLatencyTrace.Stage.COLOR_DRAW, SystemClock.elapsedRealtimeNanos())
    }

    // ---------------- Wave / Outline ----------------

    private fun drawWaveOrOutline(canvas: NativeCanvas, isWave: Boolean) {
        buildWavePoints()

        val n = WAVE_N
        val path = path
        path.rewind()
        path.fillType = Path.FillType.EVEN_ODD

        if (isWave) {
            // 바깥 사각형 + 안쪽 파도 루프 → EvenOdd 로 테두리 띠만 채워진다
            path.moveTo(0f, 0f)
            path.lineTo(w, 0f)
            path.lineTo(w, h)
            path.lineTo(0f, h)
            path.close()

            path.moveTo(waveX[0], waveY[0])
            for (i in 0 until n) {
                waveSegment(i)
                path.cubicTo(seg[2], seg[3], seg[4], seg[5], seg[6], seg[7])
            }
            path.close()
        } else {
            // 외곽선: 안쪽 루프만, 역방향으로 순회
            path.moveTo(waveX[0], waveY[0])
            for (i in n - 1 downTo 0) {
                val prev = (i + n - 1) % n
                val next = (i + 1) % n
                val nnext = (i + 2) % n
                val p0x = waveX[nnext]; val p0y = waveY[nnext]
                val p1x = waveX[next]; val p1y = waveY[next]
                val p2x = waveX[i]; val p2y = waveY[i]
                val p3x = waveX[prev]; val p3y = waveY[prev]
                path.cubicTo(
                    p1x + (p2x - p0x) / 6f, p1y + (p2y - p0y) / 6f,
                    p2x - (p3x - p1x) / 6f, p2y - (p3y - p1y) / 6f,
                    p2x, p2y
                )
            }
            path.close()
        }

        val shader = ensureWaveShader()
        val paint = fillPaint
        paint.shader = shader
        val strokeWidth = waveStrokeWidth(isWave)
        if (isWave) {
            paint.style = Paint.Style.FILL
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
        }
        // 띠에 더할 선 두께는 페인트에서 읽지 않는다. 예전에는 읽어서, 외곽선을 그리다 파도로 바꾸면 페인트에 남은
        // 외곽선의 4dp 가 채우기에는 쓰이지도 않으면서 파도의 띠만 두껍게 했다(#350).
        waveBand()
        paintEdgeBand(canvas, shader, paint.style, strokeWidth)
    }

    /** 파도는 채우기라 선 두께가 0 이고, 외곽선은 [OUTLINE_STROKE_DP] 다. */
    private fun waveStrokeWidth(isWave: Boolean): Float = if (isWave) 0f else OUTLINE_STROKE_DP * density

    /**
     * 파도 점 [i] 에서 다음 점까지의 3차 곡선을 [seg] 에 채운다: 시작점, 제어점 둘, 끝점.
     * 점들을 지나는 Catmull-Rom 곡선이다. 외곽선은 같은 곡선을 거꾸로 그린다.
     */
    private fun waveSegment(i: Int) {
        val n = WAVE_N
        val i0 = (i + n - 1) % n
        val i2 = (i + 1) % n
        val i3 = (i + 2) % n
        val p0x = waveX[i0]; val p0y = waveY[i0]
        val p1x = waveX[i]; val p1y = waveY[i]
        val p2x = waveX[i2]; val p2y = waveY[i2]
        val p3x = waveX[i3]; val p3y = waveY[i3]
        seg[0] = p1x; seg[1] = p1y
        seg[2] = p1x + (p2x - p0x) / 6f; seg[3] = p1y + (p2y - p0y) / 6f
        seg[4] = p2x - (p3x - p1x) / 6f; seg[5] = p2y - (p3y - p1y) / 6f
        seg[6] = p2x; seg[7] = p2y
    }

    /**
     * 파도·외곽선 곡선이 변마다 들어올 수 있는 가장 깊은 거리(px)를 [sideBand] 에 채우고, 조각마다 맡긴 변을 [segSide] 에 남긴다.
     * 어림이 아니라 보장된 경계다(#350, #365).
     *
     * 곡선 조각([waveSegment])마다 시작점·제어점 둘·끝점을 놓고, 네 변 각각에서 네 점 가운데 가장 먼 거리를 잰다([hullSide]).
     * 3차 베지에 곡선은 네 점의 볼록 껍질 안에 있고 한 변까지의 거리는 좌표의 일차식이라, 조각 위의 어느 점도
     * **네 변 어디에서 재든** 그 변에서 잰 이 값보다 깊지 않다. 그래서 조각을 어느 변에 맡기든, 그 변의 경계가 그 변에서 잰
     * 조각의 값 이상이기만 하면 보장된 경계다. 변마다 맡은 조각들의 최댓값을 그 변의 경계로 삼으므로 늘 그렇다.
     * 외곽선은 같은 조각을 거꾸로 그리므로 같은 값이다.
     *
     * 맡기는 것은 두 번이다.
     * 1. 조각마다 값이 가장 작은 변, 곧 껍질이 붙어 있는 변에 맡긴다([hugBand]). 그 값은 #350 의 조각 깊이(네 변 가운데 가장
     *    작은 값)와 같아서, 어느 변의 띠도 #350 의 띠보다 두껍지 않다.
     * 2. 다른 변의 1. 경계에도 이미 드는 조각은, 들 수 있는 변 가운데 1. 경계가 가장 두꺼운 변에 다시 맡긴다. 모서리를 가로지르는
     *    조각은 두 변 쪽으로 모두 걸쳐서, 두 값이 비슷하면 어느 쪽이 작은지는 파도 점이 모서리에서 얼마나 떨어졌는지로 갈린다.
     *    1. 만으로는 조용한 변이 그 조각 몫(1080×1920 에서 20px)만큼 두꺼워질 수 있는데, 이웃 변의 띠가 이미 덮으면 그쪽에 맡긴다.
     *    어느 조각도 1. 경계보다 깊은 변에 맡기지 않으므로 어느 변도 1. 보다 두꺼워지지 않는다. 한 번 더 해도 줄지 않았다.
     *
     * 모서리를 도는 조각도 따로 다루지 않는다. 위·아래 띠가 화면 너비 전체라서, 띠 네 개를 합친 곳은
     * "위에서 T 안, 아래에서 B 안, 왼쪽에서 L 안, 오른쪽에서 R 안 가운데 하나"인 점 전체와 같다([paintEdgeBand]).
     * 그래서 왼쪽에 맡긴 조각의 점이 왼쪽 띠 사각형보다 위로 나가도, 왼쪽에서 L 안이면 그 점은 위 띠에 든다.
     *
     * 파도는 바깥 사각형과 곡선 사이를 채우고 외곽선은 곡선을 따라 그으므로 네 변 모두에 그릴 것이 있다.
     * 그래서 맡은 조각이 없어도 0 에서 시작해, 어느 변이든 안티앨리어싱 몫은 남긴다.
     *
     * 파도 점만 재면 안 된다. 점 사이에서 곡선이 몇 px 더 부풀고, 소리가 없는 모서리에서는 모서리 양쪽 변의 점을 잇는
     * 곡선이 모서리를 가로질러 점보다 깊이 들어온다. 띠 밖으로 나간 만큼 잘려 보인다.
     * 예전에는 점으로 재고 그 몫을 8dp 로 어림해 더했는데, 평소 소리 크기에서는 그 여유가 띠의 30~57% 였다.
     */
    private fun waveBand() {
        hugBand.fill(0f)
        for (i in 0 until WAVE_N) {
            waveSegment(i)
            val side = hullSide(i)
            segSide[i] = side
            val depth = segHull[i * SIDES + side]
            if (depth > hugBand[side]) hugBand[side] = depth
        }
        sideBand.fill(0f)
        for (i in 0 until WAVE_N) {
            // 껍질이 붙은 변에서 시작한다. 이 조각이 드는 변 가운데 1. 경계가 더 두꺼운 변이 있으면 그쪽으로 옮긴다.
            var side = segSide[i]
            for (s in 0 until SIDES) {
                if (segHull[i * SIDES + s] <= hugBand[s] && hugBand[s] > hugBand[side]) side = s
            }
            segSide[i] = side
            val depth = segHull[i * SIDES + side]
            if (depth > sideBand[side]) sideBand[side] = depth
        }
    }

    /**
     * 지금 [seg] 에 든 곡선 조각 [i] 의 껍질 깊이, 곧 변마다 조각의 네 점 가운데 그 변에서 가장 먼 거리를 [segHull] 에 채우고,
     * 그 값이 가장 작은 변(조각의 껍질이 붙어 있는 변)을 돌려준다.
     */
    private fun hullSide(i: Int): Int {
        val o = i * SIDES
        segHull[o + SIDE_TOP] = max(max(seg[1], seg[3]), max(seg[5], seg[7]))
        segHull[o + SIDE_RIGHT] = w - min(min(seg[0], seg[2]), min(seg[4], seg[6]))
        segHull[o + SIDE_BOTTOM] = h - min(min(seg[1], seg[3]), min(seg[5], seg[7]))
        segHull[o + SIDE_LEFT] = max(max(seg[0], seg[2]), max(seg[4], seg[6]))
        var side = SIDE_TOP
        for (s in 1 until SIDES) if (segHull[o + s] < segHull[o + side]) side = s
        return side
    }

    /** 가장 가까운 화면 가장자리까지의 거리. */
    private fun edgeDistance(x: Float, y: Float): Float = min(min(x, w - x), min(y, h - y))

    private fun buildWavePoints() {
        val p = 2f * (w + h)
        val halfW = w / 2f
        channelPos[0] = 0f
        channelPos[1] = halfW / p
        channelPos[2] = (halfW + h / 2f) / p
        channelPos[3] = (halfW + h) / p
        channelPos[4] = (halfW + h + halfW) / p
        channelPos[5] = (halfW + h + w) / p
        channelPos[6] = (halfW + h + w + h / 2f) / p
        channelPos[7] = (halfW + h + w + h) / p

        val dTr = waveDepth(channelPos[1])
        val dBr = waveDepth(channelPos[3])
        val dBl = waveDepth(channelPos[5])
        val dTl = waveDepth(channelPos[7])

        for (i in 0 until WAVE_N) {
            val dist = (p * i) / WAVE_N
            val d = waveDepth(dist / p)
            roundedInnerPoint(dist, p, d, dTr, dBr, dBl, dTl)
            waveX[i] = px
            waveY[i] = py
        }
    }

    private fun waveDepth(t: Float): Float = min(baseDepth, max(0f, interpolateDepthCatmullRom(t)))

    private fun interpolateDepthCatmullRom(tIn: Float): Float {
        val t = ((tIn % 1f) + 1f) % 1f
        var idx1 = 0
        for (i in 0 until CH) if (channelPos[i] <= t) idx1 = i
        val idx0 = (idx1 + CH - 1) % CH
        val idx2 = (idx1 + 1) % CH
        val idx3 = (idx1 + 2) % CH

        val p1 = channelPos[idx1]
        var p2 = channelPos[idx2]
        if (p2 <= p1) p2 += 1f
        var at = t
        if (at < p1) at += 1f
        val segLen = p2 - p1
        val lt = (if (segLen > 0f) (at - p1) / segLen else 0f).coerceIn(0f, 1f)

        val d0 = depths[idx0]
        val d1 = depths[idx1]
        val d2 = depths[idx2]
        val d3 = depths[idx3]
        val a = -0.5f * d0 + 1.5f * d1 - 1.5f * d2 + 0.5f * d3
        val b = d0 - 2.5f * d1 + 2.0f * d2 - 0.5f * d3
        val c = -0.5f * d0 + 0.5f * d2
        return max(0f, ((a * lt + b) * lt + c) * lt + d1)
    }

    /** 둘레 거리 dist(상단 중앙 기준, 시계 방향) → 화면 테두리 좌표. 결과는 px/py. */
    private fun edgePosition(distIn: Float, p: Float) {
        val dist = ((distIn % p) + p) % p
        val halfW = w / 2f
        when {
            dist <= halfW -> { px = halfW + dist; py = 0f }
            dist <= halfW + h -> { px = w; py = dist - halfW }
            dist <= halfW + h + w -> { px = w - (dist - (halfW + h)); py = h }
            dist <= halfW + h + w + h -> { px = 0f; py = h - (dist - (halfW + h + w)) }
            else -> { px = dist - (halfW + h + w + h); py = 0f }
        }
    }

    /** 코너를 2차 베지어로 둥글린 안쪽 파도 좌표. 결과는 px/py. */
    private fun roundedInnerPoint(
        dist: Float, p: Float, d: Float,
        dTr: Float, dBr: Float, dBl: Float, dTl: Float
    ) {
        val cTr = w / 2f
        val cBr = w / 2f + h
        val cBl = cBr + w
        val cTl = cBl + h
        val maxR = min(w / 2f, h / 2f)

        // 라운딩 반경은 파도 깊이에 비례 (깊이 0 이면 깎이지 않음)
        val rTr = min(dTr * 2f, maxR)
        val rBr = min(dBr * 2f, maxR)
        val rBl = min(dBl * 2f, maxR)
        val rTl = min(dTl * 2f, maxR)

        var distToTL = dist - cTl
        if (dist < w / 2f) distToTL = dist + p - cTl

        if (abs(dist - cTr) <= rTr && rTr > 0f) {
            val t = (dist - cTr + rTr) / (2f * rTr)
            quadBezier(t, w - rTr, d, w - d, d, w - d, rTr)
            return
        }
        if (abs(dist - cBr) <= rBr && rBr > 0f) {
            val t = (dist - cBr + rBr) / (2f * rBr)
            quadBezier(t, w - d, h - rBr, w - d, h - d, w - rBr, h - d)
            return
        }
        if (abs(dist - cBl) <= rBl && rBl > 0f) {
            val t = (dist - cBl + rBl) / (2f * rBl)
            quadBezier(t, rBl, h - d, d, h - d, d, h - rBl)
            return
        }
        if (abs(distToTL) <= rTl && rTl > 0f) {
            val t = (distToTL + rTl) / (2f * rTl)
            quadBezier(t, d, rTl, d, d, rTl, d)
            return
        }

        edgePosition(dist, p)
        when {
            dist <= cTr || dist > cTl -> { px = max(d, min(w - d, px)); py = d }          // Top
            dist <= cBr -> { px = w - d; py = max(d, min(h - d, py)) }                    // Right
            dist <= cBl -> { px = max(d, min(w - d, px)); py = h - d }                    // Bottom
            else -> { px = d; py = max(d, min(h - d, py)) }                               // Left
        }
    }

    private fun quadBezier(t: Float, p0x: Float, p0y: Float, p1x: Float, p1y: Float, p2x: Float, p2y: Float) {
        val inv = 1f - t
        val a = inv * inv
        val b = 2f * inv * t
        val c = t * t
        px = a * p0x + b * p1x + c * p2x
        py = a * p0y + b * p1y + c * p2y
    }

    // ---------------- Pad ----------------

    private fun drawPad(canvas: NativeCanvas) {
        setPadCenters()

        val path = path
        path.rewind()
        path.fillType = Path.FillType.EVEN_ODD

        var any = false
        for (c in 0 until CH) {
            if (!buildPadBar(c)) continue
            any = true
            path.moveTo(padOuterX[0], padOuterY[0])
            for (i in 1..PAD_N) path.lineTo(padOuterX[i], padOuterY[i])
            for (i in PAD_N downTo 0) path.lineTo(padInnerX[i], padInnerY[i])
            path.close()
        }
        if (!any) return
        drawSolid(canvas, clip = padBand())
    }

    /** 패드 막대 가운데의 둘레 거리(상단 중앙 기준, 시계 방향)를 [centerDists] 에 채운다. */
    private fun setPadCenters() {
        val halfW = w / 2f
        centerDists[0] = 0f                          // FC 상단 중앙
        centerDists[1] = halfW                       // FR 우상단
        centerDists[2] = halfW + h / 2f              // SR 우측 중앙
        centerDists[3] = halfW + h                   // BR 우하단
        centerDists[4] = halfW + h + halfW           // BC 하단 중앙
        centerDists[5] = halfW + h + w               // BL 좌하단
        centerDists[6] = halfW + h + w + h / 2f      // SL 좌측 중앙
        centerDists[7] = halfW + h + w + h           // FL 좌상단
    }

    /**
     * 막대 [c] 의 바깥 점(화면 테두리)과 안쪽 점을 [padOuterX]·[padInnerX] 등에 채운다. 너무 얇아 보이지 않으면 false.
     * [setPadCenters] 를 먼저 불러 둔다.
     */
    private fun buildPadBar(c: Int): Boolean {
        if (!padBarVisible(c)) return false
        val maxThickness = padThickness[c]

        val p = 2f * (w + h)
        val halfW = w / 2f
        val barLen = h / 4f
        val startDist = centerDists[c] - barLen / 2f
        for (i in 0..PAD_N) {
            val distPos = startDist + (barLen * i) / PAD_N
            edgePosition(distPos, p)
            val ex = px
            val ey = py
            padOuterX[i] = ex
            padOuterY[i] = ey

            val cur = maxThickness * padEase[i]
            val dMod = ((distPos % p) + p) % p
            var ix = ex
            var iy = ey
            if (dMod <= halfW || dMod > halfW + h + w + h) {          // Top
                iy += cur; ix = max(cur, min(w - cur, ix))
            } else if (dMod <= halfW + h) {                            // Right
                ix -= cur; iy = max(cur, min(h - cur, iy))
            } else if (dMod <= halfW + h + w) {                        // Bottom
                iy -= cur; ix = max(cur, min(w - cur, ix))
            } else {                                                   // Left
                ix += cur; iy = max(cur, min(h - cur, iy))
            }
            padInnerX[i] = ix
            padInnerY[i] = iy
        }
        return true
    }

    /** 막대 [c] 를 그리는지. 너무 얇으면 보이지 않으므로 그리지도, 띠에 넣지도 않는다([padBand]). */
    private fun padBarVisible(c: Int): Boolean = padThickness[c] >= PAD_MIN_DP * density

    /**
     * 패드 막대가 변마다 들어올 수 있는 가장 깊은 거리(px)를 [sideBand] 에 채운다. 자르지 않고 그려야 하면 false.
     *
     * 막대는 직선으로 잇고, 안쪽 점은 그 막대의 두께만큼만 자기 변에서 들어온다. 가운데 막대는 한 변에만 놓인다.
     * 모서리 막대는 가운데 점이 꼭 모서리에 놓여, 앞 절반은 변 c/2 에, 뒤 절반은 다음 변에 놓이고 모서리를 가로지르는 선이 없다.
     * 모서리의 점은 두 변 모두에서 막대 두께 안이라, 막대 둘레의 어느 선도 그 선이 놓인 변에서 막대 두께보다 깊지 않다.
     * 그래서 막대의 두께를 그 막대가 놓인 변(모서리 막대는 두 변)에 넣는다(#365). 곡선과 달리 부풀 몫이 없다.
     * 그리지 않는 막대는 넣지 않으므로, 막대가 하나도 놓이지 않은 변은 -1 로 남아 그리지 않는다.
     *
     * 다만 세로가 가로의 4배를 넘는 화면에서는 위·아래 가운데 막대(길이 h/4)가 변을 넘어 모서리를 돌고, 그 선이
     * 모서리를 가로질러 더 깊이 들어올 수 있다. 그런 화면은 없지만, 있으면 자르지 않고 그린다.
     */
    private fun padBand(): Boolean {
        if (h / 4f > w) return false
        sideBand.fill(-1f)
        for (c in 0 until CH) {
            if (!padBarVisible(c)) continue
            val t = padThickness[c]
            val first = c / 2
            val second = (c + 1) / 2 % SIDES
            if (t > sideBand[first]) sideBand[first] = t
            if (t > sideBand[second]) sideBand[second] = t
        }
        return true
    }

    // ---------------- Circle ----------------

    private fun drawCircle(canvas: NativeCanvas) {
        val cx = w / 2f
        val cy = h / 2f
        // CircleRadius 10~100 → 화면 짧은 변의 0.05~0.40
        val radiusRatio = 0.05f + (settings.circleRadius - 10f) / 90f * 0.35f
        val baseRadius = min(w, h) * radiusRatio

        val path = path
        path.rewind()
        path.fillType = Path.FillType.EVEN_ODD

        for (i in 0 until CIRCLE_N) {
            val ft = circleFt[i]
            val depth = circleTargets[circleI0[i]] * (1f - ft) + circleTargets[circleI1[i]] * ft
            val r = baseRadius + depth
            val x = cx + cosT[i] * r
            val y = cy + sinT[i] * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        // 안쪽 고정 원을 역방향으로 그려 EvenOdd 로 구멍을 낸다
        for (i in 0 until CIRCLE_N) {
            val j = CIRCLE_N - 1 - i
            val x = cx + cosT[j] * baseRadius
            val y = cy + sinT[j] * baseRadius
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        // 원형은 화면 가운데라 가장자리 띠와 상관없다.
        drawSolid(canvas, clip = false)
    }

    // ---------------- 공통 페인팅 ----------------

    /** 단색으로 채운다. [clip] 이면 [padBand] 로 잰 가장자리 띠로 잘라 그린다([paintEdgeBand]). */
    private fun drawSolid(canvas: NativeCanvas, clip: Boolean) {
        val paint = fillPaint
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = (0xFF shl 24) or colorRgb
        if (clip) paintEdgeBand(canvas, null, Paint.Style.FILL, 0f)
        else paintPath(canvas, null, Paint.Style.FILL, 0f)
    }

    /**
     * [path] 를 가장자리 띠 네 개로 잘라 그린다(#172). 띠 두께는 변마다 따로 잰다(#365): [waveBand]·[padBand] 가 채운
     * 변마다의 경계로 [edgeBandThickness] 가 위·오른쪽·아래·왼쪽 두께 T·R·B·L 을 정한다. 한 변이라도 너무 두꺼우면 자르지 않고 그린다.
     *
     * 파도·외곽선·패드는 가장자리 띠만 칠하지만 경로의 범위는 화면 전체다. 그대로 그리면 안티앨리어싱
     * 마스크를 매 프레임 화면 전체 크기로 만든다. 잘라 그리면 마스크가 띠 크기로 줄고, 그려지는 화면은 같다.
     * Galaxy S25+ 에서 한 코어 기준 파도 68% → 41~45%, 외곽선 89% → 46~47%, 패드 68% → 24~26% 였다.
     * 소리가 한쪽에서만 크면 조용한 변의 띠가 그만큼 얇아진다. 예전에는 네 띠가 가장 깊은 변의 두께 하나를 썼다.
     *
     * 띠는 위 [0,w]×[0,T], 아래 [0,w]×[h−B,h], 왼쪽 [0,L]×[T,h−B], 오른쪽 [w−R,w]×[T,h−B] 다.
     * - 겹치지 않는다. 겹치면 그 자리만 두 번 칠해져 진해진다. 모서리는 위·아래 띠가 가진다.
     * - 두께가 정수라 경계가 픽셀에 맞는다. 각 두께가 짧은 변의 절반보다 얇으므로 T + B < h, L + R < w 다.
     * - 넷을 합친 곳은 "위에서 T 안, 아래에서 B 안, 왼쪽에서 L 안, 오른쪽에서 R 안 가운데 하나"인 점 전체와 같다.
     *   왼쪽에서 L 안인 점은 높이가 T 보다 위면 위 띠에, h−B 보다 아래면 아래 띠에, 그 사이면 왼쪽 띠에 든다.
     *   그래서 도형의 점마다 어느 한 변에서 그 변의 두께 안(여유까지)이기만 하면 잘리지 않는다. 모서리를 도는 조각도 같다.
     * - 띠로 둘러싼 가운데는 #350 의 가운데를 품고(어느 변도 #350 의 띠보다 두껍지 않다), 곡선은 그 가운데에 들어오지 않는다.
     *   그래서 가운데는 #350 처럼 통째로 곡선 안쪽(칠하지 않는 곳)이다.
     * - 그릴 것이 없는 변(막대가 하나도 놓이지 않은 패드의 변)은 두께 0 이라 자르기도 그리기도 하지 않는다.
     *
     * 발광이 켜져 있으면 블러가 번지는 폭([glowSpread])까지 띠에 더하고, 발광도 띠 안에서 그린다.
     * 도형이 띠 안에 있으니 발광이 번지는 곳까지 띠에 들고, 띠는 겹치지 않게 화면을 나누므로 그림은 같다.
     * 같은 기기에서 발광을 켠 채 두 방식을 번갈아 재어 파도 90% → 52%, 패드 88% → 31%, 외곽선 88% → 60% 였다(#189).
     * #172 에서는 발광이면 자르지 않는 쪽이 낫다고 보았는데, 그때 측정이 회차마다 벤치가 앞에서 밀려나
     * 그린 프레임 수가 달라진 것을 걸러 내지 않은 탓이었다. 회차를 프레임 수로 판정하고 다시 재니 뒤집혔다.
     */
    private fun paintEdgeBand(canvas: NativeCanvas, shader: Shader?, style: Paint.Style, strokeWidth: Float) {
        if (!edgeBandThickness(strokeWidth)) {
            paintPath(canvas, shader, style, strokeWidth)
            return
        }
        val top = sideThickness[SIDE_TOP]
        val bottom = h - sideThickness[SIDE_BOTTOM]
        val left = sideThickness[SIDE_LEFT]
        val right = w - sideThickness[SIDE_RIGHT]
        val glow = glowAlpha > 0f
        fillPaint.alpha = alphaByte(alpha)
        // withClip 은 인라인이라 프레임마다 할당하지 않는다. 두께가 0 인 변은 건너뛴다.
        if (top > 0f) canvas.withClip(0f, 0f, w, top) { paintInBand(this, glow, shader, style, strokeWidth) }             // 위 (모서리 포함)
        if (bottom < h) canvas.withClip(0f, bottom, w, h) { paintInBand(this, glow, shader, style, strokeWidth) }         // 아래 (모서리 포함)
        if (left > 0f) canvas.withClip(0f, top, left, bottom) { paintInBand(this, glow, shader, style, strokeWidth) }     // 왼쪽
        if (right < w) canvas.withClip(right, top, w, bottom) { paintInBand(this, glow, shader, style, strokeWidth) }     // 오른쪽
    }

    /**
     * 변마다 잘라 그릴 띠의 두께(px)를 [sideThickness] 에 채운다. 자르지 않고 그려야 하면 false.
     * 그 변의 경계([sideBand])에 안티앨리어싱 1px([EDGE_BAND_AA_PX]), 선 두께, 발광이 번지는 폭([glowSpread])을 더한다.
     * 맡은 도형이 없는 변(경계 -1)은 0 이다. 한 변이라도 화면 짧은 변의 절반에 닿으면 잘라 봐야 줄지 않으므로 false 다.
     * 변마다의 두께 가운데 가장 큰 것이 #350 의 띠 두께와 같은 값이라, 자를지 말지는 #350 과 똑같이 정해진다.
     *
     * 띠가 만나는 경계를 픽셀에 맞추려고 올림한다. 그래야 경계 줄이 두 띠에 모두 들거나 어느 쪽에도 들지 않는 일이 없다.
     * 외곽선은 선 두께를 다 더한다. 선은 곡선 양쪽으로 두께의 절반씩 나가고, 꺾인 이음(MITER)은 그보다 멀리 나갈 수 있다.
     * Catmull-Rom 곡선은 조각 사이 이음이 매끄러워 실제로는 절반만 나가지만, 두 배를 두면 이음이 꽤 꺾여도(120°까지) 띠 안에 든다.
     */
    private fun edgeBandThickness(strokeWidth: Float): Boolean {
        val glow = if (glowAlpha > 0f) glowSpread(glowRadiusPx) else 0f
        for (s in 0 until SIDES) {
            val band = sideBand[s]
            val t = if (band < 0f) 0f else ceil(band + EDGE_BAND_AA_PX + strokeWidth + glow)
            if (t * 2f >= min(w, h)) return false
            sideThickness[s] = t
        }
        return true
    }

    /** 띠 하나에 발광(켜져 있으면)과 본 도형을 그린다. 자른 범위 밖은 어차피 그려지지 않는다. */
    private fun paintInBand(canvas: NativeCanvas, glow: Boolean, shader: Shader?, style: Paint.Style, strokeWidth: Float) {
        if (glow) drawGlow(canvas, shader, style, strokeWidth)
        canvas.drawPath(path, fillPaint)
    }

    /** 발광(켜져 있으면)과 본 도형을 자르지 않고 그린다. [fillPaint] 의 색·셰이더·모양은 부르는 쪽이 정해 둔다. */
    private fun paintPath(canvas: NativeCanvas, shader: Shader?, style: Paint.Style, strokeWidth: Float) {
        if (glowAlpha > 0f) drawGlow(canvas, shader, style, strokeWidth)
        fillPaint.alpha = alphaByte(alpha)
        canvas.drawPath(path, fillPaint)
    }

    /**
     * 광원: 같은 도형을 블러 마스크로 한 번 더 깔아 아우라를 만든다.
     * 블러는 반경이 0.5px 넘게 바뀔 때만 새로 만든다. 이 0.5px 을 늘리면 띠에 더하는 발광 폭([glowSpread])의 1px 도 다시 따진다.
     */
    private fun drawGlow(canvas: NativeCanvas, shader: Shader?, style: Paint.Style, strokeWidth: Float) {
        if (blurFilter == null || abs(blurRadius - glowRadiusPx) > 0.5f) {
            blurRadius = glowRadiusPx
            blurFilter = BlurMaskFilter(glowRadiusPx, BlurMaskFilter.Blur.NORMAL)
            glowPaint.maskFilter = blurFilter
        }
        val paint = glowPaint
        paint.shader = shader
        paint.style = style
        paint.strokeWidth = strokeWidth
        paint.color = (0xFF shl 24) or colorRgb
        paint.alpha = alphaByte(glowAlpha * alpha)
        canvas.drawPath(path, paint)
    }

    /** 파도 채움: 중심 투명 → 0.7 지점 A=160 → 가장자리 불투명, 화면 비율에 맞춘 타원형 */
    private fun ensureWaveShader(): RadialGradient {
        val current = waveShader
        if (current != null && shaderW == w && shaderH == h && shaderRgb == colorRgb) return current
        shaderColors[0] = colorRgb
        shaderColors[1] = (160 shl 24) or colorRgb
        shaderColors[2] = (0xFF shl 24) or colorRgb
        val shader = RadialGradient(0.5f, 0.5f, 0.5f, shaderColors, shaderStops, Shader.TileMode.CLAMP)
        shaderMatrix.setScale(w, h)
        shader.setLocalMatrix(shaderMatrix)
        waveShader = shader
        shaderW = w
        shaderH = h
        shaderRgb = colorRgb
        return shader
    }

    private fun alphaByte(a: Float): Int = (a.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}

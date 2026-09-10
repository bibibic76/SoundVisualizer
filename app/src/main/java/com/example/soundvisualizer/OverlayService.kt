package com.example.soundvisualizer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as NativeCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class OverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private companion object {
        const val TAG = "OverlayService"
    }

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        SettingsManager.init(applicationContext)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        // 노치/상태바/내비게이션 영역까지 덮어서 파도가 화면 실제 테두리에서 시작하도록 한다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            params.fitInsetsTypes = 0
        } else {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                VisualizerOverlay()
            }
        }
        // 권한이 도중에 회수됐거나 시스템이 서비스를 되살린 경우 addView 가 BadTokenException 을
        // 던지고, onCreate 에서 터지면 프로세스가 죽는다. 확인 + 방어를 모두 건다.
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission not granted; stopping")
            stopEverything()
            return
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "failed to add overlay view", e)
            // composeView 를 비워 onDestroy 의 removeViewImmediate 를 건너뛰게 한다.
            composeView = null
            stopEverything()
            return
        }

        composeView = view
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /** 오버레이를 띄울 수 없으면 오디오 캡처도 의미가 없으므로 같이 정리한다. */
    private fun stopEverything() {
        stopService(Intent(this, AudioCaptureService::class.java))
        SettingsManager.setServiceRunning(false)
        stopSelf()
    }

    /** 프로세스가 죽었다가 시스템이 되살리면 오디오 없는 오버레이만 남는다. 되살리지 않는다. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        composeView?.let { view ->
            try {
                windowManager.removeViewImmediate(view)
            } catch (e: IllegalArgumentException) {
                // 이미 제거된 경우
            }
        }
        composeView = null
        store.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * 표현 모드. 저장은 [Enum.ordinal] 로 하므로 순서를 바꾸면 기존 설정이 어긋난다.
 */
enum class VisualMode(@StringRes val labelRes: Int) {
    Wave(R.string.mode_wave),
    Pad(R.string.mode_pad),
    CircleRipple(R.string.mode_circle),
    Outline(R.string.mode_outline)
}

/**
 * [VisualizerEngine] 이 프레임마다 읽는 바깥 세계.
 *
 * 엔진 자체는 산술만 하는데, 값의 출처는 네이티브 라이브러리와 SharedPreferences 와
 * 분류기다. 그 셋을 직접 부르면 기기 없이는 엔진을 만들 수조차 없으므로 한 겹으로 묶었다.
 * 실제 구동은 [LiveVisualizerInputs], 테스트는 가짜 구현을 넣는다.
 */
interface VisualizerInputs {
    /** [out] (크기 3) 에 `[좌 피크, 우 피크, 도착한 버퍼 수]` 를 채운다. */
    fun readPeaks(out: FloatArray)
    fun currentMode(): VisualMode
    fun settingsFor(mode: VisualMode): ModeSettings
    /** [AiClassification] 의 라벨 중 하나. */
    fun coarseLabel(): String
    fun colorFor(label: String): Int
    fun isShown(label: String): Boolean
}

/** 실제 구동 배선. */
object LiveVisualizerInputs : VisualizerInputs {
    override fun readPeaks(out: FloatArray) = AudioEngine.readPeaks(out)

    override fun currentMode(): VisualMode = SettingsManager.visualMode.value

    override fun settingsFor(mode: VisualMode): ModeSettings = when (mode) {
        VisualMode.Wave -> SettingsManager.waveMode.value
        VisualMode.Pad -> SettingsManager.padMode.value
        VisualMode.CircleRipple -> SettingsManager.circleMode.value
        VisualMode.Outline -> SettingsManager.outlineMode.value
    }

    override fun coarseLabel(): String = AiClassification.coarse()

    override fun colorFor(label: String): Int = when (label) {
        AiClassification.DANGER -> SettingsManager.colorDanger.value
        AiClassification.SPEECH -> SettingsManager.colorSpeech.value
        else -> SettingsManager.colorAmbient.value
    }

    override fun isShown(label: String): Boolean = when (label) {
        AiClassification.DANGER -> SettingsManager.showDanger.value
        AiClassification.SPEECH -> SettingsManager.showSpeech.value
        else -> SettingsManager.showAmbient.value
    }
}

/**
 * 오디오 피크 → 8채널 깊이 → 도형 → 캔버스까지 담당하는 렌더 엔진.
 *
 * 스무딩 파이프라인과 네 가지 모드의 기하 수식을 구현하되,
 *  - 프레임당 힙 할당이 0 이 되도록 모든 버퍼/Path/Paint 를 미리 잡아두고
 *  - 스무딩 계수는 60fps 기준으로 시간 정규화해서 90/120Hz 화면에서도 같은 느낌을 내고
 *  - 소리가 없으면 그리기 무효화를 멈추고(GPU 휴식), 1초 뒤엔 저빈도 폴링으로 내려간다.
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
        const val MAX_FPS = 60
        /** 이 시간 동안 아무것도 안 보이면 저빈도 폴링(idle)으로 전환 */
        private const val IDLE_AFTER_NS = 1_000_000_000L
        const val IDLE_POLL_MS = 33L
        /** maxVolume < 0.01 이면 비활성으로 본다 */
        private const val WAKE_THRESHOLD = 0.01f
        private const val MIN_VISIBLE_ALPHA = 0.002f
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
    private var visible = false
    private var wasVisible = false
    private var lastTickNanos = 0L      // 마지막으로 처리한 틱 (시간 정규화용)
    private var lastVsyncNanos = 0L     // 마지막으로 관측한 vsync (프레임 캡 누산용)
    private var invisibleSinceNanos = -1L
    private var frameAccNanos = 0L
    var isIdle = false
        private set

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
        // 프레임 캡: vsync 간격을 누적해서 1/MAX_FPS 마다 한 번만 처리 (90Hz → 2/3, 120Hz → 1/2)
        if (MAX_FPS > 0) {
            val interval = 1_000_000_000L / MAX_FPS - 200_000L
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

        val sfTremor = norm(min(1f, max(0.1f, s.sensitivity * SENSITIVITY_UI_SCALE) / 100f), k)
        smoothTotal += (total - smoothTotal) * sfTremor

        val sfPosition = norm(min(1f, max(0.1f, s.speed) / 100f), k)
        if (total > 0.0001f) {
            for (i in 0 until CH) dist[i] += (targets[i] / total - dist[i]) * sfPosition
        }

        // 4. 깊이(px). Intensity 100% 가 화면 중앙 한계선에 닿도록 매핑
        var maxBase = min(w, h) / 2f - EDGE_MARGIN_DP * density
        if (maxBase < EDGE_MARGIN_DP * density) maxBase = EDGE_MARGIN_DP * density
        val useOpacity = s.intensityAsOpacity
        val currentIntensity = if (useOpacity) s.opacityFixedSize / 2f else s.intensity
        baseDepth = maxBase * (max(0f, currentIntensity) / 100f)
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
        val coarse = inputs.coarseLabel()
        colorRgb = inputs.colorFor(coarse) and 0xFFFFFF
        val shown = inputs.isShown(coarse)
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
            targetL < WAKE_THRESHOLD && targetR < WAKE_THRESHOLD
        ) {
            enterIdle()
        }
        return needsRedraw
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

    internal fun debugState(): DebugState = DebugState(
        visible = visible,
        alpha = alpha,
        smoothTotal = smoothTotal,
        baseDepth = baseDepth,
        colorRgb = colorRgb,
        depths = depths.toList(),
        idle = isIdle
    )

    /** idle 중 저빈도 폴링. 소리가 감지되면 프레임 클럭으로 복귀한다. */
    fun pollWake() {
        inputs.readPeaks(peaks)
        if (peaks[0] > WAKE_THRESHOLD || peaks[1] > WAKE_THRESHOLD) {
            targetL = peaks[0]
            targetR = peaks[1]
            isIdle = false
            invisibleSinceNanos = -1L
            lastTickNanos = 0L
            lastVsyncNanos = 0L
            frameAccNanos = 0L
        }
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
                val i0 = (i + n - 1) % n
                val i2 = (i + 1) % n
                val i3 = (i + 2) % n
                val p0x = waveX[i0]; val p0y = waveY[i0]
                val p1x = waveX[i]; val p1y = waveY[i]
                val p2x = waveX[i2]; val p2y = waveY[i2]
                val p3x = waveX[i3]; val p3y = waveY[i3]
                path.cubicTo(
                    p1x + (p2x - p0x) / 6f, p1y + (p2y - p0y) / 6f,
                    p2x - (p3x - p1x) / 6f, p2y - (p3y - p1y) / 6f,
                    p2x, p2y
                )
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
        if (isWave) {
            paint.style = Paint.Style.FILL
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = OUTLINE_STROKE_DP * density
        }
        if (glowAlpha > 0f) drawGlow(canvas, shader, paint.style, paint.strokeWidth)
        paint.alpha = alphaByte(alpha)
        canvas.drawPath(path, paint)
    }

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
        val p = 2f * (w + h)
        val halfW = w / 2f
        centerDists[0] = 0f                          // FC 상단 중앙
        centerDists[1] = halfW                       // FR 우상단
        centerDists[2] = halfW + h / 2f              // SR 우측 중앙
        centerDists[3] = halfW + h                   // BR 우하단
        centerDists[4] = halfW + h + halfW           // BC 하단 중앙
        centerDists[5] = halfW + h + w               // BL 좌하단
        centerDists[6] = halfW + h + w + h / 2f      // SL 좌측 중앙
        centerDists[7] = halfW + h + w + h           // FL 좌상단

        val path = path
        path.rewind()
        path.fillType = Path.FillType.EVEN_ODD

        val minVisible = PAD_MIN_DP * density
        val barLen = h / 4f
        var any = false

        for (c in 0 until CH) {
            val maxThickness = padThickness[c]
            if (maxThickness < minVisible) continue
            any = true

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

            path.moveTo(padOuterX[0], padOuterY[0])
            for (i in 1..PAD_N) path.lineTo(padOuterX[i], padOuterY[i])
            for (i in PAD_N downTo 0) path.lineTo(padInnerX[i], padInnerY[i])
            path.close()
        }
        if (!any) return
        drawSolid(canvas)
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

        drawSolid(canvas)
    }

    // ---------------- 공통 페인팅 ----------------

    private fun drawSolid(canvas: NativeCanvas) {
        val paint = fillPaint
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = (0xFF shl 24) or colorRgb
        if (glowAlpha > 0f) drawGlow(canvas, null, Paint.Style.FILL, 0f)
        paint.alpha = alphaByte(alpha)
        canvas.drawPath(path, paint)
    }

    /** 광원: 같은 도형을 블러 마스크로 한 번 더 깔아 아우라를 만든다. */
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

@Composable
fun VisualizerOverlay() {
    val density = LocalDensity.current.density
    val engine = remember(density) { VisualizerEngine(density) }
    // 프레임 틱 → 그리기 무효화를 잇는 유일한 Compose 상태. 리컴포지션은 발생하지 않는다.
    val frame = remember { mutableLongStateOf(0L) }

    LaunchedEffect(engine) {
        while (true) {
            if (engine.isIdle) {
                delay(VisualizerEngine.IDLE_POLL_MS)
                engine.pollWake()
            } else {
                withFrameNanos { nanos ->
                    if (engine.tick(nanos)) frame.longValue++
                }
            }
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        engine.draw(drawContext.canvas.nativeCanvas, size.width, size.height, frame.longValue)
    }
}

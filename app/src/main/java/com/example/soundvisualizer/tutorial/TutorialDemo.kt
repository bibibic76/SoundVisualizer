package com.example.soundvisualizer.tutorial

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.example.soundvisualizer.AccentColor
import com.example.soundvisualizer.AccentFillColor
import com.example.soundvisualizer.AiClassification
import com.example.soundvisualizer.BgColor
import com.example.soundvisualizer.CardColor
import com.example.soundvisualizer.LiveVisualizerInputs
import com.example.soundvisualizer.PrimaryTextColor
import com.example.soundvisualizer.R
import com.example.soundvisualizer.SecondaryTextColor
import com.example.soundvisualizer.SettingsManager
import com.example.soundvisualizer.VisualizerEngine
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 가로로 눕힌 폰 그림의 가로:세로. 요즘 폰 화면(19.5:9 안팎)에 테두리를 더한 비율이다. */
private const val PHONE_ASPECT = 2.05f
private val PHONE_MAX_WIDTH = 340.dp
private val BEZEL = 6.dp
private val PHONE_CORNER = 22.dp
private val SCREEN_CORNER = 16.dp

/**
 * 폰 그림의 화면을 어떤 폰을 줄여 놓은 것으로 볼지: 짧은 변이 이만한(dp) 폰의 가로 화면.
 * 기기마다 달리 잡지 않고 한 폰으로 고정해, 어느 기기에서나 같은 그림이 되게 한다.
 */
private const val REFERENCE_SHORT_SIDE_DP = 411f

/** 진동 쪽에서 폰 양옆에 진동 표시를 그릴 자리. */
private val VIBRATION_MARK_ROOM = 26.dp

private val PhoneBodyColor = Color(0xFF101216)
private val PhoneEdgeColor = Color(0xFF3A3F47)

// 게임 장면. 어둡게 두어 흰 환경음 색도 잘 보이게 한다.
private val SkyTop = Color(0xFF2A3854)
private val SkyBottom = Color(0xFF151C2B)
private val FarHill = Color(0xFF1B2335)
private val NearHill = Color(0xFF0E131D)
private val Moon = Color(0x40FFFFFF)

private const val FRAME_NS = 16_666_667L

/** 120Hz 화면에서도 1초에 60번쯤만 다시 그린다. 엔진도 그만큼만 새로 계산한다. */
private const val MIN_REDRAW_NS = 16_400_000L

/**
 * 그림의 프레임을 따라 무언가를 내는 쪽(진짜 진동 [TutorialHaptics]). 그림이 움직이는 동안 프레임마다 [onFrame] 을,
 * 멈출 때(쪽을 넘김·멈춤 버튼·그림이 사라짐) [stop] 을 부른다. 메인 스레드에서만 부른다.
 */
internal interface TutorialFrameFollower {
    /** 그림이 새로 그릴 때. [t] 는 그림이 움직이기 시작한 뒤 흐른 초다. */
    fun onFrame(t: Float)

    /** 그림이 멈출 때. 다시 움직이면 [onFrame] 이 0초부터 다시 온다. */
    fun stop()
}

/**
 * 튜토리얼 한 쪽의 그림. 가로로 눕힌 폰 안의 게임 장면 위에 **실제 오버레이 엔진**이 [TutorialScript] 의 소리를 그린다.
 *
 * - 폰 그림이 실제 화면보다 작으므로 엔진의 density 를 그 비율만큼 줄인다. 여백·두께·보이기 시작하는 깊이가
 *   실제 화면을 줄여 놓은 비율로 맞는다.
 * - [running] 일 때만 움직인다. 페이저가 옆 쪽을 미리 그려 두는 동안이나 멈춤을 눌렀을 때는 그 쪽이 말하려는
 *   장면([TutorialScene.stillAtSec]) 한 장을 보여 준다. 움직이기 시작할 때마다 대본을 처음부터 튼다.
 * - 그림은 옆의 글이 말하는 것을 보여 주기만 하므로 화면 읽어주기에서는 통째로 건너뛴다.
 * - 진동 쪽은 그림 속 폰이 떠는 순간마다 진짜 폰도 울린다([TutorialHaptics], #323). 움직이는 동안만 울린다.
 * - 소리를 켜 두었으면 그림이 그리는 소리를 실제 녹음으로 함께 낸다([TutorialSounds], #327). 움직이는 동안만 낸다.
 *
 * @param time 대본의 지금 시각. 종류 쪽의 범례가 함께 읽는다.
 * @param haptics 진짜 진동. 진동 쪽이 아니거나 진동 모터가 없으면 null 이다.
 * @param sounds 그 쪽의 소리. 소리를 꺼 두었으면 null 이다. 움직이는 도중에 바뀌어도 다음 프레임부터 따른다.
 */
@Composable
internal fun TutorialIllustration(
    scene: TutorialScene,
    running: Boolean,
    time: MutableFloatState,
    modifier: Modifier = Modifier,
    haptics: TutorialFrameFollower? = if (scene == TutorialScene.Vibration) rememberTutorialHaptics() else null,
    sounds: TutorialFrameFollower? = null
) {
    BoxWithConstraints(modifier = modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        val markRoom = if (scene == TutorialScene.Vibration) VIBRATION_MARK_ROOM else 0.dp
        val phoneWidth = min(min(maxWidth - markRoom * 2, maxHeight * PHONE_ASPECT), PHONE_MAX_WIDTH)
        if (phoneWidth > 0.dp) {
            val phoneHeight = phoneWidth / PHONE_ASPECT
            // 폰 그림의 왼쪽·오른쪽은 실제 방향이다(왼쪽 소리는 왼쪽). 아랍어처럼 오른쪽에서 쓰는 언어에서도 뒤집지 않는다.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (markRoom > 0.dp) {
                        VibrationMarks(scene, time, running, mirrored = false, Modifier.width(markRoom).height(phoneHeight))
                    }
                    DemoPhone(scene, time, running, phoneWidth, phoneHeight, haptics, sounds)
                    if (markRoom > 0.dp) {
                        VibrationMarks(scene, time, running, mirrored = true, Modifier.width(markRoom).height(phoneHeight))
                    }
                }
            }
        }
    }
}

@Composable
private fun DemoPhone(
    scene: TutorialScene,
    time: MutableFloatState,
    running: Boolean,
    phoneWidth: Dp,
    phoneHeight: Dp,
    haptics: TutorialFrameFollower?,
    sounds: TutorialFrameFollower?
) {
    val density = LocalDensity.current
    val screenWidthPx = with(density) { (phoneWidth - BEZEL * 2).toPx() }
    val screenHeightPx = with(density) { (phoneHeight - BEZEL * 2).toPx() }
    val engineDensity = screenHeightPx / REFERENCE_SHORT_SIDE_DP
    // 입력은 이 쪽만 쓴다. 넘기는 동안 두 쪽의 엔진이 함께 돌기 때문이다.
    val inputs = remember(scene) { TutorialDemoInputs(scene, LiveVisualizerInputs::colorFor) }
    // 크기를 먼저 알려야 한다. 모르는 채로 틱을 돌리면 깊이가 거의 0 인 채로 굳는다.
    // 입력은 반드시 넘긴다. 빼면 기본값인 실제 소리가 그려진다.
    fun newEngine(source: TutorialDemoInputs) =
        VisualizerEngine(engineDensity, source).also { it.setSurfaceSize(screenWidthPx, screenHeightPx) }

    // 멈춰 둘 장면([TutorialScene.stillAtSec]) 한 장. 옆 쪽을 미리 그려 둘 때·멈춤·애니메이션 끔에 쓴다.
    // 대본을 2초 남짓 미리 돌려야 해서(엔진 틱 백여 번), 화면을 그리는 스레드에서 만들면 옆 쪽이 들어오거나 쪽이
    // 멈추는 순간 넘기기가 툭 끊긴다(#318). 그래서 쪽마다 한 번만, 백그라운드에서 만들어 두고 움직이다 멈춰도 다시
    // 만들지 않는다. 다 만들어지면 상태가 바뀌어 다시 그린다(애니메이션을 꺼 둔 사람에게도 그림이 비지 않는다).
    // 움직이는 엔진과 시각이 섞이지 않게 입력도 따로 쓴다.
    val stillInputs = remember(scene) { TutorialDemoInputs(scene, LiveVisualizerInputs::colorFor) }
    val stillEngine by produceState<VisualizerEngine?>(null, stillInputs, engineDensity, screenWidthPx, screenHeightPx) {
        value = withContext(Dispatchers.Default) {
            newEngine(stillInputs).also { it.runTo(stillInputs, scene.stillAtSec) }
        }
    }
    // 움직이기 시작할 때마다 새 엔진으로 처음부터 그린다. 멈춰 있던 동안의 상태가 남지 않는다. 미리 돌리지 않아 금방 만든다.
    val liveEngine = remember(inputs, engineDensity, running, screenWidthPx, screenHeightPx) {
        if (running) newEngine(inputs) else null
    }
    val engine = if (running) liveEngine else stillEngine
    // 엔진이 새로 계산할 때마다 올린다. 그리기가 이 값을 읽어, 시각(time)이 같은 값이어도 다시 그린다.
    val frame = remember { mutableIntStateOf(0) }
    // 소리는 움직이는 도중에도 켜고 끈다. 돌고 있는 루프가 지금 것을 읽게 한다.
    val currentSounds by rememberUpdatedState(sounds)

    LaunchedEffect(liveEngine) {
        val live = liveEngine
        if (live == null) {
            time.floatValue = scene.stillAtSec
            frame.intValue++
            return@LaunchedEffect
        }
        time.floatValue = 0f
        var start = -1L
        var lastDraw = 0L
        try {
            while (true) {
                // 끝없이 도는 애니메이션으로 알린다. UI 테스트처럼 이런 애니메이션을 멈춰 두는 곳에서 화면이 한가해질 수
                // 있다(무한 전환 rememberInfiniteTransition 과 같은 길). 앱에서는 withFrameNanos 와 똑같다.
                withInfiniteAnimationFrameNanos { nanos ->
                    if (start < 0L) start = nanos
                    if (nanos - lastDraw >= MIN_REDRAW_NS) {
                        lastDraw = nanos
                        val t = (nanos - start) / 1_000_000_000f
                        inputs.timeSec = t
                        // 조용해서 엔진이 쉬는 중이면 소리가 다시 날 때까지 확인만 한다. 오버레이의 쉬기(rest)와 달리
                        // OverlayWake 는 쓰지 않는다. 그 신호를 기다리는 쪽은 실제 오버레이 하나뿐이어야 한다.
                        if (live.isIdle) live.pollWake() else live.tick(nanos)
                        time.floatValue = t
                        frame.intValue++
                        // 그림의 떨림(아래 graphicsLayer)과 같은 프레임에 진짜 진동과 소리를 보낸다.
                        haptics?.onFrame(t)
                        currentSounds?.onFrame(t)
                    }
                }
            }
        } finally {
            // 멈추거나(쪽을 넘김·멈춤 버튼) 그림이 사라지면 울리던 진동과 나던 소리도 끊는다.
            haptics?.stop()
            currentSounds?.stop()
        }
    }

    val shakePx = with(density) { 2.5.dp.toPx() }
    Box(
        modifier = Modifier
            .size(phoneWidth, phoneHeight)
            .graphicsLayer {
                // 처음 설정의 위협음 진동(0.5초마다 0.2초)에 맞춰 떤다. 진짜 진동은 같은 순간에 [TutorialHaptics] 가 울린다.
                val t = time.floatValue
                translationX = if (running && TutorialScript.vibrating(scene, t)) {
                    sin(t * 2f * PI.toFloat() * 32f) * shakePx
                } else 0f
            }
            .background(PhoneBodyColor, RoundedCornerShape(PHONE_CORNER))
            .border(1.dp, PhoneEdgeColor, RoundedCornerShape(PHONE_CORNER))
            .padding(BEZEL)
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(SCREEN_CORNER))
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val sky = Brush.verticalGradient(listOf(SkyTop, SkyBottom))
                    val far = Path().apply {
                        moveTo(0f, h * 0.7f)
                        quadraticTo(w * 0.2f, h * 0.5f, w * 0.42f, h * 0.66f)
                        quadraticTo(w * 0.62f, h * 0.8f, w * 0.8f, h * 0.6f)
                        quadraticTo(w * 0.92f, h * 0.5f, w, h * 0.62f)
                        lineTo(w, h)
                        lineTo(0f, h)
                        close()
                    }
                    val near = Path().apply {
                        moveTo(0f, h * 0.86f)
                        quadraticTo(w * 0.3f, h * 0.72f, w * 0.55f, h * 0.84f)
                        quadraticTo(w * 0.78f, h * 0.94f, w, h * 0.8f)
                        lineTo(w, h)
                        lineTo(0f, h)
                        close()
                    }
                    val sourceDot = 3.5.dp.toPx()
                    val rippleStart = 5.dp.toPx()
                    val rippleGrow = 16.dp.toPx()
                    val rippleStroke = Stroke(width = 1.5.dp.toPx())
                    onDrawBehind {
                        drawRect(sky)
                        drawCircle(Moon, radius = h * 0.1f, center = Offset(w * 0.7f, h * 0.27f))
                        drawPath(far, FarHill)
                        drawPath(near, NearHill)

                        // 프레임마다 다시 그리게 하는 것은 이 읽기다. 리컴포지션은 일어나지 않는다.
                        val t = time.floatValue
                        // 방향 쪽: 장면 안에서 소리가 나는 자리
                        val side = TutorialScript.sourceSide(scene, t)
                        if (side != 0) {
                            val center = Offset(if (side < 0) w * 0.2f else w * 0.8f, h * 0.56f)
                            val level = TutorialScript.sourceLevel(scene, t)
                            drawCircle(PrimaryTextColor.copy(alpha = (0.5f + 0.5f * level).coerceAtMost(1f)), sourceDot, center)
                            val ripple = TutorialScript.sourceRipple(scene, t)
                            if (ripple >= 0f) {
                                drawCircle(
                                    PrimaryTextColor.copy(alpha = 0.6f * (1f - ripple)),
                                    rippleStart + rippleGrow * ripple, center, style = rippleStroke
                                )
                            }
                        }
                        // 값은 쓰지 않고, 읽어서 엔진이 새로 계산할 때마다 다시 그리게 한다(VisualizerOverlay 와 같은 방식).
                        val serial = frame.intValue.toLong()
                        // 멈춘 장면이 아직 만들어지는 중이면(잠깐) 게임 장면만 그린다.
                        drawIntoCanvas { engine?.draw(it.nativeCanvas, w, h, serial) }
                    }
                }
        )
    }
}

/** 멈춰 둘 때: [seconds] 까지 60fps 로 미리 돌려 그 순간의 모양을 만든다. 엔진의 크기를 먼저 알려 둬야 한다. */
private fun VisualizerEngine.runTo(inputs: TutorialDemoInputs, seconds: Float) {
    val frames = (seconds * 60f).toInt()
    var nanos = FRAME_NS
    for (i in 0..frames) {
        inputs.timeSec = i / 60f
        if (isIdle) pollWake() else tick(nanos)
        nanos += FRAME_NS
    }
}

/** 폰이 떨 때 옆에 뜨는 진동 표시. 멈춰 둔 그림에서는 늘 보인다. */
@Composable
private fun VibrationMarks(
    scene: TutorialScene,
    time: MutableFloatState,
    running: Boolean,
    mirrored: Boolean,
    modifier: Modifier
) {
    Canvas(modifier) {
        val on = !running || TutorialScript.vibrating(scene, time.floatValue)
        if (!on) return@Canvas
        val stroke = 2.5.dp.toPx()
        val gap = 6.dp.toPx()
        val cy = size.height / 2f
        // 폰 쪽에서 바깥으로: 긴 줄, 짧은 줄
        val near = if (mirrored) gap else size.width - gap
        val far = if (mirrored) gap * 2.2f else size.width - gap * 2.2f
        drawLine(PrimaryTextColor, Offset(near, cy - size.height * 0.22f), Offset(near, cy + size.height * 0.22f), stroke, StrokeCap.Round)
        drawLine(PrimaryTextColor, Offset(far, cy - size.height * 0.12f), Offset(far, cy + size.height * 0.12f), stroke, StrokeCap.Round)
    }
}

/**
 * 종류 쪽의 범례. 색만으로 전하지 않도록 종류 이름과 어떤 소리인지를 함께 적는다. 색은 사용자가 설정 탭에서 고른 색이다.
 *
 * 지금 그림에 나오는 종류는 둥근 바탕으로 밝힌다(색이 아닌 표시). 나머지도 흐리게 하지 않는다. 흐리게 하면
 * 글자 대비가 모자란다. 이 표시는 눈으로만 보는 것이라 화면 읽어주기에 알리지 않는다. 알리면 2초마다 다시 읽는다.
 */
@Composable
internal fun TypeLegend(current: String, modifier: Modifier = Modifier) {
    val ambient by SettingsManager.colorAmbient.collectAsState()
    val speech by SettingsManager.colorSpeech.collectAsState()
    val danger by SettingsManager.colorDanger.collectAsState()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LegendRow(R.string.tutorial_type_ambient, R.string.tutorial_type_ambient_examples, Color(ambient), current == AiClassification.AMBIENT)
        LegendRow(R.string.tutorial_type_speech, R.string.tutorial_type_speech_examples, Color(speech), current == AiClassification.SPEECH)
        LegendRow(R.string.tutorial_type_danger, R.string.tutorial_type_danger_examples, Color(danger), current == AiClassification.DANGER)
    }
}

@Composable
private fun LegendRow(@StringRes name: Int, @StringRes examples: Int, color: Color, active: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) CardColor else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            // 이름과 예를 한 덩어리로 읽는다("환경음, 음악·빗소리 등 그 밖의 소리").
            .semantics(mergeDescendants = true) { }
    ) {
        // 어두운 색을 골랐어도 보이도록 설정 탭의 색 동그라미처럼 테두리를 두른다.
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, SecondaryTextColor.copy(alpha = 0.5f), CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        // 이름 아래에 예를 둔다. 한 줄에 나란히 두면 큰 글꼴과 긴 이름(독일어 등)에서 예가 들어갈 폭이 거의 없다.
        // 굵기는 바꾸지 않는다. 2초마다 글자 폭이 바뀌면 줄이 바뀌어 쪽 전체가 위아래로 흔들린다.
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(name), fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor)
            Text(stringResource(examples), fontSize = 14.sp, lineHeight = 20.sp, color = SecondaryTextColor)
        }
    }
}

/** 세워 든 폰 그림의 세로:가로. */
private const val PORTRAIT_ASPECT = 2.05f
private val HOME_PHONE_MAX_WIDTH = 170.dp

/**
 * 마지막 쪽의 그림: 홈 화면을 줄여 그린 세워 든 폰. 실행 버튼 둘레로 테두리가 퍼져 누를 곳을 가리킨다.
 *
 * 진짜 버튼처럼 보이면 눌러 보게 되므로 폰 안에 작게 그린다. 그림이라 누를 수 없고 화면 읽어주기에서는 건너뛴다.
 * 글자는 글꼴 크기 설정을 따르지 않는다. 그림 속 글자가 커지면 작은 폰 화면을 넘친다.
 */
@Composable
internal fun HomeScreenIllustration(running: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        val phoneWidth = min(min(maxHeight / PORTRAIT_ASPECT, maxWidth), HOME_PHONE_MAX_WIDTH)
        if (phoneWidth > 0.dp) HomePhone(phoneWidth, phoneWidth * PORTRAIT_ASPECT, running)
    }
}

@Composable
private fun HomePhone(width: Dp, height: Dp, running: Boolean) {
    val density = LocalDensity.current
    // 그림 속 글자는 dp 로 고정한다(위 설명).
    val titleSize = with(density) { 13.dp.toSp() }
    val buttonTextSize = with(density) { 10.dp.toSp() }
    val pulse = if (running) {
        rememberInfiniteTransition(label = "start").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
            label = "pulse"
        )
    } else null
    Box(
        modifier = Modifier
            .size(width, height)
            .background(PhoneBodyColor, RoundedCornerShape(PHONE_CORNER))
            .border(1.dp, PhoneEdgeColor, RoundedCornerShape(PHONE_CORNER))
            .padding(BEZEL)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(SCREEN_CORNER))
                .background(BgColor)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(R.string.home_title),
                fontSize = titleSize, lineHeight = titleSize * 1.3f, fontWeight = FontWeight.Black, color = PrimaryTextColor
            )
            // 설명 두 줄과 상태 줄은 글자 대신 막대로 그린다.
            Spacer(Modifier.height(8.dp))
            MockLine(0.95f)
            Spacer(Modifier.height(5.dp))
            MockLine(0.7f)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(SecondaryTextColor))
                Spacer(Modifier.width(5.dp))
                MockLine(0.45f)
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                val corner = 8.dp
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 30.dp)
                        .drawBehind {
                            // 둘레로 퍼지는 테두리. 그리기 단계에서만 값을 읽어 리컴포지션을 일으키지 않는다.
                            // 멈춰 둔 그림에서는 한 겹을 멈춰 둔다.
                            val p = pulse?.value ?: 0.35f
                            val grow = 7.dp.toPx() * p
                            drawRoundRect(
                                color = AccentFillColor.copy(alpha = 0.8f * (1f - p)),
                                topLeft = Offset(-grow, -grow),
                                size = Size(size.width + grow * 2, size.height + grow * 2),
                                cornerRadius = CornerRadius(corner.toPx() + grow),
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }
                        .background(AccentFillColor, RoundedCornerShape(corner))
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.home_start),
                        fontSize = buttonTextSize, lineHeight = buttonTextSize * 1.2f, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(6.dp))
                // 꺼져 있을 때의 실행 종료 버튼(홈과 같은 비활성 색)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 30.dp)
                        .background(Color(0xFF333A44), RoundedCornerShape(corner))
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.home_stop),
                        fontSize = buttonTextSize, lineHeight = buttonTextSize * 1.2f, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, color = SecondaryTextColor, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun MockLine(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(5.dp)
            .clip(RoundedCornerShape(2.5.dp))
            .background(SecondaryTextColor.copy(alpha = 0.45f))
    )
}

package com.example.soundvisualizer.tutorial

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.soundvisualizer.AccentColor
import com.example.soundvisualizer.AccentFillColor
import com.example.soundvisualizer.BgColor
import com.example.soundvisualizer.CardColor
import com.example.soundvisualizer.PAGE_SNAP_THRESHOLD
import com.example.soundvisualizer.PrimaryTextColor
import com.example.soundvisualizer.R
import com.example.soundvisualizer.SecondaryTextColor
import com.example.soundvisualizer.SettingsManager
import com.example.soundvisualizer.rememberRestingPage
import kotlinx.coroutines.launch

/**
 * 튜토리얼의 쪽. 선언 순서가 보이는 순서다.
 *
 * @param body 설명. 마지막 쪽은 설명 대신 켜고 끄는 순서를 번호로 보여 주므로 없다.
 */
enum class TutorialPage(@StringRes val title: Int, @StringRes val body: Int?, val scene: TutorialScene?) {
    Sound(R.string.tutorial_sound_title, R.string.tutorial_sound_body, TutorialScene.Sound),
    Direction(R.string.tutorial_direction_title, R.string.tutorial_direction_body, TutorialScene.Direction),
    Types(R.string.tutorial_types_title, R.string.tutorial_types_body, TutorialScene.Types),
    Vibration(R.string.tutorial_vibration_title, R.string.tutorial_vibration_body, TutorialScene.Vibration),
    Start(R.string.tutorial_start_title, null, null);

    /**
     * 지금 모드에 맞는 제목. 외부 사운드 모드면 방향 쪽은 아직 가운데로만 그린다고 말한다(#268).
     * 그 모드는 마이크 입력을 좌우 같게 만들어(`toDualMono`) 모든 소리를 가운데로 그린다. 좌우를 구분한다는 제목은
     * 그 모드에서 거짓이고, 왼쪽에서 부르는 소리를 "앞에서 난 소리" 로 읽게 만든다.
     */
    @StringRes
    fun titleFor(externalSoundMode: Boolean): Int =
        if (this == Direction && externalSoundMode) R.string.tutorial_direction_title_external else title

    /**
     * 지금 모드에 맞는 설명. 외부 사운드 모드면 다음 두 쪽을 그 모드에 맞게 바꾼다.
     * - 소리 쪽: 마이크로 주변 소리를 그린다고 설명한다(#226). "폰에서 재생되는 소리만, 주변 소리는 듣지 않는다" 는
     *   그 모드에서 거짓이다.
     * - 방향 쪽: 아직 가운데로만 그린다고 설명한다([titleFor], #268).
     */
    @StringRes
    fun bodyFor(externalSoundMode: Boolean): Int? = when {
        !externalSoundMode -> body
        this == Sound -> R.string.tutorial_sound_body_external
        this == Direction -> R.string.tutorial_direction_body_external
        else -> body
    }
}

/**
 * 이 높이보다 낮으면(가로 화면 등) 위의 이전·건너뛰기 줄을 없애고 아래 한 줄에 모든 버튼을 모은다.
 * 위아래 버튼 줄이 화면의 절반을 차지하면 글을 읽을 자리가 거의 남지 않는다.
 */
private val COMPACT_HEIGHT = 480.dp

/** 아래 한 줄에 버튼을 모두 모을 때 필요한 폭. 이보다 좁으면 낮은 화면이어도 위아래 두 줄로 둔다. */
private val COMPACT_MIN_WIDTH = 600.dp

/**
 * 처음 여는 사람에게 앱이 무엇을 하는지 보여 주는 화면. 홈의 ‘튜토리얼 보기’로 다시 열 수 있다.
 *
 * 쪽마다 그림 · 제목 · 짧은 설명이 있고, 밀어서 넘기거나 아래 ‘다음’으로 넘긴다. 마지막 쪽의 ‘확인’과 ‘건너뛰기’,
 * 첫 쪽에서의 뒤로 가기가 [onClose] 를 부른다. 시각화를 켜지는 않는다. 켜는 것은 홈의 실행 버튼이다.
 *
 * 화면 읽어주기: 튜토리얼 전체에 창 이름(paneTitle)을 붙여 열리고 닫힐 때 알리고, 쪽이 바뀌면 새 쪽의 제목으로
 * 초점을 옮겨 제목부터 읽게 한다. 초점이 ‘다음’에 남으면 새 쪽을 들으려고 거꾸로 훑어야 한다.
 *
 * 소리(#327): 멈춤 버튼 옆의 소리 버튼을 누르면 그림이 그리는 소리를 실제 녹음으로 함께 낸다. 처음에는 꺼져 있고,
 * 튜토리얼을 다시 열면 다시 꺼진 채로 시작한다. 듣는 사람이 옆에서 함께 볼 때를 위한 것이다.
 */
@Composable
fun TutorialScreen(onClose: () -> Unit) {
    val pages = TutorialPage.entries
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    // 그림을 멈춰 둘지. 모든 쪽이 함께 쓴다. 저절로 되풀이하는 움직임은 멈출 수 있어야 한다(WCAG 2.2.2).
    // 시스템에서 애니메이션을 꺼 두었으면 멈춘 채로 시작한다.
    var paused by rememberSaveable { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    val titleFocus = remember { List(pages.size) { FocusRequester() } }

    // 소리를 켜면 그때 조각을 읽어 두고, 끄거나 튜토리얼을 닫으면 놓는다.
    var soundOn by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val soundPlayer = remember(soundOn) { if (soundOn) TutorialSoundPlayer(context) else null }
    DisposableEffect(soundPlayer) {
        onDispose { soundPlayer?.close() }
    }
    // 앱을 내리면 나던 소리도 멈춘다. 그림은 화면이 다시 보일 때까지 다음 소리를 내지 않는다.
    val lifecycle = (LocalActivity.current as? LifecycleOwner)?.lifecycle
    DisposableEffect(lifecycle, soundPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) soundPlayer?.stopAll()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    // 손을 떼고 정말 멈춘 쪽. 넘기는 동안에는 앞서 멈췄던 쪽에 머문다.
    val restingPage by rememberRestingPage(pagerState)

    // 처음 열 때와 쪽이 넘어갈 때마다 그 쪽의 제목으로 초점을 옮긴다. 넘어가는 도중이 아니라 멈춘 뒤에 옮긴다.
    // 초점을 받은 제목은 자기를 화면에 보이려고 페이저를 끌어온다. 넘기는 도중에 옮기면 넘어가던 화면이 그 쪽으로
    // 되돌아간다(#339). 그래서 페이저의 settledPage 가 아니라 정말 멈춘 쪽을 따른다.
    LaunchedEffect(restingPage) {
        // 제목이 아직 붙지 않았으면(드물다) 옮기지 않는다. 옮기지 못해도 쪽은 제대로 보인다.
        runCatching { titleFocus[restingPage].requestFocus() }
    }

    // 연달아 누르면 넘어가는 중인 쪽(targetPage)을 기준으로 한 쪽 더 간다.
    fun goBack() {
        val target = pagerState.targetPage
        if (target > 0) scope.launch { pagerState.animateScrollToPage(target - 1) } else onClose()
    }

    fun goNext() {
        val target = pagerState.targetPage
        if (target < pages.lastIndex) scope.launch { pagerState.animateScrollToPage(target + 1) } else onClose()
    }

    // 뒤로 가기는 앞 쪽으로 돌아가고, 첫 쪽이면 닫는다(건너뛰기와 같다). 탭 화면으로 가지 앱을 끄지 않는다.
    BackHandler(onBack = ::goBack)

    val paneTitle = stringResource(R.string.tutorial_pane_title)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .semantics { this.paneTitle = paneTitle }
    ) {
        // 한 줄에 모으려면 폭도 넉넉해야 한다. 좁은데 모으면 긴 번역과 큰 글꼴에서 ‘다음’ 버튼이 짓눌린다.
        val compact = maxHeight < COMPACT_HEIGHT && maxWidth >= COMPACT_MIN_WIDTH
        // 버튼 이름·보이기는 손을 뗀 뒤의 쪽이 아니라 지금 보이는 쪽을 따른다.
        val current = pagerState.currentPage
        val isFirst = current == 0
        val isLast = current == pages.lastIndex

        Column(modifier = Modifier.fillMaxSize()) {
            if (!compact) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BackButton(visible = !isFirst, onClick = ::goBack)
                    Spacer(Modifier.weight(1f))
                    SkipButton(visible = !isLast, onClick = onClose)
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                // 탭 화면과 같은 기준으로, 쪽 너비의 10%만 끌어도 넘어간다(#333, #337).
                flingBehavior = PagerDefaults.flingBehavior(state = pagerState, snapPositionalThreshold = PAGE_SNAP_THRESHOLD)
            ) { index ->
                TutorialPageContent(
                    page = pages[index],
                    // 옆 쪽은 미리 그려 두기만 하고, 그 쪽에 들어와 멈춘 뒤에야 움직인다.
                    running = !paused && restingPage == index,
                    titleFocus = titleFocus[index],
                    soundPlayer = soundPlayer
                )
            }

            if (compact) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BackButton(visible = !isFirst, onClick = ::goBack)
                    SkipButton(visible = !isLast, onClick = onClose)
                    Spacer(Modifier.weight(1f))
                    PageIndicator(pagerState, restingPage, pages.size)
                    Spacer(Modifier.weight(1f))
                    SoundButton(on = soundOn, onToggle = { soundOn = !soundOn })
                    PauseButton(paused = paused, onToggle = { paused = !paused })
                    Spacer(Modifier.width(8.dp))
                    NextButton(isLast = isLast, onClick = ::goNext, modifier = Modifier.padding(end = 8.dp))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 소리 버튼과 멈춤 버튼 사이, 가운데에 쪽 표시를 둔다.
                    SoundButton(on = soundOn, onToggle = { soundOn = !soundOn })
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        PageIndicator(pagerState, restingPage, pages.size)
                    }
                    PauseButton(paused = paused, onToggle = { paused = !paused })
                }
                NextButton(
                    isLast = isLast,
                    onClick = ::goNext,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)
                )
            }
        }
    }
}

/**
 * 첫 쪽에서 숨기는 ‘이전’. 쪽마다 줄이 흔들리지 않게 자리는 두고, 보이지 않을 때는 누를 수도 없고
 * 화면 읽어주기에도 나오지 않게 한다(투명한 것은 읽지 않는다).
 */
@Composable
private fun BackButton(visible: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = visible, modifier = Modifier.alpha(if (visible) 1f else 0f)) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.tutorial_back),
            tint = SecondaryTextColor
        )
    }
}

/** 마지막 쪽에서 숨기는 ‘건너뛰기’. 거기서는 아래 ‘확인’이 같은 일을 한다. 숨기는 방식은 [BackButton] 과 같다. */
@Composable
private fun SkipButton(visible: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = visible, modifier = Modifier.alpha(if (visible) 1f else 0f)) {
        Text(stringResource(R.string.tutorial_skip), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SecondaryTextColor)
    }
}

@Composable
private fun NextButton(isLast: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = AccentFillColor),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.heightIn(min = 56.dp)
    ) {
        Text(
            stringResource(if (isLast) R.string.tutorial_done else R.string.tutorial_next),
            fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = Color.White
        )
    }
}

/** 그림 멈추기·움직이기. */
@Composable
private fun PauseButton(paused: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (paused) Icons.Filled.PlayArrow else PauseIcon,
            contentDescription = stringResource(if (paused) R.string.tutorial_play else R.string.tutorial_pause),
            tint = SecondaryTextColor
        )
    }
}

/**
 * 튜토리얼 소리 켜기·끄기(#327). 아이콘은 지금 상태(꺼짐이면 소리 끈 스피커)를, 이름은 누르면 할 일을 말한다.
 * 켜 두면 강조색으로 보인다.
 */
@Composable
private fun SoundButton(on: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (on) SoundOnIcon else SoundOffIcon,
            contentDescription = stringResource(if (on) R.string.tutorial_sound_off else R.string.tutorial_sound_on),
            tint = if (on) AccentColor else SecondaryTextColor
        )
    }
}

/** 소리 켜진 스피커(Material "volume_up"). material-icons-core 에 없어 같은 모양을 그린다. */
private val SoundOnIcon: ImageVector = materialIcon(name = "Filled.VolumeUp") {
    addPath(
        pathData = addPathNodes(
            "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z" +
                "M14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"
        ),
        fill = SolidColor(Color.Black)
    )
}

/** 소리 끈 스피커(Material "volume_off"). */
private val SoundOffIcon: ImageVector = materialIcon(name = "Filled.VolumeOff") {
    addPath(
        pathData = addPathNodes(
            "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63z" +
                "M19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06" +
                "c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18" +
                "v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z"
        ),
        fill = SolidColor(Color.Black)
    )
}

/** 멈춤 기호(세로 막대 둘). material-icons-core 에는 재생만 있고 멈춤은 없어 같은 모양으로 그린다. */
private val PauseIcon: ImageVector = materialIcon(name = "Filled.Pause") {
    materialPath {
        moveTo(6f, 19f)
        horizontalLineToRelative(4f)
        verticalLineTo(5f)
        horizontalLineTo(6f)
        verticalLineToRelative(14f)
        close()
        moveTo(14f, 5f)
        verticalLineToRelative(14f)
        horizontalLineToRelative(4f)
        verticalLineTo(5f)
        horizontalLineToRelative(-4f)
        close()
    }
}

/**
 * 한 쪽. 세로 화면은 그림 아래 글, 가로 화면은 그림 옆 글이다.
 * 글자를 크게 키워 한 화면을 넘으면 쪽 안에서 밀어 볼 수 있고, 아래에 더 있으면 끝을 흐리게 덮어 알린다.
 * 아래 버튼과 쪽 표시는 늘 보인다.
 */
@Composable
private fun TutorialPageContent(
    page: TutorialPage,
    running: Boolean,
    titleFocus: FocusRequester,
    soundPlayer: TutorialSoundPlayer?
) {
    // 그림과 종류 범례가 함께 읽는 대본 시각
    val time = remember { mutableFloatStateOf(page.scene?.stillAtSec ?: 0f) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PageIllustration(page, running, time, soundPlayer, Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp))
                Spacer(Modifier.width(24.dp))
                ScrollingText(Modifier.weight(1f).fillMaxHeight()) {
                    PageText(page, time, titleFocus)
                }
            }
        } else {
            // 그림은 쪽 높이의 절반쯤. 글자를 크게 쓰거나 글이 긴 마지막 쪽(네 단계와 권한 안내)이면 글에 자리를 더 준다.
            val fraction = if (LocalDensity.current.fontScale >= 1.3f || page == TutorialPage.Start) 0.34f else 0.5f
            val illustrationHeight = (maxHeight * fraction).coerceIn(140.dp, 380.dp)
            ScrollingText(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PageIllustration(page, running, time, soundPlayer, Modifier.fillMaxWidth().height(illustrationHeight))
                    Spacer(Modifier.height(24.dp))
                    PageText(page, time, titleFocus)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * 세로로 밀어 볼 수 있는 칸. 짧으면 가운데에 두고, 길면 밀어 볼 수 있다(홈 탭과 같은 방법).
 * 아래에 가려진 글이 있으면 끝을 배경색으로 흐리게 덮어 이어진다는 것을 보여 준다.
 */
@Composable
private fun ScrollingText(modifier: Modifier, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight),
            verticalArrangement = Arrangement.Center
        ) {
            content()
        }
        if (scroll.canScrollForward) {
            // 누르는 것을 받지 않는 그림이라 아래 글을 밀어 올리는 데 방해되지 않는다.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, BgColor)))
            )
        }
    }
}

@Composable
private fun PageIllustration(
    page: TutorialPage,
    running: Boolean,
    time: MutableFloatState,
    soundPlayer: TutorialSoundPlayer?,
    modifier: Modifier
) {
    val scene = page.scene
    if (scene != null) TutorialIllustration(scene, running, time, modifier, sounds = rememberTutorialSounds(scene, soundPlayer))
    else HomeScreenIllustration(running, modifier)
}

@Composable
private fun PageText(page: TutorialPage, time: MutableFloatState, titleFocus: FocusRequester) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        val externalSoundMode by SettingsManager.externalSoundMode.collectAsState()
        Text(
            stringResource(page.titleFor(externalSoundMode)),
            fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor,
            textAlign = TextAlign.Center,
            // 쪽이 바뀌면 여기로 초점이 온다(TutorialScreen). 제목이라 제목끼리 건너뛰며 훑을 수도 있다.
            modifier = Modifier
                .semantics { heading() }
                .focusRequester(titleFocus)
                .focusable()
        )
        page.bodyFor(externalSoundMode)?.let { body ->
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(body),
                fontSize = 16.sp, lineHeight = 25.sp, color = SecondaryTextColor, textAlign = TextAlign.Center
            )
        }
        when (page) {
            TutorialPage.Types -> {
                // 범례는 종류가 바뀔 때만 다시 그린다. 시각을 그대로 읽으면 프레임마다 리컴포지션이 일어난다.
                val label by remember(time) {
                    derivedStateOf { TutorialScript.label(TutorialScene.Types, time.floatValue) }
                }
                TypeLegend(current = label, modifier = Modifier.padding(top = 16.dp))
            }
            TutorialPage.Vibration -> Text(
                // 위협음을 믿고 기대기 전에 한 번은 알아야 하는 한계. 도움말의 같은 문구를 쓴다.
                stringResource(R.string.help_note_ai),
                fontSize = 14.sp, lineHeight = 21.sp, color = SecondaryTextColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
            TutorialPage.Start -> OnOffSteps(Modifier.padding(top = 20.dp))
            else -> Unit
        }
    }
}

/**
 * 켜고 끄는 순서. 도움말 탭 ‘시작하기’의 네 단계를 그대로 쓴다(이미 모든 언어로 번역돼 있다).
 * 권한 이름을 늘어놓지 않는 것은, 실행을 누르면 앱이 권한마다 이유를 먼저 설명하기 때문이다. 대신 설명 없이
 * 뜨는 화면 녹화 동의가 켤 때마다 묻는다는 것(2단계)과, 그 권한으로 무엇을 하지 않는지를 적어 둔다.
 * 외부 사운드 모드면 동의를 묻지 않고 마이크로 주변 소리를 들으므로, 그 모드에 맞는 문구로 바꾼다(#226).
 */
@Composable
private fun OnOffSteps(modifier: Modifier = Modifier) {
    val externalSoundMode by SettingsManager.externalSoundMode.collectAsState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(20.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Step(1, R.string.help_start_1)
        Step(2, if (externalSoundMode) R.string.help_start_2_external else R.string.help_start_2)
        Step(3, R.string.help_start_3)
        Step(4, R.string.help_start_4)
        Text(
            stringResource(if (externalSoundMode) R.string.tutorial_privacy_note_external else R.string.tutorial_privacy_note),
            fontSize = 14.sp, lineHeight = 21.sp, color = SecondaryTextColor,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun Step(number: Int, @StringRes text: Int) {
    // 번호와 글을 한 덩어리로 읽는다("1, 홈에서 실행을 누릅니다"). 나누면 단계마다 세 번 멈춘다.
    Row(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
        Box(
            modifier = Modifier.size(24.dp).clip(CircleShape).background(AccentColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AccentColor)
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(text), fontSize = 15.sp, lineHeight = 22.sp, color = PrimaryTextColor)
    }
}

/**
 * 쪽 표시 점. 지금 쪽은 색만이 아니라 길쭉한 모양으로도 구분한다.
 * 화면 읽어주기는 점 대신 "5쪽 중 2쪽" 한 마디로 읽는다. 쪽이 바뀔 때 따로 알리지는 않는다. 그때는 초점이 새 쪽의
 * 제목으로 가서 제목을 읽는다(둘 다 말하면 겹친다). 넘기다 만 경우에 흔들리지 않게 멈춘 쪽을 따른다.
 */
@Composable
private fun PageIndicator(pagerState: PagerState, restingPage: Int, count: Int) {
    val status = stringResource(R.string.tutorial_page_status, restingPage + 1, count)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clearAndSetSemantics { contentDescription = status },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { index ->
            // 점은 밀고 있는 쪽을 바로 따라간다(손을 떼기 전에도 어디로 가는지 보인다).
            val active = index == pagerState.currentPage
            val width by animateDpAsState(if (active) 22.dp else 8.dp, label = "dot")
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(if (active) AccentColor else SecondaryTextColor)
            )
        }
    }
}

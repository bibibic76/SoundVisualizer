package com.example.soundvisualizer

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Indication
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ripple
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.soundvisualizer.feedback.HapticSettingRow
import com.example.soundvisualizer.help.HelpTab
import com.example.soundvisualizer.language.AppLanguage
import com.example.soundvisualizer.language.LanguageSettingCard
import com.example.soundvisualizer.tile.VisualizerTileService
import com.example.soundvisualizer.ui.theme.SoundVisualizerTheme
import java.util.Locale
import kotlinx.coroutines.launch

/** 앱 화면 배경. 창·스플래시 배경(res/values/colors.xml 의 app_background)과 같은 값이어야 한다. */
val BgColor = Color(0xFF2A2C31)
val CardColor = Color(0xFF1E2024)
val AccentColor = Color(0xFF3182F6)
val DangerColor = Color(0xFFE53935)
val PrimaryTextColor = Color(0xFFF2F4F6)
val SecondaryTextColor = Color(0xFF8B95A1)
/** 기능이 꺼져 있다는 안내 글자. DangerColor 는 어두운 배경에서 작은 글자로 읽기 어려워 밝은 주황을 쓴다. */
val WarningColor = Color(0xFFFFB74D)

/** 줄 전체를 누르는 자리의 눌림 표시 모양. 카드(20dp)보다 조금 작게 둬 카드 안쪽에서 어울린다. */
val RowPressShape = RoundedCornerShape(12.dp)

/**
 * 줄 전체를 누르는 자리의 눌림 표시.
 *
 * 색을 지정하지 않은 기본 눌림 표시는 화면의 글자색을 쓴다. 이 앱은 어두운 카드 위에 밝은 글씨를
 * 올려 두었는데 그 값은 기본 테마의 어두운 색이라, 눌린 자리가 **회색 사각형**으로 보인다. 모양도
 * 없어서 카드의 둥근 모서리와 따로 논다. 강조색을 옅게 쓰고, 누르는 쪽에서 [RowPressShape] 로 잘라
 * 카드와 어울리게 한다.
 *
 * 없애지는 않는다. 줄 전체를 누르게 해 둔 자리라 눌린 표시가 없으면 어디를 눌렀는지 알 수 없다.
 * (탭은 밑줄이 그 역할을 하므로 그쪽만 표시를 끈다.)
 */
val RowPressIndication: Indication = ripple(color = AccentColor)

/** 홈의 실행·실행 종료 버튼 안쪽 여백. 번역된 이름이 길어도 글자 자리가 넉넉하도록 좌우를 기본(24dp)보다 줄였다. */
private val HomeButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/**
 * 좁은 칸(모드 선택, 진동 선택지, 슬라이더 이름)에 들어가는 이름표의 글자 모양.
 * 번역된 이름이 칸보다 길면 줄을 바꾸는데, 긴 단어는 아무 글자에서나 끊지 않고 하이픈을 넣어 끊는다.
 * (하이픈 규칙이 있는 언어만 해당된다.)
 */
@Composable
fun wrappingLabelStyle(): TextStyle = LocalTextStyle.current.copy(hyphens = Hyphens.Auto)

class MainActivity : ComponentActivity() {

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            // 여기서 실행 중으로 표시하지 않는다. 서비스가 실제로 뜨면 스스로 알린다.
            VisualizerController.start(this, result.resultCode, data)
        }
    }

    /** 실행에 필요한 권한을 받는다. 설정 화면으로 보내기 전에는 무엇을 해야 하는지 먼저 설명한다. */
    private val capturePermission = CapturePermissionFlow(
        this,
        onGranted = ::launchProjectionRequest,
        onOverlaySettings = { pendingStart.awaitPermission() }
    )

    /**
     * 오버레이 권한을 켜고 돌아왔을 때 눌렀던 실행을 이어가려고 기억해 둔다.
     *
     * 화면 회전으로 다시 만들어질 때만 유지한다([onRetainCustomNonConfigurationInstance]). 저장 번들에 넣으면
     * 프로세스가 죽은 뒤 한참 있다 앱을 열었을 때도 남아, 부탁하지도 않은 화면 녹화 동의 창이 뜬다.
     */
    private var pendingStart = PendingStart()

    /** 보이는 탭. 빠른 설정 타일을 길게 눌러 들어오면 설정 탭을 연다. */
    private val selectedTab = mutableIntStateOf(TAB_HOME)

    /** 꺼짐 안내로 홈 탭에 한 번만 옮기기 위한 상태. 다시 만들어져도 유지되도록 [KEY_ROUTED_STOP_NOTICE] 로 저장한다. */
    private var stopNoticeRouting = StopNoticeRouting()

    // Android 12 이하에서는 고른 앱 언어를 여기서 입힌다. 13 이상은 시스템이 적용한다.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(this)
        AppLanguage.migrateLegacyChoice(this)
        // 권한은 [실행]을 눌렀을 때 받는다 (CapturePermissionFlow.start).
        // 여기서 요청하면 앱을 열 때마다, 화면을 돌릴 때마다 설명 없이 설정 화면으로 튕긴다.

        // 언어를 바꾸거나 화면을 돌려 다시 만들어져도 보던 탭에 남는다. 언어는 설정 탭에서 바꾸기 때문이다.
        if (savedInstanceState == null) {
            openTabFor(intent)
        } else {
            selectedTab.intValue = savedInstanceState.getInt(KEY_SELECTED_TAB, TAB_HOME)
            stopNoticeRouting = StopNoticeRouting(
                savedInstanceState.getInt(KEY_ROUTED_STOP_NOTICE, StopNoticeRouting.NONE)
            )
        }
        // 권한 화면에 보내 놓고 화면이 돌아가면 여기서 다시 만들어진다. 기다리던 실행을 잃지 않는다.
        @Suppress("DEPRECATION")
        (lastCustomNonConfigurationInstance as? PendingStart)?.let { pendingStart = it }
        addOnNewIntentListener { openTabFor(it) }

        setContent {
            SoundVisualizerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BgColor
                ) {
                    LauncherApp(
                        selectedTab = selectedTab.intValue,
                        onSelectTab = { selectedTab.intValue = it },
                        onStart = { capturePermission.start() },
                        onStop = {
                            // 직접 껐으면 기다리던 실행도 버린다. 권한을 켜고 돌아와도 다시 켜지지 않는다.
                            pendingStart.cancel()
                            VisualizerController.stop(this)
                        },
                        onAddTile = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestAddTile()
                        }
                    )
                    CapturePermissionDialogs(capturePermission)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB, selectedTab.intValue)
        outState.putInt(KEY_ROUTED_STOP_NOTICE, stopNoticeRouting.routedSeq)
    }

    /** 화면 회전으로 다시 만들어지는 동안만 [pendingStart] 를 넘긴다. 프로세스가 죽으면 함께 사라져야 한다. */
    @Suppress("DEPRECATION")
    override fun onRetainCustomNonConfigurationInstance(): Any = pendingStart

    private fun openTabFor(intent: Intent?) {
        if (intent?.action == TileService.ACTION_QS_TILE_PREFERENCES) {
            selectedTab.intValue = TAB_SETTINGS
            // 설정을 열러 온 것이므로 지금 안내로는 홈으로 옮기지 않는다. 옮긴 것으로 쳐 두면 화면을 돌리거나
            // 언어를 바꿔 다시 만들어져도 설정 탭에 남는다. 다음에 또 꺼지면 새 안내라 그때는 옮긴다.
            stopNoticeRouting.skipCurrent(SettingsManager.lastUnexpectedStopSeq.value)
        }
    }

    /** 시스템의 "빠른 설정에 추가" 창을 띄운다. 창이 뜨지 않거나 추가되지 않으면 직접 추가하는 방법을 안내한다. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestAddTile() {
        val statusBar = getSystemService(StatusBarManager::class.java)
        if (statusBar == null) {
            Toast.makeText(this, R.string.home_add_tile_manual, Toast.LENGTH_LONG).show()
            return
        }
        statusBar.requestAddTileService(
            ComponentName(this, VisualizerTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_notification),
            ContextCompat.getMainExecutor(this)
        ) { result ->
            when (result) {
                // 이미 있는 경우도 추가된 것으로 기록해 홈의 권유 버튼을 숨긴다.
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> SettingsManager.setTileAdded(true)
                // 추가되지 않았다: "추가 안 함"(TILE_NOT_ADDED), 창을 그냥 닫음, 요청 실패(TILE_ADD_REQUEST_ERROR_*).
                // 세 번 거절하면 시스템이 그다음부터는 창을 띄우지 않고 바로 거절만 돌려주는데, 그대로 두면
                // 버튼을 눌러도 아무 일도 일어나지 않는다. 어느 경우인지 결과로는 알 수 없으므로
                // 모두 직접 추가하는 방법을 알린다.
                else -> Toast.makeText(this, R.string.home_add_tile_manual, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 알림의 "중지" 나 시스템 UI 로 캡처가 끝난 경우 홈 화면 상태를 실제 서비스 상태와 맞춘다.
        SettingsManager.setServiceRunning(AudioCaptureService.isRunning)
        showStopNoticeOnHome()
        continuePendingStart()
    }

    /**
     * 오버레이 권한을 켜러 갔던 사용자가 돌아왔으면 눌렀던 실행을 이어간다. 허용하고 돌아와도 "실행 대기 중"
     * 이면 처음 쓰는 사용자는 무엇이 잘못됐는지 알 수 없다.
     *
     * 사용자가 실행을 눌러 보낸 경우에만, 한 번만 이어간다([PendingStart]). 그사이 타일 등으로 이미 켜졌으면
     * 화면 녹화 동의를 다시 물을 일이 없으므로 기다리던 실행만 버린다.
     */
    private fun continuePendingStart() {
        if (!pendingStart.consumeOnResume(Settings.canDrawOverlays(this))) return
        if (!VisualizerController.isRunning) capturePermission.start()
    }

    /**
     * 꺼짐 안내는 홈에만 있다. 설정·도움말 탭을 보다가 게임으로 나간 사이에 꺼졌으면, 최근 앱·런처·알림
     * 어디로 돌아와도 안내를 보게 홈으로 옮긴다.
     *
     * 안내 하나에 한 번만 옮긴다([StopNoticeRouting]). 안내마다 번호가 붙으므로, 닫거나 다시 켜서 지워진 뒤
     * 또 꺼지면 새 안내로 쳐서 그때 다시 한 번 옮긴다.
     */
    private fun showStopNoticeOnHome() {
        val noticeSeq = SettingsManager.lastUnexpectedStopSeq.value
            .takeIf { SettingsManager.lastUnexpectedStop.value != null }
        if (stopNoticeRouting.shouldShowOnHome(noticeSeq)) selectedTab.intValue = TAB_HOME
    }

    override fun onPause() {
        super.onPause()
        // 설정 화면에서 바꾸고 손을 떼기 전에 나가도 값이 남도록 한 번 더 저장한다.
        SettingsManager.flushModeSettings()
    }

    private fun launchProjectionRequest() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private companion object {
        const val TAB_HOME = 0
        const val TAB_SETTINGS = 1
        const val KEY_SELECTED_TAB = "selected_tab"
        const val KEY_ROUTED_STOP_NOTICE = "routed_stop_notice"
    }
}

@Composable
fun LauncherApp(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAddTile: () -> Unit
) {
    // 탭 이름은 화면 맨 위에 있다. 큰 화면에서 한 손으로 쓰면 거기까지 손이 가지 않으므로
    // 화면 아무 데서나 좌우로 밀어도 넘어가게 한다.
    val pagerState = rememberPagerState(initialPage = selectedTab) { TAB_COUNT }
    val scope = rememberCoroutineScope()

    // 액티비티가 탭을 정해 주는 경로(타일 길게 누르기, 멈춤 안내의 홈 이동)를 그대로 살린다.
    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab) pagerState.animateScrollToPage(selectedTab)
    }
    // 밀어서 넘긴 결과를 액티비티에 돌려준다. 화면 회전과 복귀 때 보던 탭이 유지되는 것은
    // 액티비티가 들고 있는 값이 맡는다.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onSelectTab(it) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // TabRow. 번역된 탭 이름이 길어 한 줄에 다 안 들어가면 옆으로 밀어 볼 수 있게 한다.
        // selectableGroup 은 화면 읽어주기에 "셋 중 몇 번째"를 알려준다.
        //
        // 선택 표시는 액티비티가 든 값이 아니라 지금 보고 있는 쪽(currentPage)을 따른다. 밀다가 절반을
        // 넘기는 순간 밑줄이 따라오므로, 손을 떼기 전에도 어디로 가는지 보인다.
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().padding(24.dp)) {
            TabButton(stringResource(R.string.tab_home), pagerState.currentPage == 0) {
                scope.launch { pagerState.animateScrollToPage(0) }
            }
            Spacer(modifier = Modifier.width(24.dp))
            TabButton(stringResource(R.string.tab_settings), pagerState.currentPage == 1) {
                scope.launch { pagerState.animateScrollToPage(1) }
            }
            Spacer(modifier = Modifier.width(24.dp))
            TabButton(stringResource(R.string.tab_help), pagerState.currentPage == 2) {
                scope.launch { pagerState.animateScrollToPage(2) }
            }
        }

        // 남은 높이를 전부 준다. 세 탭 모두 fillMaxSize 라 한 쪽씩 화면을 채운다.
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> HomeTab(onStart, onStop, onAddTile)
                1 -> SettingsTab()
                else -> HelpTab()
            }
        }
    }
}

/** 홈·설정·도움말. [LauncherApp] 의 탭 수와 [MainActivity] 의 TAB_* 이 같은 수를 가리킨다. */
private const val TAB_COUNT = 3

@Composable
fun TabButton(title: String, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        // 선택 여부가 색과 밑줄로만 보이면 화면 읽어주기 사용자는 어느 탭을 보고 있는지 알 수 없다.
        // selectable(Role.Tab) 이 "선택됨"과 탭이라는 것을 함께 읽어준다.
        modifier = Modifier.selectable(
            selected = isSelected,
            // 기본 리플이 어두운 배경에서 검은 사각형처럼 번쩍인다. 탭에는 밑줄로 충분하다.
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Tab,
            onClick = onClick
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = if (isSelected) PrimaryTextColor else SecondaryTextColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        // 밑줄은 선택 여부와 상관없이 항상 자리를 차지한다. 빼버리면 열 높이가 3dp 줄어
        // 탭을 옮길 때마다 글자가 위아래로 튄다.
        Box(
            modifier = Modifier
                .height(3.dp)
                .width(40.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(if (isSelected) PrimaryTextColor else Color.Transparent)
        )
    }
}

@Composable
fun HomeTab(onStart: () -> Unit, onStop: () -> Unit, onAddTile: () -> Unit) {
    val isRunning by SettingsManager.isServiceRunning.collectAsState()
    val tileAdded by SettingsManager.tileAdded.collectAsState()
    val aiAvailable by SettingsManager.aiAvailable.collectAsState()
    val captureBlocked by SettingsManager.isCaptureBlocked.collectAsState()
    val lastUnexpectedStop by SettingsManager.lastUnexpectedStop.collectAsState()

    // 글자 크기나 화면 확대를 크게 쓰면 안내와 버튼이 화면보다 길어진다. Column 은 남은 높이만 나눠 주므로
    // 마지막 자식(실행·실행 종료 버튼, 타일 안내)이 눌려 사라진다. 스크롤을 열고 최소 높이를 화면 높이로 잡아
    // 짧을 때는 지금처럼 가운데 정렬로, 길면 밀어 볼 수 있게 한다.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            // 줄 간격을 지정하지 않으면 큰 글꼴 설정에서 제목이 두 줄로 접힐 때 위아래 줄이 서로 붙는다.
            Text(stringResource(R.string.home_title), fontSize = 36.sp, lineHeight = 48.sp, fontWeight = FontWeight.Black, color = PrimaryTextColor, modifier = Modifier.padding(bottom = 12.dp))
            Text(
                stringResource(R.string.home_subtitle),
                fontSize = 16.sp, color = SecondaryTextColor, lineHeight = 26.sp, modifier = Modifier.padding(bottom = 24.dp)
            )

            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(if (isRunning) AccentColor else SecondaryTextColor))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(if (isRunning) R.string.home_status_running else R.string.home_status_idle),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SecondaryTextColor
                    )
                }
                // 돌고 있는데도 화면에 아무 일이 없어 보이는 두 경우를 상태 바로 아래에 알린다.
                // - 재생 중인데 아무것도 받지 못함: 소리 공유를 막은 앱이거나 그 앱이 음소거된 경우다. 청각장애
                //   사용자는 "조용한 장면"과 구분할 수 없어 앱이 고장 난 줄 안다.
                // - AI 모델 실패: 위협음 색과 진동이 켜진 줄 믿게 된다.
                // 둘 다 해당하면 받지 못한다는 쪽만 말한다. 받는 소리가 없으면 분류할 소리도 없어서 AI 안내는
                // 그 순간 의미가 없고, 막힌 앱을 벗어나면 다시 나온다. 경고를 쌓아 두면 어느 것도 읽지 않는다.
                // 글자는 상태 점 너비(12dp + 8dp)만큼 들여 상태 글자와 줄을 맞춘다.
                if (isRunning && (captureBlocked || !aiAvailable)) {
                    Text(
                        stringResource(if (captureBlocked) R.string.home_capture_blocked else R.string.home_ai_unavailable),
                        fontSize = 14.sp, color = WarningColor, lineHeight = 21.sp,
                        // 홈을 보는 중에 안내가 생길 수 있으므로 화면 읽어주기가 읽고 지나가게 한다.
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                // 사용자가 끄지 않았는데 꺼졌으면 앱을 열었을 때 알린다. 앱 알림을 꺼 두면 알림도 토스트도 뜨지 못해
                // 진동만 울리므로, 무엇이 꺼졌는지 알 수 있는 곳이 여기뿐이다. 다시 켜면 캡처 서비스가 지운다.
                // 다시 켜는 버튼은 따로 두지 않는다. 바로 아래 실행 버튼이 같은 일을 한다.
                val stop = lastUnexpectedStop
                if (!isRunning && stop != null) {
                    // 홈을 보는 중에 꺼질 수 있으므로 화면 읽어주기가 읽고 지나가게 한다.
                    // 제목과 내용을 한 덩어리로 묶어 읽고, 닫기 버튼은 따로 둔다(누를 수 있는 것은 묶이지 않는다).
                    Column(
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp)
                            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    ) {
                        Text(
                            stringResource(R.string.stopped_title),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = WarningColor, lineHeight = 21.sp
                        )
                        Text(
                            stringResource(StopAlert.textFor(stop)),
                            fontSize = 14.sp, color = WarningColor, lineHeight = 21.sp
                        )
                        TextButton(
                            onClick = { SettingsManager.setLastUnexpectedStop(null) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(stringResource(R.string.home_stopped_dismiss), color = SecondaryTextColor, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 번역된 이름이 길면 버튼 안에서 가운데 정렬로 두 줄까지 들어간다(56dp 안에 두 줄).
            // 글자 크기 설정 때문에 한쪽이 더 커지면 두 버튼 높이를 같이 맞춘다.
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Button(
                    onClick = onStart,
                    enabled = !isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentColor, disabledContainerColor = Color(0xFF333A44)),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = HomeButtonPadding,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).fillMaxHeight()
                ) {
                    Text(stringResource(R.string.home_start), fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (isRunning) SecondaryTextColor else Color.White)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = onStop,
                    enabled = isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = DangerColor, disabledContainerColor = Color(0xFF333A44)),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = HomeButtonPadding,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).fillMaxHeight()
                ) {
                    Text(stringResource(R.string.home_stop), fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (!isRunning) SecondaryTextColor else Color.White)
                }
            }

            // 빠른 설정 타일은 사용자가 알림창에 직접 추가해야 보인다. 추가했으면 숨긴다.
            if (!tileAdded) {
                Spacer(modifier = Modifier.height(24.dp))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    OutlinedButton(
                        onClick = onAddTile,
                        border = BorderStroke(1.dp, AccentColor),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) {
                        Text(stringResource(R.string.home_add_tile), fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = AccentColor)
                    }
                    Text(
                        stringResource(R.string.home_add_tile_desc),
                        fontSize = 13.sp, color = SecondaryTextColor, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    // Android 12 이하는 앱에서 추가 창을 띄울 수 없어 방법만 안내한다.
                    Text(stringResource(R.string.home_add_tile_manual), fontSize = 13.sp, color = SecondaryTextColor, lineHeight = 20.sp)
                }
            }
        }
    }
}

@Composable
fun SettingsTab() {
    val currentMode by SettingsManager.visualMode.collectAsState()

    LazyColumn(modifier = Modifier.padding(horizontal = 24.dp).fillMaxSize()) {
        item {
            // 읽지 못하는 언어로 바뀌어도 찾을 수 있게 맨 위에 둔다.
            LanguageSettingCard()

            Text(stringResource(R.string.settings_section_mode), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor, modifier = Modifier.padding(bottom = 16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = CardColor),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.settings_mode_picker_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor)
                    Text(stringResource(R.string.settings_mode_picker_desc), fontSize = 13.sp, color = SecondaryTextColor, modifier = Modifier.padding(bottom = 16.dp))

                    // 번역된 이름이 칸보다 길면 가운데 정렬로 줄을 바꾸고, 네 칸 높이를 함께 맞춘다.
                    // 고른 칸은 색으로만 보이므로, 화면 읽어주기에는 selectableGroup 과 selectable 로 알린다.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(IntrinsicSize.Min).selectableGroup()
                    ) {
                        VisualMode.values().forEach { mode ->
                            val selected = currentMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) AccentColor else Color(0xFF333A44))
                                    .selectable(selected = selected, role = Role.RadioButton) {
                                        SettingsManager.setVisualMode(mode)
                                    }
                                    .padding(horizontal = 4.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(mode.labelRes),
                                    color = if (selected) Color.White else PrimaryTextColor,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    style = wrappingLabelStyle()
                                )
                            }
                        }
                    }
                }
            }

            Text(stringResource(R.string.settings_section_mode_detail), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor, modifier = Modifier.padding(bottom = 16.dp))
        }

        // 동적으로 선택된 모드를 제일 위에 오도록 정렬
        val sortedModes = VisualMode.values().sortedBy { if (it == currentMode) 0 else 1 }

        // 현재 모드가 맨 위로 올라오며 순서가 바뀐다. key 가 없으면 기억된 펼침 상태가
        // 모드가 아니라 슬롯 인덱스에 붙어서 카드끼리 뒤바뀐다.
        items(sortedModes.size, key = { sortedModes[it].name }) { index ->
            val mode = sortedModes[index]
            when (mode) {
                VisualMode.Wave -> {
                    val waveSettings by SettingsManager.waveMode.collectAsState()
                    SettingsExpander(stringResource(R.string.mode_card_wave), isExpanded = currentMode == VisualMode.Wave) {
                        ModeSettingsSection(waveSettings) { SettingsManager.updateWaveMode(it) }
                    }
                }
                VisualMode.Pad -> {
                    val padSettings by SettingsManager.padMode.collectAsState()
                    SettingsExpander(stringResource(R.string.mode_card_pad), isExpanded = currentMode == VisualMode.Pad) {
                        ModeSettingsSection(padSettings) { SettingsManager.updatePadMode(it) }
                    }
                }
                VisualMode.CircleRipple -> {
                    val circleSettings by SettingsManager.circleMode.collectAsState()
                    SettingsExpander(stringResource(R.string.mode_card_circle), isExpanded = currentMode == VisualMode.CircleRipple) {
                        ModeSettingsSection(circleSettings, isCircle = true) { SettingsManager.updateCircleMode(it) }
                    }
                }
                VisualMode.Outline -> {
                    val outlineSettings by SettingsManager.outlineMode.collectAsState()
                    SettingsExpander(stringResource(R.string.mode_card_outline), isExpanded = currentMode == VisualMode.Outline) {
                        ModeSettingsSection(outlineSettings) { SettingsManager.updateOutlineMode(it) }
                    }
                }
            }
        }

        item {
            Text(stringResource(R.string.settings_section_ai), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor, modifier = Modifier.padding(bottom = 16.dp, top = 24.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = CardColor),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    // 모델을 못 불러온 채 실행 중이면 색 설정이 먹지 않는 이유를 카드 맨 위에 알린다.
                    // 진동이 울리지 않는다는 안내는 켜 둔 진동 스위치 바로 아래에 붙는다 (HapticSettingRow).
                    val aiAvailable by SettingsManager.aiAvailable.collectAsState()
                    if (!aiAvailable) {
                        Text(
                            stringResource(R.string.ai_unavailable),
                            fontSize = 14.sp, lineHeight = 21.sp, color = WarningColor,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }

                    val showAmbient by SettingsManager.showAmbient.collectAsState()
                    val colorAmbient by SettingsManager.colorAmbient.collectAsState()
                    ColorSettingRow(stringResource(R.string.ai_show_ambient), stringResource(R.string.cd_color_ambient), showAmbient, colorAmbient,
                        onCheckedChange = { SettingsManager.updateAISettings(showAmbient = it) },
                        onColorChange = { SettingsManager.updateAISettings(colorAmbient = it) })
                    HapticSettingRow(AiClassification.AMBIENT, showAmbient)

                    val showSpeech by SettingsManager.showSpeech.collectAsState()
                    val colorSpeech by SettingsManager.colorSpeech.collectAsState()
                    ColorSettingRow(stringResource(R.string.ai_show_speech), stringResource(R.string.cd_color_speech), showSpeech, colorSpeech,
                        onCheckedChange = { SettingsManager.updateAISettings(showSpeech = it) },
                        onColorChange = { SettingsManager.updateAISettings(colorSpeech = it) })
                    HapticSettingRow(AiClassification.SPEECH, showSpeech)

                    val showDanger by SettingsManager.showDanger.collectAsState()
                    val colorDanger by SettingsManager.colorDanger.collectAsState()
                    ColorSettingRow(stringResource(R.string.ai_show_danger), stringResource(R.string.cd_color_danger), showDanger, colorDanger,
                        onCheckedChange = { SettingsManager.updateAISettings(showDanger = it) },
                        onColorChange = { SettingsManager.updateAISettings(colorDanger = it) })
                    HapticSettingRow(AiClassification.DANGER, showDanger)
                }
            }

            Text(stringResource(R.string.settings_section_battery), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor, modifier = Modifier.padding(bottom = 16.dp, top = 24.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = CardColor),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    // 화면이 꺼질 때 읽으므로 실행 중에 바꿔도 다음 꺼짐부터 바로 적용된다.
                    val pauseWhenScreenOff by SettingsManager.pauseWhenScreenOff.collectAsState()
                    ModernSwitch(
                        stringResource(R.string.setting_pause_screen_off),
                        stringResource(R.string.setting_pause_screen_off_desc),
                        pauseWhenScreenOff
                    ) {
                        SettingsManager.setPauseWhenScreenOff(it)
                    }
                }
            }
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

/**
 * 스위치를 켜야 쓰이는 세부 설정을 감싼다. 꺼져 있으면 자리를 차지하지 않고, 켜면 위아래로 펼쳐진다.
 *
 * 쓸 수 없는 설정을 흐릿하게 남겨 두면 화면만 길어지고 지금 무엇이 먹는 값인지 한눈에 들어오지 않는다.
 * 그렇다고 움직임 없이 툭 나타났다 사라지면 어디가 늘거나 줄었는지 놓치기 쉬워서, 펼쳐지고 접히는
 * 동안을 보여 준다.
 */
@Composable
fun DependentSettings(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Column(content = content)
    }
}

@Composable
fun ModeSettingsSection(settings: ModeSettings, isCircle: Boolean = false, update: (ModeSettings.() -> Unit) -> Unit) {
    // 크기 고정을 켜면 크기/진하기 대신 고정 크기/최대 진하기가 쓰인다. 지금 먹지 않는 쪽은
    // 흐릿하게 남겨 두지 않고 접는다. 흐릿한 슬라이더가 자리를 차지하면 화면만 길어진다.
    val sizeLocked = settings.intensityAsOpacity

    ModernSwitch(
        stringResource(R.string.setting_size_lock),
        stringResource(R.string.setting_size_lock_desc),
        sizeLocked
    ) {
        update { intensityAsOpacity = it }
    }
    DependentSettings(sizeLocked) {
        ModernSlider(
            stringResource(R.string.setting_fixed_size),
            stringResource(R.string.setting_fixed_size_desc),
            settings.opacityFixedSize,
            min = 10f, max = 100f, indented = true
        ) {
            update { opacityFixedSize = it }
        }
        ModernSlider(
            stringResource(R.string.setting_max_opacity),
            stringResource(R.string.setting_max_opacity_desc),
            settings.opacityFixedMaxOpacity,
            min = 0f, max = 100f, indented = true
        ) {
            update { opacityFixedMaxOpacity = it }
        }
    }

    DependentSettings(!sizeLocked) {
        ModernSlider(
            stringResource(R.string.setting_size),
            stringResource(R.string.setting_size_desc),
            settings.intensity
        ) {
            update { intensity = it }
        }
    }
    if (isCircle) {
        ModernSlider(
            stringResource(R.string.setting_radius),
            stringResource(R.string.setting_radius_desc),
            settings.circleRadius,
            min = 10f, max = 100f
        ) {
            update { circleRadius = it }
        }
    }
    DependentSettings(!sizeLocked) {
        ModernSlider(
            stringResource(R.string.setting_opacity),
            stringResource(R.string.setting_opacity_desc),
            settings.opacity
        ) {
            update { opacity = it }
        }
    }
    ModernSlider(
        stringResource(R.string.setting_speed),
        stringResource(R.string.setting_speed_desc),
        settings.speed
    ) {
        update { speed = it }
    }
    ModernSlider(
        stringResource(R.string.setting_sensitivity),
        stringResource(R.string.setting_sensitivity_desc),
        settings.sensitivity
    ) {
        update { sensitivity = it }
    }

    ModernSwitch(
        stringResource(R.string.setting_glow),
        stringResource(R.string.setting_glow_desc),
        settings.isGlowMode
    ) {
        update { isGlowMode = it }
    }
    DependentSettings(settings.isGlowMode) {
        ModernSlider(
            stringResource(R.string.setting_glow_intensity),
            stringResource(R.string.setting_glow_intensity_desc),
            settings.glowIntensity,
            indented = true
        ) {
            update { glowIntensity = it }
        }
    }

    ModernSwitch(
        stringResource(R.string.setting_ripple),
        stringResource(R.string.setting_ripple_desc),
        settings.useRippleDelay
    ) {
        update { useRippleDelay = it }
    }
}

/**
 * 한 소리 종류의 표시 스위치와 색상 버튼.
 *
 * @param label 스위치 이름 ("환경음 표시" 등)
 * @param colorLabel 색상 버튼 이름. 화면 읽어주기가 "색상 선택" 으로만 읽으면 어느 종류의 색인지 알 수 없다.
 */
@Composable
fun ColorSettingRow(
    label: String,
    colorLabel: String,
    checked: Boolean,
    color: Int,
    onCheckedChange: (Boolean) -> Unit,
    onColorChange: (Int) -> Unit
) {
    var showColorDialog by remember { mutableStateOf(false) }

    if (showColorDialog) {
        ColorPickerDialog(
            initial = color,
            onDismiss = { showColorDialog = false },
            onConfirm = {
                onColorChange(it)
                showColorDialog = false
            }
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        // 이름과 스위치를 한 덩어리로 묶어야 화면 읽어주기가 "환경음 표시, 스위치, 켜짐" 으로 읽는다.
        // 색상 버튼은 이 덩어리 밖에 둔다. 안에 넣으면 묶여 버려 따로 고를 수 없다.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(RowPressShape)
                .toggleable(
                    value = checked,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = RowPressIndication,
                    role = Role.Switch,
                    onValueChange = onCheckedChange
                )
        ) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor, modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                // 누르는 것은 줄 전체가 받는다.
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentColor,
                    uncheckedThumbColor = SecondaryTextColor,
                    uncheckedTrackColor = Color(0xFF333A44)
                )
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        // 동그라미는 32dp 그대로 두고 누를 수 있는 칸만 권장 크기(48dp)로 키운다.
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(
                    onClickLabel = stringResource(R.string.cd_pick_color),
                    onClick = { showColorDialog = true }
                )
                .semantics { contentDescription = colorLabel },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(1.dp, SecondaryTextColor.copy(alpha = 0.5f), CircleShape)
            )
        }
    }
}

/**
 * 색 공간 전체에서 고르는 선택기.
 *
 * 채도(가로) x 명도(세로) 사각형 + 색상(Hue) 슬라이더. 자주 쓰는 색은 아래 프리셋으로 집는다.
 * 시각화는 항상 불투명하게 그리므로 알파는 다루지 않는다.
 */
@Composable
fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val startHsv = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember(initial) { mutableFloatStateOf(startHsv[0]) }
    var sat by remember(initial) { mutableFloatStateOf(startHsv[1]) }
    var bright by remember(initial) { mutableFloatStateOf(startHsv[2]) }

    val picked = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bright))
    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor,
        title = { Text(stringResource(R.string.color_picker_title), color = PrimaryTextColor, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        // 누름과 끌기를 한 핸들러에서 처리한다. detectTapGestures 와
                        // detectDragGestures 를 각각 pointerInput 으로 걸면 down 이벤트를
                        // 앞쪽이 소비해서 끌기가 씹힌다.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                sat = (down.position.x / size.width).coerceIn(0f, 1f)
                                bright = 1f - (down.position.y / size.height).coerceIn(0f, 1f)
                                drag(down.id) { change ->
                                    sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                    bright = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                }
                            }
                        }
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    // 밝은 쪽에서도 안 묻히도록 흰 링 바깥에 검은 링을 겹친다.
                    val c = Offset(sat * size.width, (1f - bright) * size.height)
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
                    drawCircle(Color.Black, radius = 11.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
                }

                Spacer(Modifier.height(16.dp))

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                hue = (down.position.x / size.width).coerceIn(0f, 1f) * 360f
                                drag(down.id) { change ->
                                    hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                                }
                            }
                        }
                ) {
                    val stops = (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }
                    drawRect(Brush.horizontalGradient(stops))
                    // 손잡이 중심을 반지름만큼 안으로 물린다. 그러지 않으면 양 끝에서 반이 잘린다.
                    val knobRadius = size.height / 2f - 3.dp.toPx()
                    val knobX = ((hue / 360f) * size.width)
                        .coerceIn(knobRadius, maxOf(knobRadius, size.width - knobRadius))
                    drawCircle(
                        Color.White,
                        radius = knobRadius,
                        center = Offset(knobX, size.height / 2f),
                        style = Stroke(3.dp.toPx())
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(picked))
                            .border(1.dp, SecondaryTextColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        String.format(Locale.US, "#%06X", picked and 0xFFFFFF),
                        color = SecondaryTextColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.color_picker_presets), color = SecondaryTextColor, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))

                // 색을 보지 못해도 무엇을 고르는지 알 수 있게 이름을 함께 둔다.
                val presets = listOf(
                    0xFFFFFF to R.string.cd_preset_white,
                    0xFF0000 to R.string.cd_preset_red,
                    0xFF7F00 to R.string.cd_preset_orange,
                    0xFFFF00 to R.string.cd_preset_yellow,
                    0x00FF00 to R.string.cd_preset_green,
                    0x00FFFF to R.string.cd_preset_cyan,
                    0x0080FF to R.string.cd_preset_blue,
                    0xFF00FF to R.string.cd_preset_magenta
                )
                // 동그라미는 30dp 그대로 두고 누를 수 있는 칸만 권장 크기(48dp)로 키운다.
                // 여덟 개를 한 줄에 두면 창 너비에 48dp 씩 들어가지 않으므로 네 개씩 두 줄로 나눈다.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.selectableGroup()) {
                    presets.chunked(4).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { (rgb, nameRes) ->
                                val argb = 0xFF000000.toInt() or rgb
                                val presetName = stringResource(nameRes)
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        // 고른 색이 무엇인지도 읽히도록 선택 상태와 이름을 함께 넘긴다.
                                        .selectable(
                                            selected = argb == picked,
                                            role = Role.RadioButton,
                                            onClick = {
                                                val out = FloatArray(3)
                                                android.graphics.Color.colorToHSV(argb, out)
                                                hue = out[0]
                                                sat = out[1]
                                                bright = out[2]
                                            }
                                        )
                                        .semantics { contentDescription = presetName },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(Color(argb))
                                            .border(1.dp, SecondaryTextColor.copy(alpha = 0.4f), CircleShape)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) {
                Text(stringResource(R.string.color_picker_confirm), color = AccentColor, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.color_picker_cancel), color = SecondaryTextColor) }
        }
    )
}

/**
 * 제목을 누르면 펼쳐지는 카드. 설정 탭의 모드 카드와 도움말 탭의 주제 카드가 함께 쓴다.
 *
 * @param isExpanded 처음 그릴 때 펼쳐 둘지. 사용자가 한 번 접거나 펼치면 그 뒤로는 사용자의 선택이 이긴다.
 */
@Composable
fun SettingsExpander(title: String, isExpanded: Boolean = false, content: @Composable () -> Unit) {
    // 펼침 상태는 목록 밖으로 스크롤되면 사라지지 않게 저장한다. remember 로 두면 펼쳐 둔 카드가
    // 화면 밖에 나갔다 돌아왔을 때 접혀 있고, 접어 둔 카드는 다시 펼쳐져 있다.
    // [isExpanded] 를 키로 두어 고른 모드가 바뀔 때만 다시 맞춘다. 키가 없으면 새로 고른 모드의 카드는
    // 접힌 채로 맨 위에 오고, 방금 쓰던 모드의 카드는 펼쳐진 채로 남는다.
    var expanded by rememberSaveable(isExpanded) { mutableStateOf(isExpanded) }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically, 
                // 아래 여백은 누르는 자리 밖에 둔다. 안에 두면 펼친 내용과의 빈 칸까지 눌린다.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (expanded) 24.dp else 0.dp)
                    .clip(RowPressShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = RowPressIndication
                    ) { expanded = !expanded }
            ) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(if (expanded) R.string.cd_collapse else R.string.cd_expand),
                    tint = SecondaryTextColor
                )
            }
            if (expanded) {
                content()
            }
        }
    }
}

@Composable
fun ModernSlider(
    label: String,
    desc: String,
    value: Float,
    min: Float = 0f,
    max: Float = 100f,
    enabled: Boolean = true,
    indented: Boolean = false,
    onValueChange: (Float) -> Unit
) {
    // 비활성 슬라이더는 글자까지 함께 죽여야 "지금은 안 먹는 값"이라는 게 읽힌다.
    val labelColor = if (enabled) PrimaryTextColor else PrimaryTextColor.copy(alpha = 0.35f)
    val descColor = if (enabled) SecondaryTextColor else SecondaryTextColor.copy(alpha = 0.4f)
    val valueColor = if (enabled) AccentColor else AccentColor.copy(alpha = 0.35f)

    Column(
        modifier = Modifier
            .padding(start = if (indented) 16.dp else 0.dp)
            .padding(bottom = 24.dp)
    ) {
        // 이름·값과 슬라이더를 한 줄에 두면 끌 수 있는 막대가 80~116dp 밖에 남지 않고, 긴 번역어는
        // 좁은 이름 칸에서 여러 줄로 접힌다. 이름과 값을 위 줄에, 슬라이더를 아래 한 줄에 둔다.
        // 이름과 값은 아래 슬라이더가 이미 함께 읽어 주므로 화면 읽어주기에서는 건너뛴다.
        // 그러지 않으면 슬라이더 하나가 "이름", "값", "이름, 슬라이더, 값" 으로 세 번 멈춘다.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics { }
        ) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = labelColor, style = wrappingLabelStyle(), modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            Text(String.format(Locale.US, "%.0f", value), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = valueColor)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            // 끄는 동안에는 화면에만 반영하고, 손을 뗄 때 저장한다.
            onValueChangeFinished = SettingsManager::flushModeSettings,
            valueRange = min..max,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = AccentColor,
                inactiveTrackColor = Color(0xFF333A44),
                disabledThumbColor = Color(0xFF6B7684),
                disabledActiveTrackColor = Color(0xFF3A4351),
                disabledInactiveTrackColor = Color(0xFF2A3038)
            ),
            // 이름이 없으면 화면 읽어주기가 "슬라이더, 50%" 로만 읽어 무엇의 값인지 알 수 없다.
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
        )
        Text(desc, fontSize = 13.sp, color = descColor)
    }
}

@Composable
fun ModernSwitch(label: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column(
        // 줄 전체를 누를 수 있게 하고 이름·설명·상태를 한 덩어리로 묶는다. 스위치만 누를 수 있으면
        // 화면 읽어주기가 이름 없이 "스위치, 켜짐" 으로 읽고, 손가락으로도 작은 스위치만 노려야 한다.
        modifier = Modifier
            // 아래 여백은 누르는 자리 밖에 둔다. 안에 두면 다음 항목과의 빈 칸까지 눌린다.
            .padding(bottom = 24.dp)
            .clip(RowPressShape)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = RowPressIndication,
                role = Role.Switch,
                onValueChange = {
                    onCheckedChange(it)
                    // 스위치는 한 번에 끝나는 조작이라 바로 저장한다.
                    SettingsManager.flushModeSettings()
                }
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor, modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                // 누르는 것은 줄 전체가 받는다.
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentColor,
                    uncheckedThumbColor = SecondaryTextColor,
                    uncheckedTrackColor = Color(0xFF333A44)
                )
            )
        }
        Text(desc, fontSize = 13.sp, color = SecondaryTextColor, modifier = Modifier.padding(top = 8.dp))
    }
}

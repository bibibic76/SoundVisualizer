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
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.soundvisualizer.language.AppLanguage
import com.example.soundvisualizer.tile.VisualizerTileService
import com.example.soundvisualizer.ui.theme.SoundVisualizerTheme
import kotlinx.coroutines.launch

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
        // 화면을 시스템 바 밑까지 그린다. 바를 비켜 놓는 것은 LauncherApp 이 한다.
        // Android 15 이상은 targetSdk 35 부터 이 방식을 강제하므로, 옛 버전(10~14)도 같은 모양이 되게 직접 켠다(#138).
        // 앱 화면은 폰의 라이트·다크 모드와 상관없이 늘 어두우므로 바의 아이콘을 밝게 고정한다.
        // 바 자체는 투명해서 앱 배경이 그대로 비친다.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
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
    @Suppress("OVERRIDE_DEPRECATION")
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

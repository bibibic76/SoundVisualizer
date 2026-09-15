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
import androidx.core.net.toUri
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
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

/** 앱 화면 배경. 창·스플래시 배경(res/values/colors.xml 의 app_background)과 같은 값이어야 한다. */
val BgColor = Color(0xFF2A2C31)
val CardColor = Color(0xFF1E2024)
val AccentColor = Color(0xFF3182F6)
val DangerColor = Color(0xFFE53935)
val PrimaryTextColor = Color(0xFFF2F4F6)
val SecondaryTextColor = Color(0xFF8B95A1)
/** 기능이 꺼져 있다는 안내 글자. DangerColor 는 어두운 배경에서 작은 글자로 읽기 어려워 밝은 주황을 쓴다. */
val WarningColor = Color(0xFFFFB74D)

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

    /** 마이크·알림 권한을 받는다. 마이크는 이유를 먼저 설명하고, 다시 묻지 못하게 되면 설정 화면으로 안내한다. */
    private val capturePermission = CapturePermissionFlow(this, onGranted = ::launchProjectionRequest)

    /** 보이는 탭. 빠른 설정 타일을 길게 눌러 들어오면 설정 탭을 연다. */
    private val selectedTab = mutableIntStateOf(TAB_HOME)

    // Android 12 이하에서는 고른 앱 언어를 여기서 입힌다. 13 이상은 시스템이 적용한다.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(this)
        AppLanguage.migrateLegacyChoice(this)
        // 오버레이 권한은 [실행]을 눌렀을 때 요청한다 (startMediaProjectionRequest).
        // 여기서 요청하면 앱을 열 때마다, 화면을 돌릴 때마다 설명 없이 설정 화면으로 튕긴다.

        // 언어를 바꾸거나 화면을 돌려 다시 만들어져도 보던 탭에 남는다. 언어는 설정 탭에서 바꾸기 때문이다.
        if (savedInstanceState == null) {
            openTabFor(intent)
        } else {
            selectedTab.intValue = savedInstanceState.getInt(KEY_SELECTED_TAB, TAB_HOME)
        }
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
                        onStart = { startMediaProjectionRequest() },
                        onStop = { VisualizerController.stop(this) },
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
    }

    private fun openTabFor(intent: Intent?) {
        if (intent?.action == TileService.ACTION_QS_TILE_PREFERENCES) selectedTab.intValue = TAB_SETTINGS
    }

    /** 시스템의 "빠른 설정에 추가" 창을 띄운다. 거절하면 아무것도 하지 않고, 실패하면 직접 추가하는 방법을 안내한다. */
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
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> SettingsManager.setTileAdded(true)
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> Unit
                else -> Toast.makeText(this, R.string.home_add_tile_manual, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 알림의 "중지" 나 시스템 UI 로 캡처가 끝난 경우 홈 화면 상태를 실제 서비스 상태와 맞춘다.
        SettingsManager.setServiceRunning(AudioCaptureService.isRunning)
    }

    override fun onPause() {
        super.onPause()
        // 설정 화면에서 바꾸고 손을 떼기 전에 나가도 값이 남도록 한 번 더 저장한다.
        SettingsManager.flushModeSettings()
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
        }
    }

    private fun startMediaProjectionRequest() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        capturePermission.start()
    }

    private fun launchProjectionRequest() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private companion object {
        const val TAB_HOME = 0
        const val TAB_SETTINGS = 1
        const val KEY_SELECTED_TAB = "selected_tab"
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
    Column(modifier = Modifier.fillMaxSize()) {
        // TabRow. 번역된 탭 이름이 길어 한 줄에 다 안 들어가면 옆으로 밀어 볼 수 있게 한다.
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(24.dp)) {
            TabButton(stringResource(R.string.tab_home), selectedTab == 0) { onSelectTab(0) }
            Spacer(modifier = Modifier.width(24.dp))
            TabButton(stringResource(R.string.tab_settings), selectedTab == 1) { onSelectTab(1) }
            Spacer(modifier = Modifier.width(24.dp))
            TabButton(stringResource(R.string.tab_help), selectedTab == 2) { onSelectTab(2) }
        }

        when (selectedTab) {
            0 -> HomeTab(onStart, onStop, onAddTile)
            1 -> SettingsTab()
            else -> HelpTab()
        }
    }
}

@Composable
fun TabButton(title: String, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(
            // 기본 리플이 어두운 배경에서 검은 사각형처럼 번쩍인다. 탭에는 밑줄로 충분하다.
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
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
    val lastUnexpectedStop by SettingsManager.lastUnexpectedStop.collectAsState()

    Column(modifier = Modifier.padding(horizontal = 24.dp).fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.home_title), fontSize = 36.sp, fontWeight = FontWeight.Black, color = PrimaryTextColor, modifier = Modifier.padding(bottom = 12.dp))
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
            // AI 모델을 못 불러와도 캡처와 시각화는 돌아 "실행 중"으로 보인다. 그대로 두면 위협음 색과 진동이
            // 켜진 줄 믿으므로 상태 바로 아래에 알린다. 글자는 상태 점 너비(12dp + 8dp)만큼 들여 상태 글자와 줄을 맞춘다.
            if (isRunning && !aiAvailable) {
                Text(
                    stringResource(R.string.home_ai_unavailable),
                    fontSize = 14.sp, color = WarningColor, lineHeight = 21.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp)
                )
            }
            // 사용자가 끄지 않았는데 꺼졌으면 앱을 열었을 때 알린다. 앱 알림을 꺼 두면 알림도 토스트도 뜨지 못해
            // 진동만 울리므로, 무엇이 꺼졌는지 알 수 있는 곳이 여기뿐이다. 다시 켜면 캡처 서비스가 지운다.
            // 다시 켜는 버튼은 따로 두지 않는다. 바로 아래 실행 버튼이 같은 일을 한다.
            val stop = lastUnexpectedStop
            if (!isRunning && stop != null) {
                Column(modifier = Modifier.padding(start = 20.dp, top = 8.dp)) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                        VisualMode.values().forEach { mode ->
                            val selected = currentMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) AccentColor else Color(0xFF333A44))
                                    .clickable { SettingsManager.setVisualMode(mode) }
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
                    ColorSettingRow(stringResource(R.string.ai_show_ambient), showAmbient, colorAmbient,
                        onCheckedChange = { SettingsManager.updateAISettings(showAmbient = it) },
                        onColorChange = { SettingsManager.updateAISettings(colorAmbient = it) })
                    HapticSettingRow(AiClassification.AMBIENT, showAmbient)

                    val showSpeech by SettingsManager.showSpeech.collectAsState()
                    val colorSpeech by SettingsManager.colorSpeech.collectAsState()
                    ColorSettingRow(stringResource(R.string.ai_show_speech), showSpeech, colorSpeech,
                        onCheckedChange = { SettingsManager.updateAISettings(showSpeech = it) },
                        onColorChange = { SettingsManager.updateAISettings(colorSpeech = it) })
                    HapticSettingRow(AiClassification.SPEECH, showSpeech)

                    val showDanger by SettingsManager.showDanger.collectAsState()
                    val colorDanger by SettingsManager.colorDanger.collectAsState()
                    ColorSettingRow(stringResource(R.string.ai_show_danger), showDanger, colorDanger,
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

@Composable
fun ModeSettingsSection(settings: ModeSettings, isCircle: Boolean = false, update: (ModeSettings.() -> Unit) -> Unit) {
    // 크기 고정을 켜면 크기/진하기 대신 고정 크기/최대 진하기가 쓰인다.
    // 그래서 어느 쪽이든 지금 실제로 먹지 않는 슬라이더는 비활성으로 둔다.
    val sizeLocked = settings.intensityAsOpacity

    ModernSwitch(
        stringResource(R.string.setting_size_lock),
        stringResource(R.string.setting_size_lock_desc),
        sizeLocked
    ) {
        update { intensityAsOpacity = it }
    }
    ModernSlider(
        stringResource(R.string.setting_fixed_size),
        stringResource(R.string.setting_fixed_size_desc),
        settings.opacityFixedSize,
        min = 10f, max = 100f, enabled = sizeLocked, indented = true
    ) {
        update { opacityFixedSize = it }
    }
    ModernSlider(
        stringResource(R.string.setting_max_opacity),
        stringResource(R.string.setting_max_opacity_desc),
        settings.opacityFixedMaxOpacity,
        min = 0f, max = 100f, enabled = sizeLocked, indented = true
    ) {
        update { opacityFixedMaxOpacity = it }
    }

    ModernSlider(
        stringResource(R.string.setting_size),
        stringResource(R.string.setting_size_desc),
        settings.intensity,
        enabled = !sizeLocked
    ) {
        update { intensity = it }
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
    ModernSlider(
        stringResource(R.string.setting_opacity),
        stringResource(R.string.setting_opacity_desc),
        settings.opacity,
        enabled = !sizeLocked
    ) {
        update { opacity = it }
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
    ModernSlider(
        stringResource(R.string.setting_glow_intensity),
        stringResource(R.string.setting_glow_intensity_desc),
        settings.glowIntensity,
        enabled = settings.isGlowMode, indented = true
    ) {
        update { glowIntensity = it }
    }

    ModernSwitch(
        stringResource(R.string.setting_ripple),
        stringResource(R.string.setting_ripple_desc),
        settings.useRippleDelay
    ) {
        update { useRippleDelay = it }
    }
}

@Composable
fun ColorSettingRow(label: String, checked: Boolean, color: Int, onCheckedChange: (Boolean) -> Unit, onColorChange: (Int) -> Unit) {
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
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentColor,
                uncheckedThumbColor = SecondaryTextColor,
                uncheckedTrackColor = Color(0xFF333A44)
            ),
            modifier = Modifier.padding(end = 16.dp)
        )
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(color))
                .border(1.dp, SecondaryTextColor.copy(alpha = 0.5f), CircleShape)
                .clickable(
                    onClickLabel = stringResource(R.string.cd_pick_color),
                    onClick = { showColorDialog = true }
                )
        )
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

                val presets = listOf(
                    0xFFFFFF, 0xFF0000, 0xFF7F00, 0xFFFF00,
                    0x00FF00, 0x00FFFF, 0x0080FF, 0xFF00FF
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    presets.forEach { rgb ->
                        val argb = 0xFF000000.toInt() or rgb
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(1.dp, SecondaryTextColor.copy(alpha = 0.4f), CircleShape)
                                .clickable {
                                    val out = FloatArray(3)
                                    android.graphics.Color.colorToHSV(argb, out)
                                    hue = out[0]
                                    sat = out[1]
                                    bright = out[2]
                                }
                        )
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

@Composable
fun SettingsExpander(title: String, isExpanded: Boolean = false, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(isExpanded) }

    // 선택된 상태가 외부에서 바뀌면 같이 반영해주기 위함
    LaunchedEffect(isExpanded) {
        expanded = isExpanded
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically, 
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(bottom = if(expanded) 24.dp else 0.dp)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 슬라이더 줄을 맞추려고 이름 칸 너비를 고정한다. 번역된 이름이 길면 줄을 바꾼다.
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = labelColor, style = wrappingLabelStyle(), modifier = Modifier.width(100.dp))
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
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
            )
            Text(String.format(Locale.US, "%.0f", value), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = valueColor, modifier = Modifier.width(40.dp))
        }
        Text(desc, fontSize = 13.sp, color = descColor, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun ModernSwitch(label: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor, modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                // 스위치는 한 번에 끝나는 조작이라 바로 저장한다.
                onCheckedChange = {
                    onCheckedChange(it)
                    SettingsManager.flushModeSettings()
                },
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

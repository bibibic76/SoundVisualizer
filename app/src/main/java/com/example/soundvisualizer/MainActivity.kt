package com.example.soundvisualizer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.soundvisualizer.ui.theme.SoundVisualizerTheme

val BgColor = Color(0xFF2A2C31)
val CardColor = Color(0xFF1E2024)
val AccentColor = Color(0xFF3182F6)
val DangerColor = Color(0xFFE53935)
val PrimaryTextColor = Color(0xFFF2F4F6)
val SecondaryTextColor = Color(0xFF8B95A1)

class MainActivity : ComponentActivity() {

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(serviceIntent)
            // Start the visual overlay
            startService(Intent(this, OverlayService::class.java))
            // 여기서 true 로 두지 않는다. 서비스가 실제로 뜨면 스스로 알린다.
        }
    }

    // RECORD_AUDIO 는 내부 오디오 캡처(AudioPlaybackCapture)에 필수. POST_NOTIFICATIONS 는 FGS 알림 표시용(선택).
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            launchProjectionRequest()
        } else {
            Toast.makeText(this, "오디오 캡처를 위해 마이크(오디오 녹음) 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(this)
        requestOverlayPermission()

        setContent {
            SoundVisualizerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BgColor
                ) {
                    LauncherApp(
                        onStart = { startMediaProjectionRequest() },
                        onStop = { 
                            stopService(Intent(this, AudioCaptureService::class.java))
                            stopService(Intent(this, OverlayService::class.java))
                            SettingsManager.setServiceRunning(false)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 알림의 "중지" 나 시스템 UI 로 캡처가 끝난 경우 홈 화면 상태를 실제 서비스 상태와 맞춘다.
        SettingsManager.setServiceRunning(AudioCaptureService.isRunning)
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun startMediaProjectionRequest() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        val needed = ArrayList<String>(2)
        if (!isGranted(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) {
            launchProjectionRequest()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun launchProjectionRequest() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun LauncherApp(onStart: () -> Unit, onStop: () -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        // TabRow
        Row(modifier = Modifier.padding(24.dp)) {
            TabButton("홈", selectedTab == 0) { selectedTab = 0 }
            Spacer(modifier = Modifier.width(24.dp))
            TabButton("설정", selectedTab == 1) { selectedTab = 1 }
        }

        if (selectedTab == 0) {
            HomeTab(onStart, onStop)
        } else {
            SettingsTab()
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
fun HomeTab(onStart: () -> Unit, onStop: () -> Unit) {
    val isRunning by SettingsManager.isServiceRunning.collectAsState()

    Column(modifier = Modifier.padding(horizontal = 24.dp).fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("Sound Visualizer", fontSize = 36.sp, fontWeight = FontWeight.Black, color = PrimaryTextColor, modifier = Modifier.padding(bottom = 12.dp))
        Text(
            "보이지 않던 소리를 화면에 그려냅니다.\n게이밍부터 영화 감상까지 새로운 경험을 시작하세요.",
            fontSize = 16.sp, color = SecondaryTextColor, lineHeight = 26.sp, modifier = Modifier.padding(bottom = 24.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 24.dp)) {
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(if (isRunning) AccentColor else SecondaryTextColor))
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isRunning) "상태: 실행 중" else "상태: 실행 대기 중", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SecondaryTextColor)
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStart,
                enabled = !isRunning,
                colors = ButtonDefaults.buttonColors(containerColor = AccentColor, disabledContainerColor = Color(0xFF333A44)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Text("실행", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (isRunning) SecondaryTextColor else Color.White)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = onStop,
                enabled = isRunning,
                colors = ButtonDefaults.buttonColors(containerColor = DangerColor, disabledContainerColor = Color(0xFF333A44)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Text("실행 종료", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (!isRunning) SecondaryTextColor else Color.White)
            }
        }
    }
}

@Composable
fun SettingsTab() {
    val currentMode by SettingsManager.visualMode.collectAsState()

    LazyColumn(modifier = Modifier.padding(horizontal = 24.dp).fillMaxSize()) {
        item {
            Text("모드 설정", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor, modifier = Modifier.padding(bottom = 16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = CardColor),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("표현 모드", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor)
                    Text("화면에 그려질 그래픽의 기본 형태를 선택합니다.", fontSize = 13.sp, color = SecondaryTextColor, modifier = Modifier.padding(bottom = 16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VisualMode.values().forEach { mode ->
                            val selected = currentMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) AccentColor else Color(0xFF333A44))
                                    .clickable { SettingsManager.setVisualMode(mode) }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(mode.displayName, color = if (selected) Color.White else PrimaryTextColor, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            Text("모드별 상세 설정", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor, modifier = Modifier.padding(bottom = 16.dp))
        }

        // 동적으로 선택된 모드를 제일 위에 오도록 정렬
        val sortedModes = VisualMode.values().sortedBy { if (it == currentMode) 0 else 1 }

        items(sortedModes.size) { index ->
            val mode = sortedModes[index]
            when (mode) {
                VisualMode.Wave -> {
                    val waveSettings by SettingsManager.waveMode.collectAsState()
                    SettingsExpander("파도 모드", isExpanded = currentMode == VisualMode.Wave) {
                        ModeSettingsSection(waveSettings) { SettingsManager.updateWaveMode(it) }
                    }
                }
                VisualMode.Pad -> {
                    val padSettings by SettingsManager.padMode.collectAsState()
                    SettingsExpander("패드 모드", isExpanded = currentMode == VisualMode.Pad) {
                        ModeSettingsSection(padSettings) { SettingsManager.updatePadMode(it) }
                    }
                }
                VisualMode.CircleRipple -> {
                    val circleSettings by SettingsManager.circleMode.collectAsState()
                    SettingsExpander("원형 모드", isExpanded = currentMode == VisualMode.CircleRipple) {
                        ModeSettingsSection(circleSettings, isCircle = true) { SettingsManager.updateCircleMode(it) }
                    }
                }
                VisualMode.Outline -> {
                    val outlineSettings by SettingsManager.outlineMode.collectAsState()
                    SettingsExpander("외곽선 모드", isExpanded = currentMode == VisualMode.Outline) {
                        ModeSettingsSection(outlineSettings) { SettingsManager.updateOutlineMode(it) }
                    }
                }
            }
        }

        item {
            Text("소리 분류 표시 (AI)", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor, modifier = Modifier.padding(bottom = 16.dp, top = 24.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = CardColor),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    val showAmbient by SettingsManager.showAmbient.collectAsState()
                    val colorAmbient by SettingsManager.colorAmbient.collectAsState()
                    ColorSettingRow("환경음 표시", showAmbient, colorAmbient,
                        onCheckedChange = { SettingsManager.updateAISettings(showAmbient = it) },
                        onColorChange = { SettingsManager.updateAISettings(colorAmbient = it) })

                    val showSpeech by SettingsManager.showSpeech.collectAsState()
                    val colorSpeech by SettingsManager.colorSpeech.collectAsState()
                    ColorSettingRow("대화음 표시", showSpeech, colorSpeech,
                        onCheckedChange = { SettingsManager.updateAISettings(showSpeech = it) },
                        onColorChange = { SettingsManager.updateAISettings(colorSpeech = it) })

                    val showDanger by SettingsManager.showDanger.collectAsState()
                    val colorDanger by SettingsManager.colorDanger.collectAsState()
                    ColorSettingRow("위협음 표시", showDanger, colorDanger,
                        onCheckedChange = { SettingsManager.updateAISettings(showDanger = it) },
                        onColorChange = { SettingsManager.updateAISettings(colorDanger = it) })
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

    ModernSwitch("크기 고정", "그래픽 크기를 고정하고, 소리 세기는 진하기로 표현합니다.", sizeLocked) {
        update { intensityAsOpacity = it }
    }
    ModernSlider("고정 크기", "그래픽이 유지할 고정 크기입니다.", settings.opacityFixedSize,
        min = 10f, max = 100f, enabled = sizeLocked, indented = true) {
        update { opacityFixedSize = it }
    }
    ModernSlider("최대 진하기", "소리가 가장 클 때 도달할 진하기입니다.", settings.opacityFixedMaxOpacity,
        min = 0f, max = 100f, enabled = sizeLocked, indented = true) {
        update { opacityFixedMaxOpacity = it }
    }

    ModernSlider("크기", "그래픽의 크기와 전체적인 볼륨감을 조절합니다.", settings.intensity,
        enabled = !sizeLocked) {
        update { intensity = it }
    }
    if (isCircle) {
        ModernSlider("반지름", "원형 모드에서 가운데 중심부의 반지름을 개별 조절합니다.", settings.circleRadius,
            min = 10f, max = 100f) {
            update { circleRadius = it }
        }
    }
    ModernSlider("진하기", "값이 클수록 진하고, 작을수록 옅고 투명해집니다.", settings.opacity,
        enabled = !sizeLocked) {
        update { opacity = it }
    }
    ModernSlider("속도", "소리의 방향이 바뀔 때 그래픽이 새 위치로 옮겨가는 속도를 조절합니다.", settings.speed) {
        update { speed = it }
    }
    ModernSlider("민감도", "소리 크기 변화에 그래픽이 얼마나 빠르게 반응(떨림)할지 조절합니다.", settings.sensitivity) {
        update { sensitivity = it }
    }

    ModernSwitch("광원", "그래픽 주변에 부드러운 아우라 형식의 네온 광원 효과를 부여합니다. 주의: 추가 리소스를 사용합니다.", settings.isGlowMode) {
        update { isGlowMode = it }
    }
    ModernSlider("광원 강도", "광원 효과가 퍼지는 정도를 조절합니다.", settings.glowIntensity,
        enabled = settings.isGlowMode, indented = true) {
        update { glowIntensity = it }
    }

    ModernSwitch("공간 리플 효과", "소리가 정면에서 후면으로 퍼져나가는 듯한 공간 지연 효과를 적용합니다.", settings.useRippleDelay) {
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
                .clickable { showColorDialog = true }
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
        title = { Text("색상 선택", color = PrimaryTextColor, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectTapGestures { o ->
                                sat = (o.x / size.width).coerceIn(0f, 1f)
                                bright = 1f - (o.y / size.height).coerceIn(0f, 1f)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                bright = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
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
                            detectTapGestures { o -> hue = (o.x / size.width).coerceIn(0f, 1f) * 360f }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                            }
                        }
                ) {
                    val stops = (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }
                    drawRect(Brush.horizontalGradient(stops))
                    drawCircle(
                        Color.White,
                        radius = size.height / 2f - 3.dp.toPx(),
                        center = Offset((hue / 360f) * size.width, size.height / 2f),
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
                        String.format("#%06X", picked and 0xFFFFFF),
                        color = SecondaryTextColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text("자주 쓰는 색", color = SecondaryTextColor, fontSize = 13.sp)
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
                Text("확인", color = AccentColor, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소", color = SecondaryTextColor) }
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
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = SecondaryTextColor)
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
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = labelColor, modifier = Modifier.width(100.dp))
            Slider(
                value = value,
                onValueChange = onValueChange,
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
            Text(String.format("%.0f", value), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = valueColor, modifier = Modifier.width(40.dp))
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
                onCheckedChange = onCheckedChange,
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

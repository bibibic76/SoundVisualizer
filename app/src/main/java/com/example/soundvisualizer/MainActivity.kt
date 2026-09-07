package com.example.soundvisualizer

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            // Start the visual overlay
            startService(Intent(this, OverlayService::class.java))
            
            SettingsManager.setServiceRunning(true)
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

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun startMediaProjectionRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
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
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = if (isSelected) PrimaryTextColor else SecondaryTextColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .height(3.dp)
                    .width(40.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(PrimaryTextColor)
            )
        }
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
    ModernSwitch("크기 고정", "세기에 비례하는 투명도를 활용하여 고정된 크기의 그래픽을 보여줍니다.", settings.intensityAsOpacity) {
        update { intensityAsOpacity = it }
    }
    ModernSlider("고정 크기", "투명도가 변할 기준이 되는 고정 크기를 설정합니다.", settings.opacityFixedSize, min = 10f, max = 100f) {
        update { opacityFixedSize = it }
    }
    ModernSlider("최대 투명도", "소리에 비례하여 나타날 최대 투명도를 설정합니다.", settings.opacityFixedMaxOpacity, min = 0f, max = 100f) {
        update { opacityFixedMaxOpacity = it }
    }
    ModernSlider("크기", "그래픽의 크기와 전체적인 볼륨감을 조절합니다.", settings.intensity) { 
        update { intensity = it }
    }
    if (isCircle) {
        ModernSlider("반지름", "원형 모드에서 가운데 중심부의 지름(반지름) 크기를 개별 조절합니다.", settings.circleRadius, min = 10f, max = 100f) { 
            update { circleRadius = it }
        }
    }
    ModernSlider("투명도", "그래픽의 투명도를 조절하여 뒤의 비침 정도를 결정합니다.", settings.opacity) { 
        update { opacity = it }
    }
    ModernSlider("속도", "그래픽이 반응하며 일렁이는 애니메이션 속도를 조절합니다.", settings.speed) { 
        update { speed = it }
    }
    ModernSlider("민감도", "작은 데시벨 소리에도 그래픽이 얼마나 민감하게 반응하여 출렁일지 조절합니다.", settings.sensitivity) { 
        update { sensitivity = it }
    }
    ModernSwitch("광원", "그래픽 주변에 부드러운 아우라 형식의 네온 광원 효과를 부여합니다. 주의: 추가 리소스를 사용합니다.", settings.isGlowMode) { 
        update { isGlowMode = it }
    }
    ModernSlider("광원 강도", "광원 효과의 퍼지는 강도를 조절합니다.", settings.glowIntensity) { 
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
        AlertDialog(
            onDismissRequest = { showColorDialog = false },
            title = { Text("색상 선택", color = PrimaryTextColor) },
            text = {
                // 간단한 프리셋 팔레트
                val presetColors = listOf(
                    android.graphics.Color.WHITE,
                    android.graphics.Color.YELLOW,
                    android.graphics.Color.RED,
                    android.graphics.Color.GREEN,
                    android.graphics.Color.BLUE,
                    android.graphics.Color.CYAN,
                    android.graphics.Color.MAGENTA,
                    android.graphics.Color.parseColor("#FFA500") // Orange
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    presetColors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .clickable {
                                    onColorChange(c)
                                    showColorDialog = false
                                }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showColorDialog = false }) { Text("닫기", color = AccentColor) }
            },
            containerColor = CardColor
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
                .clickable { showColorDialog = true }
        )
    }
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
fun ModernSlider(label: String, desc: String, value: Float, min: Float = 0f, max: Float = 100f, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor, modifier = Modifier.width(100.dp))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = min..max,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentColor, inactiveTrackColor = Color(0xFF333A44)),
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
            )
            Text(String.format("%.0f", value), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentColor, modifier = Modifier.width(40.dp))
        }
        Text(desc, fontSize = 13.sp, color = SecondaryTextColor, modifier = Modifier.padding(top = 8.dp))
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

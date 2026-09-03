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

        item {
            val waveSettings by SettingsManager.waveMode.collectAsState()
            SettingsExpander("파도 모드", isExpanded = currentMode == VisualMode.Wave) {
                ModernSlider("크기", "파도의 위아래 높이와 전체적인 볼륨감을 조절합니다.", waveSettings.intensity) { 
                    SettingsManager.updateWaveMode { intensity = it }
                }
                ModernSlider("속도", "파도가 흘러가며 일렁이는 속도를 조절합니다.", waveSettings.speed) { 
                    SettingsManager.updateWaveMode { speed = it }
                }
                ModernSlider("투명도", "그래픽 뒤의 비침 정도를 결정합니다.", waveSettings.opacity) { 
                    SettingsManager.updateWaveMode { opacity = it }
                }
            }
        }
        
        item {
            val padSettings by SettingsManager.padMode.collectAsState()
            SettingsExpander("패드 모드", isExpanded = currentMode == VisualMode.Pad) {
                ModernSlider("크기", "패드의 두께와 볼륨감을 조절합니다.", padSettings.intensity) { 
                    SettingsManager.updatePadMode { intensity = it }
                }
                ModernSlider("속도", "부풀어 오르는 애니메이션 속도를 조절합니다.", padSettings.speed) { 
                    SettingsManager.updatePadMode { speed = it }
                }
                ModernSlider("투명도", "그래픽 뒤의 비침 정도를 결정합니다.", padSettings.opacity) { 
                    SettingsManager.updatePadMode { opacity = it }
                }
            }
        }

        item {
            val circleSettings by SettingsManager.circleMode.collectAsState()
            SettingsExpander("원형 모드", isExpanded = currentMode == VisualMode.CircleRipple) {
                ModernSlider("크기", "원형 리플의 뻗어나가는 세기를 조절합니다.", circleSettings.intensity) { 
                    SettingsManager.updateCircleMode { intensity = it }
                }
                ModernSlider("속도", "물결의 진동 속도를 조절합니다.", circleSettings.speed) { 
                    SettingsManager.updateCircleMode { speed = it }
                }
                ModernSlider("반지름", "중앙 빈 공간의 크기를 조절합니다.", circleSettings.circleRadius, min = 10f, max = 100f) { 
                    SettingsManager.updateCircleMode { circleRadius = it }
                }
                ModernSlider("투명도", "그래픽 뒤의 비침 정도를 결정합니다.", circleSettings.opacity) { 
                    SettingsManager.updateCircleMode { opacity = it }
                }
            }
        }

        item {
            val outlineSettings by SettingsManager.outlineMode.collectAsState()
            SettingsExpander("외곽선 모드", isExpanded = currentMode == VisualMode.Outline) {
                ModernSlider("크기", "선의 출렁임 정도를 조절합니다.", outlineSettings.intensity) { 
                    SettingsManager.updateOutlineMode { intensity = it }
                }
                ModernSlider("속도", "선이 움직이는 애니메이션 속도를 조절합니다.", outlineSettings.speed) { 
                    SettingsManager.updateOutlineMode { speed = it }
                }
                ModernSlider("투명도", "그래픽 뒤의 비침 정도를 결정합니다.", outlineSettings.opacity) { 
                    SettingsManager.updateOutlineMode { opacity = it }
                }
            }
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
fun SettingsExpander(title: String, isExpanded: Boolean = false, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(isExpanded) }
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

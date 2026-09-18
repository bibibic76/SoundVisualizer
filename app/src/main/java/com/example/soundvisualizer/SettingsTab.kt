package com.example.soundvisualizer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.soundvisualizer.feedback.HapticSettingRow
import com.example.soundvisualizer.ai.AiFrontendMode
import com.example.soundvisualizer.language.LanguageSettingCard

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
                    // 오버레이가 프레임마다 읽으므로 켜고 끄면 실행 중에도 바로 적용된다.
                    val reducedFrameRate by SettingsManager.reducedFrameRate.collectAsState()
                    ModernSwitch(
                        stringResource(R.string.setting_reduced_frame_rate),
                        stringResource(R.string.setting_reduced_frame_rate_desc),
                        reducedFrameRate
                    ) {
                        SettingsManager.setReducedFrameRate(it)
                    }
                }
            }

            // 팀이 AI 분류를 채점하는 도구다. 사용자 기능이 아니므로 맨 아래에 작은 제목으로 둔다.
            // 숨기지는 않는다. 화면 읽어주기로도 찾을 수 있어야 하고, 켠 사람이 어디서 껐는지 알아야 한다.
            Text(
                stringResource(R.string.settings_section_developer),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = SecondaryTextColor,
                modifier = Modifier.padding(bottom = 16.dp, top = 24.dp)
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = CardColor),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    // 오버레이가 이 값을 구독하므로 켜면 실행 중에도 바로 나타난다.
                    val developerMode by SettingsManager.developerMode.collectAsState()
                    ModernSwitch(
                        stringResource(R.string.setting_developer_mode),
                        stringResource(R.string.setting_developer_mode_desc),
                        developerMode
                    ) {
                        SettingsManager.setDeveloperMode(it)
                    }
                    DependentSettings(developerMode) {
                        val diagnosticConfig by SettingsManager.aiDiagnosticConfig.collectAsState()
                        ModernSwitch(
                            stringResource(R.string.setting_ai_qualcomm_frontend),
                            stringResource(R.string.setting_ai_qualcomm_frontend_desc),
                            diagnosticConfig.frontendMode == AiFrontendMode.QUALCOMM_SOURCE
                        ) {
                            SettingsManager.setAiFrontendMode(
                                if (it) AiFrontendMode.QUALCOMM_SOURCE else AiFrontendMode.CURRENT
                            )
                        }
                        ModernSwitch(
                            stringResource(R.string.setting_ai_booster),
                            stringResource(R.string.setting_ai_booster_desc),
                            diagnosticConfig.boosterEnabled
                        ) {
                            SettingsManager.setAiBoosterEnabled(it)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(100.dp))
        }
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
                .toggleable(
                    value = checked,
                    // 눌림 표시는 두지 않는다. 어두운 카드 위에서 색 상자로 번쩍이고, 스위치가 움직이는 것으로 충분하다.
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
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

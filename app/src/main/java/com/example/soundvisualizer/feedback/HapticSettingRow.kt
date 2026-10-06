package com.example.soundvisualizer.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.soundvisualizer.AccentColor
import com.example.soundvisualizer.AccentFillColor
import com.example.soundvisualizer.AiClassification
import com.example.soundvisualizer.DependentSettings
import com.example.soundvisualizer.EqualChoiceRows
import com.example.soundvisualizer.PrimaryTextColor
import com.example.soundvisualizer.R
import com.example.soundvisualizer.SecondaryTextColor
import com.example.soundvisualizer.SettingsManager
import com.example.soundvisualizer.WarningColor
import com.example.soundvisualizer.choicesPerRow
import com.example.soundvisualizer.wrappingLabelStyle
import kotlin.math.roundToInt

/**
 * 한 소리 종류의 진동 설정 (방식, 세기).
 * 설정 화면의 소리 분류 카드에서 각 종류의 표시·색상 줄 바로 아래에 붙는다.
 *
 * 진동은 화면 표시가 켜진 종류만 울리므로, 표시가 꺼져 있으면 비활성으로 보여준다.
 * 세기는 꺼짐이 아닐 때만 펼쳐서 카드가 길어지지 않게 한다. 방식을 누르거나 세기를 바꾸면 그대로 미리 울려 본다.
 * 시각화가 실행 중이면 미리보기가 실제 진동과 섞여 무엇이 울린 것인지 헷갈리므로 울리지 않는다(#244).
 *
 * @param label [com.example.soundvisualizer.AiClassification] 의 라벨
 * @param shown 이 종류의 화면 표시가 켜져 있는지
 */
@Composable
fun HapticSettingRow(label: String, shown: Boolean) {
    val context = LocalContext.current
    val player = remember { HapticPlayer(context) }
    val settings by SettingsManager.hapticSettings(label).collectAsState()
    val aiAvailable by SettingsManager.aiAvailable.collectAsState()
    val running by SettingsManager.isServiceRunning.collectAsState()

    val rowEnabled = shown && player.hasVibrator

    // 미리보기는 2초 동안 돈다. 이 줄이 사라지면(탭을 옮기면) 이 줄이 튼 미리보기만 멈춘다.
    DisposableEffect(label) {
        onDispose { HapticPreviewGate.stopPreview(owner = label) }
    }
    // 미리보기를 튼 직후 빠른 설정 타일 등으로 시각화를 켜면, 남은 미리보기도 바로 멈춘다.
    LaunchedEffect(running) {
        if (running) HapticPreviewGate.stopPreview(owner = label)
    }

    val preview: (HapticSettings) -> Unit = preview@{ next ->
        if (running) return@preview
        val plan = HapticShapes.preview(next, player.hasAmplitudeControl)
        if (plan == null) {
            HapticPreviewGate.stopPreview(owner = label)
        } else {
            HapticPreviewGate.play(context, owner = label, plan)
        }
    }
    // 소리 종류 구분(AI)을 못 불러오면 종류별 진동이 돌지 않고, 큰 소리만 위협음 설정으로 울린다(HapticPolicy).
    // 고른 방식은 그대로라 종류별 진동을 믿게 되므로, 켜 둔 진동 바로 아래에 알린다. 위협음 줄은 이 설정으로
    // 큰 소리가 울린다고, 다른 줄은 이 종류로는 울리지 않는다고 적는다. 위협음의 표시나 진동을 꺼 두었으면 큰 소리도
    // 울리지 않으므로 다른 줄은 진동하지 않는다고만 적는다(#232). 설정값은 다음 실행을 위해 바꾸지 않는다.
    val dangerShown by SettingsManager.showDanger.collectAsState()
    val dangerHaptic by SettingsManager.hapticSettings(AiClassification.DANGER).collectAsState()
    val loudAlerts = AiUnavailableNotice.loudAlerts(dangerShown, dangerHaptic.enabled, player.hasVibrator)
    val aiNote = !aiAvailable && rowEnabled && settings.enabled
    val noteRes = when {
        !player.hasVibrator -> R.string.haptic_unsupported
        !shown -> R.string.haptic_requires_display
        aiNote && label == AiClassification.DANGER -> R.string.haptic_ai_unavailable_danger
        aiNote -> AiUnavailableNotice.otherRow(loudAlerts)
        else -> null
    }

    // 종류마다 같은 "진동"·"세기" 줄이 셋 있어서, 화면 읽어주기에는 어느 종류의 것인지 함께 읽힌다(#312).
    // 눈으로는 바로 위의 종류 줄로 알 수 있지만, 손으로 더듬으면 "세기, 50%" 만 들린다.
    val typeName = stringResource(soundTypeName(label))
    val vibrateLabel = stringResource(R.string.haptic_vibrate)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 20.dp)
    ) {
        Text(
            vibrateLabel,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (rowEnabled) PrimaryTextColor else PrimaryTextColor.copy(alpha = 0.35f),
            modifier = Modifier.semantics { contentDescription = "$typeName, $vibrateLabel" }
        )

        if (noteRes != null) {
            Text(
                stringResource(noteRes),
                fontSize = 13.sp,
                color = if (aiNote) WarningColor else SecondaryTextColor,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        HapticChoiceRow(
            options = HapticMode.values().toList(),
            selected = settings.mode,
            labelOf = { stringResource(it.labelRes) },
            enabled = rowEnabled,
            onSelect = { mode ->
                val next = settings.copy(mode = mode)
                SettingsManager.updateHaptic(label, next)
                preview(next)
            }
        )

        DependentSettings(rowEnabled && settings.enabled) {
            LevelSlider(
                typeName = typeName,
                level = settings.level,
                enabled = player.hasAmplitudeControl,
                onFinished = { level ->
                    val next = settings.copy(level = level)
                    SettingsManager.updateHaptic(label, next)
                    preview(next)
                }
            )
            if (!player.hasAmplitudeControl) {
                Text(
                    stringResource(R.string.haptic_no_amplitude),
                    fontSize = 13.sp,
                    color = SecondaryTextColor,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Text(
                stringResource(if (running) R.string.haptic_preview_hint_running else R.string.haptic_preview_hint),
                fontSize = 12.sp,
                color = SecondaryTextColor,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * 세기 슬라이더. 끄는 동안에는 화면에만 반영하고, 손을 뗄 때 한 번 저장하고 미리 울린다. 끄는 내내 울리면 진동이 겹겹이
 * 끊겨 어느 세기인지 느낄 수 없다.
 */
@Composable
private fun LevelSlider(typeName: String, level: Int, enabled: Boolean, onFinished: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val value = dragging ?: level.toFloat()
    val name = stringResource(R.string.haptic_strength)
    val percent = stringResource(R.string.haptic_level_percent, value.roundToInt())
    val labelColor = if (enabled) SecondaryTextColor else SecondaryTextColor.copy(alpha = 0.4f)
    val valueColor = if (enabled) AccentColor else AccentColor.copy(alpha = 0.35f)

    Column(modifier = Modifier.padding(top = 12.dp)) {
        // 이름과 값은 아래 슬라이더가 함께 읽어 주므로 화면 읽어주기에서는 건너뛴다(ModernSlider 와 같다).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics { }
        ) {
            Text(name, fontSize = 13.sp, color = labelColor, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                percent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = valueColor
            )
        }
        Slider(
            value = value,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                val picked = HapticSettings.clampLevel((dragging ?: level.toFloat()).roundToInt())
                dragging = null
                onFinished(picked)
            },
            valueRange = HapticSettings.MIN_LEVEL.toFloat()..HapticSettings.MAX_LEVEL.toFloat(),
            // 10% 단위: 10, 20, … 100 의 열 자리. 양 끝을 뺀 사이 칸이 여덟이다.
            steps = (HapticSettings.MAX_LEVEL - HapticSettings.MIN_LEVEL) / HapticSettings.LEVEL_STEP - 1,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = AccentFillColor,
                inactiveTrackColor = Color(0xFF333A44),
                disabledThumbColor = Color(0xFF6B7684),
                disabledActiveTrackColor = Color(0xFF3A4351),
                disabledInactiveTrackColor = Color(0xFF2A3038)
            ),
            // 이름이 없으면 화면 읽어주기가 "슬라이더, 50%" 로만 읽어 무엇의 값인지 알 수 없다. 종류 이름까지 붙여
            // "위협음, 세기" 로 읽힌다(#312). 값도 화면과 같은 글자로 읽힌다. 두지 않으면 슬라이더가 범위 안의 위치로 읽어
            // 50% 가 "44퍼센트" 가 된다(범위가 10부터라).
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "$typeName, $name"
                stateDescription = percent
            }
        )
    }
}

/** 소리 종류의 이름(분류 탭과 같은 "환경음"·"대화음"·"위협음"). */
private fun soundTypeName(label: String): Int = when (label) {
    AiClassification.DANGER -> R.string.sound_type_danger
    AiClassification.SPEECH -> R.string.sound_type_speech
    else -> R.string.sound_type_ambient
}

/** 설정 화면의 모드 선택 버튼과 같은 모양의 선택지 줄. */
@Composable
private fun <T> HapticChoiceRow(
    options: List<T>,
    selected: T,
    labelOf: @Composable (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit
) {
    // 번역된 선택지가 칸보다 길면 가운데 정렬로 줄을 바꾸고, 칸 높이를 함께 맞춘다.
    // 다섯 칸이 한 줄에 들어가야 해서 칸 사이와 안쪽 여백을 설정 화면의 다른 선택지보다 좁게 둔다. 넓히면 영어의
    // Medium 같은 짧은 단어도 칸에 들어가지 않아 글자 중간에서 끊긴다.
    // 글꼴을 키워 그래도 단어가 칸에 들어가지 않으면 한 줄의 칸 수를 줄인다(3+2, [choicesPerRow], #304).
    // 고른 칸은 색으로만 보이므로, 화면 읽어주기에는 selectableGroup 과 selectable 로 알린다.
    val labels = options.map { labelOf(it) }
    val labelStyle = wrappingLabelStyle().copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(modifier = Modifier.padding(top = 10.dp)) {
        val perRow = choicesPerRow(
            labels, maxPerRow = options.size, rowWidth = maxWidth, gap = 4.dp, cellPadding = 2.dp, style = labelStyle
        )
        EqualChoiceRows(options, perRow, gap = 4.dp) { option, cellModifier ->
            val isSelected = option == selected
            Box(
                // 칸 높이는 48dp 이상으로 둔다(#312, 글자와 안쪽 여백만으로는 약 44dp).
                modifier = cellModifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            !enabled -> Color(0xFF2A3038)
                            isSelected -> AccentFillColor
                            else -> Color(0xFF333A44)
                        }
                    )
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton
                    ) { onSelect(option) }
                    .padding(horizontal = 2.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    labelOf(option),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    style = wrappingLabelStyle(),
                    color = when {
                        !enabled -> PrimaryTextColor.copy(alpha = 0.35f)
                        isSelected -> Color.White
                        else -> PrimaryTextColor
                    }
                )
            }
        }
    }
}

package com.example.soundvisualizer.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.soundvisualizer.AccentColor
import com.example.soundvisualizer.PrimaryTextColor
import com.example.soundvisualizer.R
import com.example.soundvisualizer.SecondaryTextColor
import com.example.soundvisualizer.SettingsManager
import com.example.soundvisualizer.WarningColor
import com.example.soundvisualizer.wrappingLabelStyle

/**
 * 한 소리 종류의 진동 설정 (켜기/끄기, 세기, 패턴).
 * 설정 화면의 소리 분류 카드에서 각 종류의 표시·색상 줄 바로 아래에 붙는다.
 *
 * 진동은 화면 표시가 켜진 종류만 울리므로, 표시가 꺼져 있으면 비활성으로 보여준다.
 * 세기와 패턴은 진동을 켰을 때만 펼쳐서 카드가 길어지지 않게 한다.
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

    val switchEnabled = shown && player.hasVibrator
    // 소리 종류 구분(AI)을 못 불러오면 진동 알림 자체가 돌지 않는다. 스위치는 켜진 그대로라
    // 위협음 진동을 믿게 되므로, 켜 둔 스위치 바로 아래에 알린다. 설정값은 다음 실행을 위해 바꾸지 않는다.
    val aiNote = !aiAvailable && switchEnabled && settings.enabled
    val noteRes = when {
        !player.hasVibrator -> R.string.haptic_unsupported
        !shown -> R.string.haptic_requires_display
        aiNote -> R.string.haptic_ai_unavailable
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.haptic_vibrate),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (switchEnabled) PrimaryTextColor else PrimaryTextColor.copy(alpha = 0.35f),
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = settings.enabled,
                onCheckedChange = { SettingsManager.updateHaptic(label, settings.copy(enabled = it)) },
                enabled = switchEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentColor,
                    uncheckedThumbColor = SecondaryTextColor,
                    uncheckedTrackColor = Color(0xFF333A44)
                )
            )
        }

        if (noteRes != null) {
            Text(
                stringResource(noteRes),
                fontSize = 13.sp,
                color = if (aiNote) WarningColor else SecondaryTextColor,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (switchEnabled && settings.enabled) {
            HapticChoiceRow(
                title = stringResource(R.string.haptic_strength),
                options = HapticStrength.values().toList(),
                selected = settings.strength,
                labelOf = { stringResource(it.labelRes) },
                enabled = player.hasAmplitudeControl,
                onSelect = { strength ->
                    SettingsManager.updateHaptic(label, settings.copy(strength = strength))
                    player.play(settings.pattern, strength)
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

            HapticChoiceRow(
                title = stringResource(R.string.haptic_pattern),
                options = HapticPattern.values().toList(),
                selected = settings.pattern,
                labelOf = { stringResource(it.labelRes) },
                enabled = true,
                onSelect = { pattern ->
                    SettingsManager.updateHaptic(label, settings.copy(pattern = pattern))
                    player.play(pattern, settings.strength)
                }
            )

            Text(
                stringResource(R.string.haptic_preview_hint),
                fontSize = 12.sp,
                color = SecondaryTextColor,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** 설정 화면의 모드 선택 버튼과 같은 모양의 선택지 줄. */
@Composable
private fun <T> HapticChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    labelOf: @Composable (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            title,
            fontSize = 13.sp,
            color = if (enabled) SecondaryTextColor else SecondaryTextColor.copy(alpha = 0.4f),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        // 번역된 선택지가 칸보다 길면 가운데 정렬로 줄을 바꾸고, 칸 높이를 함께 맞춘다.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                !enabled -> Color(0xFF2A3038)
                                isSelected -> AccentColor
                                else -> Color(0xFF333A44)
                            }
                        )
                        .clickable(enabled = enabled) { onSelect(option) }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
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
}

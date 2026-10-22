package com.example.soundvisualizer.feedback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.soundvisualizer.AiClassification
import com.example.soundvisualizer.CaptureSource
import com.example.soundvisualizer.EasyNoteLineBreak
import com.example.soundvisualizer.PictureChoiceRow
import com.example.soundvisualizer.R
import com.example.soundvisualizer.SecondaryTextColor
import com.example.soundvisualizer.SettingsManager
import com.example.soundvisualizer.VibrationStepIcon
import com.example.soundvisualizer.WarningColor

/**
 * 쉬운 설정(#408)의 한 소리 종류 진동: 끔·약하게·세게 세 칸([EasyVibration]). 자세한 설정의 [HapticSettingRow] 를 줄인 것이다.
 *
 * - 칸마다 진동 그림과 낱말 하나를 둔다. 방식 다섯 칸과 세기 슬라이더는 자세한 설정에 남는다.
 * - 누르면 그 단계를 저장하고 2초 동안 미리 울린다. 이미 선택된 칸을 다시 눌러도 미리 울린다. 시각화가 실행 중이면
 *   미리보기가 실제 진동과 섞이므로 울리지 않는다(#244, 자세한 설정과 같다).
 * - 진동은 화면 표시가 켜진 종류만 울리므로, 표시가 꺼져 있으면 칸을 흐리게 두고 표시를 켜라고 적는다.
 * - 외부 사운드 모드의 상한(#354)은 [EasyVibration] 이 [HapticModeChoice] 와 같은 규칙으로 지킨다. 진동하지 않는 환경음은
 *   끔만 선택할 수 있다.
 * - 안내 글은 할 일이나 까닭을 알려 주는 것만 둔다: 진동이 없는 기기, 표시를 켜야 함, 외부 사운드 모드의 환경음, 소리 종류
 *   구분(AI)을 쓸 수 없음(자세한 설정과 같은 안내, 진동에 기대는 사람을 거짓으로 안심시키지 않는다, #232), 세기를 바꿀 수
 *   없는 기기(약하게와 세게가 같게 울린다), 자세한 설정에서 단계와 다른 세기로 맞춤(어느 칸도 선택되지 않은 까닭, #424).
 *   실행 중이라 미리 울리지 않는다는 안내는 줄마다 두지 않고 소리 종류 카드 맨 위에 한 번 둔다. 줄마다 두면 다른 안내에
 *   가려 빠지는 줄이 생기고, 같은 문장이 세 번 되풀이된다.
 *
 * @param label [AiClassification] 의 라벨
 * @param shown 이 종류의 화면 표시가 켜져 있는지
 */
@Composable
fun EasyHapticRow(label: String, shown: Boolean) {
    val context = LocalContext.current
    val player = remember { HapticPlayer(context) }
    val settings by SettingsManager.hapticSettings(label).collectAsState()
    val aiAvailable by SettingsManager.aiAvailable.collectAsState()
    val running by SettingsManager.isServiceRunning.collectAsState()
    val externalSoundMode by SettingsManager.externalSoundMode.collectAsState()
    val external = CaptureSource.of(externalSoundMode).hearsOwnVibration
    val shownStep = EasyVibration.shown(settings, label, external)
    val rowEnabled = shown && player.hasVibrator
    // 미리보기의 주인 이름. 자세한 설정의 줄과 겹치지 않게 따로 둔다. 겹치면 한쪽이 사라질 때 다른 쪽의 미리보기를 끊는다.
    val owner = EASY_PREVIEW_OWNER + label

    // 미리보기는 2초 동안 돈다. 이 줄이 사라지면(탭을 옮기거나 자세한 설정으로 바꾸면) 이 줄이 튼 미리보기만 멈춘다.
    DisposableEffect(owner) {
        onDispose { HapticPreviewGate.stopPreview(owner = owner) }
    }
    // 미리보기를 튼 직후 빠른 설정 타일 등으로 시각화를 켜면, 남은 미리보기도 바로 멈춘다.
    LaunchedEffect(running) {
        if (running) HapticPreviewGate.stopPreview(owner = owner)
    }

    val preview: (HapticSettings) -> Unit = preview@{ next ->
        if (running) return@preview
        val plan = HapticShapes.preview(next, player.hasAmplitudeControl)
        if (plan == null) {
            HapticPreviewGate.stopPreview(owner = owner)
        } else {
            HapticPreviewGate.play(context, owner = owner, plan)
        }
    }

    // 소리 종류 구분(AI)을 못 불러오면 종류별 진동이 돌지 않는다. 자세한 설정의 진동 줄과 같은 안내를 같은 조건으로 단다.
    val dangerShown by SettingsManager.showDanger.collectAsState()
    val dangerHaptic by SettingsManager.hapticSettings(AiClassification.DANGER).collectAsState()
    val loudAlerts = AiUnavailableNotice.loudAlerts(dangerShown, dangerHaptic.enabled, player.hasVibrator)
    val aiNote = !aiAvailable && rowEnabled && shownStep != EasyVibration.Off
    // 외부 사운드 모드의 환경음 안내는 환경음을 이름으로 말하므로 환경음 줄일 때만 쓴다(자세한 설정과 같은 규칙).
    val noteRes = when {
        !player.hasVibrator -> R.string.haptic_unsupported
        HapticModeChoice.requiresDisplayNote(label, shown, external) -> R.string.haptic_requires_display
        label == AiClassification.AMBIENT && HapticModeChoice.neverVibrates(label, external) -> R.string.easy_vibration_off_external
        aiNote && label == AiClassification.DANGER -> R.string.haptic_ai_unavailable_danger
        aiNote -> AiUnavailableNotice.otherRow(loudAlerts)
        rowEnabled && shownStep != EasyVibration.Off && !player.hasAmplitudeControl -> R.string.haptic_no_amplitude
        // 자세한 설정에서 단계와 다른 세기로 맞춰 두었으면 어느 칸도 선택해 두지 않으므로 그 까닭과 할 일을 적는다(크기와 같다).
        shownStep == null -> R.string.easy_vibration_custom
        else -> null
    }

    // 종류마다 같은 "진동" 줄이 셋 있어서, 화면 읽어주기에는 어느 종류의 것인지 함께 읽힌다(자세한 설정과 같다, #312).
    val typeName = stringResource(soundTypeName(label))
    val vibrateLabel = stringResource(R.string.haptic_vibrate)

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            vibrateLabel,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (rowEnabled) SecondaryTextColor else SecondaryTextColor.copy(alpha = 0.5f),
            modifier = Modifier.semantics { contentDescription = "$typeName, $vibrateLabel" }
        )
        PictureChoiceRow(
            options = EasyVibration.values().toList(),
            selected = shownStep,
            labelOf = { stringResource(it.labelRes) },
            picture = { step, color -> VibrationStepIcon(step, color) },
            enabled = rowEnabled,
            optionEnabled = { EasyVibration.selectable(it, label, external) },
            modifier = Modifier.padding(top = 8.dp),
            onSelect = { step ->
                val next = EasyVibration.toStore(settings, step, label, external)
                if (next != null) SettingsManager.updateHaptic(label, next)
                preview(HapticModeChoice.shown(next ?: settings, label, external))
            }
        )
        if (noteRes != null) {
            Text(
                stringResource(noteRes),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = if (aiNote) WarningColor else SecondaryTextColor,
                style = LocalTextStyle.current.copy(lineBreak = EasyNoteLineBreak),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** 쉬운 설정의 진동 줄이 미리보기의 주인 이름 앞에 붙이는 말. 뒤에 종류 라벨이 붙는다. */
private const val EASY_PREVIEW_OWNER = "easy:"

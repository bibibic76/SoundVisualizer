package com.example.soundvisualizer

import android.animation.ValueAnimator
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.soundvisualizer.feedback.EasyHapticRow
import com.example.soundvisualizer.feedback.HapticPlayer
import com.example.soundvisualizer.feedback.soundTypeName
import com.example.soundvisualizer.tutorial.ModePreviewPhone

/*
 * 설정 탭의 쉬운 설정(#408). 1차 실증(농학교)에서 "설정이 너무 세세하고, 글과 슬라이더보다 그림이 낫다"는 의견이 나와
 * 그림으로 선택하는 화면을 따로 두었다.
 *
 * - 보이는 글은 그림 밑의 낱말 하나와 짧은 제목뿐이다. 설명 문장은 할 일을 알려 줄 때만 둔다.
 * - 슬라이더 대신 세 단계를 둔다: 크기([EasySize]), 진동([com.example.soundvisualizer.feedback.EasyVibration]).
 * - 쉬운 설정은 자기가 맡은 값만 바꾼다. 자세한 설정에만 있는 값(속도·광원·색 등)은 그대로 남아 계속 적용된다.
 * - 새 설정은 기본으로 자세한 설정에만 넣는다. 쉬운 설정에는 대부분의 사람이 바꿔야 하는 것만 둔다.
 */

/** 선택지 칸의 바탕색. 설정 화면의 다른 선택지(모드 단추, 진동 방식)와 같다. 글자 대비는 ColorContrastTest 가 본다. */
internal val ChoiceColor = Color(0xFF333A44)
private val ChoiceDisabledColor = Color(0xFF2A3038)

/**
 * 쉬운 설정의 안내 글 줄 바꿈. 한국어·일본어는 낱말(어절) 단위로 줄을 바꾼다(Android 13 이상, 다른 언어는 그대로).
 * 기본인 글자 단위로는 "바뀝니/다."처럼 낱말 중간에서 끊겨 끝 글자만 다음 줄에 남았다(#424).
 */
internal val EasyNoteLineBreak = LineBreak.Simple.copy(wordBreak = LineBreak.WordBreak.Phrase)

/**
 * 설정 탭 맨 위의 두 칸: 쉬운 설정 | 자세한 설정. 고른 화면은 저장되어 다음에 열 때도 그 화면이다.
 *
 * 카드 위에 둔다. 고른 칸의 색(AccentFillColor)은 탭 배경 위에서는 그림 요소 대비(3:1)에 못 미치고 카드 위에서는 넘는다
 * (분류 탭의 고급 모드 스위치와 같은 까닭). 화면 읽어주기에는 다른 선택지 줄처럼 "둘 중 몇 번째, 선택됨"으로 읽힌다.
 */
@Composable
internal fun SettingsViewSwitch(easy: Boolean) {
    val options = listOf(true, false)
    val labels = listOf(stringResource(R.string.settings_view_easy), stringResource(R.string.settings_view_detailed))
    val labelStyle = wrappingLabelStyle().copy(fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.padding(6.dp)) {
            val perRow = choicesPerRow(labels, maxPerRow = 2, rowWidth = maxWidth, gap = 6.dp, cellPadding = 8.dp, style = labelStyle)
            EqualChoiceRows(options, perRow, gap = 6.dp) { option, cellModifier ->
                val selected = option == easy
                Row(
                    modifier = cellModifier
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) AccentFillColor else Color.Transparent)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            // 자세한 설정의 슬라이더를 끄던 중이었어도 남은 값을 저장하고 넘어간다.
                            SettingsManager.flushModeSettings()
                            SettingsManager.setEasySettings(option)
                        }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        labels[options.indexOf(option)],
                        color = if (selected) Color.White else PrimaryTextColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        style = wrappingLabelStyle()
                    )
                }
            }
        }
    }
}

/** 쉬운 설정의 '자세한 설정' 칸을 눌렀을 때 자세한 설정에서 보여 줄 곳(#427). */
internal sealed interface DetailTarget {
    /** 그 모드의 카드(크기가 맨 위에 있다). */
    data class Size(val mode: VisualMode) : DetailTarget

    /** 그 소리 종류의 진동 줄. [label] 은 [AiClassification] 의 라벨이다. */
    data class Vibration(val label: String) : DetailTarget
}

/**
 * 쉬운 설정 화면. 모양 → 크기 → 소리 종류 → 빠른 설정 타일 순서다.
 *
 * @param active 설정 탭이 지금 보고 있는 탭인지. 아닐 때는 모양 카드를 움직이지 않는다.
 * @param onAddTile 시스템의 "빠른 설정에 추가" 창을 띄운다. Android 13 이상에서만 부른다.
 * @param onOpenDetailed '자세한 설정' 칸을 눌렀을 때. 자세한 설정으로 넘어가 그 설정이 있는 곳을 보여 준다.
 */
@Composable
internal fun EasySettingsContent(active: Boolean, onAddTile: () -> Unit, onOpenDetailed: (DetailTarget) -> Unit) {
    val mode by SettingsManager.visualMode.collectAsState()
    val size = stringResource(R.string.easy_section_size)
    Column {
        EasySection(stringResource(R.string.easy_section_shape)) { ModeCards(active) }
        // 크기는 선택한 모드의 것이라, 화면 읽어주기에는 "크기, 원형" 처럼 어느 모드의 크기인지 함께 읽힌다.
        EasySection(size, spokenTitle = "$size, ${stringResource(mode.labelRes)}") { SizeChoices(mode, onOpenDetailed) }
        EasySection(stringResource(R.string.easy_section_types)) { SoundTypeBlocks(onOpenDetailed) }
        QuickTileCard(onAddTile, compact = true)
    }
}

/**
 * 제목과 카드 한 장. 자세한 설정의 묶음(제목 + 카드)과 같은 모양이다.
 *
 * @param spokenTitle 화면 읽어주기가 제목 대신 읽을 글. 없으면 제목을 그대로 읽는다.
 */
@Composable
private fun EasySection(title: String, spokenTitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor,
        modifier = Modifier
            .semantics {
                heading()
                if (spokenTitle != null) contentDescription = spokenTitle
            }
            .padding(bottom = 16.dp)
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/**
 * 모양 카드 넷. 카드마다 그 모드를 실제 오버레이 엔진으로 그린 작은 폰 그림과 모드 이름이 있다.
 *
 * - 평소에는 네 장 모두 멈춘 그림이다. 선택한 카드만 잠깐(5초 안) 움직인다. 카드를 선택하거나, 선택한 카드를 다시 누르거나,
 *   크기를 바꾸거나, 설정 탭에 들어올 때다([ModePreviewPhone]).
 * - 설정 탭이 보이지 않으면([active] 가 거짓) 움직이지 않는다. 페이저는 옆 탭을 미리 그려 둔다.
 * - 시스템에서 애니메이션을 꺼 둔 사람에게는 멈춘 그림만 보인다(튜토리얼과 같은 규칙).
 * - 세로 화면에서는 두 장씩 두 줄, 넓은 화면(가로 등)에서는 네 장을 한 줄에 둔다. 가로로 미는 줄은 두지 않는다.
 *   탭은 화면 어디서나 가로로 밀어 넘긴다.
 */
@Composable
private fun ModeCards(active: Boolean) {
    val current by SettingsManager.visualMode.collectAsState()
    val wave by SettingsManager.waveMode.collectAsState()
    val pad by SettingsManager.padMode.collectAsState()
    val circle by SettingsManager.circleMode.collectAsState()
    val outline by SettingsManager.outlineMode.collectAsState()
    fun settingsOf(mode: VisualMode) = when (mode) {
        VisualMode.Wave -> wave
        VisualMode.Pad -> pad
        VisualMode.CircleRipple -> circle
        VisualMode.Outline -> outline
    }
    // 선택한 카드를 움직이라는 번호. 설정 탭에 들어오거나, 선택한 모드나 그 설정(크기)이 바뀌면 올린다.
    // 실행 중 알림의 모드 칩으로 모드를 바꿔도 따라간다.
    var play by remember { mutableIntStateOf(0) }
    val currentSettings = settingsOf(current)
    LaunchedEffect(active, current, currentSettings) {
        if (active) play++
    }
    BoxWithConstraints {
        val perRow = if (maxWidth >= WIDE_MODE_CARDS) 4 else 2
        // 폰 그림의 너비는 여기서 정해 넘긴다. 칸들이 높이를 맞추려고 크기를 미리 물어보기 때문이다([ModePreviewPhone]).
        val phoneWidth = (maxWidth - MODE_CARD_GAP * (perRow - 1)) / perRow - MODE_CARD_PADDING * 2
        EqualChoiceRows(VisualMode.values().toList(), perRow, gap = MODE_CARD_GAP) { mode, cellModifier ->
            val selected = mode == current
            ModeCard(
                mode, settingsOf(mode), selected,
                play = if (selected) play else 0,
                // 시스템 애니메이션 설정은 액티비티를 다시 만들지 않고 바뀐다(접근성의 애니메이션 삭제 등). 그래서 기억해 두지 않고
                // 칸을 다시 그릴 때마다 읽는다. 움직이라는 번호가 오르면 이 칸이 다시 그려지므로, 움직이기 시작할 때는 늘 지금 값을 본다.
                canPlay = active && selected && ValueAnimator.areAnimatorsEnabled(),
                phoneWidth = phoneWidth,
                modifier = cellModifier,
                // 선택한 카드를 다시 누르면 한 번 더 움직인다. 다른 카드는 선택이 바뀌어 위의 효과가 움직인다.
                onSelect = { if (selected) play++ else SettingsManager.setVisualMode(mode) }
            )
        }
    }
}

/** 이 너비부터 모양 카드 넷을 한 줄에 둔다. 한 장이 세로 화면의 두 장씩 놓을 때만큼은 넓다. */
private val WIDE_MODE_CARDS = 560.dp
private val MODE_CARD_GAP = 10.dp
private val MODE_CARD_PADDING = 6.dp

@Composable
private fun ModeCard(
    mode: VisualMode,
    settings: ModeSettings,
    selected: Boolean,
    play: Int,
    canPlay: Boolean,
    phoneWidth: Dp,
    modifier: Modifier,
    onSelect: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) AccentFillColor else ChoiceColor)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(MODE_CARD_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ModePreviewPhone(mode, settings, play, canPlay, phoneWidth)
        // 선택한 카드는 색에 더해 체크 표시로도 보인다. 색만으로 알리지 않는다.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(top = 6.dp, bottom = 2.dp)
        ) {
            if (selected) {
                // 이름 앞의 표시는 화면 읽어주기에 "선택됨"으로 이미 읽히므로 따로 읽지 않는다.
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                stringResource(mode.labelRes),
                color = if (selected) Color.White else PrimaryTextColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                style = wrappingLabelStyle()
            )
        }
    }
}

/**
 * 크기 세 단계. 크기는 모드마다 따로라(사용자 결정 2026-10-22) 지금 선택한 모드의 크기를 보여 주고 바꾼다. 그림도 그 모드의
 * 모양으로 그린다([SizeStepIcon]). 모양 카드에서 다른 모드를 선택하면 그 모드에 정해 둔 크기로 바뀐다.
 *
 * 단계 밑에 '자세한 설정' 칸이 있다(#427). 자세한 설정에서 그 모드의 크기·진하기를 단계와 다르게 맞췄거나 크기 고정을
 * 켰으면 단계 대신 이 칸이 선택되어 보이고, 누르면 자세한 설정의 그 모드 카드로 간다. 단계를 누르면 그 모드만 바뀌고 모양
 * 카드에 바로 보이므로 따로 묻지 않는다.
 */
@Composable
private fun SizeChoices(mode: VisualMode, onOpenDetailed: (DetailTarget) -> Unit) {
    val wave by SettingsManager.waveMode.collectAsState()
    val pad by SettingsManager.padMode.collectAsState()
    val circle by SettingsManager.circleMode.collectAsState()
    val outline by SettingsManager.outlineMode.collectAsState()
    val settings = when (mode) {
        VisualMode.Wave -> wave
        VisualMode.Pad -> pad
        VisualMode.CircleRipple -> circle
        VisualMode.Outline -> outline
    }
    val selected = EasySize.of(settings)
    PictureChoiceRow(
        options = EasySize.values().toList(),
        selected = selected,
        labelOf = { stringResource(it.labelRes) },
        picture = { step, color -> SizeStepIcon(step, mode, color) },
        // 단계와 다르면 가장 가까운 단계로 보이지 않는다. 실제 크기와 다르고, 그 칸을 누르면 오히려 크기가 바뀐다(#424).
        detailedSelected = selected == null,
        onDetailed = { onOpenDetailed(DetailTarget.Size(mode)) },
        onSelect = { SettingsManager.applyEasySize(mode, it) }
    )
}

/**
 * 소리 종류 셋. 위협음 → 대화음 → 환경음, 가장 중요한 종류부터 둔다(분류 탭과 같은 순서). 종류마다 화면 표시를 켜고 끄는
 * 줄과 진동 세 칸이 있다. 색은 바꾸지 않고 종류 그림에 칠해 보여 주기만 한다. 색 바꾸기는 자세한 설정에 있다.
 */
@Composable
private fun ColumnScope.SoundTypeBlocks(onOpenDetailed: (DetailTarget) -> Unit) {
    // 모델을 못 불러온 채 실행 중이면 종류별 색과 진동이 먹지 않는 까닭을 맨 위에 알린다(자세한 설정과 같은 안내).
    val aiAvailable by SettingsManager.aiAvailable.collectAsState()
    if (!aiAvailable) {
        Text(
            stringResource(R.string.ai_unavailable),
            fontSize = 14.sp, lineHeight = 21.sp, color = WarningColor,
            style = LocalTextStyle.current.copy(lineBreak = EasyNoteLineBreak),
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }
    // 실행 중에는 진동 칸을 눌러도 미리 울리지 않는다(실제 진동과 섞이지 않게, #244). 그 까닭을 진동 줄마다가 아니라 여기 한 번
    // 적는다. 줄마다 두면 AI·세기 안내에 가려 빠지는 줄이 생긴다. 진동이 없는 기기는 줄마다 그 사실을 적으므로 여기 두지 않는다.
    val running by SettingsManager.isServiceRunning.collectAsState()
    val context = LocalContext.current
    val hasVibrator = remember { HapticPlayer(context).hasVibrator }
    if (running && hasVibrator) {
        Text(
            stringResource(R.string.easy_vibration_preview_running),
            fontSize = 13.sp, lineHeight = 19.sp, color = SecondaryTextColor,
            style = LocalTextStyle.current.copy(lineBreak = EasyNoteLineBreak),
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }
    val showDanger by SettingsManager.showDanger.collectAsState()
    val colorDanger by SettingsManager.colorDanger.collectAsState()
    val showSpeech by SettingsManager.showSpeech.collectAsState()
    val colorSpeech by SettingsManager.colorSpeech.collectAsState()
    val showAmbient by SettingsManager.showAmbient.collectAsState()
    val colorAmbient by SettingsManager.colorAmbient.collectAsState()

    SoundTypeBlock(AiClassification.DANGER, R.string.ai_show_danger, showDanger, colorDanger, onOpenDetailed) {
        SettingsManager.updateAISettings(showDanger = it)
    }
    TypeDivider()
    SoundTypeBlock(AiClassification.SPEECH, R.string.ai_show_speech, showSpeech, colorSpeech, onOpenDetailed) {
        SettingsManager.updateAISettings(showSpeech = it)
    }
    TypeDivider()
    SoundTypeBlock(AiClassification.AMBIENT, R.string.ai_show_ambient, showAmbient, colorAmbient, onOpenDetailed) {
        SettingsManager.updateAISettings(showAmbient = it)
    }
}

@Composable
private fun TypeDivider() {
    Spacer(
        Modifier
            .padding(vertical = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(ChoiceColor)
    )
}

/**
 * 소리 종류 하나: [종류 그림] 이름 ··· [눈] [스위치], 그 아래 진동 세 칸.
 *
 * 줄 전체를 누르면 화면 표시가 켜지고 꺼진다. 화면 읽어주기에는 "위협음 표시, 스위치, 켜짐" 으로 읽힌다(자세한 설정의 스위치와
 * 같은 이름). 그림과 눈 표시는 이름과 스위치가 이미 말하는 것이라 읽지 않는다.
 */
@Composable
private fun SoundTypeBlock(
    label: String,
    showLabelRes: Int,
    shown: Boolean,
    color: Int,
    onOpenDetailed: (DetailTarget) -> Unit,
    onShownChange: (Boolean) -> Unit
) {
    val showLabel = stringResource(showLabelRes)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = shown,
                // 눌림 표시는 두지 않는다. 어두운 카드 위에서 색 상자로 번쩍이고, 스위치가 움직이는 것으로 충분하다.
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onValueChange = onShownChange
            )
            .clearAndSetSemantics { contentDescription = showLabel }
    ) {
        SoundTypeBadge(label, Color(color))
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(soundTypeName(label)),
            fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor,
            modifier = Modifier.weight(1f)
        )
        ShowIcon(shown, if (shown) PrimaryTextColor else SecondaryTextColor)
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = shown,
            // 누르는 것은 줄 전체가 받는다.
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentFillColor,
                uncheckedThumbColor = SecondaryTextColor,
                uncheckedTrackColor = ChoiceColor
            )
        )
    }
    EasyHapticRow(label, shown, onOpenDetailed = { onOpenDetailed(DetailTarget.Vibration(label)) })
}

/**
 * 그림과 낱말 하나로 된 선택지 줄(#408). 설정 화면의 다른 선택지(모드 단추, 진동 방식)와 같은 색으로 선택을 보이고,
 * 선택한 칸 모서리에 체크 표시를 둔다.
 *
 * - 번역된 낱말이 칸보다 길면 줄을 바꾸고 칸 높이를 함께 맞춘다. 글꼴을 키워 낱말이 칸에 들어가지 않으면 한 줄의 칸 수를
 *   줄인다([choicesPerRow]).
 * - 그림은 아래 낱말이 말하는 것을 보여 주기만 하므로 화면 읽어주기에서는 낱말만 읽힌다.
 * - [onDetailed] 를 주면 칸들 밑에 '자세한 설정' 칸을 가로로 길게 둔다(#427). 같은 선택지 묶음이라 화면 읽어주기에
 *   "4개 중 4번째"처럼 함께 세어진다.
 *
 * @param picture 칸의 그림. 받은 색으로 그린다(선택한 칸은 흰색, 흐린 칸은 흐린 색).
 * @param optionEnabled 칸마다 선택할 수 있는지. 선택할 수 없는 칸은 줄 전체가 꺼졌을 때와 같은 색으로 흐리게 둔다.
 * @param detailedSelected 값이 어느 칸과도 맞지 않아 '자세한 설정' 칸을 선택된 것으로 보일지
 * @param onDetailed '자세한 설정' 칸을 눌렀을 때. null 이면 그 칸을 두지 않는다.
 */
@Composable
internal fun <T> PictureChoiceRow(
    options: List<T>,
    selected: T?,
    labelOf: @Composable (T) -> String,
    picture: @Composable (T, Color) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    optionEnabled: (T) -> Boolean = { true },
    detailedSelected: Boolean = false,
    onDetailed: (() -> Unit)? = null,
    onSelect: (T) -> Unit
) {
    val labels = options.map { labelOf(it) }
    val labelStyle = wrappingLabelStyle().copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val footer: (@Composable () -> Unit)? = onDetailed?.let { open ->
        { DetailedChoiceCell(selected = detailedSelected, enabled = enabled, onClick = open) }
    }
    BoxWithConstraints(modifier = modifier) {
        val perRow = choicesPerRow(labels, maxPerRow = options.size, rowWidth = maxWidth, gap = 8.dp, cellPadding = 4.dp, style = labelStyle)
        EqualChoiceRows(options, perRow, gap = 8.dp, footer = footer) { option, cellModifier ->
            val isSelected = option == selected
            val optionOn = enabled && optionEnabled(option)
            val content = when {
                !optionOn -> PrimaryTextColor.copy(alpha = 0.35f)
                isSelected -> Color.White
                else -> PrimaryTextColor
            }
            Box(
                // 칸 높이는 48dp 이상으로 둔다(#312). 그림과 낱말이 함께 들어가 그보다 높다.
                modifier = cellModifier
                    .heightIn(min = 64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            !optionOn -> ChoiceDisabledColor
                            isSelected -> AccentFillColor
                            else -> ChoiceColor
                        }
                    )
                    .selectable(selected = isSelected, enabled = optionOn, role = Role.RadioButton) { onSelect(option) }
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    picture(option, content)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        labelOf(option),
                        color = content,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        style = wrappingLabelStyle()
                    )
                }
                if (isSelected) {
                    // 선택한 칸은 색에 더해 체크 표시로도 보인다(모양 카드와 같은 규칙). 낱말 앞에 두면 긴 번역어가 좁은 칸에서
                    // 줄을 바꾸므로(360dp 폭의 "Desligada") 칸 모서리에 둔다. 화면 읽어주기에는 "선택됨"으로 이미 읽힌다.
                    Icon(
                        Icons.Default.Check, contentDescription = null, tint = content,
                        modifier = Modifier.align(Alignment.TopStart).padding(5.dp).size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * 선택지 줄 밑의 '자세한 설정' 칸(#427): [조절 그림] 자세한 설정 ··· [체크] [>].
 *
 * 자세한 설정에서 단계와 다르게 맞춘 값이면 선택된 것으로 보인다. 누르면 값을 바꾸지 않고 자세한 설정의 그 자리로 간다.
 * 이름은 맨 위 두 칸의 '자세한 설정'과 같은 글자를 쓴다. 칸이 가로로 길어 체크 표시를 낱말 뒤에 둔다.
 */
@Composable
private fun DetailedChoiceCell(selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val content = when {
        !enabled -> PrimaryTextColor.copy(alpha = 0.35f)
        selected -> Color.White
        else -> PrimaryTextColor
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    !enabled -> ChoiceDisabledColor
                    selected -> AccentFillColor
                    else -> ChoiceColor
                }
            )
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        DetailedIcon(content)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.settings_view_detailed),
            color = content,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            style = wrappingLabelStyle(),
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
    }
}

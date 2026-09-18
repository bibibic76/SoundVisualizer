package com.example.soundvisualizer

import android.graphics.drawable.Icon
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * 좁은 칸(모드 선택, 진동 선택지, 슬라이더 이름)에 들어가는 이름표의 글자 모양.
 * 번역된 이름이 칸보다 길면 줄을 바꾸는데, 긴 단어는 아무 글자에서나 끊지 않고 하이픈을 넣어 끊는다.
 * (하이픈 규칙이 있는 언어만 해당된다.)
 */
@Composable
fun wrappingLabelStyle(): TextStyle = LocalTextStyle.current.copy(hyphens = Hyphens.Auto)

/**
 * 스위치를 켜야 쓰이는 세부 설정을 감싼다. 꺼져 있으면 자리를 차지하지 않고, 켜면 위아래로 펼쳐진다.
 *
 * 쓸 수 없는 설정을 흐릿하게 남겨 두면 화면만 길어지고 지금 무엇이 먹는 값인지 한눈에 들어오지 않는다.
 * 그렇다고 움직임 없이 툭 나타났다 사라지면 어디가 늘거나 줄었는지 놓치기 쉬워서, 펼쳐지고 접히는
 * 동안을 보여 준다.
 */
@Composable
fun DependentSettings(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Column(content = content)
    }
}

/**
 * 제목을 누르면 펼쳐지는 카드. 설정 탭의 모드 카드와 도움말 탭의 주제 카드가 함께 쓴다.
 *
 * @param isExpanded 처음 그릴 때 펼쳐 둘지. 사용자가 한 번 접거나 펼치면 그 뒤로는 사용자의 선택이 이긴다.
 */
@Composable
fun SettingsExpander(title: String, isExpanded: Boolean = false, content: @Composable () -> Unit) {
    // 펼침 상태는 목록 밖으로 스크롤되면 사라지지 않게 저장한다. remember 로 두면 펼쳐 둔 카드가
    // 화면 밖에 나갔다 돌아왔을 때 접혀 있고, 접어 둔 카드는 다시 펼쳐져 있다.
    // [isExpanded] 를 키로 두어 고른 모드가 바뀔 때만 다시 맞춘다. 키가 없으면 새로 고른 모드의 카드는
    // 접힌 채로 맨 위에 오고, 방금 쓰던 모드의 카드는 펼쳐진 채로 남는다.
    var expanded by rememberSaveable(isExpanded) { mutableStateOf(isExpanded) }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically, 
                // 아래 여백은 누르는 자리 밖에 둔다. 안에 두면 펼친 내용과의 빈 칸까지 눌린다.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (expanded) 24.dp else 0.dp)
                    .clickable(
                        // 눌림 표시는 두지 않는다. 어두운 카드 위에서 색 상자로 번쩍이고, 화살표가 돌고 내용이 펼쳐지는 것으로 충분하다.
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { expanded = !expanded }
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
        // 이름·값과 슬라이더를 한 줄에 두면 끌 수 있는 막대가 80~116dp 밖에 남지 않고, 긴 번역어는
        // 좁은 이름 칸에서 여러 줄로 접힌다. 이름과 값을 위 줄에, 슬라이더를 아래 한 줄에 둔다.
        // 이름과 값은 아래 슬라이더가 이미 함께 읽어 주므로 화면 읽어주기에서는 건너뛴다.
        // 그러지 않으면 슬라이더 하나가 "이름", "값", "이름, 슬라이더, 값" 으로 세 번 멈춘다.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics { }
        ) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = labelColor, style = wrappingLabelStyle(), modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            Text(String.format(Locale.US, "%.0f", value), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = valueColor)
        }
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
            // 이름이 없으면 화면 읽어주기가 "슬라이더, 50%" 로만 읽어 무엇의 값인지 알 수 없다.
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
        )
        Text(desc, fontSize = 13.sp, color = descColor)
    }
}

@Composable
fun ModernSwitch(label: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column(
        // 줄 전체를 누를 수 있게 하고 이름·설명·상태를 한 덩어리로 묶는다. 스위치만 누를 수 있으면
        // 화면 읽어주기가 이름 없이 "스위치, 켜짐" 으로 읽고, 손가락으로도 작은 스위치만 노려야 한다.
        modifier = Modifier
            // 아래 여백은 누르는 자리 밖에 둔다. 안에 두면 다음 항목과의 빈 칸까지 눌린다.
            .padding(bottom = 24.dp)
            .toggleable(
                value = checked,
                // 눌림 표시는 두지 않는다. 어두운 카드 위에서 색 상자로 번쩍이고, 스위치가 움직이는 것으로 충분하다.
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onValueChange = {
                    onCheckedChange(it)
                    // 스위치는 한 번에 끝나는 조작이라 바로 저장한다.
                    SettingsManager.flushModeSettings()
                }
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        Text(desc, fontSize = 13.sp, color = SecondaryTextColor, modifier = Modifier.padding(top = 8.dp))
    }
}

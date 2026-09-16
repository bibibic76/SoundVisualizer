package com.example.soundvisualizer.language

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.soundvisualizer.AccentColor
import com.example.soundvisualizer.CardColor
import com.example.soundvisualizer.PrimaryTextColor
import com.example.soundvisualizer.R
import com.example.soundvisualizer.SecondaryTextColor
import java.util.Locale
import kotlin.math.sqrt

/**
 * 설정 탭 맨 위의 언어 카드. 지금 언어를 보여주고, 누르면 언어 선택 창을 연다.
 *
 * 맨 위에 두는 이유: 읽지 못하는 언어로 바뀌었을 때도 설정 탭을 열자마자 지구본과 언어 이름으로 찾을 수 있어야 한다.
 * 선택 창의 언어 이름은 번역하지 않고 각 언어로 적는다.
 */
@Composable
fun LanguageSettingCard() {
    val context = LocalContext.current
    // 언어가 바뀌면 액티비티가 다시 만들어지므로 처음 한 번만 읽는다.
    val selectedTag = remember(context) { AppLanguage.selectedTag(context) }
    val selected = remember(selectedTag) { AppLanguages.match(selectedTag) }
    var showDialog by rememberSaveable { mutableStateOf(false) }

    val currentLabel = when {
        selectedTag == null -> stringResource(R.string.settings_language_system)
        selected != null -> selected.endonym
        // 폰 설정에서 목록에 없는 언어를 고른 경우. 그 언어 스스로의 이름으로 보여준다.
        else -> Locale.forLanguageTag(selectedTag).let { it.getDisplayName(it) }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    // 눌림 표시는 두지 않는다. 어두운 카드 위에서 색 상자로 번쩍이고, 선택 창이 뜨는 것으로 충분하다.
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(R.string.cd_change_language)
                ) { showDialog = true }
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            GlobeIcon(color = AccentColor, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_language_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor)
                Text(
                    currentLabel,
                    fontSize = 13.sp,
                    color = SecondaryTextColor,
                    style = LocalTextStyle.current.copy(localeList = selected?.let { LocaleList(it.tag) })
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = SecondaryTextColor)
        }
    }

    if (showDialog) {
        LanguageDialog(
            followsSystem = selectedTag == null,
            selected = selected,
            onSelect = { language ->
                showDialog = false
                context.findActivity()?.let { AppLanguage.select(it, language?.tag) }
            },
            onDismiss = { showDialog = false }
        )
    }
}

/**
 * 한 가지만 고르는 언어 선택 창. 첫 줄은 폰 언어 따르기(null)다.
 *
 * @param followsSystem 지금 폰 언어를 따르는지
 * @param selected 지금 고른 지원 언어. 폰 언어를 따르거나 목록에 없는 언어면 null
 */
@Composable
private fun LanguageDialog(
    followsSystem: Boolean,
    selected: SupportedLanguage?,
    onSelect: (SupportedLanguage?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor,
        title = { Text(stringResource(R.string.settings_language_title), color = PrimaryTextColor, fontWeight = FontWeight.Bold) },
        text = {
            // 18개 남짓이라 LazyColumn 대신 스크롤되는 Column 으로 충분하다.
            Column(modifier = Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                LanguageOption(
                    label = stringResource(R.string.settings_language_system),
                    localeTag = null,
                    isSelected = followsSystem,
                    onClick = { onSelect(null) }
                )
                AppLanguages.all.forEach { language ->
                    LanguageOption(
                        label = language.endonym,
                        localeTag = language.tag,
                        isSelected = !followsSystem && language == selected,
                        onClick = { onSelect(language) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_language_cancel), color = AccentColor, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun LanguageOption(label: String, localeTag: String?, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(end = 8.dp)
    ) {
        RadioButton(
            selected = isSelected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = AccentColor, unselectedColor = SecondaryTextColor),
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Text(
            label,
            fontSize = 16.sp,
            color = if (isSelected) PrimaryTextColor else PrimaryTextColor.copy(alpha = 0.85f),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            // 간체·번체·일본어 한자가 각 언어의 글자 모양으로 보이도록 줄마다 로캘을 준다.
            style = LocalTextStyle.current.copy(localeList = localeTag?.let { LocaleList(it) })
        )
    }
}

/** 언어 카드의 지구본. 아이콘 라이브러리를 늘리지 않으려고 직접 그린다. */
@Composable
private fun GlobeIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.8.dp.toPx()
        val stroke = Stroke(width = strokeWidth)
        val radius = size.minDimension / 2f - strokeWidth / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f

        drawCircle(color, radius = radius, center = Offset(cx, cy), style = stroke)
        // 경선: 가운데 세로 타원
        val meridianHalfWidth = radius * 0.45f
        drawOval(
            color,
            topLeft = Offset(cx - meridianHalfWidth, cy - radius),
            size = Size(meridianHalfWidth * 2f, radius * 2f),
            style = stroke
        )
        // 위선: 원 안에 들어가는 가로줄 두 개
        val latitudeOffset = radius * 0.4f
        val halfChord = radius * sqrt(1f - 0.4f * 0.4f)
        for (y in floatArrayOf(cy - latitudeOffset, cy + latitudeOffset)) {
            drawLine(color, Offset(cx - halfChord, y), Offset(cx + halfChord, y), strokeWidth = strokeWidth)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

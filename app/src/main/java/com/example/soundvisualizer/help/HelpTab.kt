package com.example.soundvisualizer.help

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.soundvisualizer.AccentColor
import com.example.soundvisualizer.CardColor
import com.example.soundvisualizer.PrimaryTextColor
import com.example.soundvisualizer.R
import com.example.soundvisualizer.SecondaryTextColor
import com.example.soundvisualizer.SettingsExpander
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val BodySize = 15.sp
private val BodyLineHeight = 23.sp

/**
 * 도움말 탭. README 의 사용자용 내용을 앱 안에서 볼 수 있게 주제별 펼치기 카드로 보여준다.
 * 처음 쓰는 사람이 먼저 볼 "시작하기"만 펼쳐 둔다. 문구는 모두 strings.xml 의 help_* 에 있다.
 */
@Composable
fun HelpTab() {
    var showLicenses by rememberSaveable { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.padding(horizontal = 24.dp).fillMaxSize()) {
        item {
            SettingsExpander(stringResource(R.string.help_start_title), isExpanded = true) {
                NumberedSteps(R.string.help_start_1, R.string.help_start_2, R.string.help_start_3, R.string.help_start_4)
            }
        }
        item {
            SettingsExpander(stringResource(R.string.help_tile_title)) {
                Paragraphs(R.string.help_tile_body, R.string.help_tile_use, R.string.help_tile_old)
            }
        }
        item {
            SettingsExpander(stringResource(R.string.help_permissions_title)) {
                TitledItem(R.string.help_perm_overlay, R.string.help_perm_overlay_desc)
                TitledItem(R.string.help_perm_mic, R.string.help_perm_mic_desc)
                TitledItem(R.string.help_perm_capture, R.string.help_perm_capture_desc)
                TitledItem(R.string.help_perm_notification, R.string.help_perm_notification_desc)
            }
        }
        item {
            SettingsExpander(stringResource(R.string.help_modes_title)) {
                TitledItem(R.string.mode_wave, R.string.help_mode_wave_desc)
                TitledItem(R.string.mode_pad, R.string.help_mode_pad_desc)
                TitledItem(R.string.mode_outline, R.string.help_mode_outline_desc)
                TitledItem(R.string.mode_circle, R.string.help_mode_circle_desc)
                Paragraphs(R.string.help_modes_note)
            }
        }
        item {
            SettingsExpander(stringResource(R.string.help_sound_title)) {
                Paragraphs(
                    R.string.help_sound_kinds,
                    R.string.help_sound_danger,
                    R.string.help_sound_haptic,
                    R.string.help_sound_haptic_rule
                )
            }
        }
        item {
            SettingsExpander(stringResource(R.string.help_notes_title)) {
                Bullets(R.string.help_note_direction, R.string.help_note_mono, R.string.help_note_sources, R.string.help_note_ai)
            }
        }
        item {
            SettingsExpander(stringResource(R.string.help_faq_title)) {
                TitledItem(R.string.help_faq_no_graphic_q, R.string.help_faq_no_graphic_a)
                TitledItem(R.string.help_faq_no_vibration_q, R.string.help_faq_no_vibration_a)
                TitledItem(R.string.help_faq_install_q, R.string.help_faq_install_a)
                TitledItem(R.string.help_faq_battery_q, R.string.help_faq_battery_a)
                TitledItem(R.string.help_faq_language_q, R.string.help_faq_language_a)
            }
        }
        item {
            SettingsExpander(stringResource(R.string.help_about_title)) {
                val context = LocalContext.current
                val version = remember(context) { versionName(context) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.help_about_version, version), fontSize = BodySize, fontWeight = FontWeight.Bold, color = PrimaryTextColor)
                    Text(stringResource(R.string.help_about_team), fontSize = BodySize, color = PrimaryTextColor)
                    Text(stringResource(R.string.help_about_license), fontSize = 14.sp, lineHeight = 21.sp, color = SecondaryTextColor)
                }
                OutlinedButton(
                    onClick = { showLicenses = true },
                    border = BorderStroke(1.dp, AccentColor),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.help_licenses_button), fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = AccentColor)
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    if (showLicenses) {
        LicenseDialog(onDismiss = { showLicenses = false })
    }
}

@Composable
private fun Paragraphs(@StringRes vararg texts: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        texts.forEach { res ->
            Text(stringResource(res), fontSize = BodySize, lineHeight = BodyLineHeight, color = PrimaryTextColor)
        }
    }
}

@Composable
private fun NumberedSteps(@StringRes vararg steps: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        steps.forEachIndexed { index, res ->
            Row {
                Text("${index + 1}.", fontSize = BodySize, lineHeight = BodyLineHeight, fontWeight = FontWeight.Bold, color = AccentColor, modifier = Modifier.width(24.dp))
                Text(stringResource(res), fontSize = BodySize, lineHeight = BodyLineHeight, color = PrimaryTextColor)
            }
        }
    }
}

@Composable
private fun Bullets(@StringRes vararg items: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { res ->
            Row {
                Text("•", fontSize = BodySize, lineHeight = BodyLineHeight, color = SecondaryTextColor, modifier = Modifier.width(16.dp))
                Text(stringResource(res), fontSize = BodySize, lineHeight = BodyLineHeight, color = PrimaryTextColor)
            }
        }
    }
}

/** 굵은 제목 한 줄과 설명. 권한, 모드, 자주 묻는 질문에 쓴다. */
@Composable
private fun TitledItem(@StringRes title: Int, @StringRes description: Int) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(stringResource(title), fontSize = BodySize, fontWeight = FontWeight.Bold, color = PrimaryTextColor)
        Text(
            stringResource(description),
            fontSize = 14.sp, lineHeight = 21.sp, color = SecondaryTextColor,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** 오픈소스 고지 전문. 파일이 작지만(약 20KB) 메인 스레드에서 읽지 않는다. */
@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text by produceState(initialValue = "") {
        value = withContext(Dispatchers.IO) {
            LICENSE_ASSETS.joinToString(separator = "\n\n") { path ->
                context.assets.open(path).bufferedReader().use { it.readText() }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor,
        title = { Text(stringResource(R.string.help_licenses_title), color = PrimaryTextColor, fontWeight = FontWeight.Bold) },
        text = {
            // 고지 파일은 고정폭 글꼴 기준으로 줄을 맞춰 두었다.
            Text(
                text,
                fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace, color = SecondaryTextColor,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.help_close), color = AccentColor) }
        }
    )
}

private fun versionName(context: Context): String {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    return info.versionName.orEmpty()
}

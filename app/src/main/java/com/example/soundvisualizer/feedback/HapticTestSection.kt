package com.example.soundvisualizer.feedback

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.soundvisualizer.AccentColor
import com.example.soundvisualizer.PrimaryTextColor
import com.example.soundvisualizer.R
import com.example.soundvisualizer.SecondaryTextColor
import com.example.soundvisualizer.SettingsManager
import com.example.soundvisualizer.feedback.HapticSimulator.Cue

private const val OWNER = "developer-test"
private const val SEAM_ID = "seam"

/**
 * 개발자 모드의 '진동 시험'. 흉내 낸 소리를 실제 진동 경로로 울려, 소리를 낼 수 없는 폰에서도 소리 따라를 느껴 본다.
 *
 * 예시 이름은 번역하지 않는다(개발자용). 시각화가 켜져 있으면 쓸 수 없다. 실제 진동 알림과 진동기를 다투지 않게 하려는 것이다.
 *
 * `seam` 은 이 폰에서 다시 보내기의 이음매가 느껴지는지 재는 시험이다. 1.5초씩 쉬며 넷을 차례로 울린다.
 *  - A: 한 계획으로 2초 동안 세기 130
 *  - B: 같은 것을 160ms 마다 다시 보냄
 *  - C: 하이로 사이렌을 미리 계산해 한 파형으로(Android 12 이상)
 *  - D: 같은 것을 실제처럼 여러 계획으로
 * B 가 A 보다 거칠면 이 폰은 이음매가 느껴진다. D 가 C 보다 거칠면 소리 따라가 아직 끊긴다.
 * C 가 밋밋하면 이 폰은 파형 도중의 세기 변화를 따르지 않는다.
 */
@Composable
fun HapticTestSection() {
    val context = LocalContext.current
    val running by SettingsManager.isServiceRunning.collectAsState()
    val owner by HapticPreviewGate.owner.collectAsState()
    var strength by rememberSaveable { mutableStateOf(HapticStrength.Medium) }
    var chosen by remember { mutableStateOf<String?>(null) }
    // 예시가 저절로 끝나면 주인이 비므로 눌림 표시도 풀린다.
    val playing = if (owner == OWNER) chosen else null

    DisposableEffect(Unit) {
        onDispose { HapticPreviewGate.stopPreview(OWNER) }
    }
    // 시각화를 켜면 시험을 멈춘다. 실제 진동 알림과 진동기를 다투지 않게 한다.
    LaunchedEffect(running) {
        if (running) HapticPreviewGate.stopPreview(OWNER)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            stringResource(R.string.setting_haptic_test),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (running) PrimaryTextColor.copy(alpha = 0.35f) else PrimaryTextColor
        )
        Text(
            stringResource(R.string.setting_haptic_test_desc),
            fontSize = 13.sp,
            color = SecondaryTextColor,
            modifier = Modifier.padding(top = 4.dp)
        )
        HapticChoiceRow(
            title = stringResource(R.string.haptic_strength),
            options = HapticStrength.values().toList(),
            selected = strength,
            labelOf = { stringResource(it.labelRes) },
            enabled = !running,
            onSelect = { strength = it }
        )
        Spacer(modifier = Modifier.padding(top = 8.dp))
        val ids = HapticScenes.all.map { it.id } + SEAM_ID
        ids.chunked(4).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                row.forEach { id ->
                    val selected = playing == id
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    running -> Color(0xFF2A3038)
                                    selected -> AccentColor
                                    else -> Color(0xFF333A44)
                                }
                            )
                            .selectable(selected = selected, enabled = !running, role = Role.Button) {
                                if (selected) {
                                    HapticPreviewGate.stopPreview(OWNER)
                                } else {
                                    chosen = id
                                    if (id == SEAM_ID) {
                                        HapticPreviewGate.startSequence(context, OWNER, seamCues(Build.VERSION.SDK_INT))
                                    } else {
                                        val scene = HapticScenes.byId(id) ?: return@selectable
                                        HapticPreviewGate.startScene(context, OWNER, scene, scene.label, strength)
                                    }
                                }
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            id,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                running -> PrimaryTextColor.copy(alpha = 0.35f)
                                selected -> Color.White
                                else -> PrimaryTextColor
                            }
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/** 이음매 시험의 계획들: A, B, (C,) D 가 1.5초씩 쉬며 차례로 나온다. */
internal fun seamCues(sdk: Int): List<Cue> {
    val limits = HapticTuning.limitsFor(sdk)
    val out = ArrayList<Cue>()
    var t = 0L
    val gap = 1500L
    val level = 130
    val lengthMs = 2000L

    // A: 한 계획
    out.add(Cue(t, segment(PlanReason.TEST, lengthMs, level, limits)))
    t += lengthMs + 200 + gap

    // B: 같은 내용을 160ms 마다 다시 보낸다
    val bStart = t
    var k = 0L
    while (k < lengthMs) {
        out.add(Cue(bStart + k, segment(PlanReason.TEST, lengthMs - k, level, limits)))
        k += 160
    }
    t = bStart + lengthMs + 200 + gap

    // C·D: 하이로 사이렌 4초
    val scene = HapticScenes.hilo.trimmed(4000L)
    val cues = HapticSimulator.run(scene, HapticStrength.Medium, sdk = sdk, tailMs = 600L)
    val until = scene.durationMs + 400L
    if (sdk >= 31) {
        out.add(Cue(t, HapticSimulator.stitch(cues, until)))
        t += until + gap
    }
    for (c in cues) if (c.atMs < until) out.add(Cue(t + c.atMs, c.plan))
    return out
}

private fun segment(reason: PlanReason, ms: Long, level: Int, limits: HapticTuning.PlanLimits): HapticPlan =
    HapticPlanBuilder(reason, limits, amplitudeMode = true)
        .body(ms, level)
        .fade(level, limits.endFade, limits.endFadeStepMs)
        .build()

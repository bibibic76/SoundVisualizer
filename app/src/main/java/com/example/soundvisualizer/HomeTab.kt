package com.example.soundvisualizer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.soundvisualizer.feedback.AiUnavailableNotice
import com.example.soundvisualizer.feedback.HapticPlayer
import kotlin.math.roundToInt

/** 홈의 실행·실행 종료 버튼 안쪽 여백. 번역된 이름이 길어도 글자 자리가 넉넉하도록 좌우를 기본(24dp)보다 줄였다. */
private val HomeButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

@Composable
fun HomeTab(onStart: () -> Unit, onStop: () -> Unit, onOpenTutorial: () -> Unit) {
    val isRunning by SettingsManager.isServiceRunning.collectAsState()
    val aiAvailable by SettingsManager.aiAvailable.collectAsState()
    val captureBlocked by SettingsManager.isCaptureBlocked.collectAsState()
    val lastUnexpectedStop by SettingsManager.lastUnexpectedStop.collectAsState()
    val externalSoundMode by SettingsManager.externalSoundMode.collectAsState()
    // 실행 중에는 설정값이 아니라 서비스가 실제로 연 소스를 적는다. 둘이 어긋나면 방을 듣지 않는데도 듣는다고 믿게 된다.
    val runningSource by SettingsManager.runningCaptureSource.collectAsState()
    val listeningAround = runningSource == CaptureSource.Microphone
    val dangerShown by SettingsManager.showDanger.collectAsState()
    val dangerHaptic by SettingsManager.hapticSettings(AiClassification.DANGER).collectAsState()
    val context = LocalContext.current
    val hasVibrator = remember { HapticPlayer(context).hasVibrator }

    // 글자 크기나 화면 확대를 크게 쓰면 안내와 버튼이 화면보다 길어진다. Column 은 남은 높이만 나눠 주므로
    // 마지막 자식(실행·실행 종료 버튼)이 눌려 사라진다. 스크롤을 열고 최소 높이를 화면 높이로 잡아
    // 짧을 때는 가운데 정렬로, 길면 밀어 볼 수 있게 한다.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp)
        ) {
            // 본 내용은 가운데에, ‘튜토리얼 보기’는 맨 아래에 둔다. 스크롤 안이라 최대 높이는 끝이 없지만, 최소 높이
            // (화면 높이)까지 남는 자리는 weight 를 준 두 빈칸이 나눠 가진다. 내용이 화면보다 길면 남는 자리가 없어
            // 빈칸은 0 이 되고, ‘튜토리얼 보기’는 스크롤 끝에 온다.
            Spacer(modifier = Modifier.weight(1f))

            // 줄 간격을 지정하지 않으면 큰 글꼴 설정에서 제목이 두 줄로 접힐 때 위아래 줄이 서로 붙는다.
            Text(stringResource(R.string.home_title), fontSize = 36.sp, lineHeight = 48.sp, fontWeight = FontWeight.Black, color = PrimaryTextColor, modifier = Modifier.padding(bottom = 12.dp))
            Text(
                stringResource(R.string.home_subtitle),
                fontSize = 16.sp, color = SecondaryTextColor, lineHeight = 26.sp, modifier = Modifier.padding(bottom = 24.dp)
            )

            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(if (isRunning) AccentColor else SecondaryTextColor))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(
                            when {
                                !isRunning -> R.string.home_status_idle
                                // 마이크로 듣는 중이면 상태에 적는다. 방 소리를 들어 확인할 수 없는 사람에게 필요하다(#226).
                                listeningAround -> R.string.home_status_running_external
                                else -> R.string.home_status_running
                            }
                        ),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SecondaryTextColor
                    )
                }
                // 돌고 있는데도 화면에 아무 일이 없어 보이는 두 경우를 상태 바로 아래에 알린다.
                // - 재생 중인데 아무것도 받지 못함: 소리 공유를 막은 앱이거나 그 앱이 음소거된 경우다. 청각장애
                //   사용자는 "조용한 장면"과 구분할 수 없어 앱이 고장 난 줄 안다. 외부 사운드 모드에서는 마이크로
                //   아무것도 들어오지 않는 경우다(통화 중, 다른 앱이 마이크를 씀, 마이크 차단).
                // - AI 모델 실패: 위협음 색과 종류별 진동이 그대로인 줄 믿게 된다. 진동은 큰 소리에만 울리고,
                //   위협음의 표시나 진동을 꺼 두었으면 그것도 울리지 않는다(#232).
                // 둘 다 해당하면 받지 못한다는 쪽만 말한다. 받는 소리가 없으면 분류할 소리도 없어서 AI 안내는
                // 그 순간 의미가 없고, 막힌 앱을 벗어나면 다시 나온다. 경고를 쌓아 두면 어느 것도 읽지 않는다.
                // 글자는 상태 점 너비(12dp + 8dp)만큼 들여 상태 글자와 줄을 맞춘다.
                if (isRunning && (captureBlocked || !aiAvailable)) {
                    val loudAlerts = AiUnavailableNotice.loudAlerts(dangerShown, dangerHaptic.enabled, hasVibrator)
                    Text(
                        stringResource(
                            when {
                                !captureBlocked -> AiUnavailableNotice.home(loudAlerts)
                                listeningAround -> R.string.home_mic_silenced
                                else -> R.string.home_capture_blocked
                            }
                        ),
                        fontSize = 14.sp, color = WarningColor, lineHeight = 21.sp,
                        // 홈을 보는 중에 안내가 생길 수 있으므로 화면 읽어주기가 읽고 지나가게 한다.
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                // 사용자가 끄지 않았는데 꺼졌으면 앱을 열었을 때 알린다. 앱 알림을 꺼 두면 알림도 토스트도 뜨지 못해
                // 진동만 울리므로, 무엇이 꺼졌는지 알 수 있는 곳이 여기뿐이다. 다시 켜면 캡처 서비스가 지운다.
                // 다시 켜는 버튼은 따로 두지 않는다. 바로 아래 실행 버튼이 같은 일을 한다.
                val stop = lastUnexpectedStop
                if (!isRunning && stop != null) {
                    // 홈을 보는 중에 꺼질 수 있으므로 화면 읽어주기가 읽고 지나가게 한다.
                    // 제목과 내용을 한 덩어리로 묶어 읽고, 닫기 버튼은 따로 둔다(누를 수 있는 것은 묶이지 않는다).
                    Column(
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp)
                            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    ) {
                        Text(
                            stringResource(R.string.stopped_title),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = WarningColor, lineHeight = 21.sp
                        )
                        Text(
                            stringResource(StopAlert.textFor(stop)),
                            fontSize = 14.sp, color = WarningColor, lineHeight = 21.sp
                        )
                        TextButton(
                            onClick = { SettingsManager.setLastUnexpectedStop(null) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(stringResource(R.string.home_stopped_dismiss), color = SecondaryTextColor, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 가로 화면은 높이가 짧아(폰에서 약 285dp) 외부 사운드 모드 카드 아래의 실행 버튼이 화면 밖으로 밀린다(#264).
            // 그래서 가로일 때만 실행 버튼을 카드 위에 둔다. 세로는 고르고 나서 누르는 순서 그대로 카드가 먼저다.
            if (landscape) {
                StartStopButtons(isRunning, onStart, onStop)
                ExternalModeCard(isRunning, externalSoundMode, Modifier.padding(top = 24.dp))
            } else {
                ExternalModeCard(isRunning, externalSoundMode, Modifier.padding(bottom = 24.dp))
                StartStopButtons(isRunning, onStart, onStop)
            }

            Spacer(modifier = Modifier.weight(1f))

            // 처음 열 때 한 번 저절로 뜬 튜토리얼을 다시 보는 곳. 실행 버튼들과 겨루지 않게 회색 글자 버튼으로 둔다.
            TextButton(
                onClick = onOpenTutorial,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 24.dp, bottom = 8.dp)
                    .heightIn(min = 48.dp)
            ) {
                Text(
                    stringResource(R.string.home_tutorial),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = SecondaryTextColor
                )
            }
        }
    }
}

/**
 * 외부 사운드 모드(#226). 켜기 전에 고르고, 실행 중에는 잠근다. 소스는 켤 때 정해져 돌고 있는 실행에는
 * 적용되지 않으므로, 바꿀 수 있는 것처럼 두면 스위치와 실제로 듣는 곳이 어긋난다.
 */
@Composable
private fun ExternalModeCard(isRunning: Boolean, externalSoundMode: Boolean, modifier: Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
            ModernSwitch(
                stringResource(R.string.home_external_mode),
                stringResource(if (isRunning) R.string.home_external_mode_locked else R.string.home_external_mode_desc),
                externalSoundMode,
                enabled = !isRunning
            ) {
                SettingsManager.setExternalSoundMode(it)
            }
            // 감도는 모드를 켰을 때만 보인다. 잰 크기에만 곱하므로 도는 실행에도 바로 먹어, 실행 중에 그림을 보며 맞출 수 있다.
            DependentSettings(externalSoundMode) {
                MicSensitivitySlider()
            }
        }
    }
}

/**
 * 실행·실행 종료 버튼. 번역된 이름이 길면 버튼 안에서 가운데 정렬로 두 줄까지 들어간다(56dp 안에 두 줄).
 * 글자 크기 설정 때문에 한쪽이 더 커지면 두 버튼 높이를 같이 맞춘다.
 */
@Composable
private fun StartStopButtons(isRunning: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Button(
            onClick = onStart,
            enabled = !isRunning,
            colors = ButtonDefaults.buttonColors(containerColor = AccentColor, disabledContainerColor = Color(0xFF333A44)),
            shape = RoundedCornerShape(14.dp),
            contentPadding = HomeButtonPadding,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).fillMaxHeight()
        ) {
            Text(stringResource(R.string.home_start), fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (isRunning) SecondaryTextColor else Color.White)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Button(
            onClick = onStop,
            enabled = isRunning,
            colors = ButtonDefaults.buttonColors(containerColor = DangerColor, disabledContainerColor = Color(0xFF333A44)),
            shape = RoundedCornerShape(14.dp),
            contentPadding = HomeButtonPadding,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).fillMaxHeight()
        ) {
            Text(stringResource(R.string.home_stop), fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (!isRunning) SecondaryTextColor else Color.White)
        }
    }
}

/**
 * 외부 사운드 모드의 마이크 감도(#226). 칸마다 약 3dB 이고, 끄는 동안 바로 적용한다.
 *
 * 올리면 작은 소리도 그리지만 조용하지 않은 곳의 잡음도 그리므로, 기본은 감도를 조절하기 전과 같은 100% 다.
 * 오버레이·진동이 보는 크기에만 곱하고 AI 가 받는 소리는 그대로다([MicSensitivity]).
 */
@Composable
private fun MicSensitivitySlider() {
    val percent by SettingsManager.micSensitivity.collectAsState()
    val name = stringResource(R.string.home_mic_sensitivity)
    val value = stringResource(R.string.home_mic_sensitivity_value, percent)
    val last = MicSensitivity.STEPS.size - 1
    Column(modifier = Modifier.padding(bottom = 20.dp)) {
        // 이름과 값은 아래 슬라이더가 함께 읽어 주므로 화면 읽어주기에서는 건너뛴다(ModernSlider 와 같다).
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clearAndSetSemantics { }) {
            Text(name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentColor)
        }
        Slider(
            value = MicSensitivity.indexOf(percent).toFloat(),
            onValueChange = { SettingsManager.setMicSensitivity(MicSensitivity.STEPS[it.roundToInt().coerceIn(0, last)]) },
            valueRange = 0f..last.toFloat(),
            steps = last - 1,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = AccentColor,
                inactiveTrackColor = Color(0xFF333A44)
            ),
            // 값을 화면 글자 그대로 읽힌다. 두지 않으면 슬라이더 위치(칸 번호)를 퍼센트로 읽는다.
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = name
                stateDescription = value
            }
        )
    }
}

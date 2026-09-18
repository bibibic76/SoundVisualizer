package com.example.soundvisualizer

import android.os.Build
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 홈의 실행·실행 종료 버튼 안쪽 여백. 번역된 이름이 길어도 글자 자리가 넉넉하도록 좌우를 기본(24dp)보다 줄였다. */
private val HomeButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

@Composable
fun HomeTab(onStart: () -> Unit, onStop: () -> Unit, onAddTile: () -> Unit) {
    val isRunning by SettingsManager.isServiceRunning.collectAsState()
    val tileAdded by SettingsManager.tileAdded.collectAsState()
    val aiAvailable by SettingsManager.aiAvailable.collectAsState()
    val captureBlocked by SettingsManager.isCaptureBlocked.collectAsState()
    val lastUnexpectedStop by SettingsManager.lastUnexpectedStop.collectAsState()

    // 글자 크기나 화면 확대를 크게 쓰면 안내와 버튼이 화면보다 길어진다. Column 은 남은 높이만 나눠 주므로
    // 마지막 자식(실행·실행 종료 버튼, 타일 안내)이 눌려 사라진다. 스크롤을 열고 최소 높이를 화면 높이로 잡아
    // 짧을 때는 지금처럼 가운데 정렬로, 길면 밀어 볼 수 있게 한다.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center
        ) {
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
                        stringResource(if (isRunning) R.string.home_status_running else R.string.home_status_idle),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SecondaryTextColor
                    )
                }
                // 돌고 있는데도 화면에 아무 일이 없어 보이는 두 경우를 상태 바로 아래에 알린다.
                // - 재생 중인데 아무것도 받지 못함: 소리 공유를 막은 앱이거나 그 앱이 음소거된 경우다. 청각장애
                //   사용자는 "조용한 장면"과 구분할 수 없어 앱이 고장 난 줄 안다.
                // - AI 모델 실패: 위협음 색과 진동이 켜진 줄 믿게 된다.
                // 둘 다 해당하면 받지 못한다는 쪽만 말한다. 받는 소리가 없으면 분류할 소리도 없어서 AI 안내는
                // 그 순간 의미가 없고, 막힌 앱을 벗어나면 다시 나온다. 경고를 쌓아 두면 어느 것도 읽지 않는다.
                // 글자는 상태 점 너비(12dp + 8dp)만큼 들여 상태 글자와 줄을 맞춘다.
                if (isRunning && (captureBlocked || !aiAvailable)) {
                    Text(
                        stringResource(if (captureBlocked) R.string.home_capture_blocked else R.string.home_ai_unavailable),
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

            // 번역된 이름이 길면 버튼 안에서 가운데 정렬로 두 줄까지 들어간다(56dp 안에 두 줄).
            // 글자 크기 설정 때문에 한쪽이 더 커지면 두 버튼 높이를 같이 맞춘다.
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

            // 빠른 설정 타일은 사용자가 알림창에 직접 추가해야 보인다. 추가했으면 숨긴다.
            if (!tileAdded) {
                Spacer(modifier = Modifier.height(24.dp))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    OutlinedButton(
                        onClick = onAddTile,
                        border = BorderStroke(1.dp, AccentColor),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) {
                        Text(stringResource(R.string.home_add_tile), fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = AccentColor)
                    }
                    Text(
                        stringResource(R.string.home_add_tile_desc),
                        fontSize = 13.sp, color = SecondaryTextColor, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    // Android 12 이하는 앱에서 추가 창을 띄울 수 없어 방법만 안내한다.
                    Text(stringResource(R.string.home_add_tile_manual), fontSize = 13.sp, color = SecondaryTextColor, lineHeight = 20.sp)
                }
            }
        }
    }
}

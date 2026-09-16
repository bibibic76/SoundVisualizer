package com.example.soundvisualizer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** 개발자 모드 HUD 를 얼마나 자주 다시 읽는지. 추론이 250ms 주기라 그보다 촘촘해야 한다. */
private const val DEBUG_POLL_MS = 100L

/** 창 알파(약 0.8)가 곱해지므로 판은 완전히 불투명하게 둔다. 반투명을 쓰면 두 번 곱해져 흐려진다. */
private val PlateColor = Color(0xFF101216)

/**
 * 개발자 모드에서 오버레이 왼쪽 위에 뜨는 AI 분류 결과.
 *
 * 팀이 영상·게임을 틀어 놓고 **보면서 분류가 맞는지 채점하는** 도구다. 무엇을 왜 보여주는지는
 * [AiDebugText] 와 docs/ARCHITECTURE.md 4장에 적어 두었다.
 *
 * **상태 읽기를 이 함수 안에서만 한다.** 바깥(오버레이 본문이나 감싸는 Box 의 content)에서 읽으면
 * 오버레이 전체가 초당 4회 리컴포지션되고, 프레임마다 할당하지 않도록 맞춰 둔 Canvas 의 그리기
 * 람다가 매번 새로 만들어진다. 여기서만 읽으면 다시 그려지는 것은 이 작은 서브트리뿐이다.
 *
 * 글자를 넣어도 터치 통과는 깨지지 않는다. 그 성질은 창 플래그(`FLAG_NOT_TOUCHABLE`)에 걸려 있다.
 * 같은 이유로 이 HUD 를 별도 창으로 띄우면 안 된다. 불투명한 두 번째 오버레이 창은 그 영역의
 * "신뢰할 수 없는 터치 차단" 을 다시 부른다.
 */
@Composable
fun AiDebugOverlay() {
    val enabled by SettingsManager.developerMode.collectAsState()
    if (!enabled) return

    val aiAvailable by SettingsManager.aiAvailable.collectAsState()

    // 추론 결과는 250ms 주기로만 바뀐다. produceState 의 상태는 구조적 동등성을 쓰고
    // AiClassificationResult 는 data class 이므로, 같은 결과가 계속 돌아오는 동안 대입이 no-op 이라
    // 리컴포지션이 일어나지 않는다. 대기 중에 저절로 조용해지는 셈이다.
    val lines by produceState(
        initialValue = AiDebugText.format(null, 0L, 0f, shown = false, aiAvailable = true),
        aiAvailable
    ) {
        val capturePaused = SettingsManager.isCapturePaused
        while (true) {
            // 화면이 꺼져 캡처를 쉬는 동안에는 폴링도 멈춘다. 주머니 속에서 100ms 타이머로
            // CPU 를 깨울 이유가 없다 (렌더 루프가 멈추는 것과 같은 이유).
            if (capturePaused.value) capturePaused.first { !it }

            val result = AiClassification.latest()
            value = AiDebugText.format(
                result = result,
                nowMs = System.currentTimeMillis(),
                level = AudioEngine.currentLevel(),
                // 그 종류 표시를 꺼 뒀으면 오버레이가 아무것도 그리지 않는다. 이걸 같이 보여주지 않으면
                // 설정 상태를 "AI 가 못 잡았다" 로 오독한다.
                shown = result?.let { LiveVisualizerInputs.isShown(it.coarse) } ?: false,
                aiAvailable = aiAvailable
            )
            delay(DEBUG_POLL_MS)
        }
    }

    Column(
        modifier = Modifier
            .statusBarsPadding()
            .padding(start = 12.dp, top = 12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(PlateColor)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            // 개발용 표시라 화면 읽어주기로는 읽지 않는다. 정작 이 앱 사용자에게는 소음이다.
            .clearAndSetSemantics { }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DebugText(lines.coarse, weight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(6.dp))
            // 그 종류에 사용자가 설정한 실제 색. 설정 화면을 열지 않고 어떤 색을 몰았는지 본다.
            lines.colorLabel?.let { label ->
                Spacer(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(LiveVisualizerInputs.colorFor(label)))
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            DebugText(lines.label)
            Spacer(modifier = Modifier.width(8.dp))
            DebugText(lines.confidence, weight = FontWeight.Bold)
        }
        DebugText(lines.detail)
        DebugText(lines.timing)
    }
}

/**
 * HUD 한 줄. 고정폭 글꼴을 써서 값이 4Hz 로 바뀌어도 자리가 흔들리지 않게 한다.
 * 밝은 게임 화면 위에서도 읽히도록 판은 불투명하게, 글자는 밝게 둔다.
 */
@Composable
private fun DebugText(text: String, weight: FontWeight = FontWeight.Normal) {
    Text(
        text,
        color = Color(0xFFE8EAED),
        fontSize = 11.sp,
        fontWeight = weight,
        fontFamily = FontFamily.Monospace
    )
}

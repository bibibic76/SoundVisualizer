package com.example.soundvisualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.soundvisualizer.feedback.EasyVibration

/*
 * 쉬운 설정(#408)의 그림. 글 대신 모양으로 무엇인지 알아볼 수 있게 한다.
 *
 * 아이콘 묶음(material-icons-extended)을 들이지 않고 언어 단추의 지구본처럼 직접 그린다. 앱이 쓰는 아이콘 묶음
 * (material-icons-core)에는 경고 삼각형만 있고 말풍선·눈은 없다.
 * 그림은 옆이나 아래의 낱말이 말하는 것을 보여 주기만 한다. 화면 읽어주기의 이름은 부르는 쪽이 단다.
 */

/** 종류 그림 안의 기호 색. 밝은 종류 색(기본값 흰색·노랑·빨강) 위에서 잘 보이는 어두운 색이다. */
private val BadgeGlyphColor = Color(0xFF101216)

/**
 * 소리 종류의 그림: 위협음은 경고 삼각형, 대화음은 말풍선, 환경음은 물결이 든 동그라미. 사용자가 정한 종류 색으로 칠한다.
 * 모양이 종류마다 달라 색을 구분하기 어려워도 알아볼 수 있다. 어두운 색을 정해도 보이도록 설정 탭의 색 동그라미처럼
 * 테두리를 두른다.
 */
@Composable
fun SoundTypeBadge(label: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(36.dp)) {
        val border = Stroke(width = 1.dp.toPx())
        val edge = SecondaryTextColor.copy(alpha = 0.5f)
        when (label) {
            AiClassification.DANGER -> {
                val s = size.minDimension
                val round = s * 0.12f
                // 꼭짓점을 둥글게: 선 굵기의 절반만큼 줄인 삼각형을 채우고, 같은 색의 둥근 이음 선으로 감싼다.
                val triangle = Path().apply {
                    moveTo(s / 2f, round)
                    lineTo(s - round * 0.8f, s - round * 0.9f)
                    lineTo(round * 0.8f, s - round * 0.9f)
                    close()
                }
                // 테두리는 한 겹 굵은 선을 먼저 깔아 둥근 삼각형 바깥에 가는 띠로 남긴다.
                drawPath(triangle, edge, style = Stroke(width = round * 2f + border.width * 2f, join = StrokeJoin.Round))
                drawPath(triangle, color, style = Fill)
                drawPath(triangle, color, style = Stroke(width = round * 2f, join = StrokeJoin.Round))
                // 느낌표
                val bar = s * 0.11f
                drawLine(BadgeGlyphColor, Offset(s / 2f, s * 0.38f), Offset(s / 2f, s * 0.64f), bar, StrokeCap.Round)
                drawCircle(BadgeGlyphColor, bar * 0.62f, Offset(s / 2f, s * 0.8f))
            }
            AiClassification.SPEECH -> {
                val s = size.minDimension
                val body = Path().apply {
                    addRoundRect(RoundRect(s * 0.04f, s * 0.1f, s * 0.96f, s * 0.72f, CornerRadius(s * 0.2f)))
                }
                val tail = Path().apply {
                    moveTo(s * 0.22f, s * 0.66f)
                    lineTo(s * 0.46f, s * 0.66f)
                    lineTo(s * 0.16f, s * 0.94f)
                    close()
                }
                // 몸통과 꼬리를 한 모양으로 합친다. 한 경로에 따로 넣으면 감는 방향이 달라 겹친 곳이 비고, 테두리도 말풍선 안에
                // 선으로 남는다.
                val bubble = Path.combine(PathOperation.Union, body, tail)
                drawPath(bubble, color, style = Fill)
                drawPath(bubble, edge, style = border)
                // 말줄임 점 셋
                for (x in floatArrayOf(0.3f, 0.5f, 0.7f)) {
                    drawCircle(BadgeGlyphColor, s * 0.07f, Offset(s * x, s * 0.41f))
                }
            }
            else -> {
                val s = size.minDimension
                val center = Offset(s / 2f, s / 2f)
                drawCircle(color, s * 0.46f, center)
                drawCircle(edge, s * 0.46f, center, style = border)
                // 물결 한 줄
                val wave = Path().apply {
                    val left = s * 0.2f
                    val right = s * 0.8f
                    val amp = s * 0.11f
                    val step = (right - left) / 4f
                    moveTo(left, center.y)
                    for (i in 0 until 4) {
                        val x0 = left + step * i
                        val dir = if (i % 2 == 0) -1f else 1f
                        quadraticTo(x0 + step / 2f, center.y + dir * amp * 2f, x0 + step, center.y)
                    }
                }
                drawPath(wave, BadgeGlyphColor, style = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** 화면 표시의 그림: 뜬 눈. 꺼져 있으면 눈 위에 빗금을 긋는다. */
@Composable
fun ShowIcon(shown: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val s = size.minDimension
        val line = Stroke(width = s * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val eye = Path().apply {
            moveTo(s * 0.06f, s * 0.5f)
            quadraticTo(s * 0.5f, s * 0.04f, s * 0.94f, s * 0.5f)
            quadraticTo(s * 0.5f, s * 0.96f, s * 0.06f, s * 0.5f)
            close()
        }
        drawPath(eye, color, style = line)
        drawCircle(color, s * 0.13f, Offset(s / 2f, s / 2f))
        if (!shown) {
            drawLine(color, Offset(s * 0.14f, s * 0.86f), Offset(s * 0.86f, s * 0.14f), s * 0.08f, StrokeCap.Round)
        }
    }
}

/**
 * 크기 단계의 그림: 가로로 눕힌 화면에 [mode] 의 모양을 그 단계의 크기로 줄여 그린다. 크기는 모드마다 따로라, 선택한 모드의
 * 모양으로 보여 준다.
 *
 * - 파도: 화면 테두리를 따라 채운 띠. 단계가 클수록 두껍다.
 * - 외곽선: 테두리 안쪽의 선. 단계가 클수록 안쪽으로 들어온다.
 * - 패드: 네 변 가운데의 막대. 단계가 클수록 두껍다.
 * - 원형: 가운데의 고리. 단계가 클수록 크다.
 */
@Composable
fun SizeStepIcon(step: EasySize, mode: VisualMode, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 44.dp, height = 28.dp)) {
        val frame = Stroke(width = 1.5.dp.toPx())
        val inset = frame.width / 2f
        val corner = 5.dp.toPx()
        val outer = RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(corner))
        drawPath(Path().apply { addRoundRect(outer) }, color, style = frame)
        val innerHeight = size.height - inset * 2
        val depth = innerHeight * when (step) {
            EasySize.Small -> 0.14f
            EasySize.Medium -> 0.24f
            EasySize.Large -> 0.36f
        }
        val fill = color.copy(alpha = color.alpha * 0.75f)
        when (mode) {
            VisualMode.Wave -> {
                val band = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRoundRect(outer)
                    addRoundRect(insetRect(outer, depth, corner))
                }
                drawPath(band, fill, style = Fill)
            }
            VisualMode.Outline ->
                drawPath(Path().apply { addRoundRect(insetRect(outer, depth, corner)) }, color, style = frame)
            VisualMode.Pad -> {
                val thick = depth * 0.8f
                val horizontal = (outer.right - outer.left) * 0.34f
                val vertical = innerHeight * 0.46f
                val cx = (outer.left + outer.right) / 2f
                val cy = (outer.top + outer.bottom) / 2f
                val round = CornerRadius(thick / 2f)
                drawRoundRect(fill, Offset(cx - horizontal / 2f, outer.top), Size(horizontal, thick), round)
                drawRoundRect(fill, Offset(cx - horizontal / 2f, outer.bottom - thick), Size(horizontal, thick), round)
                drawRoundRect(fill, Offset(outer.left, cy - vertical / 2f), Size(thick, vertical), round)
                drawRoundRect(fill, Offset(outer.right - thick, cy - vertical / 2f), Size(thick, vertical), round)
            }
            VisualMode.CircleRipple -> {
                val radius = innerHeight * when (step) {
                    EasySize.Small -> 0.16f
                    EasySize.Medium -> 0.26f
                    EasySize.Large -> 0.38f
                }
                drawCircle(color, radius, Offset(size.width / 2f, size.height / 2f), style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

/** [outer] 를 [depth] 만큼 안으로 줄인 둥근 사각형. 모서리도 그만큼 줄이되 1dp 아래로는 줄이지 않는다. */
private fun DrawScope.insetRect(outer: RoundRect, depth: Float, corner: Float): RoundRect = RoundRect(
    outer.left + depth, outer.top + depth, outer.right - depth, outer.bottom - depth,
    CornerRadius((corner - depth).coerceAtLeast(1.dp.toPx()))
)

/** '자세한 설정' 칸의 그림: 손잡이가 달린 가로 막대 셋. 자세한 설정의 슬라이더를 뜻한다. */
@Composable
fun DetailedIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val s = size.minDimension
        val line = s * 0.08f
        val knob = s * 0.12f
        val rows = floatArrayOf(0.24f, 0.5f, 0.76f)
        val knobs = floatArrayOf(0.66f, 0.32f, 0.54f)
        for (i in rows.indices) {
            val y = s * rows[i]
            drawLine(color, Offset(s * 0.08f, y), Offset(s * 0.92f, y), line, StrokeCap.Round)
            drawCircle(color, knob, Offset(s * knobs[i], y))
        }
    }
}

/**
 * 진동 단계의 그림: 세워 든 폰과 양옆의 진동 표시. 끔은 빗금, 약하게는 한 줄, 세게는 두 줄이다.
 * 튜토리얼 진동 쪽의 진동 표시와 같은 모양이다.
 */
@Composable
fun VibrationStepIcon(step: EasyVibration, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 44.dp, height = 28.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        val phoneW = h * 0.52f
        val phone = RoundRect(
            (w - phoneW) / 2f, stroke, (w + phoneW) / 2f, h - stroke,
            CornerRadius(3.dp.toPx())
        )
        drawPath(Path().apply { addRoundRect(phone) }, color, style = Stroke(width = stroke))
        val cy = h / 2f
        val gap = 3.5.dp.toPx()
        fun marks(near: Float, far: Float?) {
            for (side in floatArrayOf(-1f, 1f)) {
                val edge = if (side < 0) phone.left else phone.right
                drawLine(color, Offset(edge + side * gap, cy - h * near), Offset(edge + side * gap, cy + h * near), stroke, StrokeCap.Round)
                if (far != null) {
                    val x = edge + side * gap * 2.1f
                    drawLine(color, Offset(x, cy - h * far), Offset(x, cy + h * far), stroke, StrokeCap.Round)
                }
            }
        }
        when (step) {
            EasyVibration.Off ->
                drawLine(color, Offset(phone.left - gap, phone.bottom), Offset(phone.right + gap, phone.top), stroke, StrokeCap.Round)
            EasyVibration.Low -> marks(near = 0.18f, far = null)
            EasyVibration.High -> marks(near = 0.24f, far = 0.14f)
        }
    }
}

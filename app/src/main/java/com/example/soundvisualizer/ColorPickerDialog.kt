package com.example.soundvisualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import java.util.Locale

/**
 * 색 공간 전체에서 고르는 선택기.
 *
 * 채도(가로) x 명도(세로) 사각형 + 색상(Hue) 슬라이더. 자주 쓰는 색은 아래 프리셋으로 집는다.
 * 시각화는 항상 불투명하게 그리므로 알파는 다루지 않는다.
 */
@Composable
fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val startHsv = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    // 고르던 색은 화면이 다시 만들어져도 남아야 한다. 창만 살아남고 색이 처음으로 돌아가면 더 헷갈린다(#183).
    var hue by rememberSaveable(initial) { mutableFloatStateOf(startHsv[0]) }
    var sat by rememberSaveable(initial) { mutableFloatStateOf(startHsv[1]) }
    var bright by rememberSaveable(initial) { mutableFloatStateOf(startHsv[2]) }

    // 가로 화면에서는 창 높이가 모자라 색상 막대·프리셋·색 코드가 잘린다. 색 사각형을 줄여 다 들어가게 한다(#183).
    // 사각형은 손짓을 직접 받으므로, 밀어서 보게 하는 것만으로는 부족하다.
    // 높이는 Configuration.screenHeightDp 가 아니라 실제 창 크기로 잰다. 앞쪽은 targetSdk 에 따라 인셋을 넣는
    // 방식이 달라지고 dp 로 반올림돼, 쓸 수 있는 창 높이와 어긋날 수 있다(#191). 창 크기가 아직 0 으로 오는
    // 첫 컴포지션에는 줄이지 않는다. 줄여 놓고 다시 늘리면 사각형이 한 번 튄다.
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val shortWindow = windowHeight > 0.dp && windowHeight < 500.dp
    val squareHeight = if (shortWindow) 120.dp else 180.dp

    val picked = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bright))
    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))

    // 화면 읽어주기용 설명. 값은 백분율로 읽어 준다 — 0~1 소수는 귀로 듣기 어렵다.
    val squareDescription = stringResource(
        R.string.color_picker_square_desc,
        (sat * 100).roundToInt(),
        (bright * 100).roundToInt()
    )
    val hueDescription = stringResource(R.string.color_picker_hue_desc, hue.roundToInt())

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor,
        title = { Text(stringResource(R.string.color_picker_title), color = PrimaryTextColor, fontWeight = FontWeight.Bold) },
        text = {
            // 가로 화면에서는 창 높이가 모자라 색상 막대·프리셋·색 코드가 잘린다. 밀어서 볼 수 있게 한다(#183).
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(squareHeight)
                        .clip(RoundedCornerShape(12.dp))
                        // 누름과 끌기를 한 핸들러에서 처리한다. detectTapGestures 와
                        // detectDragGestures 를 각각 pointerInput 으로 걸면 down 이벤트를
                        // 앞쪽이 소비해서 끌기가 씹힌다.
                        //
                        // 이벤트를 소비하는 것이 중요하다. 소비하지 않으면 터치 기울기를 넘긴 순간
                        // 부모의 verticalScroll 이 손짓을 가져가, 위아래로 끌 때 밝기가 아니라 창이
                        // 밀린다(#202). 밝기는 위아래 끌기로만 고를 수 있어서 그러면 어두운 색을
                        // 만들 방법이 없어진다.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                sat = (down.position.x / size.width).coerceIn(0f, 1f)
                                bright = 1f - (down.position.y / size.height).coerceIn(0f, 1f)
                                drag(down.id) { change ->
                                    change.consume()
                                    sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                    bright = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                }
                            }
                        }
                        // 화면 읽어주기로는 끌기를 흉내낼 수 없다. 최소한 무엇이고 지금 값이 얼마인지는
                        // 읽히게 둔다. 색 자체를 고르는 길은 아래의 미리 담긴 색 8개다(#202).
                        .semantics {
                            contentDescription = squareDescription
                        }
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    // 밝은 쪽에서도 안 묻히도록 흰 링 바깥에 검은 링을 겹친다.
                    val c = Offset(sat * size.width, (1f - bright) * size.height)
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
                    drawCircle(Color.Black, radius = 11.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
                }

                Spacer(Modifier.height(16.dp))

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        // 사각형과 같은 이유로 소비한다. 좌우 끌기라도 대각선으로 움직이면
                        // 부모 스크롤이 가져간다(#202).
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                hue = (down.position.x / size.width).coerceIn(0f, 1f) * 360f
                                drag(down.id) { change ->
                                    change.consume()
                                    hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                                }
                            }
                        }
                        .semantics {
                            contentDescription = hueDescription
                        }
                ) {
                    val stops = (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }
                    drawRect(Brush.horizontalGradient(stops))
                    // 손잡이 중심을 반지름만큼 안으로 물린다. 그러지 않으면 양 끝에서 반이 잘린다.
                    val knobRadius = size.height / 2f - 3.dp.toPx()
                    val knobX = ((hue / 360f) * size.width)
                        .coerceIn(knobRadius, maxOf(knobRadius, size.width - knobRadius))
                    drawCircle(
                        Color.White,
                        radius = knobRadius,
                        center = Offset(knobX, size.height / 2f),
                        style = Stroke(3.dp.toPx())
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(picked))
                            .border(1.dp, SecondaryTextColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        String.format(Locale.US, "#%06X", picked and 0xFFFFFF),
                        color = SecondaryTextColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.color_picker_presets), color = SecondaryTextColor, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))

                // 색을 보지 못해도 무엇을 고르는지 알 수 있게 이름을 함께 둔다.
                val presets = listOf(
                    0xFFFFFF to R.string.cd_preset_white,
                    0xFF0000 to R.string.cd_preset_red,
                    0xFF7F00 to R.string.cd_preset_orange,
                    0xFFFF00 to R.string.cd_preset_yellow,
                    0x00FF00 to R.string.cd_preset_green,
                    0x00FFFF to R.string.cd_preset_cyan,
                    0x0080FF to R.string.cd_preset_blue,
                    0xFF00FF to R.string.cd_preset_magenta
                )
                // 동그라미는 30dp 그대로 두고 누를 수 있는 칸만 권장 크기(48dp)로 키운다.
                // 여덟 개를 한 줄에 두면 창 너비에 48dp 씩 들어가지 않으므로 네 개씩 두 줄로 나눈다.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.selectableGroup()) {
                    presets.chunked(4).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { (rgb, nameRes) ->
                                val argb = 0xFF000000.toInt() or rgb
                                val presetName = stringResource(nameRes)
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        // 고른 색이 무엇인지도 읽히도록 선택 상태와 이름을 함께 넘긴다.
                                        .selectable(
                                            selected = argb == picked,
                                            role = Role.RadioButton,
                                            onClick = {
                                                val out = FloatArray(3)
                                                android.graphics.Color.colorToHSV(argb, out)
                                                hue = out[0]
                                                sat = out[1]
                                                bright = out[2]
                                            }
                                        )
                                        .semantics { contentDescription = presetName },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(Color(argb))
                                            .border(1.dp, SecondaryTextColor.copy(alpha = 0.4f), CircleShape)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) {
                Text(stringResource(R.string.color_picker_confirm), color = AccentColor, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.color_picker_cancel), color = SecondaryTextColor) }
        }
    )
}

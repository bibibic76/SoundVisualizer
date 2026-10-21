package com.example.soundvisualizer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 쉬운 설정(#408)의 소리 종류 그림이 한 덩어리로 칠해지는지. 그림은 Canvas 라서 찍어서 색을 본다.
 */
@RunWith(AndroidJUnit4::class)
class EasyIconsInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun speechBubbleTailJoinsTheBodyInOnePiece() {
        rule.setContent {
            Box(Modifier.background(Color.Black)) {
                SoundTypeBadge(AiClassification.SPEECH, Color.Yellow, Modifier.testTag("badge"))
            }
        }
        val pixels = rule.onNodeWithTag("badge").captureToImage().toPixelMap()
        fun at(x: Float, y: Float) = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()].toArgb()

        // 꼬리가 몸통과 겹치는 곳. 몸통과 꼬리를 따로 넣으면 감는 방향이 서로 달라 이곳이 비고 바탕이 보였다.
        assertEquals("꼬리와 몸통이 겹치는 곳이 비었다", Color.Yellow.toArgb(), at(0.3f, 0.69f))
        // 꼬리의 윗변. 테두리를 모양마다 그리면 말풍선 안에 선으로 남았다.
        assertEquals("말풍선 안에 꼬리의 테두리가 남았다", Color.Yellow.toArgb(), at(0.34f, 0.66f))
    }
}

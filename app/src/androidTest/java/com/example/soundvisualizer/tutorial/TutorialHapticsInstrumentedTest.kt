package com.example.soundvisualizer.tutorial

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 튜토리얼 진동 쪽의 진짜 진동(#323)이 그림과 함께 움직이고 멈추는지. 가짜 진동기를 넘기므로 폰은 울리지 않는다.
 *
 * 그림의 애니메이션은 끝없이 돌아 테스트가 멈춰 두므로, 시계를 손으로 넘겨 프레임을 만든다.
 */
@RunWith(AndroidJUnit4::class)
class TutorialHapticsInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private var plays = 0
    private var cancels = 0
    private val haptics = TutorialHaptics(canPlay = { true }, play = { plays++ }, cancel = { cancels++ })

    @Test
    fun vibratesWithTheDrawingOnlyWhileItMoves() {
        var running by mutableStateOf(true)
        val time = mutableFloatStateOf(0f)
        val cycleMs = (TutorialScene.Vibration.cycleSec * 1000).toLong()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TutorialIllustration(TutorialScene.Vibration, running, time, Modifier.size(320.dp, 180.dp), haptics)
        }

        // 두 바퀴. 한 바퀴에 세 번(1.5, 2.0, 2.5초) 떤다.
        rule.mainClock.advanceTimeBy(cycleMs * 2 + 200)
        assertEquals("두 바퀴 동안 울린 횟수", 6, plays)

        // 멈춤을 누르거나 쪽을 넘기면 울리던 것을 끊고, 더 울리지 않는다.
        rule.runOnUiThread { running = false }
        rule.mainClock.advanceTimeBy(cycleMs)
        assertEquals("멈출 때 끊은 횟수", 1, cancels)
        assertEquals("멈춘 뒤에 울렸다", 6, plays)

        // 다시 움직이면 그림이 대본을 처음부터 튼다. 1.5초에 다시 운다.
        rule.runOnUiThread { running = true }
        rule.mainClock.advanceTimeBy(1_700)
        assertEquals("다시 움직인 뒤 첫 울림", 7, plays)
        rule.mainClock.autoAdvance = true
    }
}

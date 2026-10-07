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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 그림이 진짜 진동(#323)과 소리(#327)에 프레임을 넘기고, 멈출 때 멈추라고 알리는지. 언제 울리고 무엇을 내는지는 JVM
 * 테스트(TutorialHapticsTest, TutorialSoundsTest)가 보고, 여기서는 그림 루프와의 연결만 본다. 가짜를 넘기므로 폰은
 * 울리지도 소리를 내지도 않는다.
 *
 * 그림의 애니메이션은 끝없이 돌아 테스트가 멈춰 두므로 시계를 손으로 넘긴다. 프레임마다 실제 엔진이 그리므로 느린 CI
 * 에뮬레이터를 생각해 몇 프레임만 넘긴다.
 */
@RunWith(AndroidJUnit4::class)
class TutorialFrameFollowerInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val FRAME_SEC = 0.02f
    }

    private class Recorder : TutorialFrameFollower {
        val frames = mutableListOf<Float>()
        var stops = 0
        override fun onFrame(t: Float) {
            frames += t
        }

        override fun stop() {
            stops++
        }
    }

    @Test
    fun drawingFeedsFramesWhileItMovesAndSaysStopWhenItStops() {
        val haptics = Recorder()
        var running by mutableStateOf(true)
        val time = mutableFloatStateOf(0f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TutorialIllustration(TutorialScene.Vibration, running, time, Modifier.size(140.dp, 60.dp), haptics)
        }

        rule.mainClock.advanceTimeBy(200)
        assertTrue("움직이는데 프레임이 오지 않았다", haptics.frames.isNotEmpty())
        // 첫 프레임은 0초다. 테스트 시계는 0에서 시작해 첫 프레임을 한 번 건너뛸 수 있어 한 프레임은 봐준다.
        assertEquals("첫 프레임이 0초 근처가 아니다", 0f, haptics.frames.first(), FRAME_SEC)
        assertEquals("시각이 거꾸로 갔다", haptics.frames.sorted(), haptics.frames)

        // 멈춤을 누르거나 쪽을 넘기면 멈추라고 알리고, 프레임을 더 넘기지 않는다.
        rule.runOnUiThread { running = false }
        rule.mainClock.advanceTimeBy(200)
        val stopped = haptics.frames.size
        assertEquals("멈출 때 알린 횟수", 1, haptics.stops)
        rule.mainClock.advanceTimeBy(200)
        assertEquals("멈춘 뒤에 프레임이 왔다", stopped, haptics.frames.size)

        // 다시 움직이면 0초부터 다시 온다.
        rule.runOnUiThread { running = true }
        rule.mainClock.advanceTimeBy(200)
        assertTrue("다시 움직였는데 프레임이 오지 않았다", haptics.frames.size > stopped)
        assertEquals("다시 움직이면 0초부터", 0f, haptics.frames[stopped], FRAME_SEC)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun soundTurnedOnWhileMovingGetsTheNextFramesAndTheStop() {
        val sounds = Recorder()
        var current by mutableStateOf<TutorialFrameFollower?>(null)
        var running by mutableStateOf(true)
        val time = mutableFloatStateOf(0f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TutorialIllustration(TutorialScene.Types, running, time, Modifier.size(140.dp, 60.dp), haptics = null, sounds = current)
        }

        rule.mainClock.advanceTimeBy(200)
        // 움직이는 도중에 켜면 그림을 처음부터 다시 틀지 않고 다음 프레임부터 받는다.
        rule.runOnUiThread { current = sounds }
        rule.mainClock.advanceTimeBy(200)
        assertTrue("켠 뒤에 프레임이 오지 않았다", sounds.frames.isNotEmpty())
        assertTrue("도중에 켰는데 그림이 처음부터 다시 돌았다", sounds.frames.first() > 0.1f)

        rule.runOnUiThread { running = false }
        rule.mainClock.advanceTimeBy(200)
        assertEquals("멈출 때 알린 횟수", 1, sounds.stops)
        rule.mainClock.autoAdvance = true
    }
}

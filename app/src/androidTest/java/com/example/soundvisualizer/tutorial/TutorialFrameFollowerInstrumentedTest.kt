package com.example.soundvisualizer.tutorial

import android.os.SystemClock
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
 * 그림은 멈춰 둔 장면에서 이어서 움직이고, 멈췄다 다시 움직이면 멈춘 시각에서 이어 간다(#331). 처음부터 다시 틀면
 * 넘기는 동안 보이던 장면이 손을 떼는 순간 빈 화면으로 툭 바뀐다.
 *
 * 그림의 애니메이션은 끝없이 돌아 테스트가 멈춰 두므로 시계를 손으로 넘긴다. 프레임마다 실제 엔진이 그리므로 느린 CI
 * 에뮬레이터를 생각해 몇 프레임만 넘긴다. 엔진은 백그라운드에서 만들어지므로 그것을 기다릴 때만 실제 시간을 쓴다.
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

    /** 백그라운드에서 엔진이 만들어질 때까지 시계를 한 프레임씩 넘기며 기다린다. */
    private fun advanceUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "기다렸지만 오지 않았다: $what" }
            rule.mainClock.advanceTimeByFrame()
            SystemClock.sleep(5)
        }
    }

    @Test
    fun drawingContinuesFromTheStillSceneAndFromWhereItStopped() {
        val scene = TutorialScene.Vibration
        val haptics = Recorder()
        var running by mutableStateOf(false)
        // 엔진이 만들어지면 그림이 시각을 멈춰 둔 장면으로 맞춘다. 그것으로 엔진이 준비됐는지 안다.
        val time = mutableFloatStateOf(-1f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TutorialIllustration(scene, running, time, Modifier.size(140.dp, 60.dp), haptics)
        }

        // 옆 쪽으로 미리 그려 둔 동안(움직이지 않음)에는 멈춰 둔 장면이고, 프레임을 넘기지 않는다.
        advanceUntil("멈춰 둔 장면") { time.floatValue == scene.stillAtSec }
        rule.mainClock.advanceTimeBy(100)
        assertTrue("움직이지 않는데 프레임이 왔다", haptics.frames.isEmpty())

        // 그 쪽에 들어와 멈추면, 보이던 장면에서 이어서 움직인다. 0초부터 다시 틀지 않는다.
        rule.runOnUiThread { running = true }
        advanceUntil("움직이기 시작") { haptics.frames.isNotEmpty() }
        rule.mainClock.advanceTimeBy(200)
        assertEquals("멈춰 둔 장면에서 이어지지 않았다", scene.stillAtSec, haptics.frames.first(), FRAME_SEC)
        assertEquals("시각이 거꾸로 갔다", haptics.frames.sorted(), haptics.frames)

        // 멈춤을 누르거나 쪽을 넘기면 멈추라고 알리고, 그 자리에서 멈춘다.
        rule.runOnUiThread { running = false }
        rule.mainClock.advanceTimeBy(200)
        val stopped = haptics.frames.size
        val stoppedAt = haptics.frames.last()
        assertEquals("멈출 때 알린 횟수", 1, haptics.stops)
        assertEquals("멈춘 그림의 시각이 그 자리가 아니다", stoppedAt, time.floatValue, 0f)
        rule.mainClock.advanceTimeBy(200)
        assertEquals("멈춘 뒤에 프레임이 왔다", stopped, haptics.frames.size)

        // 다시 움직이면 멈춘 시각에서 이어 간다.
        rule.runOnUiThread { running = true }
        rule.mainClock.advanceTimeBy(200)
        assertTrue("다시 움직였는데 프레임이 오지 않았다", haptics.frames.size > stopped)
        assertEquals("멈춘 시각에서 이어지지 않았다", stoppedAt, haptics.frames[stopped], FRAME_SEC)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun soundTurnedOnWhileMovingGetsTheNextFramesAndTheStop() {
        val scene = TutorialScene.Types
        val sounds = Recorder()
        var current by mutableStateOf<TutorialFrameFollower?>(null)
        var running by mutableStateOf(true)
        val time = mutableFloatStateOf(-1f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TutorialIllustration(scene, running, time, Modifier.size(140.dp, 60.dp), haptics = null, sounds = current)
        }
        advanceUntil("움직이기 시작") { time.floatValue > scene.stillAtSec }

        // 움직이는 도중에 켜면 그림을 다시 틀지 않고 다음 프레임부터 받는다.
        val before = time.floatValue
        rule.runOnUiThread { current = sounds }
        rule.mainClock.advanceTimeBy(200)
        assertTrue("켠 뒤에 프레임이 오지 않았다", sounds.frames.isNotEmpty())
        assertTrue("도중에 켰는데 그림이 다시 돌았다", sounds.frames.first() >= before)

        rule.runOnUiThread { running = false }
        rule.mainClock.advanceTimeBy(200)
        assertEquals("멈출 때 알린 횟수", 1, sounds.stops)
        rule.mainClock.autoAdvance = true
    }
}

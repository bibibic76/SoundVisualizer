package com.example.soundvisualizer.tutorial

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 튜토리얼 진동 쪽의 진짜 진동(#323). 그림이 프레임마다 시각을 넘기면, 그림이 떠는 순간마다 한 번씩만 울려야 한다.
 * 프레임이 고르지 않아도, 멈췄다 다시 움직여도 그렇다.
 */
class TutorialHapticsTest {

    private var plays = 0
    private var cancels = 0
    private var allowed = true
    private val haptics = TutorialHaptics(canPlay = { allowed }, play = { plays++ }, cancel = { cancels++ })
    private val cycle = TutorialScene.Vibration.cycleSec

    /** [fromSec] 부터 [toSec] 앞까지 [fps] 로 프레임을 넘긴다. */
    private fun frames(fromSec: Float, toSec: Float, fps: Int = 60) {
        var i = 0
        while (fromSec + i / fps.toFloat() < toSec) {
            haptics.onFrame(fromSec + i / fps.toFloat())
            i++
        }
    }

    @Test
    fun `그림이 떠는 순간마다 한 번만 울린다`() {
        frames(0f, cycle * 2)
        // 한 바퀴에 세 번(1.5, 2.0, 2.5초) 떤다. 한 울림(0.2초)에 프레임이 열두 번 와도 한 번이다.
        assertEquals(6, plays)
    }

    @Test
    fun `프레임이 드문드문 와도 울림마다 한 번이다`() {
        // 화면이 버벅여 1초에 열 번만 그려도 울림(0.2초)마다 프레임이 하나 이상 걸린다.
        frames(0f, cycle * 2, fps = 10)
        assertEquals(6, plays)
    }

    @Test
    fun `울리면 안 될 때 시작한 울림은 건너뛴다`() {
        // 시각화가 실행 중이면 울리지 않는다. 그사이 시작한 울림은 도중에 풀려도 늦게 울리지 않고, 다음 울림부터 울린다.
        allowed = false
        frames(0f, 1.6f)
        allowed = true
        frames(1.6f, 1.75f)
        assertEquals(0, plays)
        frames(1.75f, 2.1f)
        assertEquals(1, plays)
    }

    @Test
    fun `멈췄다 다시 움직이면 처음부터 센다`() {
        frames(0f, 1.6f)
        assertEquals(1, plays)
        haptics.stop()
        assertEquals("멈출 때 울리던 것을 끊는다", 1, cancels)
        // 다시 움직이면 그림은 대본을 0초부터 튼다. 첫 울림도 다시 울린다.
        frames(0f, 1.6f)
        assertEquals(2, plays)
    }
}

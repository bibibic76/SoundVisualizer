package com.example.soundvisualizer.tutorial

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.soundvisualizer.ModeSettings
import com.example.soundvisualizer.VisualMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 쉬운 설정의 모양 카드 그림(#408)이 정해 둔 동안만 움직이는지. 카드는 평소에 멈춰 있고, 번호를 받으면 대본의 처음부터
 * [PREVIEW_PLAY_SEC] 동안 움직인 뒤 스스로 멈춘다(WCAG 2.2.2: 5초 넘게 저절로 움직이지 않는다). 움직일 수 없게 되면
 * (설정 탭이 보이지 않거나 다른 카드를 고름) 바로 멈춘다. 움직이는 도중에 번호가 다시 오르면(선택한 카드를 다시 누름)
 * 처음부터 다시 움직인다.
 *
 * 그림은 끝없이 도는 루프라 테스트가 멈춰 두므로 시계를 손으로 넘긴다(TutorialFrameFollowerInstrumentedTest 와 같은 방식).
 * 엔진은 백그라운드에서 만들어지므로 그것을 기다릴 때만 실제 시간을 쓴다. 진동은 넘기지 않으므로 폰은 울리지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class ModePreviewInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        /** 카드 그림은 1초에 30번쯤 그린다. 프레임 시각을 견줄 때 그만큼 어긋나도 된다. */
        const val FRAME_SEC = 0.04f
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

    /**
     * 실제 시간으로 [ms] 동안 시계를 한 프레임씩 넘긴다. 엔진이 없으면 그림이 움직일 수 없으므로, 움직이지 않는지 볼 때는
     * 엔진이 백그라운드에서 만들어질 만큼(몇 ms) 실제로 기다려야 한다. 가상 시간만 넘기면 엔진이 오기 전에 끝날 수 있다.
     */
    private fun advanceForRealTime(ms: Long) {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            rule.mainClock.advanceTimeByFrame()
            SystemClock.sleep(5)
        }
    }

    @Test
    fun playsFromTheStartForAShortWhileThenStopsByItself() {
        val frames = Recorder()
        var play by mutableIntStateOf(0)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ModePreviewPhone(VisualMode.Pad, ModeSettings(), play, canPlay = true, width = 160.dp, frames = frames)
        }

        // 번호가 없으면 멈춘 그림이다.
        advanceForRealTime(1_000)
        assertTrue("번호가 없는데 움직였다", frames.frames.isEmpty())

        // 번호를 받으면 대본의 처음부터 움직인다.
        rule.runOnUiThread { play = 1 }
        advanceUntil("움직이기 시작") { frames.frames.isNotEmpty() }
        assertEquals("대본의 처음부터 움직이지 않았다", 0f, frames.frames.first(), FRAME_SEC)

        // 정해 둔 동안 움직이고 스스로 멈춘다. 멈춘 뒤에는 프레임이 오지 않는다.
        advanceUntil("스스로 멈춤") { frames.stops > 0 }
        assertEquals("멈출 때 알린 횟수", 1, frames.stops)
        assertTrue("5초 넘게 움직였다: ${frames.frames.last()}", frames.frames.last() <= 5f)
        assertTrue("너무 일찍 멈췄다: ${frames.frames.last()}", frames.frames.last() >= PREVIEW_PLAY_SEC - FRAME_SEC)
        val count = frames.frames.size
        rule.mainClock.advanceTimeBy(500)
        assertEquals("멈춘 뒤에 프레임이 왔다", count, frames.frames.size)

        // 번호가 다시 오르면(같은 카드를 다시 누름) 처음부터 다시 움직인다.
        rule.runOnUiThread { play = 2 }
        advanceUntil("다시 움직이기") { frames.frames.size > count }
        assertEquals("다시 움직일 때 처음부터가 아니다", 0f, frames.frames[count], FRAME_SEC)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun doesNotPlayWhileItCannotAndStopsAsSoonAsItCannot() {
        val frames = Recorder()
        var canPlay by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ModePreviewPhone(VisualMode.Wave, ModeSettings(), play = 1, canPlay = canPlay, width = 160.dp, frames = frames)
        }

        // 설정 탭이 보이지 않거나 애니메이션을 꺼 두었으면 번호가 있어도 움직이지 않는다.
        advanceForRealTime(1_000)
        assertTrue("움직일 수 없는데 움직였다", frames.frames.isEmpty())

        rule.runOnUiThread { canPlay = true }
        advanceUntil("움직이기 시작") { frames.frames.isNotEmpty() }

        // 움직이는 도중에 움직일 수 없게 되면 바로 멈춘다.
        rule.runOnUiThread { canPlay = false }
        rule.mainClock.advanceTimeBy(200)
        assertEquals("멈출 때 알린 횟수", 1, frames.stops)
        val count = frames.frames.size
        rule.mainClock.advanceTimeBy(500)
        assertEquals("멈춘 뒤에 프레임이 왔다", count, frames.frames.size)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun aNewNumberWhilePlayingStartsAgainFromTheStart() {
        // 움직이는 도중에 선택한 카드를 다시 누르면(번호가 오름) 그 자리에서 이어 가지 않고 처음부터 다시 움직인다.
        val frames = Recorder()
        var play by mutableIntStateOf(1)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ModePreviewPhone(VisualMode.Wave, ModeSettings(), play, canPlay = true, width = 160.dp, frames = frames)
        }
        advanceUntil("1초쯤 움직임") { (frames.frames.lastOrNull() ?: 0f) >= 1f }

        rule.runOnUiThread { play = 2 }
        advanceUntil("움직이던 것을 멈춤") { frames.stops > 0 }
        assertEquals("멈출 때 알린 횟수", 1, frames.stops)
        val count = frames.frames.size
        advanceUntil("다시 움직이기") { frames.frames.size > count }
        assertEquals("다시 움직일 때 처음부터가 아니다", 0f, frames.frames[count], FRAME_SEC)
        rule.mainClock.autoAdvance = true
    }
}

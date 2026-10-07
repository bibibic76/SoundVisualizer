package com.example.soundvisualizer.tutorial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 튜토리얼 소리의 박자(#327). 그림이 프레임마다 시각을 넘기면 대본의 소리를 한 번씩, 제때 낸다.
 * 앱을 내렸다 돌아와 시각이 건너뛰어도 지난 소리를 몰아 내지 않는다.
 */
class TutorialSoundsTest {

    private val played = mutableListOf<Pair<Float, TutorialCue>>()
    private var cancels = 0
    private var allowed = true
    private var now = 0f
    private val scene = TutorialScene.Types
    private val sounds = TutorialSounds(
        TutorialScript.cues(scene), scene.cycleSec,
        canPlay = { allowed },
        play = { played += now to it },
        cancel = { cancels++ }
    )

    /** [fromSec] 부터 [toSec] 앞까지 [fps] 로 프레임을 넘긴다. */
    private fun frames(fromSec: Float, toSec: Float, fps: Int = 60) {
        var i = 0
        while (fromSec + i / fps.toFloat() < toSec) {
            now = fromSec + i / fps.toFloat()
            sounds.onFrame(now)
            i++
        }
    }

    @Test
    fun `바퀴마다 대본의 소리를 한 번씩 제때 낸다`() {
        frames(0f, scene.cycleSec * 2)
        val cues = TutorialScript.cues(scene)
        assertEquals(cues + cues, played.map { it.second })
        for ((at, cue) in played) {
            // 첫 바퀴인지 둘째 바퀴인지는 순서로 안다. 낸 시각은 대본 시각에서 한 프레임 안이다.
            val expected = cue.atSec + if (at >= scene.cycleSec) scene.cycleSec else 0f
            assertTrue("${cue.clip} ${cue.atSec}초를 $at 초에 냈다", at - expected in 0f..(1f / 60 + 1e-4f))
        }
    }

    @Test
    fun `첫 프레임(0초)의 소리도 낸다`() {
        sounds.onFrame(0f)
        assertEquals(TutorialClip.Birds, played.single().second.clip)
    }

    @Test
    fun `시각이 건너뛰면 지난 소리는 내지 않는다`() {
        // 앱을 내렸다 돌아오면 그림의 시각이 그만큼 건너뛴다. 그사이의 대화·경적을 한꺼번에 내지 않는다.
        frames(0f, 1f)
        now = 5.5f
        sounds.onFrame(now)
        assertEquals("늦은 대화·경적을 냈다", listOf(TutorialClip.Birds), played.map { it.second.clip })
        // 그다음부터는 다시 제때 낸다(5.9초의 경적, 다음 바퀴의 새소리).
        frames(5.5f + 1f / 60, scene.cycleSec + 0.1f)
        assertEquals(listOf(TutorialClip.Birds, TutorialClip.Honk, TutorialClip.Birds), played.map { it.second.clip })
    }

    @Test
    fun `내면 안 될 때 시작한 소리는 건너뛰고 멈췄다 다시 움직이면 처음부터 센다`() {
        allowed = false
        frames(0f, 1f)
        assertTrue(played.isEmpty())
        allowed = true
        sounds.stop()
        assertEquals("멈출 때 나던 소리를 멈춘다", 1, cancels)
        frames(0f, 0.1f)
        assertEquals(TutorialClip.Birds, played.single().second.clip)
    }
}

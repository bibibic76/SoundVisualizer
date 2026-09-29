package com.example.soundvisualizer.tutorial

import com.example.soundvisualizer.AiClassification
import com.example.soundvisualizer.VisualizerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 튜토리얼 그림의 소리 대본.
 *
 * 그림은 앱의 오버레이 엔진이 그대로 그리므로, 대본이 말하려는 것(왼쪽 소리는 왼쪽이 깊다, 위협음은 위협음 색이다)이
 * 실제 엔진을 거쳐서도 그렇게 그려지는지 본다. 쪽의 글과 그림이 어긋나면 처음 보는 사람을 오히려 헷갈리게 한다.
 * 엔진의 기본값이나 계산이 바뀌어 그림이 밋밋해져도 여기서 걸린다.
 */
class TutorialScriptTest {

    @Test
    fun `피크는 늘 0에서 1 사이다`() {
        val out = FloatArray(3)
        for (scene in TutorialScene.entries) {
            var t = -scene.cycleSec
            while (t < scene.cycleSec * 2) {
                TutorialScript.peaks(scene, t, out)
                assertTrue("$scene t=$t 왼쪽 ${out[0]}", out[0] in 0f..1f)
                assertTrue("$scene t=$t 오른쪽 ${out[1]}", out[1] in 0f..1f)
                t += 0.01f
            }
        }
    }

    @Test
    fun `방향 쪽은 왼쪽 소리 다음에 오른쪽 소리가 난다`() {
        val out = FloatArray(3)
        val scene = TutorialScene.Direction
        TutorialScript.peaks(scene, 1.0f, out)
        assertTrue("왼쪽 구간인데 오른쪽이 더 크다", out[0] > out[1] * 3)
        assertEquals(-1, TutorialScript.sourceSide(scene, 1.0f))

        TutorialScript.peaks(scene, 3.0f, out)
        assertTrue("오른쪽 구간인데 왼쪽이 더 크다", out[1] > out[0] * 3)
        assertEquals(1, TutorialScript.sourceSide(scene, 3.0f))

        TutorialScript.peaks(scene, 2.0f, out)
        assertEquals("쉬는 구간", 0f, out[0] + out[1], 0f)
        assertEquals(0, TutorialScript.sourceSide(scene, 2.0f))
        assertEquals(-1f, TutorialScript.sourceRipple(scene, 2.0f), 0f)
    }

    @Test
    fun `종류 쪽은 환경음 대화음 위협음 순서로 바뀐다`() {
        val scene = TutorialScene.Types
        assertEquals(AiClassification.AMBIENT, TutorialScript.label(scene, 1f))
        assertEquals(AiClassification.SPEECH, TutorialScript.label(scene, 3f))
        assertEquals(AiClassification.DANGER, TutorialScript.label(scene, 5f))
        assertEquals("한 바퀴 돌면 처음부터", AiClassification.AMBIENT, TutorialScript.label(scene, scene.cycleSec + 1f))
    }

    @Test
    fun `진동 쪽은 위협음이 날 때 한 번만 짧게 두 번 떤다`() {
        val scene = TutorialScene.Vibration
        var t = 0f
        var buzzes = 0
        var wasBuzzing = false
        while (t < scene.cycleSec) {
            val buzzing = TutorialScript.vibrating(scene, t)
            if (buzzing) {
                assertEquals("t=$t 위협음이 아닌데 떤다", AiClassification.DANGER, TutorialScript.label(scene, t))
                if (!wasBuzzing) buzzes++
            }
            wasBuzzing = buzzing
            t += 0.005f
        }
        // 기본 위협음 진동은 강하게 두 번이고, 같은 종류는 2초 안에 다시 울리지 않는다(HapticPolicy).
        assertEquals("한 바퀴에 떠는 횟수", 2, buzzes)
        for (other in TutorialScene.entries - scene) {
            assertFalse("$other 쪽이 떤다", TutorialScript.vibrating(other, 1.55f))
        }
    }

    @Test
    fun `엔진을 거치면 왼쪽 소리는 왼쪽이 오른쪽 소리는 오른쪽이 깊다`() {
        val run = DemoRun(TutorialScene.Direction)
        run.runTo(1.5f)
        val left = run.engine.debugState()
        assertTrue("왼쪽 구간에 그리지 않는다", left.visible)
        assertTrue("왼쪽 소리인데 왼쪽이 깊지 않다: ${left.depths}", left.depths[SL] > left.depths[SR] * 3)

        run.runTo(3.7f)
        val right = run.engine.debugState()
        assertTrue("오른쪽 구간에 그리지 않는다", right.visible)
        assertTrue("오른쪽 소리인데 오른쪽이 깊지 않다: ${right.depths}", right.depths[SR] > right.depths[SL] * 3)
    }

    @Test
    fun `엔진을 거치면 위협음은 위협음 색으로 그린다`() {
        val run = DemoRun(TutorialScene.Types)
        run.runTo(1.5f)
        assertEquals(AMBIENT_COLOR and 0xFFFFFF, run.engine.debugState().colorRgb)
        run.runTo(3.5f)
        assertEquals(SPEECH_COLOR and 0xFFFFFF, run.engine.debugState().colorRgb)
        run.runTo(5.0f)
        val danger = run.engine.debugState()
        assertTrue(danger.visible)
        assertEquals(DANGER_COLOR and 0xFFFFFF, danger.colorRgb)
    }

    @Test
    fun `첫 쪽의 쿵은 눈에 띄게 커졌다 작아진다`() {
        // 짧게 치고 마는 소리는 엔진이 부드럽게 따라가는 사이에 묻혀, 작은 폰 그림에서는 거의 움직이지 않는다.
        val run = DemoRun(TutorialScene.Sound)
        var smallest = Float.MAX_VALUE
        var largest = 0f
        var frame = 0
        run.runTo(TutorialScene.Sound.cycleSec) // 한 바퀴는 흘려보내고 두 번째 바퀴를 잰다
        while (frame < 240) {
            run.runTo(TutorialScene.Sound.cycleSec + frame / 60f)
            val deepest = run.engine.debugState().depths.max()
            smallest = minOf(smallest, deepest)
            largest = maxOf(largest, deepest)
            frame++
        }
        val base = run.engine.debugState().baseDepth
        assertTrue("쿵이 너무 작다: ${largest / base}", largest > base * 0.5f)
        assertTrue("쿵 사이에 충분히 가라앉지 않는다: $smallest → $largest", largest > smallest * 3)
    }

    @Test
    fun `멈춰 둔 장면에는 그 쪽이 말하는 것이 크게 그려져 있다`() {
        // 멈춘 한 장면이 비거나 작으면, 애니메이션을 꺼 둔 사람에게 그 쪽의 그림은 빈 폰 화면이 된다.
        for (scene in TutorialScene.entries) {
            val run = DemoRun(scene)
            run.runTo(scene.stillAtSec)
            val state = run.engine.debugState()
            assertTrue("$scene 의 멈춘 장면에 아무것도 없다", state.visible)
            assertTrue(
                "$scene 의 멈춘 장면이 너무 작다: ${state.depths.max() / state.baseDepth}",
                state.depths.max() > state.baseDepth * 0.5f
            )
        }
        val direction = DemoRun(TutorialScene.Direction).apply { runTo(TutorialScene.Direction.stillAtSec) }
        val depths = direction.engine.debugState().depths
        assertTrue("방향 쪽의 멈춘 장면에서 왼쪽이 깊지 않다: $depths", depths[SL] > depths[SR] * 3)
        assertEquals(
            "종류 쪽의 멈춘 장면은 위협음이다(범례도 위협음을 밝힌다)",
            AiClassification.DANGER, TutorialScript.label(TutorialScene.Types, TutorialScene.Types.stillAtSec)
        )
        assertEquals(
            "진동 쪽의 멈춘 장면은 위협음이다",
            AiClassification.DANGER, TutorialScript.label(TutorialScene.Vibration, TutorialScene.Vibration.stillAtSec)
        )
    }

    /** 튜토리얼 그림과 같은 크기의 작은 화면에 대본을 60fps 로 흘린다. */
    private class DemoRun(scene: TutorialScene) {
        val inputs = TutorialDemoInputs(scene) { label ->
            when (label) {
                AiClassification.DANGER -> DANGER_COLOR
                AiClassification.SPEECH -> SPEECH_COLOR
                else -> AMBIENT_COLOR
            }
        }

        // 세로 화면 폰(density 2.625)에서 폰 그림의 화면이 288×128dp 일 때. 그림처럼 짧은 변 411dp 폰을 줄인 것으로 본다.
        val engine = VisualizerEngine(128f * DENSITY / 411f, inputs).also {
            it.setSurfaceSize(288f * DENSITY, 128f * DENSITY)
        }
        private var frame = 0
        private var nanos = FRAME_NS

        fun runTo(seconds: Float) {
            while (frame / 60f <= seconds) {
                inputs.timeSec = frame / 60f
                if (engine.isIdle) engine.pollWake() else engine.tick(nanos)
                nanos += FRAME_NS
                frame++
            }
        }
    }

    private companion object {
        const val DENSITY = 2.625f
        const val FRAME_NS = 16_666_667L
        const val SL = 6
        const val SR = 2
        const val AMBIENT_COLOR = 0xFFFFFFFF.toInt()
        const val SPEECH_COLOR = 0xFFFFFF00.toInt()
        const val DANGER_COLOR = 0xFFFF0000.toInt()
    }
}

package com.example.soundvisualizer

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "그래픽 덜 자주 그리기" 설정이 엔진의 프레임 캡까지 이어지는지.
 *
 * 설정값과 엔진이 쓰는 수를 잇는 곳은 [LiveVisualizerInputs.framesPerSecond] 한 곳뿐이다.
 * 그 배선이 끊기면 설정을 켜도 아무 일도 일어나지 않는데, 화면에는 스위치가 켜진 것으로 보인다.
 */
class ReducedFrameRateTest {

    @After
    fun tearDown() {
        // SettingsManager 는 object 라 상태가 테스트 사이에 남는다 (LiveVisualizerInputsTest 참고).
        SettingsManager.load(MemoryPrefs())
    }

    private fun loadPrefs(block: SharedPreferences.Editor.() -> Unit) {
        val prefs = MemoryPrefs()
        prefs.edit().apply(block).apply()
        SettingsManager.load(prefs)
    }

    @Test
    fun `저장된 적이 없으면 꺼져 있다`() {
        // 소리를 눈으로 보는 앱이라 부드러운 쪽이 기본이어야 한다.
        SettingsManager.load(MemoryPrefs())

        assertFalse(SettingsManager.reducedFrameRate.value)
        assertEquals(VisualizerEngine.FULL_FPS, LiveVisualizerInputs.framesPerSecond())
    }

    @Test
    fun `켜면 엔진이 줄인 프레임 수를 받는다`() {
        loadPrefs { putBoolean("reduced_frame_rate", true) }

        assertTrue(SettingsManager.reducedFrameRate.value)
        assertEquals(VisualizerEngine.REDUCED_FPS, LiveVisualizerInputs.framesPerSecond())
    }

    @Test
    fun `실행 중에 바꾸면 다음 프레임부터 적용된다`() {
        SettingsManager.load(MemoryPrefs())
        assertEquals(VisualizerEngine.FULL_FPS, LiveVisualizerInputs.framesPerSecond())

        SettingsManager.setReducedFrameRate(true)
        assertEquals(VisualizerEngine.REDUCED_FPS, LiveVisualizerInputs.framesPerSecond())

        SettingsManager.setReducedFrameRate(false)
        assertEquals(VisualizerEngine.FULL_FPS, LiveVisualizerInputs.framesPerSecond())
    }

    @Test
    fun `껐다 켜도 저장돼 다음 실행에 남는다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setReducedFrameRate(true)

        // 같은 저장소로 다시 읽는다. 앱을 다시 켠 것과 같다.
        SettingsManager.load(prefs)

        assertTrue(SettingsManager.reducedFrameRate.value)
    }

    @Test
    fun `줄인 프레임 수가 기본보다 적고 0 이 아니다`() {
        // 0 은 "화면 주사율 그대로" 라는 뜻이라, 아끼려다 오히려 더 그리게 된다.
        assertTrue(VisualizerEngine.REDUCED_FPS in 1 until VisualizerEngine.FULL_FPS)
    }

    @Test
    fun `오버레이 창은 그리는 만큼만 화면 주사율을 요청한다`() {
        // 요청하지 않으면 S25+ 는 홈 화면 위에서도 120Hz 로 돌았다(#428). 그리는 수보다 높게 요청하면 그만큼 헛돈다.
        assertEquals(VisualizerEngine.FULL_FPS.toFloat(), VisualizerEngine.overlayRefreshRate(reduced = false))
        assertEquals(VisualizerEngine.REDUCED_FPS.toFloat(), VisualizerEngine.overlayRefreshRate(reduced = true))
        // 오버레이 창은 처음 값과 실행 중에 바뀐 값을 모두 이 수로 요청한다.
        val service = java.io.File("src/main/java/com/example/soundvisualizer/OverlayService.kt").readText(Charsets.UTF_8)
        assertTrue(service.contains("params.preferredRefreshRate = VisualizerEngine.overlayRefreshRate(SettingsManager.reducedFrameRate.value)"))
        assertTrue(service.contains("SettingsManager.reducedFrameRate.collect { reduced -> requestRefreshRate(VisualizerEngine.overlayRefreshRate(reduced)) }"))
    }
}

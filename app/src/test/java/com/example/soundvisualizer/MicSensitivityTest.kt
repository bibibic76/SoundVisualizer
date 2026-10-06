package com.example.soundvisualizer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 외부 사운드 모드의 마이크 감도(#226). 저장·복원과, 감도가 화면·진동이 보는 크기에만 걸리고 AI 가 받는 소리는
 * 바꾸지 않는다는 약속.
 */
class MicSensitivityTest {

    @After
    fun restoreSingleton() {
        SettingsManager.load(MemoryPrefs())
    }

    @Test
    fun `기본값은 감도를 조절하기 전과 같다`() {
        assertEquals(100, MicSensitivity.DEFAULT)
        assertEquals(1f, MicSensitivity.gain(MicSensitivity.DEFAULT), 0f)
        assertTrue(MicSensitivity.DEFAULT in MicSensitivity.STEPS)
        assertEquals("저장값이 없으면 기본값", MicSensitivity.DEFAULT, SettingsManager.loadMicSensitivity(MemoryPrefs()))
    }

    @Test
    fun `칸은 올라가고 한 칸마다 약 2_5dB 로 고르다`() {
        val steps = MicSensitivity.STEPS
        for (i in 1 until steps.size) {
            val ratio = steps[i].toFloat() / steps[i - 1]
            assertTrue("${steps[i - 1]} → ${steps[i]}", ratio in 1.32f..1.35f)
        }
        assertTrue("내릴 칸도 있어야 시끄러운 곳에서 잡음을 덜 그린다", steps.first() < MicSensitivity.DEFAULT)
    }

    @Test
    fun `열여섯 칸이고 맨 오른쪽이 32배다`() {
        assertEquals(16, MicSensitivity.STEPS.size)
        assertEquals((1..16).toList(), MicSensitivity.STEPS.map { MicSensitivity.level(it) })
        assertEquals("최대는 ×32", 32f, MicSensitivity.gain(MicSensitivity.STEPS.last()), 0f)
        assertEquals("기본은 왼쪽에서 네 번째 칸", 4, MicSensitivity.level(MicSensitivity.DEFAULT))
        val oldMax = MemoryPrefs().apply { edit().putInt("mic_sensitivity", 800).apply() }
        assertEquals("예전 최대(800%)는 가장 가까운 열한 번째 칸(755%)으로 읽힌다", 755, SettingsManager.loadMicSensitivity(oldMax))
        assertEquals(11, MicSensitivity.level(SettingsManager.loadMicSensitivity(oldMax)))
    }

    @Test
    fun `칸에 없는 값은 가장 가까운 칸으로 맞춘다`() {
        assertEquals(42, MicSensitivity.clamp(0))
        assertEquals(133, MicSensitivity.clamp(130))
        assertEquals(3200, MicSensitivity.clamp(10_000))
        for (p in MicSensitivity.STEPS) assertEquals(p, MicSensitivity.clamp(p))
    }

    @Test
    fun `저장한 감도를 읽고 칸에 없는 값은 맞춘다`() {
        val prefs = MemoryPrefs()
        prefs.edit().putInt("mic_sensitivity", 424).apply()
        assertEquals(424, SettingsManager.loadMicSensitivity(prefs))
        prefs.edit().putInt("mic_sensitivity", 123).apply()
        assertEquals(133, SettingsManager.loadMicSensitivity(prefs))
    }

    @Test
    fun `바꾼 감도는 저장되고 다시 읽힌다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setMicSensitivity(317)
        assertEquals(317, SettingsManager.micSensitivity.value)
        SettingsManager.load(prefs)
        assertEquals(317, SettingsManager.micSensitivity.value)
    }

    @Test
    fun `감도는 마이크의 크기에만 곱하고 AI 에는 걸지 않는다`() {
        // 캡처 루프가 같은 버퍼를 네이티브(크기)와 AI(PCM)에 넘긴다. 감도는 네이티브 인자로만 넘겨야 AI 가 받는 소리가
        // 그대로다. PCM 을 곱하면 가람 님의 AI 평가(#117)가 보는 입력이 바뀐다.
        val loop = File("src/main/java/com/example/soundvisualizer/AudioCaptureService.kt").readText(Charsets.UTF_8)
            .let { it.substring(it.indexOf("private fun captureLoop("), it.indexOf("private fun toDualMono(")) }
        assertTrue(loop.contains("val levelGain = if (dualMono) MicSensitivity.gain(SettingsManager.micSensitivity.value) else 1f"))
        assertTrue(loop.contains("AudioEngine.pushAudioBuffer(buffer, floats, levelGain)"))
        assertTrue("감도를 PCM 에 곱한다", !Regex("""put\([^)]*levelGain|\*\s*levelGain""").containsMatchIn(loop))
    }
}

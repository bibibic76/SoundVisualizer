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
    fun `칸은 올라가고 한 칸마다 약 3dB 다`() {
        val steps = MicSensitivity.STEPS
        for (i in 1 until steps.size) {
            val ratio = steps[i].toFloat() / steps[i - 1]
            assertTrue("${steps[i - 1]} → ${steps[i]}", ratio in 1.35f..1.5f)
        }
        assertTrue("내릴 칸도 있어야 시끄러운 곳에서 잡음을 덜 그린다", steps.first() < MicSensitivity.DEFAULT)
    }

    @Test
    fun `화면에는 퍼센트 대신 1부터 10까지의 단계로 보인다`() {
        assertEquals(10, MicSensitivity.STEPS.size)
        assertEquals((1..10).toList(), MicSensitivity.STEPS.map { MicSensitivity.level(it) })
        assertEquals("기본은 3단계", 3, MicSensitivity.level(MicSensitivity.DEFAULT))
        assertEquals("예전 최대(800%)는 9단계로 그대로 읽힌다", 9, MicSensitivity.level(SettingsManager.loadMicSensitivity(MemoryPrefs().apply { edit().putInt("mic_sensitivity", 800).apply() })))
    }

    @Test
    fun `칸에 없는 값은 가장 가까운 칸으로 맞춘다`() {
        assertEquals(50, MicSensitivity.clamp(0))
        assertEquals(140, MicSensitivity.clamp(130))
        assertEquals(1130, MicSensitivity.clamp(10_000))
        for (p in MicSensitivity.STEPS) assertEquals(p, MicSensitivity.clamp(p))
    }

    @Test
    fun `저장한 감도를 읽고 칸에 없는 값은 맞춘다`() {
        val prefs = MemoryPrefs()
        prefs.edit().putInt("mic_sensitivity", 400).apply()
        assertEquals(400, SettingsManager.loadMicSensitivity(prefs))
        prefs.edit().putInt("mic_sensitivity", 123).apply()
        assertEquals(140, SettingsManager.loadMicSensitivity(prefs))
    }

    @Test
    fun `바꾼 감도는 저장되고 다시 읽힌다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setMicSensitivity(280)
        assertEquals(280, SettingsManager.micSensitivity.value)
        SettingsManager.load(prefs)
        assertEquals(280, SettingsManager.micSensitivity.value)
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

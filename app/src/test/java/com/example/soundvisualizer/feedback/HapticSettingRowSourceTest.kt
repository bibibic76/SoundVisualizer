package com.example.soundvisualizer.feedback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 설정 화면의 진동 줄이 외부 사운드 모드 규칙([HapticModeChoice], #290)을 쓰는지. Compose 화면이라 JVM 에서 그릴 수
 * 없으므로 소스에서 확인한다([HapticSourceContractTest] 와 같은 방식). 규칙 자체는 [HapticExternalModeTest] 가 본다.
 */
class HapticSettingRowSourceTest {

    private val row = File("src/main/java/com/example/soundvisualizer/feedback/HapticSettingRow.kt").readText(Charsets.UTF_8)
        .split('\n')
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.contains("/**") }
        .joinToString("\n")

    @Test
    fun `고른 칸은 실제로 울릴 방식이고 연속 칸은 외부 사운드 모드에서 고를 수 없다`() {
        assertTrue(row.contains("selected = shownSettings.mode"))
        assertTrue(row.contains("optionEnabled = { HapticModeChoice.selectable(it, external) }"))
        assertTrue(row.contains("val external = CaptureSource.of(externalSoundMode).hearsOwnVibration"))
    }

    @Test
    fun `저장은 규칙을 거치고 미리보기는 실제로 울릴 방식으로 울린다`() {
        assertTrue(row.contains("HapticModeChoice.toStore(settings, mode, external)"))
        // 외부 사운드 모드에서 '연속'으로 저장된 줄이 미리보기로 2초 동안 '연속'을 울리면 안 된다.
        val previews = Regex("(?<![.\\w])preview\\(").findAll(row).map { m ->
            row.substring(m.range.first, row.indexOf('\n', m.range.first))
        }.filterNot { it.startsWith("preview@") }.toList()
        assertTrue("미리보기 호출이 없다", previews.isNotEmpty())
        for (line in previews) assertTrue("실제로 울릴 방식으로 미리 울리지 않는다: $line", line.contains("HapticModeChoice.shown("))
    }

    @Test
    fun `외부 사운드 모드에서는 연속이 흐린 까닭을 적는다`() {
        assertTrue(row.contains("if (rowEnabled && HapticModeChoice.showsNote(external))"))
        assertTrue(row.contains("R.string.haptic_continuous_external"))
    }
}

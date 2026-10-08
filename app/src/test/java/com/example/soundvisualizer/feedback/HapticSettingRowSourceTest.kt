package com.example.soundvisualizer.feedback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 설정 화면의 진동 줄이 외부 사운드 모드 규칙([HapticModeChoice], #290·#354)을 쓰는지. Compose 화면이라 JVM 에서 그릴 수
 * 없으므로 소스에서 확인한다([HapticSourceContractTest] 와 같은 방식). 규칙 자체는 [HapticExternalModeTest] 가 본다.
 */
class HapticSettingRowSourceTest {

    private val row = File("src/main/java/com/example/soundvisualizer/feedback/HapticSettingRow.kt").readText(Charsets.UTF_8)
        .split('\n')
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.contains("/**") }
        .joinToString("\n")

    @Test
    fun `고른 칸은 실제로 울릴 방식이고 상한보다 빠른 칸은 외부 사운드 모드에서 고를 수 없다`() {
        assertTrue(row.contains("val external = CaptureSource.of(externalSoundMode).hearsOwnVibration"))
        assertTrue(row.contains("val shownSettings = HapticModeChoice.shown(settings, label, external)"))
        assertTrue(row.contains("selected = shownSettings.mode"))
        assertTrue(row.contains("optionEnabled = { HapticModeChoice.selectable(it, label, external) }"))
    }

    @Test
    fun `저장은 규칙을 거치고 미리보기는 실제로 울릴 방식으로 울린다`() {
        assertTrue(row.contains("HapticModeChoice.toStore(settings, mode, label, external)"))
        // 외부 사운드 모드에서 상한보다 빠르게 저장된 줄이 미리보기로 2초 동안 그 방식을 울리면 안 된다.
        val previews = Regex("(?<![.\\w])preview\\(").findAll(row).map { m ->
            row.substring(m.range.first, row.indexOf('\n', m.range.first))
        }.filterNot { it.startsWith("preview@") }.toList()
        assertTrue("미리보기 호출이 없다", previews.size >= 2)
        for (line in previews) {
            assertTrue("실제로 울릴 방식으로 미리 울리지 않는다: $line", line.contains("HapticModeChoice.shown("))
            assertTrue("종류의 상한을 거치지 않고 미리 울린다: $line", line.contains(", label, external))"))
        }
    }

    @Test
    fun `외부 사운드 모드에서는 상한의 까닭을 적고 환경음은 진동하지 않는다고 적는다`() {
        assertTrue(row.contains("if (rowEnabled && HapticModeChoice.showsNote(external))"))
        assertTrue(row.contains("val cap = HapticSettings.externalCap(label)"))
        assertTrue(row.contains("if (cap == HapticMode.Off) stringResource(R.string.haptic_off_external)"))
        assertTrue(row.contains("stringResource(R.string.haptic_cap_external, typeName, stringResource(cap.labelRes))"))
        assertFalse("'연속 → 빠름' 안내(#341)가 남았다", row.contains("haptic_continuous_external"))
    }

    @Test
    fun `세기와 AI 안내는 실제로 울릴 방식이 꺼짐이 아닐 때만 보인다`() {
        // 외부 사운드 모드의 환경음은 꺼짐으로만 울리므로 꺼짐 줄처럼 세기를 접고 AI 안내도 붙이지 않는다(#354).
        assertTrue(row.contains("DependentSettings(rowEnabled && shownSettings.enabled)"))
        assertTrue(row.contains("val aiNote = !aiAvailable && rowEnabled && shownSettings.enabled"))
    }
}

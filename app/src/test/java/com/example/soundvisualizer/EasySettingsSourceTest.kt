package com.example.soundvisualizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 쉬운 설정(#408)의 Compose 화면이 정해 둔 규칙을 쓰는지. Compose 화면이라 JVM 에서 그릴 수 없으므로 소스에서 확인한다
 * (HapticSettingRowSourceTest 와 같은 방식). 규칙 자체는 EasyVibrationTest·EasySizeTest·EasySettingsSettingTest 가 보고,
 * 화면은 EasySettingsInstrumentedTest 가 본다.
 */
class EasySettingsSourceTest {

    private fun source(path: String) = File("src/main/java/com/example/soundvisualizer/$path").readText(Charsets.UTF_8)
        .split('\n')
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.contains("/**") }
        .joinToString("\n")

    private val haptic by lazy { source("feedback/EasyHapticRow.kt") }
    private val view by lazy { source("EasySettingsView.kt") }
    private val launcher by lazy { source("LauncherApp.kt") }
    private val demo by lazy { source("tutorial/TutorialDemo.kt") }

    @Test
    fun `쉬운 진동 칸은 외부 사운드 모드 규칙을 거쳐 저장하고 실제로 울릴 설정으로 미리 울린다`() {
        // 자세한 설정의 진동 줄과 같은 판단이다(HapticSettingRowSourceTest).
        assertTrue(haptic.contains("val external = CaptureSource.of(externalSoundMode).hearsOwnVibration"))
        // 선택되어 보이는 칸도 실제로 울릴 설정으로 정한다(외부 사운드 모드의 환경음은 끔으로 보인다).
        assertTrue(haptic.contains("val shownStep = EasyVibration.shown(settings, label, external)"))
        assertTrue(haptic.contains("selected = shownStep,"))
        assertTrue(haptic.contains("optionEnabled = { EasyVibration.selectable(it, label, external) }"))
        assertTrue(haptic.contains("val next = EasyVibration.toStore(settings, step, label, external)"))
        assertTrue(haptic.contains("preview(HapticModeChoice.shown(next ?: settings, label, external))"))
        // 외부 사운드 모드에서 상한보다 빠르게 저장된 방식이 2초 동안 울리면 안 된다.
        val previews = Regex("(?<![.\\w])preview\\(").findAll(haptic).map { m ->
            haptic.substring(m.range.first, haptic.indexOf('\n', m.range.first))
        }.toList()
        assertTrue("미리보기 호출이 없다", previews.isNotEmpty())
        for (line in previews) assertTrue("실제로 울릴 설정으로 미리 울리지 않는다: $line", line.contains("HapticModeChoice.shown("))
    }

    @Test
    fun `쉬운 진동 칸의 미리보기는 실행 중에 울리지 않고 자기 것만 멈춘다`() {
        assertTrue("실행 중에도 미리 울린다", haptic.contains("if (running) return@preview"))
        assertTrue(haptic.contains("val owner = EASY_PREVIEW_OWNER + label"))
        // 주인 이름이 자세한 설정 줄(라벨 그대로)과 같으면 한쪽이 사라질 때 다른 쪽의 미리보기를 끊는다.
        assertFalse("자세한 설정 줄과 같은 주인 이름을 쓴다", haptic.contains("owner = label"))
        assertTrue(haptic.contains("onDispose { HapticPreviewGate.stopPreview(owner = owner) }"))
        assertTrue(haptic.contains("if (running) HapticPreviewGate.stopPreview(owner = owner)"))
        assertTrue("표시를 끈 종류나 진동이 없는 기기에서도 칸이 켜진다", haptic.contains("val rowEnabled = shown && player.hasVibrator"))
    }

    @Test
    fun `실행 중이라 미리 울리지 않는다는 안내는 카드 맨 위에 한 번 두고 다른 안내에 가리지 않는다`() {
        // 줄마다 두면 AI·세기 안내가 먼저 걸린 줄에서 빠지고, 같은 문장이 세 번 되풀이된다.
        assertFalse("진동 줄에 실행 중 안내가 남았다", haptic.contains("easy_vibration_preview_running"))
        assertTrue(view.contains("if (running && hasVibrator)"))
        assertTrue(view.contains("stringResource(R.string.easy_vibration_preview_running)"))
    }

    @Test
    fun `외부 사운드 모드의 환경음 안내는 환경음 줄에만 붙는다`() {
        // 안내가 환경음을 이름으로 말하므로, 상한만 보고 고르면 다른 종류의 상한을 꺼짐으로 바꿨을 때 엉뚱한 줄에 붙는다.
        assertTrue(haptic.contains("label == AiClassification.AMBIENT && HapticModeChoice.neverVibrates(label, external) -> R.string.easy_vibration_off_external"))
    }

    @Test
    fun `크기 칸은 선택한 모드의 크기를 보여 주고 그 모드에만 쓴다`() {
        // 크기는 모드마다 따로다(사용자 결정 2026-10-22). 그림도 그 모드의 모양이다.
        assertTrue(view.contains("EasySection(size, spokenTitle = \"\$size, \${stringResource(mode.labelRes)}\") { SizeChoices(mode) }"))
        assertTrue(view.contains("selected = EasySize.of(settings)"))
        assertTrue(view.contains("picture = { step, color -> SizeStepIcon(step, mode, color) }"))
        assertTrue(view.contains("onSelect = { SettingsManager.applyEasySize(mode, it) }"))
    }

    @Test
    fun `그림 선택지는 선택한 칸을 색만으로 알리지 않는다`() {
        // 선택한 칸(#2563EB)과 다른 칸(#333A44)의 대비가 2.2:1 이라 색만으로는 구분이 어렵다. 모양 카드처럼 체크 표시를 둔다.
        val row = view.substringAfter("internal fun <T> PictureChoiceRow(")
        assertTrue("선택한 칸에 체크 표시가 없다", row.contains("if (isSelected) {") && row.contains("Icons.Default.Check"))
        // 낱말 앞에 두면 긴 번역어가 좁은 칸에서 줄을 바꾸므로 칸 모서리에 둔다.
        assertTrue(row.contains("modifier = Modifier.align(Alignment.TopStart)"))
    }

    @Test
    fun `모양 카드는 설정 탭이 보이고 애니메이션이 켜져 있을 때 선택한 카드만 움직인다`() {
        assertTrue(launcher.contains("1 -> SettingsTab(onAddTile, active = restingPage == 1)"))
        assertTrue(view.contains("canPlay = active && selected && ValueAnimator.areAnimatorsEnabled()"))
        // 애니메이션 삭제는 구성 변경이 아니라서, 처음 한 번만 읽으면 설정 탭을 연 채 바꾼 값을 놓친다.
        assertFalse("시스템 애니메이션 설정을 한 번만 읽는다", Regex("remember\\s*\\{\\s*ValueAnimator").containsMatchIn(view))
        assertTrue(view.contains("play = if (selected) play else 0"))
        // 설정 탭에 들어오거나, 선택한 모드나 그 크기가 바뀌거나, 선택한 카드를 다시 누르면 움직인다.
        assertTrue(view.contains("LaunchedEffect(active, current, currentSettings) {"))
        assertTrue(view.contains("onSelect = { if (selected) play++ else SettingsManager.setVisualMode(mode) }"))
    }

    @Test
    fun `모양 카드 그림은 진짜 진동을 울리지 않고 테스트가 멈출 수 있는 루프로 돈다`() {
        val preview = demo.substringAfter("internal fun ModePreviewPhone(").substringBefore("\n}\n")
        assertTrue("모양 카드 그림이 진짜 진동을 넘긴다", preview.contains("haptics = null"))
        assertTrue("정해 둔 시간만 움직이지 않는다", preview.contains("stopAfterSec = PREVIEW_PLAY_SEC"))
        // 끝없는 루프는 테스트가 멈춰 둘 수 있는 길로 돈다(그렇지 않으면 설정 탭을 여는 Compose 테스트가 한가해지지 않는다).
        assertTrue(demo.contains("withInfiniteAnimationFrameNanos"))
    }
}

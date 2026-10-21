package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val AMBIENT = AiClassification.AMBIENT
private const val SPEECH = AiClassification.SPEECH
private const val DANGER = AiClassification.DANGER

/**
 * 쉬운 설정의 진동 세 단계(#408)가 저장된 진동 설정을 어떻게 보여 주고 어떻게 바꾸는지.
 * 자세한 설정에서 정해 둔 방식을 지우지 않는지와 외부 사운드 모드의 상한(#354)을 지키는지를 본다.
 */
class EasyVibrationTest {

    private val labels = listOf(AMBIENT, SPEECH, DANGER)

    /** [tapped] 를 누른 뒤 저장되어 있을 설정. 저장하지 않으면 그대로다. */
    private fun tap(stored: HapticSettings, tapped: EasyVibration, label: String, external: Boolean = false): HapticSettings =
        EasyVibration.toStore(stored, tapped, label, external) ?: stored

    @Test
    fun `처음 설정은 위협음 세게 대화음과 환경음 끔으로 보인다`() {
        assertEquals(EasyVibration.High, EasyVibration.shown(HapticSettings.defaultFor(DANGER), DANGER, external = false))
        assertEquals(EasyVibration.Off, EasyVibration.shown(HapticSettings.defaultFor(SPEECH), SPEECH, external = false))
        assertEquals(EasyVibration.Off, EasyVibration.shown(HapticSettings.defaultFor(AMBIENT), AMBIENT, external = false))
    }

    @Test
    fun `세기 50 까지는 약하게 그보다 세면 세게로 보인다`() {
        for (level in HapticSettings.MIN_LEVEL..HapticSettings.MAX_LEVEL step HapticSettings.LEVEL_STEP) {
            val expected = if (level <= 50) EasyVibration.Low else EasyVibration.High
            assertEquals("세기 $level", expected, EasyVibration.shown(HapticSettings(HapticMode.Fast, level), SPEECH, external = false))
        }
    }

    @Test
    fun `꺼진 종류를 켜면 위협음은 중간 나머지는 느림으로 켜진다`() {
        for (label in labels) {
            val off = HapticSettings(HapticMode.Off, 70)
            val onMode = if (label == DANGER) HapticMode.Medium else HapticMode.Slow
            assertEquals(label, HapticSettings(onMode, 40), tap(off, EasyVibration.Low, label))
            assertEquals(label, HapticSettings(onMode, 100), tap(off, EasyVibration.High, label))
        }
    }

    @Test
    fun `켜져 있던 종류는 자세한 설정에서 정한 방식을 두고 세기만 바꾼다`() {
        for (mode in listOf(HapticMode.Slow, HapticMode.Medium, HapticMode.Fast, HapticMode.Continuous)) {
            assertEquals(mode.name, HapticSettings(mode, 100), tap(HapticSettings(mode, 30), EasyVibration.High, SPEECH))
            assertEquals(mode.name, HapticSettings(mode, 40), tap(HapticSettings(mode, 90), EasyVibration.Low, SPEECH))
        }
    }

    @Test
    fun `끄면 방식만 꺼짐이 되고 세기는 남는다`() {
        assertEquals(HapticSettings(HapticMode.Off, 80), tap(HapticSettings(HapticMode.Fast, 80), EasyVibration.Off, DANGER))
    }

    @Test
    fun `이미 보이는 단계를 다시 누르면 저장하지 않는다`() {
        // 약하게로 보이는 세기 20 을 다시 누르면 40 으로 바뀌지 않는다. 자세한 설정에서 맞춘 세기가 남아야 한다.
        assertNull(EasyVibration.toStore(HapticSettings(HapticMode.Slow, 20), EasyVibration.Low, SPEECH, external = false))
        assertNull(EasyVibration.toStore(HapticSettings(HapticMode.Medium, 100), EasyVibration.High, DANGER, external = false))
        assertNull(EasyVibration.toStore(HapticSettings(HapticMode.Off, 60), EasyVibration.Off, SPEECH, external = false))
    }

    @Test
    fun `세 단계의 세기는 자세한 설정의 슬라이더에 있는 값이다`() {
        for (step in listOf(EasyVibration.Low, EasyVibration.High)) {
            assertEquals(step.name, HapticSettings.clampLevel(step.level), step.level)
        }
        assertTrue("약하게는 약하게로 보여야 한다", EasyVibration.Low.level <= EasyVibration.LOW_MAX_LEVEL)
        assertTrue("세게는 세게로 보여야 한다", EasyVibration.High.level > EasyVibration.LOW_MAX_LEVEL)
    }

    @Test
    fun `켤 때의 방식은 외부 사운드 모드의 상한 안이다`() {
        // 환경음은 외부 사운드 모드에서 진동하지 않으므로(상한이 꺼짐) 선택할 수 없다. 아래에서 따로 본다.
        for (label in listOf(SPEECH, DANGER)) {
            assertTrue(label, EasyVibration.onMode(label) <= HapticSettings.externalCap(label))
        }
    }

    @Test
    fun `외부 사운드 모드에서는 실제로 울릴 설정으로 보인다`() {
        // 대화음을 '빠름'으로 저장해도 외부 사운드 모드에서는 느림으로 울린다. 세기는 그대로라 단계도 그대로다.
        assertEquals(EasyVibration.High, EasyVibration.shown(HapticSettings(HapticMode.Fast, 90), SPEECH, external = true))
        // 환경음은 외부 사운드 모드에서 울리지 않으므로 끔으로 보인다.
        assertEquals(EasyVibration.Off, EasyVibration.shown(HapticSettings(HapticMode.Medium, 90), AMBIENT, external = true))
    }

    @Test
    fun `외부 사운드 모드에서 환경음은 끔만 선택할 수 있다`() {
        assertTrue(EasyVibration.selectable(EasyVibration.Off, AMBIENT, external = true))
        assertFalse(EasyVibration.selectable(EasyVibration.Low, AMBIENT, external = true))
        assertFalse(EasyVibration.selectable(EasyVibration.High, AMBIENT, external = true))
        assertNull(EasyVibration.toStore(HapticSettings(HapticMode.Off, 30), EasyVibration.High, AMBIENT, external = true))
        // 외부 사운드 모드가 아니면 환경음도 켤 수 있다.
        for (step in EasyVibration.values()) assertTrue(step.name, EasyVibration.selectable(step, AMBIENT, external = false))
    }

    @Test
    fun `외부 사운드 모드에서도 정해 둔 더 빠른 방식은 지우지 않는다`() {
        // 상한으로 보이는 '빠름'(대화음)의 세기만 바꾼다. 외부 사운드 모드를 끄면 정해 둔 대로 빠름으로 울린다.
        assertEquals(HapticSettings(HapticMode.Fast, 40), tap(HapticSettings(HapticMode.Fast, 90), EasyVibration.Low, SPEECH, external = true))
    }
}

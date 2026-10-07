package com.example.soundvisualizer.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 외부 사운드 모드에서 '연속'을 '빠름'으로 울리고, 설정 화면이 그렇게 보여 주는 규칙(#290).
 * 저장된 설정은 바꾸지 않으므로 외부 사운드 모드를 끄면 다시 '연속'이다.
 */
class HapticExternalModeTest {

    private val continuous = HapticSettings(HapticMode.Continuous, 70)

    @Test
    fun `외부 사운드 모드에서는 연속만 빠름으로 바뀌고 세기는 그대로다`() {
        assertEquals(HapticSettings(HapticMode.Fast, 70), continuous.inExternalSound())
        for (mode in HapticMode.entries.filter { it != HapticMode.Continuous }) {
            val settings = HapticSettings(mode, 40)
            assertEquals(mode.name, settings, settings.inExternalSound())
        }
        assertEquals("두 번 바꿔도 같다", continuous.inExternalSound(), continuous.inExternalSound().inExternalSound())
    }

    @Test
    fun `종류 설정도 표시 여부는 그대로 두고 방식만 바꾼다`() {
        val hidden = HapticPolicy.ClassConfig(shown = false, haptic = continuous)
        assertEquals(HapticPolicy.ClassConfig(false, HapticSettings(HapticMode.Fast, 70)), hidden.inExternalSound())
    }

    @Test
    fun `외부 사운드 모드에서 연속으로 저장된 줄은 빠름으로 보이고 안내가 붙는다`() {
        assertEquals(HapticMode.Fast, HapticModeChoice.shown(continuous, external = true).mode)
        assertEquals(70, HapticModeChoice.shown(continuous, external = true).level)
        assertFalse(HapticModeChoice.selectable(HapticMode.Continuous, external = true))
        assertTrue(HapticModeChoice.showsNote(external = true))
        for (mode in HapticMode.entries.filter { it != HapticMode.Continuous }) {
            assertTrue(mode.name, HapticModeChoice.selectable(mode, external = true))
        }
    }

    @Test
    fun `빠름으로 보이는 연속을 눌러도 연속이 남아 모드를 끄면 돌아온다`() {
        assertNull(HapticModeChoice.toStore(continuous, HapticMode.Fast, external = true))
        assertNull("고를 수 없는 연속을 저장했다", HapticModeChoice.toStore(continuous, HapticMode.Continuous, external = true))
        assertEquals(HapticMode.Continuous, HapticModeChoice.shown(continuous, external = false).mode)
    }

    @Test
    fun `외부 사운드 모드에서 다른 방식을 고르면 그대로 저장된다`() {
        assertEquals(HapticSettings(HapticMode.Medium, 70), HapticModeChoice.toStore(continuous, HapticMode.Medium, external = true))
        assertEquals(HapticSettings(HapticMode.Off, 70), HapticModeChoice.toStore(continuous, HapticMode.Off, external = true))
    }

    @Test
    fun `외부 사운드 모드를 끄면 지금까지와 같다`() {
        val medium = HapticSettings(HapticMode.Medium, 60)
        assertEquals(continuous, HapticModeChoice.shown(continuous, external = false))
        assertFalse(HapticModeChoice.showsNote(external = false))
        assertTrue(HapticMode.entries.all { HapticModeChoice.selectable(it, external = false) })
        assertEquals(HapticSettings(HapticMode.Continuous, 60), HapticModeChoice.toStore(medium, HapticMode.Continuous, external = false))
        assertNull("이미 고른 방식을 다시 누르면 저장할 것이 없다", HapticModeChoice.toStore(medium, HapticMode.Medium, external = false))
    }

    @Test
    fun `외부 사운드 모드에서는 어느 줄이든 연속이 흐린 까닭을 적는다`() {
        // 기본값(위협음 '중간')에서 '연속'을 고르려는 사람도 칸이 왜 흐린지 알아야 한다.
        assertTrue(HapticModeChoice.showsNote(external = true))
        assertFalse(HapticModeChoice.selectable(HapticMode.Continuous, external = true))
    }
}

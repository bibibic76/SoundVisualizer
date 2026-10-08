package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val AMBIENT = AiClassification.AMBIENT
private const val SPEECH = AiClassification.SPEECH
private const val DANGER = AiClassification.DANGER

/**
 * 외부 사운드 모드에서 종류마다 진동 방식에 상한을 두고(#354), 설정 화면이 그렇게 보여 주는 규칙.
 * 저장된 설정은 바꾸지 않으므로 외부 사운드 모드를 끄면 정해 둔 대로 울린다. #341 의 '연속 → 빠름' 을 대신한다.
 */
class HapticExternalModeTest {

    private val labels = listOf(AMBIENT, SPEECH, DANGER)

    /** 종류마다, 저장된 방식(꺼짐·느림·중간·빠름·연속 순서)이 외부 사운드 모드에서 실제로 울릴 방식. 이슈 #354 의 표다. */
    private val played: Map<String, List<HapticMode>> = mapOf(
        AMBIENT to listOf(HapticMode.Off, HapticMode.Off, HapticMode.Off, HapticMode.Off, HapticMode.Off),
        SPEECH to listOf(HapticMode.Off, HapticMode.Slow, HapticMode.Slow, HapticMode.Slow, HapticMode.Slow),
        DANGER to listOf(HapticMode.Off, HapticMode.Slow, HapticMode.Medium, HapticMode.Medium, HapticMode.Medium)
    )

    /**
     * 설정 줄에서 [tapped] 를 누른 것과 같다([HapticSettingRow] 의 onSelect. 배선은 [HapticSettingRowSourceTest] 가 고정한다).
     * @return 누른 뒤 저장된 설정과 미리 울린 설정
     */
    private fun tap(stored: HapticSettings, tapped: HapticMode, label: String, external: Boolean): Pair<HapticSettings, HapticSettings> {
        val next = HapticModeChoice.toStore(stored, tapped, label, external)
        return (next ?: stored) to HapticModeChoice.shown(next ?: stored, label, external)
    }

    // ---------------------------------------------------------------
    // 상한
    // ---------------------------------------------------------------

    @Test
    fun `방식은 느린 것부터 빠른 것 순서다`() {
        // 상한은 이 순서로 비교한다. 순서가 바뀌면 상한이 엉뚱한 방식을 막는다.
        assertEquals(
            listOf(HapticMode.Off, HapticMode.Slow, HapticMode.Medium, HapticMode.Fast, HapticMode.Continuous),
            HapticMode.entries
        )
    }

    @Test
    fun `종류마다 상한은 환경음 꺼짐 대화음 느림 위협음 중간이다`() {
        assertEquals(HapticMode.Off, HapticSettings.externalCap(AMBIENT))
        assertEquals(HapticMode.Slow, HapticSettings.externalCap(SPEECH))
        assertEquals(HapticMode.Medium, HapticSettings.externalCap(DANGER))
        assertEquals("모르는 라벨은 환경음으로 본다", HapticMode.Off, HapticSettings.externalCap("unknown"))
    }

    @Test
    fun `외부 사운드 모드에서 상한보다 빠른 방식은 상한으로 울리고 세기는 그대로다`() {
        for (label in labels) {
            HapticMode.entries.forEachIndexed { i, mode ->
                val external = HapticSettings(mode, 70).inExternalSound(label)
                assertEquals("$label $mode", HapticSettings(played.getValue(label)[i], 70), external)
                assertEquals("$label $mode: 두 번 걸어도 같다", external, external.inExternalSound(label))
            }
        }
    }

    @Test
    fun `외부 사운드 모드에서는 빠름과 연속이 어느 종류로도 울리지 않는다`() {
        // 빠름은 꼬리를 뺀 듣는 틈이 0.04초뿐이고 연속에는 틈이 없다(#290). 이 규칙이 #341 의 '연속 → 빠름' 을 대신한다.
        for (label in labels) {
            for (mode in HapticMode.entries) {
                val external = HapticSettings(mode, 50).inExternalSound(label).mode
                assertTrue("$label $mode 가 $external 로 울린다", external != HapticMode.Fast && external != HapticMode.Continuous)
            }
        }
    }

    @Test
    fun `종류 설정도 표시 여부는 그대로 두고 방식만 바꾼다`() {
        val hidden = HapticPolicy.ClassConfig(shown = false, haptic = HapticSettings(HapticMode.Continuous, 70))
        assertEquals(HapticPolicy.ClassConfig(false, HapticSettings(HapticMode.Medium, 70)), hidden.inExternalSound(DANGER))
        assertEquals(HapticPolicy.ClassConfig(false, HapticSettings(HapticMode.Slow, 70)), hidden.inExternalSound(SPEECH))
        val ambient = HapticPolicy.ClassConfig(shown = true, haptic = HapticSettings(HapticMode.Fast, 70))
        assertFalse("외부 사운드 모드의 환경음이 진동한다", ambient.inExternalSound(AMBIENT).vibrates)
    }

    // ---------------------------------------------------------------
    // 진동 판단. 알림의 틱은 외부 사운드 모드에서 설정을 라벨마다 상한에 맞춰 읽는다(배선은 HapticSourceContractTest).
    // ---------------------------------------------------------------

    /** 모든 종류를 [mode] 로 정해 둔 설정을, 알림의 틱처럼 외부 사운드 모드의 상한에 맞춰 읽는다. */
    private fun externalTick(mode: HapticMode, level: Int = 80): (String) -> HapticPolicy.ClassConfig =
        { label -> HapticPolicy.ClassConfig(true, HapticSettings(mode, level)).inExternalSound(label) }

    @Test
    fun `외부 사운드 모드에서 라벨이 있는 소리는 그 종류의 상한까지 울린다`() {
        assertEquals(HapticPolicy.Vibe(HapticMode.Slow, 80), HapticPolicy().onTick(0, SPEECH, 0.5f, externalTick(HapticMode.Fast)))
        assertEquals(HapticPolicy.Vibe(HapticMode.Medium, 80), HapticPolicy().onTick(0, DANGER, 0.5f, externalTick(HapticMode.Continuous)))
        assertNull("환경음이 진동한다", HapticPolicy().onTick(0, AMBIENT, 0.5f, externalTick(HapticMode.Medium)))
    }

    @Test
    fun `AI 를 쓸 수 없을 때 큰 소리는 위협음 상한인 중간까지만 울린다`() {
        // 종류를 모르는 큰 소리는 위협음 설정으로 울린다(#225). 외부 사운드 모드에서는 그 설정에 위협음의 상한이 걸린다.
        for (mode in listOf(HapticMode.Fast, HapticMode.Continuous)) {
            val vibe = HapticPolicy().onTick(0, null, 0.5f, externalTick(mode), unlabeledAlerts = true)
            assertEquals("$mode", HapticPolicy.Vibe(HapticMode.Medium, 80), vibe)
        }
        assertEquals(
            "상한 아래는 그대로다",
            HapticPolicy.Vibe(HapticMode.Slow, 80),
            HapticPolicy().onTick(0, null, 0.5f, externalTick(HapticMode.Slow), unlabeledAlerts = true)
        )
    }

    // ---------------------------------------------------------------
    // 설정 화면
    // ---------------------------------------------------------------

    @Test
    fun `외부 사운드 모드에서는 상한까지만 고를 수 있다`() {
        for (label in labels) {
            for (mode in HapticMode.entries) {
                val allowed = mode <= HapticSettings.externalCap(label)
                assertEquals("$label $mode", allowed, HapticModeChoice.selectable(mode, label, external = true))
            }
        }
        assertEquals(
            "환경음은 꺼짐만 고를 수 있다",
            listOf(HapticMode.Off),
            HapticMode.entries.filter { HapticModeChoice.selectable(it, AMBIENT, external = true) }
        )
    }

    @Test
    fun `상한보다 빠르게 저장된 줄은 상한으로 보인다`() {
        val fast = HapticSettings(HapticMode.Fast, 70)
        assertEquals(HapticSettings(HapticMode.Off, 70), HapticModeChoice.shown(fast, AMBIENT, external = true))
        assertEquals(HapticSettings(HapticMode.Slow, 70), HapticModeChoice.shown(fast, SPEECH, external = true))
        assertEquals(HapticSettings(HapticMode.Medium, 70), HapticModeChoice.shown(fast, DANGER, external = true))
        val slow = HapticSettings(HapticMode.Slow, 30)
        assertEquals("상한 아래는 그대로 보인다", slow, HapticModeChoice.shown(slow, DANGER, external = true))
    }

    @Test
    fun `상한으로 보이는 칸을 눌러도 정해 둔 방식이 남아 모드를 끄면 돌아온다`() {
        val cases = listOf(
            SPEECH to HapticSettings(HapticMode.Fast, 70),
            DANGER to HapticSettings(HapticMode.Continuous, 70),
            AMBIENT to HapticSettings(HapticMode.Slow, 70)
        )
        for ((label, stored) in cases) {
            val cap = HapticSettings.externalCap(label)
            assertNull("$label: 보이는 상한을 눌렀는데 저장했다", HapticModeChoice.toStore(stored, cap, label, external = true))
            assertEquals(label, stored, tap(stored, cap, label, external = true).first)
            assertEquals("$label: 모드를 끄면 정해 둔 대로다", stored, HapticModeChoice.shown(stored, label, external = false))
        }
    }

    @Test
    fun `고를 수 없는 칸은 눌려도 저장하지 않는다`() {
        val medium = HapticSettings(HapticMode.Medium, 60)
        assertNull(HapticModeChoice.toStore(medium, HapticMode.Fast, DANGER, external = true))
        assertNull(HapticModeChoice.toStore(medium, HapticMode.Continuous, DANGER, external = true))
        assertNull(HapticModeChoice.toStore(medium, HapticMode.Medium, SPEECH, external = true))
        assertNull(HapticModeChoice.toStore(medium, HapticMode.Slow, AMBIENT, external = true))
    }

    @Test
    fun `외부 사운드 모드에서 고를 수 있는 다른 방식을 고르면 그대로 저장된다`() {
        val continuous = HapticSettings(HapticMode.Continuous, 70)
        assertEquals(HapticSettings(HapticMode.Slow, 70), HapticModeChoice.toStore(continuous, HapticMode.Slow, DANGER, external = true))
        assertEquals(HapticSettings(HapticMode.Off, 70), HapticModeChoice.toStore(continuous, HapticMode.Off, DANGER, external = true))
        assertEquals(HapticSettings(HapticMode.Off, 70), HapticModeChoice.toStore(continuous, HapticMode.Off, SPEECH, external = true))
    }

    @Test
    fun `미리보기는 실제로 울릴 방식으로 울린다`() {
        // 방식을 누르면 저장과 상관없이 보이는 방식으로 2초 울린다. 상한보다 빠른 방식이 미리 울리면 실제와 다르다.
        val fast = HapticSettings(HapticMode.Fast, 70)
        val (storedAfter, previewed) = tap(fast, HapticMode.Medium, DANGER, external = true)
        assertEquals("정해 둔 빠름이 남는다", fast, storedAfter)
        assertEquals(HapticSettings(HapticMode.Medium, 70), previewed)
        assertSamePreview(HapticSettings(HapticMode.Medium, 70), previewed)
        assertEquals(HapticSettings(HapticMode.Slow, 70), tap(fast, HapticMode.Slow, SPEECH, external = true).second)

        // 세기를 바꾸면 세기만 저장되고, 미리보기는 상한으로 울린다([HapticSettingRow] 의 세기 슬라이더).
        assertEquals(HapticSettings(HapticMode.Slow, 90), HapticModeChoice.shown(fast.copy(level = 90), SPEECH, external = true))

        // 외부 사운드 모드의 환경음은 꺼짐으로 보이므로 미리보기가 울리지 않는다.
        val ambient = tap(HapticSettings(HapticMode.Slow, 70), HapticMode.Off, AMBIENT, external = true).second
        assertNull(HapticShapes.preview(ambient, amplitudeControl = true))

        // 폰 안의 소리는 고른 방식 그대로 울린다.
        assertEquals(
            HapticSettings(HapticMode.Fast, 70),
            tap(HapticSettings(HapticMode.Slow, 70), HapticMode.Fast, SPEECH, external = false).second
        )
    }

    private fun assertSamePreview(expected: HapticSettings, actual: HapticSettings) {
        val want = HapticShapes.preview(expected, amplitudeControl = true)
        val got = HapticShapes.preview(actual, amplitudeControl = true)
        assertNotNull("미리보기가 울리지 않는다", got)
        assertArrayEquals(want!!.timings, got!!.timings)
        assertArrayEquals(want.amplitudes, got.amplitudes)
    }

    @Test
    fun `외부 사운드 모드에서는 어느 줄이든 상한 안내를 적는다`() {
        // 기본값(위협음 '중간')에서 더 빠른 방식을 고르려는 사람도 칸이 왜 흐린지 알아야 한다. 환경음은 상한이 꺼짐이라
        // 진동하지 않는다는 안내가 붙는다(HapticSettingRow).
        assertTrue(HapticModeChoice.showsNote(external = true))
        assertFalse(HapticModeChoice.selectable(HapticMode.Fast, DANGER, external = true))
    }

    @Test
    fun `외부 사운드 모드를 끄면 지금까지와 같다`() {
        for (label in labels) {
            for (mode in HapticMode.entries) {
                val stored = HapticSettings(mode, 60)
                assertEquals("$label $mode", stored, HapticModeChoice.shown(stored, label, external = false))
                assertTrue("$label $mode", HapticModeChoice.selectable(mode, label, external = false))
            }
        }
        assertFalse(HapticModeChoice.showsNote(external = false))
        val medium = HapticSettings(HapticMode.Medium, 60)
        assertEquals(HapticSettings(HapticMode.Continuous, 60), HapticModeChoice.toStore(medium, HapticMode.Continuous, SPEECH, external = false))
        assertEquals(HapticSettings(HapticMode.Fast, 60), HapticModeChoice.toStore(medium, HapticMode.Fast, AMBIENT, external = false))
        assertNull("이미 고른 방식을 다시 누르면 저장할 것이 없다", HapticModeChoice.toStore(medium, HapticMode.Medium, DANGER, external = false))
        val stored: (String) -> HapticPolicy.ClassConfig = { HapticPolicy.ClassConfig(true, HapticSettings(HapticMode.Continuous, 80)) }
        assertEquals("폰 안의 소리는 정해 둔 방식 그대로 울린다", HapticPolicy.Vibe(HapticMode.Continuous, 80), HapticPolicy().onTick(0, AMBIENT, 0.5f, stored))
    }
}

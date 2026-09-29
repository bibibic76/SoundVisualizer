package com.example.soundvisualizer

import com.example.soundvisualizer.ai.YamnetThreeClassMapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 분류 탭에서 바꾼 소리 종류의 저장·복원과, 바꾼 값이 분류기(YamnetThreeClassMapper)까지 가는지.
 *
 * SettingsManager 는 싱글턴이고 분류기의 표도 전역이라, 테스트마다 빈 프리퍼런스로 다시 읽어 되돌린다.
 */
class SoundTypeSettingsTest {

    @After
    fun resetSingletons() {
        SettingsManager.load(MemoryPrefs())
    }

    @Test
    fun `새로 설치하면 바꾼 소리가 없다`() {
        assertTrue(SettingsManager.loadSoundTypes(MemoryPrefs()).isEmpty())
        SettingsManager.load(MemoryPrefs())
        assertTrue(SettingsManager.soundTypes.value.isEmpty())
        assertTrue(YamnetThreeClassMapper.userOverrides.isEmpty())
    }

    @Test
    fun `고른 종류를 저장하고 분류기에도 바로 넣는다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)

        SettingsManager.setSoundType("Doorbell", AiClassification.DANGER)

        val expected = mapOf("Doorbell" to AiClassification.DANGER)
        assertEquals(expected, SettingsManager.soundTypes.value)
        assertEquals("실행 중인 분류가 다음 판정부터 따라야 한다", expected, YamnetThreeClassMapper.userOverrides)
        assertEquals("앱을 다시 켜도 남아야 한다", expected, SettingsManager.loadSoundTypes(prefs))
    }

    @Test
    fun `저장해 둔 종류는 앱을 켤 때 분류기에 들어간다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setSoundType("Walk, footsteps", AiClassification.AMBIENT)

        // 프로세스가 새로 뜬 것처럼 전역 값을 비운 뒤 같은 프리퍼런스로 다시 읽는다.
        YamnetThreeClassMapper.userOverrides = emptyMap()
        SettingsManager.load(prefs)

        assertEquals(AiClassification.AMBIENT, YamnetThreeClassMapper.mapDisplayNameToCoarse("Walk, footsteps"))
    }

    @Test
    fun `기본 종류를 고르면 바꾼 것을 지운다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setSoundType("Doorbell", AiClassification.SPEECH)
        SettingsManager.setSoundType("Doorbell", YamnetThreeClassMapper.defaultCoarse("Doorbell"))

        assertTrue(SettingsManager.soundTypes.value.isEmpty())
        assertTrue(YamnetThreeClassMapper.userOverrides.isEmpty())
        assertNull("남은 것이 없으면 키를 지운다", prefs.getString("sound_types", null))
    }

    @Test
    fun `세 종류가 아닌 값은 무시한다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setSoundType("Doorbell", "loud")

        assertTrue(SettingsManager.soundTypes.value.isEmpty())
        assertFalse(prefs.contains("sound_types"))
    }

    @Test
    fun `모두 되돌리면 저장값과 분류기의 표를 비운다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setSoundType("Doorbell", AiClassification.DANGER)
        SettingsManager.setSoundType("Siren", AiClassification.AMBIENT)

        SettingsManager.resetSoundTypes()

        assertTrue(SettingsManager.soundTypes.value.isEmpty())
        assertTrue(YamnetThreeClassMapper.userOverrides.isEmpty())
        assertFalse(prefs.contains("sound_types"))
        assertEquals(AiClassification.DANGER, YamnetThreeClassMapper.mapDisplayNameToCoarse("Siren"))
    }

    @Test
    fun `쉼표나 따옴표가 든 이름도 그대로 저장하고 읽는다`() {
        // YAMNet 이름에는 쉼표, 괄호, 아포스트로피가 들어 있다.
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setSoundType("Dental drill, dentist's drill", AiClassification.DANGER)
        SettingsManager.setSoundType("Fire engine, fire truck (siren)", AiClassification.AMBIENT)
        SettingsManager.setSoundType("Name with \"quotes\"", AiClassification.SPEECH)

        assertEquals(SettingsManager.soundTypes.value, SettingsManager.loadSoundTypes(prefs))
        assertEquals(3, SettingsManager.loadSoundTypes(prefs).size)
    }

    @Test
    fun `읽을 수 없거나 틀린 저장값은 버린다`() {
        val broken = MemoryPrefs().apply { edit().putString("sound_types", "{not json").apply() }
        assertTrue("읽을 수 없는 저장값은 바꾼 것이 없는 것으로 본다", SettingsManager.loadSoundTypes(broken).isEmpty())

        val mixed = MemoryPrefs().apply {
            edit().putString(
                "sound_types",
                """{"Doorbell":"danger","Rain":"loud","Siren":"danger","Speech":7}"""
            ).apply()
        }
        assertEquals(
            "세 종류가 아닌 값, 기본 종류와 같은 값(사이렌은 기본이 위협음)은 버린다",
            mapOf("Doorbell" to AiClassification.DANGER),
            SettingsManager.loadSoundTypes(mixed)
        )
    }

    @Test
    fun `다른 설정과 키가 겹치지 않는다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        // 다른 설정을 먼저 기본값이 아닌 값으로 둔다. 기본값인 채로 두면 키가 겹쳐 값이 망가져도 읽을 때 기본값으로
        // 돌아와 이 테스트가 그대로 통과한다(가짜 프리퍼런스는 타입이 다르면 기본값을 준다).
        SettingsManager.updateAISettings(showDanger = false, colorDanger = 0xFF00FF00.toInt())
        SettingsManager.setDeveloperMode(true)
        val before = prefs.all.keys.toSet()

        SettingsManager.setSoundType("Doorbell", AiClassification.DANGER)
        assertEquals("소리 종류는 키 하나만 더한다", setOf("sound_types"), prefs.all.keys - before)

        SettingsManager.load(prefs)
        assertFalse("바꿔 둔 표시 설정이 남아 있어야 한다", SettingsManager.showDanger.value)
        assertEquals(0xFF00FF00.toInt(), SettingsManager.colorDanger.value)
        assertTrue(SettingsManager.developerMode.value)
        assertEquals(mapOf("Doorbell" to AiClassification.DANGER), SettingsManager.soundTypes.value)

        SettingsManager.resetSoundTypes()
        assertEquals("되돌리기는 그 키만 지운다", before, prefs.all.keys.toSet())
    }
}

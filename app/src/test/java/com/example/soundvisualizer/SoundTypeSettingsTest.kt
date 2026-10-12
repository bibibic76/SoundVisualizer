package com.example.soundvisualizer

import android.content.SharedPreferences
import com.example.soundvisualizer.ai.YamnetThreeClassMapper
import com.example.soundvisualizer.ai.YamnetMappingPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 분류 탭에서 바꾼 소리 종류의 저장·복원과, AI 가 읽어 갈 창구([SettingsManager.soundTypeOverride]).
 *
 * 기본 mapper는 불변이고, 추론별 정책 스냅샷에만 사용자 설정을 반영한다 (#291).
 * SettingsManager 는 싱글턴이라 테스트마다 빈 프리퍼런스로 다시 읽어 되돌린다.
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
        assertNull(SettingsManager.soundTypeOverride("Doorbell"))
    }

    @Test
    fun `고른 종류를 저장하고 창구로 바로 읽힌다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)

        SettingsManager.setSoundType("Doorbell", AiClassification.DANGER)

        val expected = mapOf("Doorbell" to AiClassification.DANGER)
        assertEquals(expected, SettingsManager.soundTypes.value)
        assertEquals("다음 호출부터 바로 반영돼야 한다", AiClassification.DANGER, SettingsManager.soundTypeOverride("Doorbell"))
        assertNull("바꾸지 않은 소리는 null", SettingsManager.soundTypeOverride("Siren"))
        assertEquals("앱을 다시 켜도 남아야 한다", expected, SettingsManager.loadSoundTypes(prefs))
    }

    @Test
    fun `저장해 둔 종류는 앱을 켤 때 다시 읽힌다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setSoundType("Siren", AiClassification.AMBIENT)

        // 프로세스가 새로 뜬 것처럼 빈 값으로 읽은 뒤 같은 프리퍼런스로 다시 읽는다.
        SettingsManager.load(MemoryPrefs())
        assertNull(SettingsManager.soundTypeOverride("Siren"))
        SettingsManager.load(prefs)

        assertEquals(AiClassification.AMBIENT, SettingsManager.soundTypeOverride("Siren"))
    }

    @Test
    fun `기본 매핑은 불변이고 사용자 선택은 정책 스냅샷에 반영된다`() {
        val names = listOf("Siren", "Doorbell", "Speech", "Rain", "Gunshot, gunfire")
        val before = names.associateWith { YamnetThreeClassMapper.mapDisplayNameToCoarse(it) }
        SettingsManager.load(MemoryPrefs())

        SettingsManager.setSoundType("Siren", AiClassification.AMBIENT)
        SettingsManager.setSoundType("Doorbell", AiClassification.DANGER)
        SettingsManager.setSoundType("Speech", AiClassification.AMBIENT)
        SettingsManager.setSoundType("Rain", AiClassification.SPEECH)

        assertEquals(before, names.associateWith { YamnetThreeClassMapper.mapDisplayNameToCoarse(it) })
        val policy = YamnetMappingPolicy.from(SettingsManager.soundTypes.value, names)
        assertEquals("ambient", policy.coarse("Siren"))
        assertEquals("danger", policy.coarse("Doorbell"))
        assertEquals("ambient", policy.coarse("Speech"))
        assertEquals("speech", policy.coarse("Rain"))
        assertEquals(before.getValue("Gunshot, gunfire"), policy.coarse("Gunshot, gunfire"))
    }

    @Test
    fun `기본 종류는 AI 의 매핑을 읽는다`() {
        for (name in listOf("Siren", "Doorbell", "Speech", "Walk, footsteps", "Thunder")) {
            assertEquals(name, YamnetThreeClassMapper.mapDisplayNameToCoarse(name), SettingsManager.defaultSoundType(name))
        }
    }

    @Test
    fun `지금 종류는 바꾼 종류가 있으면 그것이고 없으면 기본 종류다`() {
        SettingsManager.load(MemoryPrefs())
        assertEquals(AiClassification.DANGER, SettingsManager.soundType("Siren"))
        SettingsManager.setSoundType("Siren", AiClassification.AMBIENT)
        SettingsManager.setSoundType("Doorbell", AiClassification.DANGER)

        assertEquals(AiClassification.AMBIENT, SettingsManager.soundType("Siren"))
        assertEquals(AiClassification.DANGER, SettingsManager.soundType("Doorbell"))
        assertEquals(SettingsManager.defaultSoundType("Speech"), SettingsManager.soundType("Speech"))
        // AI 가 추론마다 쓰는 정책과 같은 규칙이다(#321). 둘이 어긋나면 알림이 AI 판정과 다른 소리 이름을 적는다(#325).
        val names = listOf("Siren", "Doorbell", "Speech", "Rain")
        val policy = YamnetMappingPolicy.from(SettingsManager.soundTypes.value, names)
        for (name in names) assertEquals(name, policy.coarse(name), SettingsManager.soundType(name))
    }

    @Test
    fun `기본 종류를 고르면 바꾼 것을 지운다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setSoundType("Doorbell", AiClassification.SPEECH)
        SettingsManager.setSoundType("Doorbell", SettingsManager.defaultSoundType("Doorbell"))

        assertTrue(SettingsManager.soundTypes.value.isEmpty())
        assertNull(SettingsManager.soundTypeOverride("Doorbell"))
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
    fun `모두 되돌리면 저장값과 창구를 비운다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setSoundType("Doorbell", AiClassification.DANGER)
        SettingsManager.setSoundType("Siren", AiClassification.AMBIENT)

        SettingsManager.resetSoundTypes()

        assertTrue(SettingsManager.soundTypes.value.isEmpty())
        assertNull(SettingsManager.soundTypeOverride("Doorbell"))
        assertNull(SettingsManager.soundTypeOverride("Siren"))
        assertFalse(prefs.contains("sound_types"))
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
        assertEquals(AiClassification.AMBIENT, SettingsManager.soundTypeOverride("Fire engine, fire truck (siren)"))
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

    @Test
    fun `여러 소리를 한 번에 바꾸면 흐름에 한 번 싣고 한 번 저장한다`() {
        // 분류 탭이 묶음째 고를 때(#349). AI 가 반쯤 바뀐 맵을 읽지 않게 한 번에 바꾼다.
        val prefs = CountingPrefs()
        SettingsManager.load(prefs)
        prefs.edits = 0

        val emissions = emissionsDuring { SettingsManager.setSoundTypes(SIRENS, AiClassification.AMBIENT) }

        assertEquals("흐름에 실은 횟수", 1, emissions)
        assertEquals("저장한 횟수", 1, prefs.edits)
        val expected = SIRENS.associateWith { AiClassification.AMBIENT }
        assertEquals(expected, SettingsManager.soundTypes.value)
        assertEquals("앱을 다시 켜도 남아야 한다", expected, SettingsManager.loadSoundTypes(prefs))
    }

    @Test
    fun `묶음에서 기본 종류를 고르면 그 소리들의 바꾼 것을 지우고 다른 소리는 그대로 둔다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setSoundType("Doorbell", AiClassification.DANGER)
        SettingsManager.setSoundTypes(SIRENS, AiClassification.AMBIENT)
        SettingsManager.setSoundType("Civil defense siren", AiClassification.SPEECH)

        SettingsManager.setSoundTypes(SIRENS, SettingsManager.defaultSoundType("Siren"))

        val expected = mapOf("Doorbell" to AiClassification.DANGER)
        assertEquals(expected, SettingsManager.soundTypes.value)
        assertEquals(expected, SettingsManager.loadSoundTypes(prefs))
        assertNull(SettingsManager.soundTypeOverride("Civil defense siren"))
    }

    @Test
    fun `세 종류가 아닌 값이나 바뀌는 것이 없는 호출은 흐름도 저장도 건드리지 않는다`() {
        val prefs = CountingPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setSoundTypes(SIRENS, AiClassification.AMBIENT)
        prefs.edits = 0

        val emissions = emissionsDuring {
            SettingsManager.setSoundTypes(SIRENS, "loud")
            SettingsManager.setSoundTypes(SIRENS, AiClassification.AMBIENT)
            SettingsManager.setSoundTypes(emptyList(), AiClassification.DANGER)
            SettingsManager.setSoundTypes(listOf("Rain", "Wind"), SettingsManager.defaultSoundType("Rain"))
        }

        assertEquals(0, emissions)
        assertEquals(0, prefs.edits)
        assertEquals(SIRENS.associateWith { AiClassification.AMBIENT }, SettingsManager.soundTypes.value)
    }

    @Test
    fun `한 번에 바꾼 것은 하나씩 바꾼 것과 같고 AI 의 정책 스냅샷도 모두 본다`() {
        val names = listOf("Doorbell", "Ding-dong", "Knock", "Tap")
        SettingsManager.load(MemoryPrefs())
        names.forEach { SettingsManager.setSoundType(it, AiClassification.DANGER) }
        val oneByOne = SettingsManager.soundTypes.value

        SettingsManager.load(MemoryPrefs())
        SettingsManager.setSoundTypes(names, AiClassification.DANGER)

        assertEquals(oneByOne, SettingsManager.soundTypes.value)
        val policy = YamnetMappingPolicy.from(SettingsManager.soundTypes.value, names)
        for (name in names) assertEquals(name, AiClassification.DANGER, policy.coarse(name))
    }

    @Test
    fun `여러 소리를 한 번에 바꿔도 키는 소리 종류 하나만 더한다`() {
        val prefs = MemoryPrefs()
        SettingsManager.load(prefs)
        SettingsManager.setDeveloperMode(true)
        val before = prefs.all.toMap()

        SettingsManager.setSoundTypes(SIRENS + "Doorbell", AiClassification.SPEECH)

        assertEquals(setOf("sound_types"), prefs.all.keys - before.keys)
        assertEquals("다른 키의 값은 그대로다", before, prefs.all.filterKeys { it != "sound_types" })
    }

    /**
     * [block] 이 [SettingsManager.soundTypes] 에 새 값을 실은 횟수. 처음 값은 세지 않는다.
     * 막히지 않는 디스패처로 모아서, 값이 바뀌는 그 자리에서 센다. 흐름은 같은 값을 다시 싣지 않는다.
     */
    private fun emissionsDuring(block: () -> Unit): Int = runBlocking {
        var count = 0
        val job = launch(Dispatchers.Unconfined) { SettingsManager.soundTypes.drop(1).collect { count++ } }
        block()
        job.cancel()
        count
    }

    private companion object {
        val SIRENS = listOf("Siren", "Ambulance (siren)", "Fire engine, fire truck (siren)", "Police car (siren)", "Civil defense siren")
    }
}

/** 저장한 횟수를 세는 프리퍼런스. 값은 [MemoryPrefs] 에 맡기고 에디터를 연 횟수(`edit { }` 한 번이 한 번)만 센다. */
private class CountingPrefs(private val memory: MemoryPrefs = MemoryPrefs()) : SharedPreferences by memory {
    var edits = 0

    override fun edit(): SharedPreferences.Editor {
        edits++
        return memory.edit()
    }
}

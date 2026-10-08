package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 분류 탭의 카드 나누기(규칙 B), 묶음 상태, 미리보기, 덮어쓰는 수(#349).
 *
 * 지금 매핑으로는 어떤 매핑에서도 지켜야 할 규칙만 본다. 묶음마다의 기본 종류나 구역·카드의 수는 일부러 못박지 않는다.
 * 그것은 AI 의 매핑(가람 님 담당)이 정하고, 매핑이 바뀌어도 화면이 따라가야 하기 때문이다. 나머지 테스트는 기본 종류를
 * 정해 둔 가짜 매핑을 써서, 매핑이 바뀌어도 흔들리지 않게 한다.
 */
class SoundGroupsTest {

    /** 모든 소리의 기본 종류가 환경음인 목록. 매핑과 상관없이 묶음 하나가 카드 하나다. */
    private val plain = SoundGroupFixtures.entries { AiClassification.AMBIENT }
    private val plainSections = SoundGroups.sections(plain)

    private fun entry(name: String, list: List<SoundEntry> = plain) = list.first { it.name == name }
    private fun names(list: List<SoundEntry>) = list.map { it.name }

    private fun card(sections: List<SoundSection>, group: SoundGroup, type: String = AiClassification.AMBIENT): SoundCard =
        sections.single { it.type == type }.cards.single { it.group == group }

    /** 어떤 매핑에서도 지켜야 할 것. */
    private fun assertCardRules(sections: List<SoundSection>, entries: List<SoundEntry>) {
        val types = sections.map { it.type }
        assertEquals("구역은 위협음, 대화음, 환경음 순서다", SoundGroups.SECTION_ORDER.filter { it in types }, types)
        for (section in sections) {
            assertTrue("빈 구역이 있다", section.cards.isNotEmpty())
            for (card in section.cards) {
                assertEquals(section.type, card.defaultType)
                assertTrue("${card.id} 에 기본 종류가 섞였다", card.members.all { it.defaultType == card.defaultType })
                assertTrue("${card.id} 의 묶음이 섞였다", card.members.all { it.group == card.group })
                assertEquals("${card.id} 의 소리는 표 순서다", card.members.sortedBy { it.order }, card.members)
            }
            val groups = section.cards.mapNotNull { it.group }
            assertEquals("구역 안의 카드는 묶음 순서다", groups.sorted(), groups)
            assertEquals("구역 안에서 묶음 하나는 카드 하나다", groups.toSet().size, groups.size)
            val firstSingle = section.cards.indexOfFirst { it.group == null }
            if (firstSingle >= 0) {
                assertTrue("표에 없는 소리는 구역 끝에 온다", section.cards.drop(firstSingle).all { it.group == null })
            }
        }
        val shown = sections.flatMap { s -> s.cards.flatMap { it.members } }.map { it.index }
        assertEquals("모든 소리가 한 번씩 카드에 든다", entries.map { it.index }.sorted(), shown.sorted())
    }

    @Test
    fun `지금 매핑으로 나눠도 카드마다 기본 종류가 하나다`() {
        val entries = SoundGroupFixtures.entries()
        assertCardRules(SoundGroups.sections(entries), entries)
        assertEquals(
            listOf(AiClassification.DANGER, AiClassification.SPEECH, AiClassification.AMBIENT),
            SoundGroups.SECTION_ORDER
        )
    }

    @Test
    fun `매핑이 바뀐 소리는 새 구역에 같은 묶음 이름으로 따로 보인다`() {
        // 매핑이 노크를 위협음으로, 사이렌을 대화음으로 옮겼다고 친다. 표는 고치지 않는다.
        val flipped = mapOf("Knock" to AiClassification.DANGER, "Siren" to AiClassification.SPEECH)
        val drifted = SoundGroupFixtures.entries { flipped[it] ?: AiClassification.AMBIENT }
        val sections = SoundGroups.sections(drifted)
        assertCardRules(sections, drifted)
        assertEquals(listOf(AiClassification.DANGER, AiClassification.SPEECH, AiClassification.AMBIENT), sections.map { it.type })

        val knock = card(sections, SoundGroup.DOORBELL, AiClassification.DANGER)
        assertEquals(listOf("Knock"), names(knock.members))
        assertEquals("묶음 이름은 그대로 초인종이다", "Doorbell", knock.titleEntry?.name)
        assertEquals(listOf("Doorbell", "Ding-dong", "Tap"), names(card(sections, SoundGroup.DOORBELL).members))

        val siren = card(sections, SoundGroup.SIRENS, AiClassification.SPEECH)
        assertEquals(listOf("Siren"), names(siren.members))
        val otherSirens = card(sections, SoundGroup.SIRENS)
        assertEquals(4, otherSirens.members.size)
        assertEquals("다른 구역으로 간 사이렌의 이름을 그대로 쓴다", "Siren", otherSirens.titleEntry?.name)
        assertNotEquals("한 묶음이 두 구역에 나뉘어도 카드 이름은 겹치지 않는다", siren.id, otherSirens.id)
    }

    @Test
    fun `표에 없는 소리는 그 소리 하나로 구역 끝에 온다`() {
        // 모델이 바뀌어 표에 없는 소리가 생겼다고 친다. 하나는 표에서 빠졌고, 하나는 처음 보는 이름이다.
        val table = SoundGroupFixtures.table - "Doorbell"
        val list = SoundCatalog.fromNames(SoundGroupFixtures.names + "Brand new sound", table) { AiClassification.AMBIENT }
        val sections = SoundGroups.sections(list)
        assertCardRules(sections, list)

        val singles = sections.single().cards.takeLast(2)
        assertEquals(listOf("Doorbell", "Brand new sound"), singles.map { it.members.single().name })
        for (single in singles) {
            assertNull(single.group)
            assertEquals("제 이름이 카드 이름이다", single.members.single(), single.titleEntry)
            assertEquals(0, single.titleRes)
            assertEquals(GroupPreview(emptyList(), 0), SoundGroups.preview(single))
        }
        val doorbell = card(sections, SoundGroup.DOORBELL)
        assertEquals(listOf("Ding-dong", "Knock", "Tap"), names(doorbell.members))
        assertEquals("표에서 빠져도 모델에 있는 소리의 이름은 쓴다", "Doorbell", doorbell.titleEntry?.name)
    }

    @Test
    fun `이름인 소리가 모델에서 사라지면 맨 앞 소리의 이름을 쓴다`() {
        val names = SoundGroupFixtures.names.map { if (it == "Doorbell") "Door bell" else it }
        val sections = SoundGroups.sections(SoundCatalog.fromNames(names, SoundGroupFixtures.table) { AiClassification.AMBIENT })
        assertEquals("Ding-dong", card(sections, SoundGroup.DOORBELL).titleEntry?.name)
    }

    @Test
    fun `카드 이름은 새로 지은 문구이거나 소리 이름이다`() {
        val horns = card(plainSections, SoundGroup.HORNS)
        assertEquals(R.string.sound_group_horns, horns.titleRes)
        assertNull(horns.titleEntry)
        val sirens = card(plainSections, SoundGroup.SIRENS)
        assertEquals(0, sirens.titleRes)
        assertEquals(entry("Siren"), sirens.titleEntry)
        assertEquals("sirens:ambient", sirens.id)
    }

    @Test
    fun `묶음 상태는 소리마다의 지금 종류로 정한다`() {
        val sirens = card(plainSections, SoundGroup.SIRENS)
        assertEquals("기본 그대로", GroupState.Uniform(AiClassification.AMBIENT), SoundGroups.state(sirens, emptyMap()))

        val whole = sirens.members.associate { it.name to AiClassification.DANGER }
        assertEquals("묶음째 바꾼 것", GroupState.Uniform(AiClassification.DANGER), SoundGroups.state(sirens, whole))

        val one = mapOf("Civil defense siren" to AiClassification.DANGER)
        assertEquals("하나만 바꾼 것", GroupState.Mixed, SoundGroups.state(sirens, one))
        assertEquals(
            "바꿔도 모두 같은 종류면 섞인 것이 아니다",
            GroupState.Uniform(AiClassification.SPEECH),
            SoundGroups.state(sirens, sirens.members.associate { it.name to AiClassification.SPEECH })
        )
    }

    @Test
    fun `섞인 카드가 있는지는 모든 카드를 본다`() {
        assertFalse(SoundGroups.anyMixed(plainSections, emptyMap()))
        assertFalse("소리 하나뿐인 묶음은 섞일 수 없다", SoundGroups.anyMixed(plainSections, mapOf("Chainsaw" to AiClassification.DANGER)))
        val wholeSirens = card(plainSections, SoundGroup.SIRENS).members.associate { it.name to AiClassification.DANGER }
        assertFalse("묶음째 바꾼 것은 섞인 것이 아니다", SoundGroups.anyMixed(plainSections, wholeSirens))
        assertTrue(SoundGroups.anyMixed(plainSections, wholeSirens + ("Doorbell" to AiClassification.DANGER)))
        assertTrue(SoundGroups.anyMixed(plainSections, mapOf("Singing bowl" to AiClassification.SPEECH)))
    }

    @Test
    fun `미리보기는 이름인 소리를 빼고 세 개까지 보이고 나머지는 센다`() {
        assertEquals(
            GroupPreview(listOf(entry("Ambulance (siren)"), entry("Fire engine, fire truck (siren)"), entry("Police car (siren)")), 1),
            SoundGroups.preview(card(plainSections, SoundGroup.SIRENS))
        )
        assertEquals(
            "새로 지은 이름을 쓰는 묶음은 맨 앞 소리부터 보인다",
            GroupPreview(listOf(entry("Vehicle horn, car horn, honking"), entry("Air horn, truck horn"), entry("Car alarm")), 0),
            SoundGroups.preview(card(plainSections, SoundGroup.HORNS))
        )
        val music = card(plainSections, SoundGroup.MUSIC)
        assertEquals(music.members.size - 1 - SoundGroups.PREVIEW_SIZE, SoundGroups.preview(music).more)
    }

    @Test
    fun `이름인 소리 하나뿐인 묶음은 미리보기가 없다`() {
        assertEquals(GroupPreview(emptyList(), 0), SoundGroups.preview(card(plainSections, SoundGroup.CHAINSAW)))
        assertEquals(GroupPreview(emptyList(), 0), SoundGroups.preview(card(plainSections, SoundGroup.SLAM)))
    }

    @Test
    fun `찾은 소리는 미리보기 앞에 온다`() {
        val doorbell = card(plainSections, SoundGroup.DOORBELL)
        assertEquals(
            GroupPreview(listOf(entry("Knock"), entry("Ding-dong"), entry("Tap")), 0),
            SoundGroups.preview(doorbell, listOf(entry("Knock")))
        )
        // 묶음 이름인 소리는 찾아도 미리보기에 다시 넣지 않는다.
        val music = card(plainSections, SoundGroup.MUSIC)
        val preview = SoundGroups.preview(music, listOf(entry("Music"), entry("Singing bowl"), entry("Piano")))
        assertEquals(listOf(entry("Piano"), entry("Singing bowl"), entry("Musical instrument")), preview.names)
        assertEquals(music.members.size - 1 - SoundGroups.PREVIEW_SIZE, preview.more)
    }

    @Test
    fun `덮어쓰는 수는 고른 종류와 지금 종류가 다른 소리다`() {
        val sirens = card(plainSections, SoundGroup.SIRENS)
        val one = mapOf("Civil defense siren" to AiClassification.DANGER)
        assertEquals("기본을 고르면 하나씩 바꾼 소리만 바뀐다", 1, SoundGroups.overwriteCount(sirens, one, AiClassification.AMBIENT))
        assertEquals(4, SoundGroups.overwriteCount(sirens, one, AiClassification.DANGER))
        assertEquals(5, SoundGroups.overwriteCount(sirens, one, AiClassification.SPEECH))
        assertEquals(0, SoundGroups.overwriteCount(sirens, emptyMap(), AiClassification.AMBIENT))
    }
}

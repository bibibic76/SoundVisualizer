package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 묶음 화면의 찾기와 거르기(#349). 기본 모드는 카드로, 고급 모드는 같은 결과를 줄로 편다.
 *
 * 보이는 이름은 실제 한국어 문구(values-ko)다. 기본 종류는 매핑과 상관없이 정해 둔다(사이렌·경보음 묶음만 위협음, 나머지는
 * 환경음). 매핑이 바뀌어도 이 테스트가 흔들리지 않게 하려는 것이다.
 */
class SoundGroupSearchTest {

    private val dangerGroups = setOf(SoundGroup.SIRENS, SoundGroup.ALARMS)
    private val entries = SoundGroupFixtures.entries { name ->
        if (SoundGroupFixtures.table[name]?.group in dangerGroups) AiClassification.DANGER else AiClassification.AMBIENT
    }
    private val sections = SoundGroups.sections(entries)

    private val ko = SoundGroupFixtures.strings("values-ko")

    /** 앱이 한국어일 때의 찾기 값. 분류 탭처럼 보이는 이름과 모델 이름을 잇는다. */
    private val koKeys = entries.map { SoundCatalog.searchKey(ko.getValue("sound_name_${it.index}"), it.name) }
    private val koTitles = SoundGroup.entries.filter { it.titleRes != 0 }
        .associateWith { SoundCatalog.searchKey(ko.getValue("sound_group_${it.id}"), "") }

    private fun search(query: String, filter: SoundFilter = SoundFilter.ALL, overrides: Map<String, String> = emptyMap()) =
        SoundGroups.filter(sections, koKeys, koTitles, overrides, query, filter)

    private fun entry(name: String) = entries.first { it.name == name }
    private fun names(list: List<SoundEntry>) = list.map { it.name }
    private fun List<ShownSection>.groups() = flatMap { section -> section.cards.map { it.card.group } }
    private fun List<ShownSection>.cardOf(group: SoundGroup) = flatMap { it.cards }.single { it.card.group == group }

    @Test
    fun `찾는 말과 거르기가 없으면 모든 카드와 소리가 남는다`() {
        val all = search("  ")
        assertEquals(sections.map { it.type }, all.map { it.type })
        assertEquals(sections.flatMap { it.cards }, all.flatMap { section -> section.cards.map { it.card } })
        assertTrue(all.flatMap { it.cards }.all { it.shown == it.card.members })
    }

    @Test
    fun `묶음 이름이 맞으면 묶음 전체가 남는다`() {
        // 사이렌 묶음의 이름은 '사이렌' 소리의 이름이다.
        val sirens = search("사이렌")
        assertEquals(listOf(SoundGroup.SIRENS), sirens.groups())
        assertEquals(sirens.cardOf(SoundGroup.SIRENS).card.members, sirens.cardOf(SoundGroup.SIRENS).shown)
        // 새로 지은 묶음 이름으로도 찾는다. '부엌' 은 소리 이름에는 없는 말이다.
        val kitchen = search("부엌")
        assertEquals(listOf(SoundGroup.KITCHEN), kitchen.groups())
        assertEquals(kitchen.cardOf(SoundGroup.KITCHEN).card.members, kitchen.cardOf(SoundGroup.KITCHEN).shown)
    }

    @Test
    fun `소리 이름이 맞으면 묶음이 남고 맞은 소리가 미리보기 앞에 온다`() {
        // '노크' 를 찾으면 환경음 › 초인종 묶음이 '노크 소리 • 딩동 • 톡톡 두드리는 소리' 로 보인다. 빈 구역은 숨는다.
        val result = search("노크")
        assertEquals(listOf(AiClassification.AMBIENT), result.map { it.type })
        assertEquals(listOf(SoundGroup.DOORBELL), result.groups())
        val doorbell = result.cardOf(SoundGroup.DOORBELL)
        assertEquals(listOf("Knock"), names(doorbell.shown))
        val preview = SoundGroups.preview(doorbell.card, doorbell.shown)
        assertEquals(listOf("노크 소리", "딩동", "톡톡 두드리는 소리"), preview.names.map { ko.getValue("sound_name_${it.index}") })
        assertEquals(0, preview.more)
    }

    @Test
    fun `묶음 이름에 맞은 묶음과 소리 이름에 맞은 묶음이 함께 남는다`() {
        // '경적' 은 경적 묶음의 이름에도, 다른 묶음의 기차 경적·안개 경적·짧은 경적에도 들어 있다.
        val result = search("경적")
        assertEquals(listOf(SoundGroup.HORNS, SoundGroup.VEHICLE_WARNINGS, SoundGroup.VEHICLE), result.groups())
        assertEquals(result.cardOf(SoundGroup.HORNS).card.members, result.cardOf(SoundGroup.HORNS).shown)
        assertEquals(listOf("Train horn", "Foghorn"), names(result.cardOf(SoundGroup.VEHICLE_WARNINGS).shown))
        assertEquals(listOf("Toot"), names(result.cardOf(SoundGroup.VEHICLE).shown))
    }

    @Test
    fun `번역된 이름과 영어 이름 어느 쪽으로도 찾는다`() {
        // 앱이 한국어여도 모델 이름(영어)으로 찾는다.
        val knock = search("knock")
        assertEquals(listOf("Knock"), names(knock.cardOf(SoundGroup.DOORBELL).shown))
        assertEquals(listOf("Engine knocking"), names(knock.cardOf(SoundGroup.VEHICLE).shown))
        // 앱이 영어면 새로 지은 묶음 이름도 영어 문구로 찾는다.
        val en = SoundGroupFixtures.strings("values")
        val enKeys = entries.map { SoundCatalog.searchKey(it.name, it.name) }
        val enTitles = SoundGroup.entries.filter { it.titleRes != 0 }
            .associateWith { SoundCatalog.searchKey(en.getValue("sound_group_${it.id}"), "") }
        val kitchen = SoundGroups.filter(sections, enKeys, enTitles, emptyMap(), "Kitchen", SoundFilter.ALL)
        assertEquals(listOf(SoundGroup.KITCHEN), kitchen.groups())
    }

    @Test
    fun `보이는 이름과 모델 이름을 이어 붙인 곳에서는 맞지 않는다`() {
        assertTrue(search("노크 소리knock").isEmpty())
        assertTrue(search("zzzz").isEmpty())
    }

    @Test
    fun `종류 거르기는 지금 종류를 따르고 바꾼 소리도 제 구역에 있다`() {
        val overrides = mapOf("Doorbell" to AiClassification.DANGER, "Siren" to AiClassification.AMBIENT)

        val danger = search("", SoundFilter.DANGER, overrides)
        assertEquals(listOf(SoundGroup.ALARMS, SoundGroup.SIRENS, SoundGroup.DOORBELL), danger.groups())
        assertFalse("Siren" in names(danger.cardOf(SoundGroup.SIRENS).shown))
        assertEquals(4, danger.cardOf(SoundGroup.SIRENS).shown.size)
        assertEquals(listOf("Doorbell"), names(danger.cardOf(SoundGroup.DOORBELL).shown))
        assertEquals(
            "구역은 기본 종류라 위협음으로 바꾼 초인종도 환경음 구역에 있다",
            listOf(AiClassification.DANGER, AiClassification.AMBIENT),
            danger.map { it.type }
        )

        val ambient = search("", SoundFilter.AMBIENT, overrides)
        assertEquals(listOf("Siren"), names(ambient.cardOf(SoundGroup.SIRENS).shown))
        assertEquals(listOf("Ding-dong", "Knock", "Tap"), names(ambient.cardOf(SoundGroup.DOORBELL).shown))
        assertTrue("대화음인 소리가 없다", search("", SoundFilter.SPEECH, overrides).isEmpty())
    }

    @Test
    fun `바꾼 소리 거르기는 바꾼 소리가 있는 카드만 남긴다`() {
        val overrides = mapOf("Doorbell" to AiClassification.DANGER, "Siren" to AiClassification.AMBIENT)
        val changed = search("", SoundFilter.CHANGED, overrides)
        assertEquals(listOf(SoundGroup.SIRENS, SoundGroup.DOORBELL), changed.groups())
        assertEquals(listOf("Siren"), names(changed.cardOf(SoundGroup.SIRENS).shown))
        assertEquals(listOf("Doorbell"), names(changed.cardOf(SoundGroup.DOORBELL).shown))
        assertEquals("찾는 말과 함께 쓴다", listOf(SoundGroup.DOORBELL), search("초인종", SoundFilter.CHANGED, overrides).groups())
        assertTrue(search("", SoundFilter.CHANGED).isEmpty())
    }

    @Test
    fun `한글은 치는 중의 모든 모양에서 묶음이 끊기지 않는다`() {
        // SoundCatalogTest 와 같은 경우를 묶음 찾기로 본다. 보이는 이름은 영어 그대로 두고 사이렌만 다른 말로 보인다고 친다.
        val typing = mapOf(
            "사이렌" to listOf("ㅅ", "사", "상", "사이", "사일", "사이레", "사이렌"),
            "화재 경보음" to listOf("ㅎ", "호", "화", "홪", "화재", "화잭", "화재 겨", "화재 경", "화재 경보", "화재 경봉", "화재 경보음"),
            "닭 울음소리" to listOf("ㄷ", "다", "달", "닭", "닭 울")
        )
        typing.forEach { (label, steps) ->
            val keys = entries.map { SoundCatalog.searchKey(if (it.name == "Siren") label else it.name, it.name) }
            steps.forEach { step ->
                val sirens = SoundGroups.filter(sections, keys, emptyMap(), emptyMap(), step, SoundFilter.ALL)
                    .flatMap { it.cards }
                    .singleOrNull { it.card.group == SoundGroup.SIRENS }
                assertEquals("\"$label\" 을 치는 중 \"$step\" 에서 사이렌 묶음이 통째로 남아야 한다", 5, sirens?.shown?.size)
            }
        }
    }

    @Test
    fun `고급 모드는 맞는 줄과 그 위의 묶음과 구역 머리만 보인다`() {
        val knock = SoundGroups.advancedItems(search("노크"))
        assertEquals(listOf("s:ambient", "g:doorbell:ambient", "r:${entry("Knock").index}"), knock.map { it.key })

        // 묶음 이름이 맞으면 그 묶음의 줄이 모두 보인다.
        val doorbell = SoundGroups.advancedItems(search("초인종"))
        assertEquals(
            listOf("s:ambient", "g:doorbell:ambient") +
                listOf("Doorbell", "Ding-dong", "Knock", "Tap").map { "r:${entry(it).index}" },
            doorbell.map { it.key }
        )

        // 거르기에 맞는 줄이 없는 묶음은 머리도 없다.
        val danger = SoundGroups.advancedItems(search("", SoundFilter.DANGER, mapOf("Doorbell" to AiClassification.DANGER)))
        assertEquals(
            listOf("s:ambient", "g:doorbell:ambient", "r:${entry("Doorbell").index}"),
            danger.dropWhile { it.key != "s:ambient" }.map { it.key }
        )
    }

    @Test
    fun `고급 모드에는 모든 소리가 한 번씩 보이고 키가 겹치지 않는다`() {
        val items = SoundGroups.advancedItems(search(""))
        assertEquals(entries.map { it.index }.sorted(), items.filterIsInstance<AdvancedItem.Row>().map { it.entry.index }.sorted())
        assertEquals(sections.sumOf { it.cards.size }, items.count { it is AdvancedItem.GroupHeading })
        assertEquals(sections.size, items.count { it is AdvancedItem.SectionHeading })
        assertEquals("목록의 키가 겹친다", items.size, items.map { it.key }.toSet().size)
    }
}

package com.example.soundvisualizer

import androidx.annotation.StringRes
import java.io.InputStream
import java.util.Locale

/**
 * 분류 탭의 소리 묶음(#349). 항목 순서가 화면에 보이는 순서다.
 *
 * 어느 소리가 어느 묶음인지는 여기가 아니라 assets/sound_groups.tsv 에 둔다. 그 표는 AudioSet 온톨로지(CC BY-SA 4.0)의
 * 갈래를 읽고 만든 것이라 같은 라이선스로 따로 내놓고, 이 코드와 새로 지은 묶음 이름은 앱과 같은 Apache-2.0 으로 둔다.
 * 표의 group_id 는 항목 이름을 소문자로 쓴 것([id])이다.
 *
 * 묶음이 어느 구역에 보이는지는 여기서 정하지 않는다. 소리마다의 기본 종류(AI 의 매핑)로 그때그때 나눈다
 * ([SoundGroups.sections]). 아래 주석의 구역은 지금 매핑에서 그렇다는 뜻이다.
 *
 * @param titleRes 새로 지은 묶음 이름. 0 이면 [titleSound] 의 이름을 쓴다.
 * @param titleSound 이름(sound_names)이 그대로 묶음 이름인 소리의 모델 이름. 이미 모든 언어로 번역된 이름이라
 *   묶음 이름을 따로 번역하지 않아도 된다.
 */
enum class SoundGroup(@StringRes val titleRes: Int = 0, val titleSound: String? = null) {
    // 위협음
    ALARMS(titleSound = "Alarm"),
    SIRENS(titleSound = "Siren"),
    HORNS(R.string.sound_group_horns),
    VEHICLE_WARNINGS(R.string.sound_group_vehicle_warnings),
    BREAKING(R.string.sound_group_breaking),
    SLAM(titleSound = "Slam"),
    GUNSHOTS(titleSound = "Gunshot, gunfire"),
    EXPLOSIONS(titleSound = "Explosion"),
    FIREWORKS(titleSound = "Fireworks"),
    THUNDER(titleSound = "Thunder"),
    CHAINSAW(titleSound = "Chainsaw"),

    // 대화음
    TALKING(titleSound = "Speech"),
    DISTRESS(R.string.sound_group_distress),
    CROWD(titleSound = "Crowd"),
    LAUGHTER(titleSound = "Laughter"),
    SINGING(titleSound = "Singing"),

    // 환경음. 주의를 끄는 소리(초인종·전화·알람·아기·불·개)부터 둔다.
    DOORBELL(titleSound = "Doorbell"),
    PHONE(titleSound = "Telephone"),
    SIGNALS(R.string.sound_group_signals),
    BABY(titleSound = "Baby cry, infant cry"),
    FIRE(titleSound = "Fire"),
    DOG(titleSound = "Dog"),
    VOICES(R.string.sound_group_voices),
    FOOTSTEPS(titleSound = "Walk, footsteps"),
    DOOR(titleSound = "Door"),
    WATER(R.string.sound_group_water),
    KITCHEN(R.string.sound_group_kitchen),
    APPLIANCES(R.string.sound_group_appliances),
    TOOLS(R.string.sound_group_tools),
    VEHICLE(titleSound = "Vehicle"),
    BELL(titleSound = "Bell"),
    ANIMAL(titleSound = "Animal"),
    NATURE(R.string.sound_group_nature),
    BODY(R.string.sound_group_body),
    OBJECTS(R.string.sound_group_objects),
    MUSIC(titleSound = "Music"),
    BACKGROUND(R.string.sound_group_background);

    /** 표(assets/sound_groups.tsv)의 group_id. */
    val id: String = name.lowercase(Locale.ROOT)

    companion object {
        private val BY_ID = entries.associateBy { it.id }

        /** 표의 group_id 로 찾는다. 모르는 이름이면 null. */
        fun fromId(id: String): SoundGroup? = BY_ID[id]
    }
}

/** 묶음 표의 한 줄. [order] 는 머리글을 뺀 줄 순서(0부터)이고, 묶음 화면에서 소리가 보이는 순서다. */
data class SoundGroupRow(val group: SoundGroup, val order: Int)

/**
 * 분류 탭의 카드 하나. 같은 묶음이면서 기본 종류가 같은 소리들이다(규칙 B).
 *
 * 지금은 묶음마다 기본 종류가 하나라 카드 하나가 곧 묶음 하나다. AI 의 매핑이 바뀌어 한 소리의 기본 종류가 달라지면, 그
 * 소리는 새 종류의 구역에 같은 묶음 이름의 카드로 따로 보인다. 그래서 한 카드의 소리는 늘 기본 종류가 같고, 카드에서
 * 고르는 "(기본)" 이 모든 소리에 맞다. 섞인 카드([GroupState.Mixed])도 하나씩 바꾼 소리가 있을 때만 생긴다.
 *
 * @param group 표에 없는 소리면 null 이고, 그 소리 하나가 카드가 된다.
 * @param members 표의 순서대로.
 * @param titleEntry 이름(sound_names)이 곧 카드 이름인 소리. 새로 지은 이름을 쓰는 묶음이면 null 이다. 매핑이 바뀌어
 *   묶음이 두 구역으로 나뉘면 다른 구역의 카드에 들어 있을 수 있다.
 */
data class SoundCard(
    val group: SoundGroup?,
    val defaultType: String,
    val members: List<SoundEntry>,
    val titleEntry: SoundEntry?
) {
    /** 새로 지은 카드 이름. 0 이면 [titleEntry] 의 이름이 카드 이름이다. */
    @get:StringRes
    val titleRes: Int get() = if (titleEntry == null) group?.titleRes ?: 0 else 0

    /** 목록에서 카드를 가리키는 이름. 한 묶음이 두 구역에 나뉘어도 겹치지 않게 기본 종류를 붙인다. */
    val id: String get() = "${group?.id ?: "sound_${members.first().index}"}:$defaultType"
}

/** 구역 하나. [type] 이 기본 종류인 카드들이다. */
data class SoundSection(val type: String, val cards: List<SoundCard>)

/** 카드의 지금 종류. 저장하지 않고 소리마다의 종류에서 그때그때 정한다. */
sealed interface GroupState {

    /** 모든 소리가 [type] 이다. 카드의 기본 종류와 다르면 묶음째 바꾼 것이다. */
    data class Uniform(val type: String) : GroupState

    /** 소리마다 종류가 다르다. 카드의 기본 종류는 하나라(규칙 B) 하나씩 바꾼 소리가 있다는 뜻이다. */
    data object Mixed : GroupState
}

/** 카드 이름 밑의 작은 글씨. [names] 는 [SoundGroups.PREVIEW_SIZE] 개까지이고, [more] 는 못 보인 소리의 수("+N")다. */
data class GroupPreview(val names: List<SoundEntry>, val more: Int)

/**
 * 찾는 말과 거르기에 남은 카드.
 *
 * @param shown 찾는 말과 거르기에 맞는 소리(표 순서). 기본 모드는 이 소리를 미리보기 앞에 두고, 고급 모드는 이 소리만
 *   줄로 보인다.
 */
data class ShownCard(val card: SoundCard, val shown: List<SoundEntry>)

/** 남은 카드가 있는 구역. 남은 카드가 없는 구역은 목록에 넣지 않는다. */
data class ShownSection(val type: String, val cards: List<ShownCard>)

/** 고급 모드 목록의 한 칸. [key] 는 목록의 키로, 칸의 종류마다 앞 글자를 달리해 서로 겹치지 않는다. */
sealed interface AdvancedItem {
    val key: String

    /** 구역 머리. 그 아래 묶음들의 기본 종류다. */
    data class SectionHeading(val type: String) : AdvancedItem {
        override val key: String get() = "s:$type"
    }

    /** 묶음 머리. 그 아래 줄들이 이 카드의 소리다. */
    data class GroupHeading(val card: SoundCard) : AdvancedItem {
        override val key: String get() = "g:${card.id}"
    }

    /** 소리 한 줄. */
    data class Row(val entry: SoundEntry) : AdvancedItem {
        override val key: String get() = "r:${entry.index}"
    }
}

/**
 * 분류 탭의 묶음 화면에 필요한 계산. 기기 없이 테스트할 수 있게 Compose 와 Context 를 모른다.
 * 묶음 상태는 저장하지 않는다. 저장하는 것은 지금처럼 소리마다의 종류([SettingsManager.soundTypes])뿐이다.
 */
object SoundGroups {

    /** 미리보기에 이름을 보이는 소리 수. 나머지는 "+N" 으로 센다. */
    const val PREVIEW_SIZE = 3

    /** 구역 순서. 작고 중요한 위협음을 맨 위에, 긴 환경음을 맨 아래에 둔다. */
    val SECTION_ORDER = listOf(AiClassification.DANGER, AiClassification.SPEECH, AiClassification.AMBIENT)

    private val BY_ORDER = compareBy<SoundEntry>({ it.order }, { it.index })

    /** 표에 없는 소리의 카드는 표의 묶음 뒤에 온다. */
    private val CARD_ORDER = compareBy<SoundCard>({ it.group?.ordinal ?: Int.MAX_VALUE }, { it.members.first().order })

    /**
     * 묶음 표(assets/sound_groups.tsv)를 읽는다. 키는 소리 이름(display_name)이다.
     *
     * '#' 로 시작하는 줄(라이선스 머리글)과 빈 줄은 건너뛴다. 줄 끝은 LF·CRLF 어느 쪽이든 된다. 윈도에서는 git 이 CRLF 로
     * 바꿔 꺼내고, 그대로 APK 에 들어간다. 모르는 묶음, 탭이 없는 줄, 이미 나온 소리는 버린다. 그 소리는 표에 없는 것이
     * 되어 제 이름의 카드로 보인다. 탭이 깨지지 않는 쪽을 고른 것이고, 표가 맞는지는 SoundGroupTableTest 가 막는다.
     */
    fun parseTable(input: InputStream): Map<String, SoundGroupRow> {
        val rows = LinkedHashMap<String, SoundGroupRow>()
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab < 0) continue
                val group = SoundGroup.fromId(line.substring(0, tab)) ?: continue
                val name = line.substring(tab + 1)
                if (name.isEmpty() || name in rows) continue
                rows[name] = SoundGroupRow(group, rows.size)
            }
        }
        return rows
    }

    /**
     * 카드를 구역별로 나눈다. 카드는 (묶음, 소리마다의 기본 종류)다(규칙 B).
     *
     * 기본 종류는 [SoundEntry.defaultType] 그대로다. 매핑이 바뀌면 표를 고치지 않아도 그 소리가 새 구역에 같은 묶음
     * 이름으로 따로 보인다. 구역은 [SECTION_ORDER] 순서이고 카드가 없는 구역은 뺀다. 구역 안의 카드는 [SoundGroup]
     * 순서이고, 표에 없는 소리는 그 뒤에 한 소리씩 카드가 된다. 카드 안의 소리는 표의 순서다.
     */
    fun sections(entries: List<SoundEntry>): List<SoundSection> {
        val byName = entries.associateBy { it.name }
        val cards = entries
            .groupBy { CardKey(it.group, it.defaultType, if (it.group == null) it.index else -1) }
            .map { (key, members) ->
                val sorted = members.sortedWith(BY_ORDER)
                val title = when {
                    key.group == null -> sorted.first()
                    key.group.titleSound != null -> byName[key.group.titleSound] ?: sorted.first()
                    else -> null
                }
                SoundCard(key.group, key.type, sorted, title)
            }
        return cards.groupBy { it.defaultType }
            .toList()
            .sortedBy { (type, _) -> sectionRank(type) }
            .map { (type, inSection) -> SoundSection(type, inSection.sortedWith(CARD_ORDER)) }
    }

    /** 표에 없는 소리는 [single] 에 번호를 넣어 저마다 카드가 되게 한다. */
    private data class CardKey(val group: SoundGroup?, val type: String, val single: Int)

    /** 세 종류 밖의 라벨은 생기지 않지만, 생기더라도 소리를 잃지 않게 맨 뒤 구역으로 둔다. */
    private fun sectionRank(type: String): Int = SECTION_ORDER.indexOf(type).let { if (it < 0) SECTION_ORDER.size else it }

    /** 카드의 지금 종류. 사용자가 바꾼 것([overrides])까지 반영한다. */
    fun state(card: SoundCard, overrides: Map<String, String>): GroupState {
        val type = SoundCatalog.typeOf(card.members.first(), overrides)
        return if (card.members.all { SoundCatalog.typeOf(it, overrides) == type }) GroupState.Uniform(type) else GroupState.Mixed
    }

    /**
     * 섞인 카드가 하나라도 있는지. 고급 모드를 끌 때 덮어쓰기를 먼저 알릴지 정한다.
     * 찾는 말·거르기와 상관없이 모든 카드를 보고, 지난번에 바꿔 둔 것도 저장값으로 함께 본다.
     */
    fun anyMixed(sections: List<SoundSection>, overrides: Map<String, String>): Boolean =
        sections.any { section -> section.cards.any { state(it, overrides) is GroupState.Mixed } }

    /**
     * 카드 이름 밑에 보일 소리. 카드 이름인 소리([SoundCard.titleEntry])는 이미 보이므로 뺀다. 그래서 그 소리 하나뿐인
     * 카드(전기톱 등)는 빈 미리보기다. [first] 에 든 소리(찾는 말에 맞은 소리)를 앞에 두고, 나머지는 표의 순서다.
     */
    fun preview(card: SoundCard, first: Collection<SoundEntry> = emptyList()): GroupPreview {
        val firstIndexes = first.mapTo(HashSet()) { it.index }
        val (matched, others) = card.members
            .filter { it.index != card.titleEntry?.index }
            .partition { it.index in firstIndexes }
        val ordered = matched + others
        val names = ordered.take(PREVIEW_SIZE)
        return GroupPreview(names, ordered.size - names.size)
    }

    /**
     * 찾는 말과 거르기에 맞는 카드와 소리. 기본 모드와 고급 모드가 함께 쓴다.
     *
     * - 찾는 말은 카드 이름과 소리 이름에서, 보이는 이름과 모델 이름 어느 쪽으로든 찾는다([SoundCatalog.normalize]).
     *   그래서 한글을 치는 중의 모양("상", "홪")에서도 결과가 끊기지 않는다.
     * - 카드 이름이 맞으면 묶음의 소리가 모두 맞은 것으로 본다. 소리 이름이 맞으면 그 소리만 맞은 것이다.
     * - 거르기는 소리마다 지금 종류(또는 바꿨는지)로 본다. 구역은 기본 종류라, 바꾼 묶음도 제 구역에 그대로 있다.
     * - 둘 다 맞는 소리가 하나라도 있으면 카드가 남는다. 남은 카드가 없는 구역은 뺀다.
     *
     * @param searchKeys 소리마다 [SoundCatalog.searchKey] 로 만든 값. entry.index 자리에 둔다.
     * @param titleKeys 새로 지은 묶음 이름의 [SoundCatalog.searchKey]. 소리 이름이 곧 묶음 이름이면 그 소리의 값을 쓰므로
     *   넣지 않아도 된다.
     */
    fun filter(
        sections: List<SoundSection>,
        searchKeys: List<String>,
        titleKeys: Map<SoundGroup, String>,
        overrides: Map<String, String>,
        query: String,
        filter: SoundFilter
    ): List<ShownSection> {
        val needle = SoundCatalog.normalize(query)
        return sections.mapNotNull { section ->
            val cards = section.cards.mapNotNull { card ->
                val titleMatched = needle.isEmpty() || needle in titleKey(card, searchKeys, titleKeys)
                val shown = card.members.filter { entry ->
                    SoundCatalog.passes(entry, overrides, filter) &&
                        (titleMatched || needle in searchKeys.getOrElse(entry.index) { "" })
                }
                if (shown.isEmpty()) null else ShownCard(card, shown)
            }
            if (cards.isEmpty()) null else ShownSection(section.type, cards)
        }
    }

    private fun titleKey(card: SoundCard, searchKeys: List<String>, titleKeys: Map<SoundGroup, String>): String =
        card.titleEntry?.let { searchKeys.getOrNull(it.index) } ?: card.group?.let { titleKeys[it] } ?: ""

    /**
     * 고급 모드 목록. [filter] 의 결과를 구역 머리, 묶음 머리, 소리 줄 차례로 편다. 그래서 맞는 줄이 있는 묶음과 구역만
     * 머리가 보이고, 카드 이름이 맞은 묶음은 소리가 모두 보인다.
     */
    fun advancedItems(shown: List<ShownSection>): List<AdvancedItem> = buildList {
        for (section in shown) {
            add(AdvancedItem.SectionHeading(section.type))
            for (card in section.cards) {
                add(AdvancedItem.GroupHeading(card.card))
                card.shown.forEach { add(AdvancedItem.Row(it)) }
            }
        }
    }

    /** 기본 모드에서 [card] 를 [picked] 로 고르면 종류가 바뀌는 소리 수. 섞인 카드를 고를 때 묻는 창에 쓴다. */
    fun overwriteCount(card: SoundCard, overrides: Map<String, String>, picked: String): Int =
        card.members.count { SoundCatalog.typeOf(it, overrides) != picked }
}

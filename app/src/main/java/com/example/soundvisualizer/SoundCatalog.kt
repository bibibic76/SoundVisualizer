package com.example.soundvisualizer

import android.content.Context
import com.example.soundvisualizer.ai.YamnetCoarseClassifier
import com.example.soundvisualizer.ai.YamnetThreeClassMapper
import java.text.Normalizer
import java.util.Locale

/**
 * 분류 탭의 한 줄. AI 가 알아듣는 소리 하나와 그 소리의 기본 종류.
 *
 * @param index YAMNet 클래스 번호. 화면에 보이는 이름(sound_names.xml 의 배열)도 이 순서다.
 * @param name 모델이 쓰는 영어 이름. 사용자가 고른 종류를 저장하는 키이고, 분류기가 받는 이름과 같아야 해서
 *   번역된 이름이 아니라 이것을 쓴다.
 * @param defaultType 키워드 규칙만으로 정한 종류([AiClassification] 라벨).
 * @param group 분류 탭에서 이 소리가 든 묶음(assets/sound_groups.tsv, #349). 표에 없으면 null 이고, 그 소리 하나가
 *   카드가 된다([SoundGroups.sections]).
 * @param order 묶음 화면에서 보이는 자리. 표의 줄 순서이고, 표에 없는 소리는 표 뒤에 번호 순서로 온다.
 */
data class SoundEntry(
    val index: Int,
    val name: String,
    val defaultType: String,
    val group: SoundGroup? = null,
    val order: Int = index
)

/** 분류 탭의 거르기. 종류는 사용자가 바꾼 것까지 반영한 지금 종류로 본다. */
enum class SoundFilter { ALL, AMBIENT, SPEECH, DANGER, CHANGED }

object SoundCatalog {

    /** 분류기(RealtimeAiPipeline)가 읽는 클래스 목록과 같은 파일이다. */
    private const val CLASS_MAP_ASSET = "ai/yamnet_class_map.csv"

    /**
     * 분류 탭의 소리 묶음 표(#349). AudioSet 온톨로지를 바탕으로 만든 표라 CC BY-SA 4.0 머리글을 달아 assets/ai 밖에
     * 따로 둔다. AI 는 이 표를 읽지 않는다.
     */
    private const val GROUPS_ASSET = "sound_groups.tsv"

    @Volatile
    private var cached: List<SoundEntry>? = null

    /** 이미 읽어 둔 목록. 아직이면 null. 탭에 다시 들어올 때 빈 화면이 깜빡이지 않게 쓴다. */
    fun cachedOrNull(): List<SoundEntry>? = cached

    /**
     * 파일을 읽으므로 메인 스레드에서 부르지 않는다. 한 번 읽으면 프로세스가 끝날 때까지 들고 있는다.
     * 묶음 표도 여기서 함께 읽는다. 탭은 이 목록 하나만 IO 스레드에서 기다리면 되고, 표를 따로 읽느라 다시 그리지 않는다.
     */
    fun load(context: Context): List<SoundEntry> =
        cached ?: run {
            val names = context.assets.open(CLASS_MAP_ASSET).use { YamnetCoarseClassifier.loadClassNames(it) }
            val groups = context.assets.open(GROUPS_ASSET).use { SoundGroups.parseTable(it) }
            fromNames(names, groups)
        }.also { cached = it }

    /**
     * 모델의 소리 이름(번호 순서)으로 목록을 만든다.
     *
     * @param groups 소리 이름별 묶음([SoundGroups.parseTable]). 표에 없는 소리는 묶음이 없다.
     * @param defaultOf 기본 종류. AI 의 매핑이 바뀐 경우를 테스트가 흉내 낼 때만 바꾼다.
     */
    internal fun fromNames(
        names: List<String>,
        groups: Map<String, SoundGroupRow> = emptyMap(),
        defaultOf: (String) -> String = SettingsManager::defaultSoundType
    ): List<SoundEntry> {
        val afterTable = (groups.values.maxOfOrNull { it.order } ?: -1) + 1
        return names.mapIndexed { index, name ->
            val row = groups[name]
            SoundEntry(index, name, defaultOf(name), row?.group, row?.order ?: (afterTable + index))
        }
    }

    /** 사용자가 바꾼 것까지 반영한 지금 종류. */
    fun typeOf(entry: SoundEntry, overrides: Map<String, String>): String = overrides[entry.name] ?: entry.defaultType

    /**
     * 찾는 말과 거르기에 맞는 줄만 남긴다. 순서는 그대로다.
     *
     * @param searchKeys 줄마다 [searchKey] 로 만든 값. 입력할 때마다 521개 이름을 다시 다듬지 않게 미리 만든다.
     */
    fun filter(
        entries: List<SoundEntry>,
        searchKeys: List<String>,
        overrides: Map<String, String>,
        query: String,
        filter: SoundFilter
    ): List<SoundEntry> {
        val needle = normalize(query)
        return entries.filterIndexed { i, entry ->
            passes(entry, overrides, filter) && (needle.isEmpty() || needle in searchKeys[i])
        }
    }

    /** [entry] 가 거르기에 맞는지. 묶음 화면([SoundGroups.filter])도 소리마다 같은 기준으로 본다. */
    internal fun passes(entry: SoundEntry, overrides: Map<String, String>, filter: SoundFilter): Boolean {
        val type = typeOf(entry, overrides)
        return when (filter) {
            SoundFilter.ALL -> true
            SoundFilter.AMBIENT -> type == AiClassification.AMBIENT
            SoundFilter.SPEECH -> type == AiClassification.SPEECH
            SoundFilter.DANGER -> type == AiClassification.DANGER
            SoundFilter.CHANGED -> type != entry.defaultType
        }
    }

    /**
     * 찾기에 쓰는 값. 보이는 이름과 모델 이름 어느 쪽으로 찾아도 맞게 둘을 잇는다.
     * 영어 이름을 함께 넣는 것은 개발자 모드 글자판(영어)에서 본 이름으로도 찾을 수 있게 하려는 것이다.
     */
    fun searchKey(label: String, name: String): String = normalize(label) + "\n" + normalize(name)

    /**
     * 대소문자, 띄어쓰기, 악센트 같은 결합 부호를 가리지 않는다. "fire truck" 은 "Firetruck" 으로도,
     * "sirène" 은 "sirene" 으로도 찾힌다.
     *
     * 한글은 낱자로 풀어 첫소리와 받침을 같은 글자로 본다. 글자를 치는 중에는 입력 칸에 "ㅅ", "상"(다음 글자의
     * 첫소리가 받침으로 붙은 모양), "홪" 같은 모양이 잠깐 들어오는데, 음절째 비교하면 한 글자 칠 때마다 결과가
     * 사라졌다 나타난다. 겹받침·겹모음(ㄺ, ㅘ)도 낱자 둘로 푼다.
     */
    internal fun normalize(text: String): String {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace(WHITESPACE, "")
            .lowercase(Locale.ROOT)
        return buildString(plain.length) { for (c in plain) append(HANGUL_LETTERS[c] ?: c) }
    }

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    /** 한글 낱자(첫소리·가운뎃소리·받침, 겹자모)를 입력 칸에 보이는 호환용 낱자로. NFD 가 음절을 이 낱자들로 푼다. */
    private val HANGUL_LETTERS: Map<Char, String> = buildMap {
        "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ".forEachIndexed { i, c -> put('ᄀ' + i, c.toString()) }
        listOf(
            "ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅗㅏ", "ㅗㅐ", "ㅗㅣ", "ㅛ", "ㅜ", "ㅜㅓ", "ㅜㅔ", "ㅜㅣ",
            "ㅠ", "ㅡ", "ㅡㅣ", "ㅣ"
        ).forEachIndexed { i, v -> put('ᅡ' + i, v) }
        listOf(
            "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ", "ㄹ", "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ", "ㄹㅍ", "ㄹㅎ",
            "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
        ).forEachIndexed { i, f -> put('ᆨ' + i, f) }
        // 치는 중에 입력 칸에 보이는 호환용 겹자모
        mapOf(
            'ㄳ' to "ㄱㅅ", 'ㄵ' to "ㄴㅈ", 'ㄶ' to "ㄴㅎ", 'ㄺ' to "ㄹㄱ", 'ㄻ' to "ㄹㅁ", 'ㄼ' to "ㄹㅂ", 'ㄽ' to "ㄹㅅ",
            'ㄾ' to "ㄹㅌ", 'ㄿ' to "ㄹㅍ", 'ㅀ' to "ㄹㅎ", 'ㅄ' to "ㅂㅅ", 'ㅘ' to "ㅗㅏ", 'ㅙ' to "ㅗㅐ", 'ㅚ' to "ㅗㅣ",
            'ㅝ' to "ㅜㅓ", 'ㅞ' to "ㅜㅔ", 'ㅟ' to "ㅜㅣ", 'ㅢ' to "ㅡㅣ"
        ).forEach { (k, v) -> put(k, v) }
    }
}

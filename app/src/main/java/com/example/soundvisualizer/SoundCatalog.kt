package com.example.soundvisualizer

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
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

/**
 * 분류 탭이 그리는 데 쓰는 것을 앱 언어 하나로 한 번 만들어 둔 것(#368). [SoundCatalog.loadIndex] 가 만든다.
 *
 * 탭은 페이저 안에 있어서 홈에서 설정으로 넘기기만 해도 옆 쪽으로 미리 만들어진다. 그때마다 메인 스레드에서 이것을
 * 다시 만들면 이름 521개를 읽고 정규화를 1,042번 한다. 그래서 언어마다 한 번, 메인 스레드 밖에서 만들어 두고 쓴다.
 *
 * 문자열만 들고 Context·Resources 는 들고 있지 않는다. 프로세스가 끝날 때까지 남는 값이라, 들고 있으면 언어를 바꿔
 * 액티비티를 다시 만든 뒤에도 옛 액티비티가 메모리에 남는다.
 *
 * @param locales 만든 앱 언어(Configuration.getLocales 의 태그). 캐시의 키다.
 * @param entries 소리 목록([SoundCatalog.load]).
 * @param labels 앱 언어의 소리 이름(sound_names). 클래스 번호 순서다.
 * @param searchKeys 소리마다의 [SoundCatalog.searchKey]. entry.index 자리에 둔다.
 * @param sections 묶음과 구역([SoundGroups.sections]). 언어와 상관없지만 목록이 같으면 늘 같으므로 함께 만들어 둔다.
 * @param titleKeys 새로 지은 묶음 이름의 찾기 값([SoundGroups.titleKeys]).
 */
class SoundIndex internal constructor(
    val locales: String,
    val entries: List<SoundEntry>,
    private val labels: List<String>,
    val searchKeys: List<String>,
    val sections: List<SoundSection>,
    val titleKeys: Map<SoundGroup, String>
) {
    /** 앱 언어의 소리 이름. 배열에 없는 번호면 모델 이름이다. */
    fun labelOf(entry: SoundEntry): String = labels.getOrElse(entry.index) { entry.name }
}

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

    /** 이미 읽어 둔 목록. 아직이면 null. 잠금 화면 알림이 메인 스레드에서 파일을 읽지 않고 소리 번호를 찾을 때 쓴다(#356). */
    fun cachedOrNull(): List<SoundEntry>? = cached

    /**
     * 파일을 읽으므로 메인 스레드에서 부르지 않는다. 한 번 읽으면 프로세스가 끝날 때까지 들고 있는다.
     * 묶음 표도 여기서 함께 읽는다. 분류 탭은 색인([loadIndex])을 만들 때, 잠금 화면 알림은 AI 를 불러올 때 읽는다.
     */
    fun load(context: Context): List<SoundEntry> =
        cached ?: run {
            val names = context.assets.open(CLASS_MAP_ASSET).use { YamnetCoarseClassifier.loadClassNames(it) }
            val groups = context.assets.open(GROUPS_ASSET).use { SoundGroups.parseTable(it) }
            fromNames(names, groups)
        }.also { cached = it }

    /** 분류 탭의 색인. 앱 언어 하나의 것만 둔다. 언어가 바뀌면 액티비티가 다시 만들어지고, 탭이 새 언어로 다시 만든다. */
    @Volatile
    private var cachedIndex: SoundIndex? = null

    /** 색인을 한 번에 하나만 만들게 한다. */
    private val indexLock = Any()

    /**
     * [locales] 로 만들어 둔 색인. 아직 없거나 다른 언어로 만든 것이면 null. 파일을 읽지 않으므로 메인 스레드에서 불러도
     * 된다. 탭에 다시 들어오거나 페이저가 탭을 미리 만들 때 이것으로 바로 그린다.
     */
    fun cachedIndexOrNull(locales: String): SoundIndex? = cachedIndex?.takeIf { it.locales == locales }

    /**
     * 분류 탭의 색인을 [resources] 의 언어로 만든다. 파일을 읽고 이름을 1,042번 다듬으므로 메인 스레드에서 부르지 않는다.
     * 그 언어로 이미 만들어 두었으면 그것을 돌려준다.
     *
     * [resources] 는 액티비티의 것을 넘긴다. Android 12 이하에서는 앱 언어가 액티비티의 컨텍스트에만 입혀지고,
     * applicationContext 의 리소스는 폰 언어 그대로다(AppLanguage). 거기서 읽으면 폰 언어의 이름이 보인다.
     *
     * 다 만든 색인은 여기서 캐시에 넣는다. 부른 쪽(탭의 코루틴)이 그사이 취소돼도(만드는 중에 탭을 떠남) 만든 것을
     * 버리지 않아서, 다음에 들어올 때 바로 그린다.
     */
    fun loadIndex(context: Context, resources: Resources): SoundIndex {
        val locales = resources.configuration.locales.toLanguageTags()
        return index(locales) {
            // 새로 지은 묶음 이름은 영어로도 찾는다. 영어는 기본 문구(values)에 있어서, 앱 언어와 상관없이 꺼내려고
            // 영어로 맞춘 리소스를 따로 만든다.
            val english = context.createConfigurationContext(
                Configuration(resources.configuration).apply { setLocale(Locale.ENGLISH) }
            ).resources
            buildIndex(
                locales,
                load(context),
                resources.getStringArray(R.array.sound_names).asList(),
                titleLocal = { resources.getString(it) },
                titleEnglish = { english.getString(it) }
            )
        }
    }

    /**
     * [locales] 의 색인. 없으면 [build] 로 만들어 캐시에 넣는다. 여럿이 함께 불러도 한 번만 만든다. 페이저가 탭을 미리
     * 만들었다가 버리고 곧 다시 만들면, 뒤에 부른 쪽은 앞의 것이 끝나기를 기다렸다가 그 색인을 쓴다.
     *
     * 리소스를 읽는 일([build])은 밖에서 받는다. 그래서 기기 없이 테스트한다.
     */
    internal fun index(locales: String, build: () -> SoundIndex): SoundIndex {
        cachedIndexOrNull(locales)?.let { return it }
        return synchronized(indexLock) {
            cachedIndexOrNull(locales) ?: build().also { cachedIndex = it }
        }
    }

    /**
     * 색인 하나를 만든다. 시간은 거의 찾기 값을 만드는 데(소리마다 두 이름을 [normalize]) 든다.
     *
     * 빠르게 하려고 [normalize] 를 손으로 다시 쓰지 않고, 만든 것을 들고 있는 쪽을 택했다. 앱의 정규식은 ICU 를 거치고
     * JVM 테스트는 JDK 의 정규식을 거쳐서, 다시 쓰면 한쪽에서만 찾는 결과가 달라질 수 있다.
     *
     * @param labels 앱 언어의 소리 이름. 클래스 번호 순서다.
     * @param titleLocal 앱 언어의 문구(새로 지은 묶음 이름).
     * @param titleEnglish 영어(기본 문구 values)의 문구.
     */
    internal fun buildIndex(
        locales: String,
        entries: List<SoundEntry>,
        labels: List<String>,
        titleLocal: (Int) -> String,
        titleEnglish: (Int) -> String
    ): SoundIndex {
        val labelOf = { entry: SoundEntry -> labels.getOrElse(entry.index) { entry.name } }
        return SoundIndex(
            locales = locales,
            entries = entries,
            labels = labels,
            searchKeys = entries.map { searchKey(labelOf(it), it.name) },
            sections = SoundGroups.sections(entries),
            titleKeys = SoundGroups.titleKeys(local = titleLocal, english = titleEnglish)
        )
    }

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
     * 모델 이름의 클래스 번호. 앱 언어의 이름(sound_names)을 이 번호로 꺼낸다. 잠금 화면 알림은 AI 가 확정한 이름을
     * 문자열로만 받으므로 이것으로 번호를 찾는다(#356). 모델 이름은 서로 겹치지 않는다. 목록에 없는 이름이면 null.
     */
    fun indexOf(entries: List<SoundEntry>, name: String): Int? = entries.firstOrNull { it.name == name }?.index

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
     * 문장 부호(쉼표, 가운뎃점 '·'·'・', 붙임표, 괄호)도 띄어쓰기처럼 뺀다. 묶음 이름은 낱말을 이런 부호로 잇는데
     * ("부엌·요리", "TV, radio and …"), 사람은 띄어 쳐서 찾는다. 부호를 남겨 두면 "부엌 요리" 처럼 이어진 두 낱말로는
     * 아무것도 찾지 못했다(#349). 보이는 이름과 모델 이름을 가르는 줄바꿈은 [searchKey] 가 다듬은 뒤에 넣으므로 그대로다.
     *
     * 한글은 낱자로 풀어 첫소리와 받침을 같은 글자로 본다. 글자를 치는 중에는 입력 칸에 "ㅅ", "상"(다음 글자의
     * 첫소리가 받침으로 붙은 모양), "홪" 같은 모양이 잠깐 들어오는데, 음절째 비교하면 한 글자 칠 때마다 결과가
     * 사라졌다 나타난다. 겹받침·겹모음(ㄺ, ㅘ)도 낱자 둘로 푼다.
     */
    internal fun normalize(text: String): String {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace(SEPARATORS, "")
            .lowercase(Locale.ROOT)
        return buildString(plain.length) { for (c in plain) append(HANGUL_LETTERS[c] ?: c) }
    }

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** 띄어쓰기와 문장 부호(유니코드 P 갈래). */
    private val SEPARATORS = Regex("[\\s\\p{P}]+")

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

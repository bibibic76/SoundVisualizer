package com.example.soundvisualizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 분류 탭의 문구(#349)가 모든 언어에 있는지 본다.
 *
 * 다른 문구는 번역이 늦으면 그 언어에서 영어로 보여도 된다(StringResourcesTest). 분류 탭은 묶음 화면으로 바뀌며 설명과
 * 안내를 새로 쓰고 키 이름도 바꿨다. 한 언어라도 빠지면 그 언어의 탭에 영어 안내가 섞이고, 옛 키가 남으면 지금 화면과
 * 맞지 않는 안내(소리마다 고르기)가 남는다. 그래서 이 탭의 문구만은 모든 언어에 있어야 한다.
 *
 * 문구의 뜻도 몇 가지 본다. 도움말의 초인종 예시는 모든 언어에서 '투표에만 들어간다' 는 단서를 붙이고(결정 6),
 * 영어의 덮어쓰기 안내는 수가 하나일 때도 어색하지 않다. 묶음 이름은 다른 묶음의 이름이나 다른 묶음에 든 소리의
 * 이름과 같지 않다(#349 의 번역 규칙, #370).
 *
 * 유닛 테스트는 app 모듈 폴더에서 돈다.
 */
class SoundGroupStringsTest {

    private val resDir = File("src/main/res")

    /** strings.xml 이 있는 언어 폴더(values, values-xx). values-v31 같은 다른 폴더는 뺀다. */
    private val dirs: List<String> by lazy {
        val found = resDir.listFiles { f -> f.isDirectory && (f.name == "values" || f.name.startsWith("values-")) }
            .orEmpty()
            .filter { File(it, "strings.xml").isFile }
            .map { it.name }
            .sorted()
        assertTrue("res 폴더를 찾지 못했습니다: ${resDir.absolutePath}", found.isNotEmpty())
        found
    }

    private val strings: Map<String, Map<String, String>> by lazy { dirs.associateWith { SoundGroupFixtures.strings(it) } }

    /**
     * 모든 언어에 있어야 하는 문구. 영어(values)의 분류 탭 문구(classify_*, cd_classify_*, 묶음 이름 sound_group_*) 가운데
     * 번역하는 것과, 이름을 바꾼 도움말 문구다. 숫자만 있는 "+N"(classify_group_more)은 번역하지 않는다.
     */
    private val requiredKeys: List<String> by lazy {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val nodes = factory.newDocumentBuilder().parse(File(resDir, "values/strings.xml")).documentElement
            .getElementsByTagName("string")
        val classify = (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .filter { it.getAttribute("translatable") != "false" }
            .map { it.getAttribute("name") }
            .filter { name -> CLASSIFY_PREFIXES.any { name.startsWith(it) } }
        classify + RENAMED_HELP_KEY
    }

    @Test
    fun `분류 탭 문구는 모든 언어에 있다`() {
        assertTrue("분류 탭 문구를 찾지 못했습니다", requiredKeys.size > 10)
        val missing = dirs.mapNotNull { dir ->
            val keys = requiredKeys.filter { it !in strings.getValue(dir) }
            if (keys.isEmpty()) null else "$dir: ${keys.size}개 빠짐 $keys"
        }
        assertTrue("분류 탭 문구는 모든 언어에 넣습니다:\n${missing.joinToString("\n")}", missing.isEmpty())
    }

    @Test
    fun `이름을 바꾼 옛 문구는 어느 언어에도 없다`() {
        val left = dirs.flatMap { dir -> OLD_KEYS.filter { it in strings.getValue(dir) }.map { "$dir: $it" } }
        assertTrue(
            "옛 키는 지금 화면과 맞지 않는 안내라 모든 언어에서 지웁니다(새 키: classify_desc_groups, " +
                "classify_sibling_note, help_sound_classify_groups):\n${left.joinToString("\n")}",
            left.isEmpty()
        )
    }

    @Test
    fun `함께 들리는 이름 안내는 그 언어의 사이렌과 경보음 이름을 쓴다`() {
        // 안내를 읽고 그 이름으로 찾아볼 수 있어야 한다. 찾기와 같은 기준(대소문자·띄어쓰기 무시)으로 본다.
        // 안내가 빠진 언어는 위 테스트가 잡는다.
        val english = strings.getValue("values")
        val problems = dirs.mapNotNull { dir ->
            val texts = strings.getValue(dir)
            val note = texts["classify_sibling_note"] ?: return@mapNotNull null
            val notQuoted = QUOTED_SOUNDS.map { texts[it] ?: english.getValue(it) }
                .filter { SoundCatalog.normalize(it) !in SoundCatalog.normalize(note) }
            if (notQuoted.isEmpty()) null else "$dir: $notQuoted"
        }
        assertTrue("classify_sibling_note 에 그 언어의 소리 이름을 그대로 씁니다:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    @Test
    fun `도움말의 초인종 예시는 새로 위협음으로 정한 소리가 투표에만 들어간다고 알린다`() {
        // 새로 위협음으로 정한 소리는 AI 의 상위 3개 투표에만 들어간다(docs/ai/user-mapping.md). 그래서 도움말은 초인종이
        // 울릴 때마다 위협음으로 알린다고 약속하지 않는다(#349 결정 6). 언어마다 그 말('투표')이 있는지 본다.
        val problems = dirs.mapNotNull { dir ->
            val help = strings.getValue(dir)[RENAMED_HELP_KEY] ?: return@mapNotNull null
            val word = VOTE_WORDS[dir] ?: return@mapNotNull "$dir: 새 언어는 VOTE_WORDS 에 그 언어의 '투표' 낱말을 넣습니다"
            if (word.lowercase(Locale.ROOT) in help.lowercase(Locale.ROOT)) null else "$dir: \"$word\" 이 없음"
        }
        assertTrue("$RENAMED_HELP_KEY 의 초인종 예시에 '투표에만 들어간다' 는 단서를 붙입니다:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    @Test
    fun `묶음 덮어쓰기 안내의 영어는 하나만 바뀔 때도 어색하지 않다`() {
        // 섞인 묶음은 소리 하나만 바뀌는 일이 흔하다. plurals 없이 수를 이름표 뒤에 두어, 수 뒤에 명사가 와서
        // "1 sounds" 로 읽히지 않게 한다.
        val message = strings.getValue("values").getValue("classify_group_overwrite_message")
        val one = String.format(Locale.ROOT, message, "Siren", 1, "Danger")
        assertFalse(one, Regex("""\b1 \p{L}+s\b""").containsMatchIn(one))
    }

    @Test
    fun `묶음 이름은 다른 묶음의 이름이나 다른 묶음에 든 소리의 이름과 같지 않다`() {
        // 같으면 화면에서도 찾기에서도 어느 묶음인지 가릴 수 없다. 소리를 다른 묶음으로 옮기면 그 소리 이름을 그대로 쓴
        // 묶음 이름이 여기에 걸린다. 찾기와 같은 기준(대소문자·띄어쓰기·문장 부호 무시)으로 본다. 여러 낱말을 이은 이름의
        // 한 낱말이 다른 묶음의 소리 이름인지(#370 에서 고친 목소리 묶음)는 언어마다 낱말을 나누는 법이 달라 보지 않는다.
        val english = strings.getValue("values")
        val names = SoundGroupFixtures.names
        val table = SoundGroupFixtures.table
        val problems = dirs.flatMap { dir ->
            val texts = strings.getValue(dir)
            fun text(key: String) = texts[key] ?: english.getValue(key)
            fun soundName(name: String) = text("sound_name_${names.indexOf(name)}")
            val titles = SoundGroup.entries.associateWith { group ->
                group.titleSound?.let { soundName(it) } ?: text("sound_group_${group.id}")
            }
            val shared = titles.entries.groupBy({ SoundCatalog.normalize(it.value) }, { it.key }).values
                .filter { it.size > 1 }
                .map { "$dir: 이름이 같은 묶음 $it" }
            val namedSounds = titles.filterKeys { it.titleRes != 0 }.flatMap { (group, title) ->
                names.filter { table[it]?.group != group && SoundCatalog.normalize(soundName(it)) == SoundCatalog.normalize(title) }
                    .map { "$dir: $group 의 이름 \"$title\" 이 다른 묶음(${table[it]?.group})의 소리 $it 의 이름이다" }
            }
            shared + namedSounds
        }
        assertTrue("묶음 이름이 겹친다:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    private companion object {
        val CLASSIFY_PREFIXES = listOf("classify_", "cd_classify_", "sound_group_")

        const val RENAMED_HELP_KEY = "help_sound_classify_groups"

        /** 이름을 바꾼 옛 키(#349). */
        val OLD_KEYS = listOf("classify_desc", "classify_danger_note", "help_sound_classify")

        /** 안내가 예로 드는 소리: Siren, Alarm. */
        val QUOTED_SOUNDS = listOf("sound_name_390", "sound_name_382")

        /** 도움말의 단서에 쓴 언어마다의 '투표'. 낱말이 꼴을 바꾸는 언어(pl, ru)는 줄기만 둔다. */
        val VOTE_WORDS = mapOf(
            "values" to "vote",
            "values-ar" to "تصويت",
            "values-de" to "Abstimmung",
            "values-es" to "votación",
            "values-fr" to "vote",
            "values-hi" to "वोट",
            "values-in" to "voting",
            "values-it" to "voto",
            "values-ja" to "投票",
            "values-ko" to "투표",
            "values-pl" to "głosowani",
            "values-pt-rBR" to "votação",
            "values-ru" to "голосовани",
            "values-th" to "โหวต",
            "values-tr" to "oylama",
            "values-vi" to "bỏ phiếu",
            "values-zh-rCN" to "投票",
            "values-zh-rTW" to "投票"
        )
    }
}

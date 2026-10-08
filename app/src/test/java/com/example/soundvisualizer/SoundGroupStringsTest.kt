package com.example.soundvisualizer

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 분류 탭의 문구(#349)가 모든 언어에 있는지 본다.
 *
 * 다른 문구는 번역이 늦으면 그 언어에서 영어로 보여도 된다(StringResourcesTest). 분류 탭은 묶음 화면으로 바뀌며 설명과
 * 안내를 새로 쓰고 키 이름도 바꿨다. 한 언어라도 빠지면 그 언어의 탭에 영어 안내가 섞이고, 옛 키가 남으면 지금 화면과
 * 맞지 않는 안내(소리마다 고르기)가 남는다. 그래서 이 탭의 문구만은 모든 언어에 있어야 한다.
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

    private companion object {
        val CLASSIFY_PREFIXES = listOf("classify_", "cd_classify_", "sound_group_")

        const val RENAMED_HELP_KEY = "help_sound_classify_groups"

        /** 이름을 바꾼 옛 키(#349). */
        val OLD_KEYS = listOf("classify_desc", "classify_danger_note", "help_sound_classify")

        /** 안내가 예로 드는 소리: Siren, Alarm. */
        val QUOTED_SOUNDS = listOf("sound_name_390", "sound_name_382")
    }
}

package com.example.soundvisualizer.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * res 의 values, values-ko 같은 폴더에 있는 strings.xml 문구를 기기 없이 검사한다.
 *
 * - 영어(values)가 기본이고, 한국어(values-ko)는 번역할 문구를 모두 가져야 한다.
 * - 다른 언어는 빠진 문구가 있어도 된다(영어로 보이고 Lint 가 경고한다). 대신 없는 키나 틀린 서식은 막는다.
 * - 번역 폴더와 [AppLanguages] 목록이 서로 어긋나지 않아야 한다.
 *
 * 유닛 테스트는 app 모듈 폴더에서 돈다.
 */
class StringResourcesTest {

    private class StringFile(val dir: String, val strings: Map<String, StringEntry>)

    private class StringEntry(val value: String, val translatable: Boolean)

    private val resDir = File("src/main/res")

    /** strings.xml 이 있는 values 폴더 이름들. */
    private val stringDirs: List<String> by lazy {
        val dirs = resDir.listFiles { file -> file.isDirectory && (file.name == "values" || file.name.startsWith("values-")) }
            .orEmpty()
            .filter { File(it, "strings.xml").isFile }
            .map { it.name }
            .sorted()
        assertTrue("res 폴더를 찾지 못했습니다: ${resDir.absolutePath}", dirs.isNotEmpty())
        dirs
    }

    private val files: Map<String, StringFile> by lazy { stringDirs.associateWith { parse(it) } }

    private val defaults: StringFile get() = files.getValue(DEFAULT_DIR)

    private val translations: List<StringFile> get() = files.values.filter { it.dir != DEFAULT_DIR }

    private val translatableKeys: Set<String> get() = defaults.strings.filterValues { it.translatable }.keys

    @Test
    fun `모든 strings_xml 이 올바른 XML 이다`() {
        val broken = stringDirs.mapNotNull { dir ->
            runCatching { parse(dir) }.exceptionOrNull()?.let { "$dir/strings.xml: ${it.message}" }
        }
        assertTrue("XML 을 읽지 못했습니다:\n${broken.joinToString("\n")}", broken.isEmpty())
    }

    @Test
    fun `기본 문구는 영어 폴더 values 에 있다`() {
        assertTrue("$DEFAULT_DIR/strings.xml 이 없습니다", DEFAULT_DIR in stringDirs)
        assertTrue("기본 문구가 비어 있습니다", defaults.strings.isNotEmpty())
    }

    @Test
    fun `한국어는 영어의 번역할 문구를 빠짐없이 똑같이 가진다`() {
        val korean = files["values-ko"] ?: throw AssertionError("values-ko/strings.xml 이 없습니다")
        val missing = (translatableKeys - korean.strings.keys).sorted()
        val extra = (korean.strings.keys - translatableKeys).sorted()
        assertTrue(
            "values/ 에 문구를 추가했으면 values-ko/ 에도 넣습니다.\n빠진 문구: $missing\nvalues/ 의 번역할 문구가 아닌 것: $extra",
            missing.isEmpty() && extra.isEmpty()
        )
    }

    @Test
    fun `번역 파일에는 기본 파일에 없는 문구가 없다`() {
        val problems = translations.flatMap { file ->
            (file.strings.keys - defaults.strings.keys).sorted().map { "${file.dir}: $it" }
        }
        assertTrue("values/strings.xml 에 없는 키입니다. 영어에 먼저 추가하세요:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    @Test
    fun `번역 파일에 번역하지 않는 문구가 없다`() {
        val fixed = defaults.strings.filterValues { !it.translatable }.keys
        val problems = translations.flatMap { file ->
            val marked = file.strings.filterValues { !it.translatable }.keys
            ((file.strings.keys intersect fixed) + marked).sorted().map { "${file.dir}: $it" }
        }
        assertTrue("translatable=\"false\" 문구는 values/strings.xml 에만 둡니다:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    @Test
    fun `서식 지정자가 기본 문구와 같다`() {
        val problems = translations.flatMap { file ->
            file.strings.mapNotNull { (key, entry) ->
                val expected = defaults.strings[key]?.let { formatSpecifiers(it.value) } ?: return@mapNotNull null
                val actual = formatSpecifiers(entry.value)
                if (actual == expected) null else "${file.dir}: $key 기대 $expected, 실제 $actual"
            }
        }
        assertTrue("서식 지정자(%1\$s 등)는 영어와 똑같이 둡니다:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    @Test
    fun `아포스트로피와 큰따옴표는 이스케이프되어 있다`() {
        val problems = files.values.flatMap { file ->
            file.strings.filterValues { hasUnescapedQuote(it.value) }.keys.sorted().map { "${file.dir}: $it" }
        }
        assertTrue(
            "' 와 \" 는 \\' \\\" 로 쓰거나 ‘ ’ “ ” 같은 인쇄용 따옴표를 씁니다:\n${problems.joinToString("\n")}",
            problems.isEmpty()
        )
    }

    @Test
    fun `문구가 리소스 참조 기호로 시작하지 않는다`() {
        val problems = files.values.flatMap { file ->
            file.strings.filterValues { it.value.trimStart().let { v -> v.startsWith("@") || v.startsWith("?") } }
                .keys.sorted().map { "${file.dir}: $it" }
        }
        assertTrue("@ 나 ? 로 시작하면 리소스 참조로 읽힙니다. \\@ \\? 로 씁니다:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    @Test
    fun `번역 폴더는 모두 지원 언어 목록에 있다`() {
        val supported = AppLanguages.all.map { it.resourceDir }.toSet()
        val unknown = stringDirs.filter { it !in supported }
        assertTrue("AppLanguages 에 없는 폴더입니다. 목록에 언어를 추가하세요: $unknown", unknown.isEmpty())
    }

    @Test
    fun `지원 언어 목록의 기본 언어가 values 폴더다`() {
        assertEquals(DEFAULT_DIR, AppLanguages.byTag(AppLanguages.DEFAULT_TAG)?.resourceDir)
    }

    @Test
    fun `지원 언어는 모두 번역 폴더가 있다`() {
        val missing = AppLanguages.all.map { it.resourceDir }.filter { it != DEFAULT_DIR && it !in stringDirs }
        assertTrue("strings.xml 이 없는 지원 언어 폴더: $missing", missing.isEmpty())
    }

    private fun parse(dir: String): StringFile {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val root = factory.newDocumentBuilder().parse(File(resDir, "$dir/strings.xml")).documentElement
        assertEquals("$dir/strings.xml 의 최상위 태그", "resources", root.tagName)
        val nodes = root.getElementsByTagName("string")
        val strings = LinkedHashMap<String, StringEntry>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            val name = element.getAttribute("name")
            assertTrue("$dir: name 이 없는 string 이 있습니다", name.isNotEmpty())
            assertTrue("$dir: $name 이 두 번 있습니다", name !in strings)
            strings[name] = StringEntry(element.textContent, element.getAttribute("translatable") != "false")
        }
        return StringFile(dir, strings)
    }

    private companion object {
        const val DEFAULT_DIR = "values"

        /** %1$s, %d, %.1f 같은 서식 지정자. %% 는 글자 % 라서 뺀다. */
        val FORMAT_SPECIFIER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z]""")

        fun formatSpecifiers(value: String): List<String> =
            FORMAT_SPECIFIER.findAll(value.replace("%%", "")).map { it.value }.sorted().toList()

        /** 앞에 붙은 백슬래시가 홀수 개면 이스케이프된 것이다. */
        fun hasUnescapedQuote(value: String): Boolean =
            value.indices.any { i ->
                val c = value[i]
                if (c != '\'' && c != '"') return@any false
                var backslashes = 0
                var j = i - 1
                while (j >= 0 && value[j] == '\\') {
                    backslashes++
                    j--
                }
                backslashes % 2 == 0
            }
    }
}

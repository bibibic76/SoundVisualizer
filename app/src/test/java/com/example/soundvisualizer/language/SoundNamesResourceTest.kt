package com.example.soundvisualizer.language

import com.example.soundvisualizer.ai.YamnetCoarseClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 분류 탭의 소리 이름(res/values…/sound_names.xml)을 기기 없이 검사한다.
 *
 * - 영어(values)는 모델의 클래스 목록(assets/ai/yamnet_class_map.csv)과 번호·이름이 같아야 한다. 탭은 이 번호로
 *   번역된 이름과 모델 이름을 짝짓고, 사용자가 고른 종류는 모델 이름으로 저장한다. 하나라도 밀리면 다른 소리의
 *   이름을 보고 종류를 바꾸게 된다.
 * - 번역은 언어마다 전부 있거나 전혀 없어야 한다. 일부만 있으면 목록에 두 언어가 섞여 보인다.
 * - 한 언어 안에서 두 소리가 같은 이름이면 어느 줄이 어느 소리인지 알 수 없다.
 *
 * 따옴표 이스케이프, 서식 지정자, 한국어 누락 같은 일반 문구 규칙은 StringResourcesTest 가 이 파일까지 함께 본다.
 */
class SoundNamesResourceTest {

    private val resDir = File("src/main/res")

    private val classNames: List<String> by lazy {
        File("src/main/assets/ai/yamnet_class_map.csv").inputStream().use { YamnetCoarseClassifier.loadClassNames(it) }
    }

    /** sound_names.xml 이 있는 values 폴더 이름. */
    private val dirs: List<String> by lazy {
        resDir.listFiles { f -> f.isDirectory && (f.name == "values" || f.name.startsWith("values-")) }
            .orEmpty()
            .filter { File(it, FILE).isFile }
            .map { it.name }
            .sorted()
    }

    @Test
    fun `영어 이름은 모델의 클래스 목록과 번호와 이름이 같다`() {
        val english = parseStrings("values")
        assertEquals(YamnetCoarseClassifier.NUM_CLASSES, classNames.size)
        assertEquals("키는 sound_name_0 부터 번호 순서로", keys(), english.keys.toList())
        classNames.forEachIndexed { i, name ->
            assertEquals("sound_name_$i", name, unescape(english.getValue("sound_name_$i")))
        }
    }

    @Test
    fun `이름 배열은 번호 순서대로 이름을 가리키고 번역하지 않는다`() {
        val root = parse("values")
        val arrays = root.getElementsByTagName("string-array")
        assertEquals(1, arrays.length)
        val array = arrays.item(0) as Element
        assertEquals("sound_names", array.getAttribute("name"))
        assertEquals("번역 파일은 이름만 번역하고 배열은 영어 파일 하나만 둔다", "false", array.getAttribute("translatable"))
        val items = array.getElementsByTagName("item")
        val refs = (0 until items.length).map { items.item(it).textContent.trim() }
        assertEquals(keys().map { "@string/$it" }, refs)
    }

    @Test
    fun `한국어 이름이 있다`() {
        assertTrue("values-ko/$FILE 이 없습니다", "values-ko" in dirs)
    }

    @Test
    fun `번역은 모든 소리를 빠짐없이 가지고 다른 것은 두지 않는다`() {
        val problems = dirs.filter { it != "values" }.mapNotNull { dir ->
            val names = parseStrings(dir).keys
            val missing = keys() - names
            val extra = names - keys().toSet()
            if (missing.isEmpty() && extra.isEmpty()) null else "$dir: 빠짐 ${missing.take(5)}… (${missing.size}), 모르는 키 $extra"
        }
        assertTrue("번역은 521개를 모두 넣습니다:\n${problems.joinToString("\n")}", problems.isEmpty())

        val arraysInTranslations = dirs.filter { it != "values" && parse(it).getElementsByTagName("string-array").length > 0 }
        assertTrue("배열은 values/$FILE 에만 둡니다: $arraysInTranslations", arraysInTranslations.isEmpty())
    }

    @Test
    fun `한 언어 안에서 두 소리가 같은 이름을 쓰지 않는다`() {
        val problems = dirs.flatMap { dir ->
            parseStrings(dir).entries
                .groupBy({ normalized(it.value) }, { it.key })
                .filter { it.value.size > 1 }
                .map { (name, keys) -> "$dir: \"$name\" ← $keys" }
        }
        assertTrue("같은 이름은 어느 줄이 어느 소리인지 알 수 없습니다:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    @Test
    fun `이름은 비어 있지 않고 앞뒤에 공백이 없다`() {
        val problems = dirs.flatMap { dir ->
            parseStrings(dir).filterValues { it.isBlank() || it != it.trim() }.keys.map { "$dir: $it" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `번역이 있는 폴더는 모두 지원 언어다`() {
        val supported = AppLanguages.all.map { it.resourceDir }.toSet()
        assertTrue((dirs.toSet() - supported).toString(), (dirs.toSet() - supported).isEmpty())
    }

    private fun keys(): List<String> = (0 until YamnetCoarseClassifier.NUM_CLASSES).map { "sound_name_$it" }

    private fun parse(dir: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return factory.newDocumentBuilder().parse(File(resDir, "$dir/$FILE")).documentElement
    }

    /** 문서 순서대로 이름 → 값. 배열 안의 item 은 string 이 아니라서 들어오지 않는다. */
    private fun parseStrings(dir: String): LinkedHashMap<String, String> {
        val nodes = parse(dir).getElementsByTagName("string")
        val out = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            val name = element.getAttribute("name")
            assertTrue("$dir: $name 이 두 번 있습니다", name !in out)
            out[name] = element.textContent
        }
        return out
    }

    private companion object {
        const val FILE = "sound_names.xml"

        /** 안드로이드 문구 이스케이프를 푼다. 이름에는 \' \" \\ 만 나온다. */
        fun unescape(value: String): String = value.replace(Regex("""\\(.)""")) { it.groupValues[1] }

        /** 대소문자와 공백만 다른 이름도 같은 이름으로 본다. */
        fun normalized(value: String): String = unescape(value).lowercase().replace(Regex("\\s+"), " ").trim()
    }
}

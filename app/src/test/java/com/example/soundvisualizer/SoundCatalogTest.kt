package com.example.soundvisualizer

import com.example.soundvisualizer.ai.YamnetCoarseClassifier
import com.example.soundvisualizer.ai.YamnetThreeClassMapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 분류 탭의 목록(기본 종류)과 찾기·거르기. */
class SoundCatalogTest {

    companion object {
        private lateinit var entries: List<SoundEntry>

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val csv = SoundCatalogTest::class.java.classLoader
                ?.getResourceAsStream("ai/yamnet_class_map.csv")
                ?: File("src/main/assets/ai/yamnet_class_map.csv").inputStream()
            entries = SoundCatalog.fromNames(YamnetCoarseClassifier.loadClassNames(csv))
        }

        private fun entry(name: String): SoundEntry = entries.first { it.name == name }

        /** 테스트에서는 보이는 이름으로 영어 이름을 그대로 쓰되, [labels] 로 몇 개만 다른 말로 바꿔 본다. */
        private fun keys(labels: Map<String, String> = emptyMap()): List<String> =
            entries.map { SoundCatalog.searchKey(labels[it.name] ?: it.name, it.name) }

        private fun names(result: List<SoundEntry>) = result.map { it.name }
    }

    @After
    fun resetSettings() {
        SettingsManager.load(MemoryPrefs())
    }

    @Test
    fun `모델의 소리 521가지를 번호 순서대로 늘어놓는다`() {
        assertEquals(YamnetCoarseClassifier.NUM_CLASSES, entries.size)
        entries.forEachIndexed { i, e -> assertEquals(i, e.index) }
        assertEquals("Speech", entries.first().name)
        assertEquals("Gunshot, gunfire", entries[421].name)
    }

    @Test
    fun `기본 종류는 AI 의 매핑이고 사용자가 바꾼 것에 흔들리지 않는다`() {
        SettingsManager.load(MemoryPrefs())
        SettingsManager.setSoundType("Siren", AiClassification.AMBIENT)
        val fresh = SoundCatalog.fromNames(entries.map { it.name })
        assertEquals(AiClassification.DANGER, fresh.first { it.name == "Siren" }.defaultType)
        fresh.forEach { assertEquals(it.name, YamnetThreeClassMapper.mapDisplayNameToCoarse(it.name), it.defaultType) }
    }

    @Test
    fun `지금 종류는 바꾼 것이 있으면 그것, 없으면 기본 종류다`() {
        val overrides = mapOf("Siren" to AiClassification.AMBIENT)
        assertEquals(AiClassification.AMBIENT, SoundCatalog.typeOf(entry("Siren"), overrides))
        assertEquals(AiClassification.DANGER, SoundCatalog.typeOf(entry("Explosion"), overrides))
    }

    @Test
    fun `찾는 말이 없으면 거르기만 적용하고 순서를 지킨다`() {
        val all = SoundCatalog.filter(entries, keys(), emptyMap(), "", SoundFilter.ALL)
        assertEquals(entries, all)

        val danger = SoundCatalog.filter(entries, keys(), emptyMap(), "  ", SoundFilter.DANGER)
        assertTrue(danger.isNotEmpty())
        assertTrue(danger.all { it.defaultType == AiClassification.DANGER })
        assertEquals("번호 순서 그대로", danger.sortedBy { it.index }, danger)
    }

    @Test
    fun `보이는 이름과 모델 이름 어느 쪽으로도 찾는다`() {
        val labels = mapOf("Siren" to "사이렌", "Civil defense siren" to "민방위 사이렌")
        val k = keys(labels)
        assertEquals(listOf("Siren", "Civil defense siren"), names(SoundCatalog.filter(entries, k, emptyMap(), "사이렌", SoundFilter.ALL)))
        assertEquals(
            "영어 이름으로도 찾는다",
            listOf("Civil defense siren"),
            names(SoundCatalog.filter(entries, k, emptyMap(), "civil defense", SoundFilter.ALL))
        )
    }

    @Test
    fun `한글은 치는 중의 모든 모양에서 결과가 끊기지 않는다`() {
        // 한글 자판으로 칠 때 입력 칸에 차례로 들어오는 모양. 다음 글자의 첫소리가 앞 글자의 받침으로 먼저 붙는다.
        val typing = mapOf(
            "사이렌" to listOf("ㅅ", "사", "상", "사이", "사일", "사이레", "사이렌"),
            "화재 경보음" to listOf("ㅎ", "호", "화", "홪", "화재", "화잭", "화재 겨", "화재 경", "화재 경보", "화재 경봉", "화재 경보음"),
            "닭 울음소리" to listOf("ㄷ", "다", "달", "닭", "닭 울")
        )
        typing.forEach { (label, steps) ->
            val k = keys(mapOf("Siren" to label))
            steps.forEach { step ->
                assertTrue(
                    "\"$label\" 을 치는 중 \"$step\" 에서 찾아져야 한다",
                    "Siren" in names(SoundCatalog.filter(entries, k, emptyMap(), step, SoundFilter.ALL))
                )
            }
        }
    }

    @Test
    fun `대소문자, 띄어쓰기, 악센트를 가리지 않는다`() {
        val labels = mapOf("Siren" to "Sirène", "Fire engine, fire truck (siren)" to "Camion de pompiers")
        val k = keys(labels)
        assertEquals(listOf("Siren"), names(SoundCatalog.filter(entries, k, emptyMap(), "SIRENE", SoundFilter.ALL)))
        assertEquals(
            listOf("Fire engine, fire truck (siren)"),
            names(SoundCatalog.filter(entries, k, emptyMap(), "firetruck", SoundFilter.ALL))
        )
        assertEquals(
            listOf("Fire engine, fire truck (siren)"),
            names(SoundCatalog.filter(entries, k, emptyMap(), "camion de", SoundFilter.ALL))
        )
    }

    @Test
    fun `보이는 이름과 모델 이름을 이어 붙인 곳에서는 맞지 않는다`() {
        // "Rain" 을 "비" 로 보일 때 "비rain" 처럼 둘을 잇는 말은 어느 이름에도 없다.
        val k = keys(mapOf("Rain" to "비"))
        assertTrue(SoundCatalog.filter(entries, k, emptyMap(), "비rain", SoundFilter.ALL).isEmpty())
    }

    @Test
    fun `종류 거르기는 사용자가 바꾼 종류를 따른다`() {
        val overrides = mapOf("Siren" to AiClassification.AMBIENT, "Doorbell" to AiClassification.DANGER)
        val danger = names(SoundCatalog.filter(entries, keys(), overrides, "", SoundFilter.DANGER))
        assertTrue("Doorbell" in danger)
        assertTrue("Siren" !in danger)
        assertTrue("Siren" in names(SoundCatalog.filter(entries, keys(), overrides, "", SoundFilter.AMBIENT)))
    }

    @Test
    fun `바꾼 소리 거르기는 기본 종류와 다른 것만 보여 준다`() {
        val overrides = mapOf("Siren" to AiClassification.AMBIENT, "Doorbell" to AiClassification.DANGER)
        assertEquals(
            listOf("Siren", "Doorbell").sortedBy { name -> entry(name).index },
            names(SoundCatalog.filter(entries, keys(), overrides, "", SoundFilter.CHANGED))
        )
        assertEquals(
            "찾는 말과 함께 쓴다",
            listOf("Doorbell"),
            names(SoundCatalog.filter(entries, keys(), overrides, "door", SoundFilter.CHANGED))
        )
        assertTrue(SoundCatalog.filter(entries, keys(), emptyMap(), "", SoundFilter.CHANGED).isEmpty())
    }
}

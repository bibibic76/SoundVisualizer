package com.example.soundvisualizer

import com.example.soundvisualizer.ai.YamnetCoarseClassifier
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 묶음 테스트들(SoundGroupTableTest, SoundGroupsTest, SoundGroupSearchTest)이 함께 읽는 실제 파일.
 * 앱과 같은 모델 클래스 목록, 묶음 표, 문구를 읽는다. 유닛 테스트는 app 모듈 폴더에서 돈다.
 */
internal object SoundGroupFixtures {

    val tableFile = File("src/main/assets/sound_groups.tsv")

    /** 모델의 소리 이름 521개. 번호 순서다. */
    val names: List<String> by lazy {
        File("src/main/assets/ai/yamnet_class_map.csv").inputStream().use { YamnetCoarseClassifier.loadClassNames(it) }
    }

    val table: Map<String, SoundGroupRow> by lazy { tableFile.inputStream().use { SoundGroups.parseTable(it) } }

    /**
     * 기본 종류를 [defaultOf] 로 정한 목록. 앱([SoundCatalog.load])과 같은 표를 쓰고, 기본 종류만 바꿔 끼운다.
     * 묶음 표만 보는 테스트는 매핑이 바뀌어도 흔들리지 않게 기본 종류를 정해 두고 쓴다.
     */
    fun entries(defaultOf: (String) -> String = SettingsManager::defaultSoundType): List<SoundEntry> =
        SoundCatalog.fromNames(names, table, defaultOf)

    /** 묶음 표에서 [group] 의 소리 이름. 표의 순서다. */
    fun members(group: SoundGroup): List<String> = table.filterValues { it.group == group }.keys.toList()

    /** values 폴더 하나의 문구(strings.xml, sound_names.xml). 안드로이드가 하듯 백슬래시 이스케이프를 푼다. */
    fun strings(dir: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val out = HashMap<String, String>()
        for (fileName in listOf("strings.xml", "sound_names.xml")) {
            val file = File("src/main/res/$dir/$fileName")
            if (!file.isFile) continue
            val nodes = factory.newDocumentBuilder().parse(file).documentElement.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                out[element.getAttribute("name")] = element.textContent.replace(Regex("""\\(.)"""), "$1")
            }
        }
        return out
    }
}

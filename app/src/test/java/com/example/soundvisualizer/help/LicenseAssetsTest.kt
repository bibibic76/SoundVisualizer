package com.example.soundvisualizer.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 도움말 탭의 오픈소스 라이선스는 APK 에 넣은 복사본(app/src/main/assets/licenses)을 보여준다.
 * 저장소 루트의 NOTICE, LICENSE 만 고치고 복사본을 잊으면 배포되는 앱에 옛 고지가 남으므로 여기서 막는다.
 *
 * 유닛 테스트는 app 모듈 폴더에서 돈다.
 */
class LicenseAssetsTest {

    @Test
    fun `앱에 넣은 라이선스 고지가 저장소 원본과 같다`() {
        val pairs = mapOf(
            "../NOTICE" to "src/main/assets/licenses/NOTICE.txt",
            "../LICENSE" to "src/main/assets/licenses/LICENSE.txt"
        )
        for ((originalPath, copyPath) in pairs) {
            val original = File(originalPath)
            val copy = File(copyPath)
            assertTrue("원본이 없습니다: ${original.absolutePath}", original.isFile)
            assertTrue("복사본이 없습니다: ${copy.absolutePath}", copy.isFile)
            assertEquals(
                "${original.name} 를 고쳤으면 app/$copyPath 에도 복사하세요",
                original.readText().normalizeNewlines(),
                copy.readText().normalizeNewlines()
            )
        }
    }

    @Test
    fun `도움말이 읽는 경로가 복사본 위치와 같다`() {
        for (path in LICENSE_ASSETS) {
            assertTrue("assets 에 $path 가 없습니다", File("src/main/assets/$path").isFile)
        }
    }

    @Test
    fun `분류 탭의 묶음 표와 소리 이름을 온톨로지의 각색물로 적는다`() {
        // 묶음 표와 소리 이름(번역)은 AudioSet 온톨로지(CC BY-SA 4.0)를 바탕으로 만들었다(#349). 출처, 라이선스와 그 링크,
        // 바꾼 점을 고지에 적는다. 표 파일 머리글은 SoundGroupTableTest 가 본다.
        val notice = File("../NOTICE").readText().normalizeNewlines()
        listOf(
            "app/src/main/assets/sound_groups.tsv",
            "sound_names.xml",
            "\"AudioSet Ontology\" by Google Inc.",
            "https://github.com/audioset/ontology (commit d417d32)",
            "(CC BY-SA 4.0)",
            "https://creativecommons.org/licenses/by-sa/4.0/",
            "regrouped into everyday groups"
        ).forEach { assertTrue("NOTICE 에 \"$it\" 가 없습니다", it in notice) }
    }

    @Test
    fun `온톨로지의 라이선스를 데이터셋의 CC BY 4_0 으로 적지 않는다`() {
        // 온톨로지는 CC BY-SA 4.0, 데이터셋은 CC BY 4.0 이다. 예전 고지는 온톨로지를 CC BY 4.0 이라고 적었다(#342).
        val sentences = File("../NOTICE").readText().replace(Regex("\\s+"), " ").split(Regex("(?<=\\.) "))
        val wrong = sentences.filter { ("온톨로지" in it || "Ontology" in it) && "(CC BY 4.0)" in it }
        assertTrue("온톨로지를 CC BY 4.0 으로 적은 문장:\n${wrong.joinToString("\n")}", wrong.isEmpty())
    }

    private fun String.normalizeNewlines() = replace("\r\n", "\n")
}

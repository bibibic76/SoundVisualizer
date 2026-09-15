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

    private fun String.normalizeNewlines() = replace("\r\n", "\n")
}

package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 분류 탭은 AI 판정에 연결된 뒤(#321) 모든 사용자에게 보인다(#328). 자리는 설정과 도움말 사이다.
 * 화면 구성은 Compose 라 JVM 에서 그릴 수 없으므로 소스에서 확인한다.
 */
class ClassifyTabPlacementTest {

    private val launcher = File("src/main/java/com/example/soundvisualizer/LauncherApp.kt").readText(Charsets.UTF_8)

    @Test
    fun `분류 탭은 개발자 모드와 상관없이 보인다`() {
        assertFalse("탭 화면이 개발자 모드를 본다", launcher.contains("developerMode"))
        assertTrue("탭 수가 고정이 아니다", launcher.contains("rememberPagerState(initialPage = selectedTab) { TAB_COUNT }"))
    }

    @Test
    fun `탭은 홈 설정 분류 도움말 순서다`() {
        assertTrue(launcher.contains("private const val TAB_COUNT = 4"))
        assertTrue(launcher.contains("private const val TAB_CLASSIFY = 2"))
        assertTrue(launcher.contains("private const val TAB_HELP = 3"))
        assertTrue(launcher.contains("TAB_CLASSIFY -> ClassifyTab()"))
        assertTrue("도움말이 맨 뒤(나머지)가 아니다", launcher.contains("else -> HelpTab()"))
        val order = listOf("R.string.tab_home", "R.string.tab_settings", "R.string.tab_classify", "R.string.tab_help")
            .map { launcher.indexOf(it) }
        assertTrue("탭 버튼이 하나씩 있어야 한다", order.all { it >= 0 })
        assertEquals("탭 버튼 순서", order.sorted(), order)
        // 액티비티가 정해 주는 홈·설정의 번호는 그대로다(타일 길게 누르기, 멈춤 안내).
        val main = File("src/main/java/com/example/soundvisualizer/MainActivity.kt").readText(Charsets.UTF_8)
        assertTrue(main.contains("const val TAB_HOME = 0") && main.contains("const val TAB_SETTINGS = 1"))
    }
}

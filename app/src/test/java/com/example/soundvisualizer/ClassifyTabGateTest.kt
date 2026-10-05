package com.example.soundvisualizer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 분류 탭은 AI 판정에 연결되기 전(#291)이라 개발자 모드에서만 보여야 한다(#283). 바꿔도 아무 일도 일어나지 않는 탭이
 * 출시판에 보이면 고장으로 보인다. 화면 구성은 Compose 라 JVM 에서 그릴 수 없으므로 소스에서 확인한다.
 */
class ClassifyTabGateTest {

    private val launcher = File("src/main/java/com/example/soundvisualizer/LauncherApp.kt").readText(Charsets.UTF_8)

    /** [start] 뒤 첫 `{` 부터 짝이 맞는 `}` 까지. */
    private fun block(source: String, start: Int): String {
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        return source.substring(open)
    }

    @Test
    fun `분류 탭 버튼은 개발자 모드일 때만 그린다`() {
        assertTrue("분류 탭 버튼은 한 곳에만 있다", launcher.split("R.string.tab_classify").size == 2)
        val gate = launcher.indexOf("if (developerMode.value) {")
        assertTrue("개발자 모드 조건이 없다", gate >= 0)
        assertTrue("분류 탭 버튼이 개발자 모드 조건 블록 밖에 있다", block(launcher, gate).contains("R.string.tab_classify"))
    }

    @Test
    fun `탭 수는 개발자 모드를 따른다`() {
        val pager = launcher.substring(launcher.indexOf("rememberPagerState("), launcher.indexOf("val scope"))
        assertTrue("탭 수가 개발자 모드를 보지 않는다", pager.contains("developerMode.value") && pager.contains("TAB_COUNT_WITH_CLASSIFY"))
    }

    @Test
    fun `분류 탭은 맨 뒤라 다른 탭 번호가 바뀌지 않는다`() {
        assertTrue(launcher.contains("private const val TAB_COUNT = 3"))
        assertTrue(launcher.contains("private const val TAB_CLASSIFY = 3"))
        assertTrue(launcher.contains("TAB_CLASSIFY -> ClassifyTab()"))
        val main = File("src/main/java/com/example/soundvisualizer/MainActivity.kt").readText(Charsets.UTF_8)
        assertTrue(main.contains("const val TAB_HOME = 0") && main.contains("const val TAB_SETTINGS = 1"))
    }
}

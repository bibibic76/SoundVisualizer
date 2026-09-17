package com.example.soundvisualizer

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 앱을 켜서 Compose 가 첫 화면을 그리기 전에는 창 테마(res 의 values, values-v31 폴더 themes.xml)의 배경이 보인다.
 * 그 배경이 앱 배경색(BgColor)과 다르면 켤 때마다 다른 색이 번쩍이고, 밝은 테마면 흰 화면이 번쩍인다.
 * 기기 없이 리소스 파일을 읽어 막는다.
 *
 * 유닛 테스트는 app 모듈 폴더에서 돈다.
 */
class AppWindowThemeTest {

    private class Style(val parent: String?, val items: Map<String, String>)

    /** 부모를 따라 올라가 모은 스타일. [platformParent] 는 마지막에 닿은 플랫폼 테마 이름이다. */
    private class ResolvedStyle(val platformParent: String, val items: Map<String, String>)

    private val resDir = File("src/main/res")

    @Test
    fun `창 배경색 리소스가 Compose 화면 배경색과 같다`() {
        val colors = parseElements("values/colors.xml", "color")
        val value = colors[APP_BACKGROUND] ?: throw AssertionError("values/colors.xml 에 $APP_BACKGROUND 가 없습니다")
        assertEquals(
            "values/colors.xml 의 $APP_BACKGROUND 를 MainActivity.kt 의 BgColor 와 같게 맞추세요",
            BgColor.toArgb(),
            parseColor(value)
        )
    }

    @Test
    fun `Android 11 이하 시작 창은 어두운 테마에 앱 배경색을 깐다`() {
        val theme = resolve(APP_THEME, listOf("values"))
        assertFalse(
            "밝은 테마를 부모로 두면 앱을 켤 때 흰 화면이 번쩍입니다: ${theme.platformParent}",
            theme.platformParent.contains("Light")
        )
        assertEquals("창 배경", COLOR_REF, theme.items["android:windowBackground"])
        assertEquals("상태 표시줄", COLOR_REF, theme.items["android:statusBarColor"])
        assertEquals("내비게이션 바", COLOR_REF, theme.items["android:navigationBarColor"])
    }

    @Test
    fun `Android 12 이상 스플래시 배경도 앱 배경색이다`() {
        // API 31 이상 기기는 values-v31 을 먼저 보고, 없는 스타일은 values 에서 찾는다.
        val theme = resolve(APP_THEME, listOf("values-v31", "values"))
        assertEquals("창 배경", COLOR_REF, theme.items["android:windowBackground"])
        assertEquals(
            "values-v31/themes.xml 의 $APP_THEME 에 android:windowSplashScreenBackground 를 두세요",
            COLOR_REF,
            theme.items["android:windowSplashScreenBackground"]
        )
    }

    @Test
    fun `타일의 투명 화면은 시스템 바까지 투명하게 그린다`() {
        // 옛 플랫폼 투명 테마(Theme.Translucent.NoTitleBar)는 바 배경을 그리지 않아, 권한 안내 창이 떠 있는 동안
        // 보던 게임 위에 위아래 바만 검게 칠해졌다(#142).
        val themeRef = activityTheme(TILE_ACTIVITY)
        assertTrue(
            "$TILE_ACTIVITY 는 themes.xml 의 전용 투명 테마를 씁니다: $themeRef",
            themeRef.startsWith("@style/")
        )
        val theme = resolve(themeRef.removePrefix("@style/"), listOf("values-v31", "values"))
        assertTrue(
            "바 배경을 창이 그리는 Material 테마를 부모로 둡니다: ${theme.platformParent}",
            theme.platformParent.removePrefix("@").startsWith("android:Theme.Material")
        )
        assertEquals("투명 창", "true", theme.items["android:windowIsTranslucent"])
        assertEquals("창 배경", TRANSPARENT, theme.items["android:windowBackground"])
        assertEquals("상태 표시줄", TRANSPARENT, theme.items["android:statusBarColor"])
        assertEquals("내비게이션 바", TRANSPARENT, theme.items["android:navigationBarColor"])
    }

    /** 매니페스트에서 [name] 액티비티에 지정한 android:theme 값. */
    private fun activityTheme(name: String): String {
        val manifest = parseRoot(File("src/main/AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val element = activities.item(i) as Element
            if (element.getAttributeNS(ANDROID_NS, "name") == name) {
                return element.getAttributeNS(ANDROID_NS, "theme").ifEmpty {
                    throw AssertionError("매니페스트의 $name 에 android:theme 이 없습니다")
                }
            }
        }
        throw AssertionError("매니페스트에 $name 액티비티가 없습니다")
    }

    /**
     * [dirs] 순서대로 스타일을 찾고 부모를 따라 올라가며 항목을 모은다. 자식의 항목이 부모보다 앞선다.
     * android: 로 시작하는 부모(플랫폼 테마)에 닿으면 멈춘다.
     */
    private fun resolve(name: String, dirs: List<String>): ResolvedStyle {
        val stylesByDir = dirs.map { parseStyles(it) }
        val items = LinkedHashMap<String, String>()
        var current = name
        repeat(MAX_PARENT_DEPTH) { _ ->
            val style = stylesByDir.firstNotNullOfOrNull { styles -> styles[current] }
                ?: throw AssertionError("$dirs 의 themes.xml 에 $current 스타일이 없습니다")
            for ((key, value) in style.items) {
                if (key !in items) items[key] = value
            }
            val parent = style.parent ?: throw AssertionError("$current 에 parent 를 적어 주세요")
            if (parent.startsWith("android:") || parent.startsWith("@android:")) return ResolvedStyle(parent, items)
            current = parent.removePrefix("@style/")
        }
        throw AssertionError("$name 의 부모를 ${MAX_PARENT_DEPTH}단계 넘게 따라갔습니다")
    }

    private fun parseStyles(dir: String): Map<String, Style> {
        val file = File(resDir, "$dir/themes.xml")
        if (!file.isFile) return emptyMap()
        val root = parseRoot(file)
        val nodes = root.getElementsByTagName("style")
        val styles = LinkedHashMap<String, Style>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            val itemNodes = element.getElementsByTagName("item")
            val items = LinkedHashMap<String, String>()
            for (j in 0 until itemNodes.length) {
                val item = itemNodes.item(j) as Element
                items[item.getAttribute("name")] = item.textContent.trim()
            }
            styles[element.getAttribute("name")] = Style(element.getAttribute("parent").ifEmpty { null }, items)
        }
        return styles
    }

    private fun parseElements(path: String, tag: String): Map<String, String> {
        val file = File(resDir, path)
        if (!file.isFile) throw AssertionError("파일이 없습니다: ${file.absolutePath}")
        val nodes = parseRoot(file).getElementsByTagName(tag)
        return (0 until nodes.length).associate { i ->
            val element = nodes.item(i) as Element
            element.getAttribute("name") to element.textContent.trim()
        }
    }

    private fun parseRoot(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private companion object {
        const val APP_THEME = "Theme.SoundVisualizer"
        const val APP_BACKGROUND = "app_background"
        const val COLOR_REF = "@color/$APP_BACKGROUND"
        const val MAX_PARENT_DEPTH = 10
        const val TILE_ACTIVITY = ".tile.StartVisualizerActivity"
        const val TRANSPARENT = "@android:color/transparent"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /** #RRGGBB 나 #AARRGGBB 를 ARGB 정수로 바꾼다. */
        fun parseColor(value: String): Int {
            val hex = value.removePrefix("#")
            val argb = when (hex.length) {
                6 -> "FF$hex"
                8 -> hex
                else -> throw AssertionError("색은 #RRGGBB 나 #AARRGGBB 로 적습니다: $value")
            }
            return argb.toLong(16).toInt()
        }
    }
}

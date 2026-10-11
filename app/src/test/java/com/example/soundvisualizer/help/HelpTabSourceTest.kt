package com.example.soundvisualizer.help

import com.example.soundvisualizer.AiClassification
import com.example.soundvisualizer.feedback.HapticSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 도움말의 자주 묻는 질문이 외부 사운드 모드에서 그 모드의 답을 고르는지(#268, #354). Compose 화면이라 JVM 에서 그릴 수
 * 없으므로 소스에서 확인한다(HapticSettingRowSourceTest 와 같은 방식). 답의 문구는 res 에서 읽는다.
 */
class HelpTabSourceTest {

    private val help = File("src/main/java/com/example/soundvisualizer/help/HelpTab.kt").readText(Charsets.UTF_8)
        .split('\n')
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.contains("/**") }
        .joinToString("\n")

    @Test
    fun `외부 사운드 모드의 답이 있는 질문은 그 모드에서 그 답을 보인다`() {
        // 폰 안의 소리의 답은 화면 녹화 동의, 소리 캡처를 막는 앱, 화면이 꺼지면 쉬는 것, 미디어 볼륨을 말한다. 외부 사운드
        // 모드에는 맞지 않으므로 _external 답이 있는 질문은 모드에 따라 고른다(#268). 진동 답은 #354 에서 더했다.
        val externalAnswers = strings("values").keys.filter { it.startsWith("help_faq_") && it.endsWith("_external") }
        assertTrue("외부 사운드 모드의 진동 답이 없다", "help_faq_no_vibration_answer_external" in externalAnswers)
        for (key in externalAnswers) {
            val base = key.removeSuffix("_external")
            assertTrue("$key 를 외부 사운드 모드에서 고르지 않는다", help.contains("if (externalSoundMode) R.string.$key else R.string.$base"))
        }
    }

    @Test
    fun `외부 사운드 모드의 진동 답은 화면이 꺼지면 쉰다거나 미디어 볼륨을 올리라고 하지 않는다`() {
        // 외부 사운드 모드는 화면이 꺼져도 쉬지 않고(#260, CaptureSource.followsScreenOffPause) 마이크로 듣는다.
        val mediaVolume = mapOf("values" to "media volume", "values-ko" to "미디어 볼륨")
        for ((dir, volume) in mediaVolume) {
            val strings = strings(dir)
            val internal = strings.getValue("help_faq_no_vibration_answer")
            val external = strings["help_faq_no_vibration_answer_external"]
            assertNotNull("$dir 에 외부 사운드 모드의 진동 답이 없다", external)
            val pause = strings.getValue("setting_pause_screen_off")
            // 폰 안의 소리의 답에 둘 다 있어야 이 검사가 뜻이 있다.
            assertTrue("$dir: 폰 안의 소리의 답에 '$pause' 가 없다", internal.contains(pause))
            assertTrue("$dir: 폰 안의 소리의 답에 '$volume' 이 없다", internal.contains(volume))
            assertFalse("$dir: 외부 사운드 모드는 화면이 꺼져도 쉬지 않는다", external!!.contains(pause))
            assertFalse("$dir: 외부 사운드 모드는 폰 안의 소리를 받지 않는다", external.contains(volume))
        }
    }

    @Test
    fun `외부 사운드 모드의 진동 답은 대화음과 위협음의 상한을 적는다`() {
        // 상한(HapticSettings.externalCap)을 바꾸면 이 답도 함께 고친다(#354).
        for (dir in listOf("values", "values-ko")) {
            val strings = strings(dir)
            val external = strings.getValue("help_faq_no_vibration_answer_external")
            for (label in listOf(AiClassification.SPEECH, AiClassification.DANGER)) {
                val capName = strings.getValue("haptic_mode_" + HapticSettings.externalCap(label).name.lowercase())
                assertTrue("$dir: $label 의 상한 '$capName' 이 답에 없다", external.contains(capName))
            }
        }
    }

    /** 그 폴더의 strings.xml 문구(이름 → 글). 유닛 테스트는 app 모듈 폴더에서 돈다. */
    private fun strings(dir: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val root = factory.newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml")).documentElement
        val nodes = root.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val element = nodes.item(i) as Element
            element.getAttribute("name") to element.textContent
        }
    }
}

package com.example.soundvisualizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 쉬운 설정(#408)의 문구가 모든 언어에 있고, 도움말·튜토리얼이 쉬운 설정의 이름을 그 언어의 단추 글자 그대로 인용하는지 본다.
 *
 * 쉬운 설정은 글을 덜 읽게 하려고 만든 화면이라, 한 언어라도 빠지면 그 언어 사용자만 쉬운 화면에서 영어 낱말을 읽게 된다
 * (분류 탭 문구와 같은 까닭, SoundGroupStringsTest). 도움말과 튜토리얼은 색·배터리·진하기·진동 방식이 '자세한 설정'에
 * 있다고 알린다. 인용한 이름이 단추 글자와 다르면 사용자가 그 단추를 찾지 못한다(CONTRIBUTING 의 번역 규칙).
 *
 * 유닛 테스트는 app 모듈 폴더에서 돈다.
 */
class EasySettingsStringsTest {

    private val resDir = File("src/main/res")

    /** strings.xml 이 있는 언어 폴더(values, values-xx). values-v31 같은 다른 폴더는 뺀다. */
    private val dirs: List<String> by lazy {
        val found = resDir.listFiles { f -> f.isDirectory && (f.name == "values" || f.name.startsWith("values-")) }
            .orEmpty()
            .filter { File(it, "strings.xml").isFile }
            .map { it.name }
            .sorted()
        assertTrue("res 폴더를 찾지 못했습니다: ${resDir.absolutePath}", found.isNotEmpty())
        found
    }

    private val strings: Map<String, Map<String, String>> by lazy { dirs.associateWith { SoundGroupFixtures.strings(it) } }

    /** 쉬운 설정의 문구: 맨 위 두 칸(settings_view_*)과 쉬운 화면(easy_*). */
    private val easyKeys: List<String> by lazy {
        strings.getValue("values").keys.filter { it.startsWith("settings_view_") || it.startsWith("easy_") }.sorted()
    }

    /** '자세한 설정'에 있다고 알리는 도움말·튜토리얼 문구와, 쉬운 설정에서 자세한 설정으로 맞춘 값이라고 알리는 안내(#424). */
    private val pointsToDetailed = listOf(
        "easy_size_custom",
        "easy_vibration_custom",
        "tutorial_types_body",
        "help_modes_note",
        "help_sound_kinds",
        "help_sound_haptic",
        "help_note_screen_off",
        "help_faq_no_graphic_a",
        "help_faq_no_graphic_a_external",
        "help_faq_no_vibration_answer",
        "help_faq_battery_less_drawing"
    )

    @Test
    fun `쉬운 설정 문구는 모든 언어에 있다`() {
        assertTrue("쉬운 설정 문구를 찾지 못했습니다: $easyKeys", easyKeys.size >= 15)
        val missing = dirs.mapNotNull { dir ->
            val keys = easyKeys.filter { it !in strings.getValue(dir) }
            if (keys.isEmpty()) null else "$dir: ${keys.size}개 빠짐 $keys"
        }
        assertTrue("쉬운 설정 문구는 모든 언어에 넣습니다:\n${missing.joinToString("\n")}", missing.isEmpty())
    }

    @Test
    fun `두 칸의 이름은 서로 다르고 탭 이름과도 다르다`() {
        // 계측 시험은 탭을 글자 그대로 찾는다. 설정 탭 안에 탭 이름과 같은 글자의 노드가 있으면 둘이 걸려 찾지 못한다.
        for (dir in dirs) {
            val s = strings.getValue(dir)
            val tabs = listOf("tab_home", "tab_settings", "tab_classify", "tab_help").mapNotNull { s[it] }
            val easy = s.getValue("settings_view_easy")
            val detailed = s.getValue("settings_view_detailed")
            assertNotEquals("$dir: 두 칸의 이름이 같다", easy, detailed)
            assertFalse("$dir: '$easy' 가 탭 이름과 같다", easy in tabs)
            assertFalse("$dir: '$detailed' 가 탭 이름과 같다", detailed in tabs)
        }
    }

    @Test
    fun `도움말과 튜토리얼은 자세한 설정을 단추 글자 그대로 인용한다`() {
        val wrong = dirs.flatMap { dir ->
            val s = strings.getValue(dir)
            val detailed = s.getValue("settings_view_detailed")
            pointsToDetailed.filter { key -> !s.getValue(key).contains(detailed) }.map { key -> "$dir: $key 에 '$detailed' 가 없다" }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `진동 도움말은 쉬운 설정의 세 단계를 단추 글자 그대로 적는다`() {
        val wrong = dirs.flatMap { dir ->
            val s = strings.getValue(dir)
            val haptic = s.getValue("help_sound_haptic")
            listOf("settings_view_easy", "easy_vibration_off", "easy_vibration_low", "easy_vibration_high")
                .filter { key -> !haptic.contains(s.getValue(key)) }
                .map { key -> "$dir: help_sound_haptic 에 '${s.getValue(key)}'($key) 가 없다" }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }
}

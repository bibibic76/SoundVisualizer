package com.example.soundvisualizer.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguagesTest {

    private fun matched(tag: String?): String? = AppLanguages.match(tag)?.tag

    @Test
    fun `태그와 폴더와 이름이 겹치지 않는다`() {
        val all = AppLanguages.all
        assertEquals("태그", all.size, all.map { it.tag.lowercase() }.toSet().size)
        assertEquals("폴더", all.size, all.map { it.resourceDir }.toSet().size)
        assertEquals("이름", all.size, all.map { it.endonym }.toSet().size)
    }

    @Test
    fun `목록의 태그는 자기 자신과 맞는다`() {
        AppLanguages.all.forEach { assertEquals(it.tag, matched(it.tag)) }
    }

    @Test
    fun `영어가 목록 맨 앞의 기본 언어다`() {
        assertEquals(AppLanguages.DEFAULT_TAG, AppLanguages.all.first().tag)
        assertEquals("values", AppLanguages.all.first().resourceDir)
        assertTrue(AppLanguages.all.drop(1).all { it.resourceDir.startsWith("values-") })
    }

    @Test
    fun `지역이 붙어도 언어가 같으면 맞는다`() {
        assertEquals("en", matched("en-US"))
        assertEquals("en", matched("en_GB"))
        assertEquals("ko", matched("ko-KR"))
        assertEquals("pt-BR", matched("pt-PT"))
        assertEquals("pt-BR", matched("pt"))
        assertEquals("es", matched("es-419"))
        assertEquals("de", matched("DE-at"))
    }

    @Test
    fun `인도네시아어의 옛 코드도 맞는다`() {
        assertEquals("id", matched("in"))
        assertEquals("id", matched("in-ID"))
        assertEquals("id", matched("id-ID"))
    }

    @Test
    fun `중국어는 문자와 지역으로 간체와 번체를 가른다`() {
        assertEquals("zh-CN", matched("zh"))
        assertEquals("zh-CN", matched("zh-CN"))
        assertEquals("zh-CN", matched("zh-Hans"))
        assertEquals("zh-CN", matched("zh-SG"))
        assertEquals("zh-CN", matched("zh-Hans-HK"))
        assertEquals("zh-TW", matched("zh-TW"))
        assertEquals("zh-TW", matched("zh-Hant"))
        assertEquals("zh-TW", matched("zh-HK"))
        assertEquals("zh-TW", matched("zh-Hant-MO"))
    }

    @Test
    fun `지원하지 않거나 빈 태그는 맞는 언어가 없다`() {
        assertNull(matched(null))
        assertNull(matched(""))
        assertNull(matched("  "))
        assertNull(matched("sv-SE"))
        assertNull(matched("iw"))
    }
}

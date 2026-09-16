package com.example.soundvisualizer.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** 제보 링크가 깨지지 않고, 넘긴 값 말고는 아무것도 붙지 않는지 고정한다. */
class ReportLinkTest {

    private val env = ReportLink.environment(
        appVersion = "1.4.0",
        androidRelease = "15",
        sdkInt = 35,
        manufacturer = "samsung",
        model = "SM-G998N",
        language = "ko-KR"
    )

    @Test
    fun `환경 정보는 네 줄이고 넘긴 값만 담는다`() {
        assertEquals(
            listOf(
                "App version: 1.4.0",
                "Android: 15 (API 35)",
                "Device: samsung SM-G998N",
                "App language: ko-KR"
            ),
            env.lines()
        )
    }

    @Test
    fun `이슈 주소는 제목과 본문을 인코딩해 붙인다`() {
        val url = ReportLink.issueUrl("앱에서 보낸 제보", "무슨 일이 있었나요?\n\n무엇을 듣고 있었나요?", env)

        assertTrue(url.startsWith(ReportLink.ISSUES_URL + "?title="))
        assertTrue(url.contains("&body="))
        // 주소에는 날것의 공백이나 줄바꿈이 남으면 안 된다. 남으면 일부 앱이 링크를 잘라 버린다.
        assertFalse(url.contains(" "))
        assertFalse(url.contains("\n"))

        val title = URLDecoder.decode(url.substringAfter("?title=").substringBefore("&body="), "UTF-8")
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertEquals("앱에서 보낸 제보", title)
        assertEquals("무슨 일이 있었나요?\n\n무엇을 듣고 있었나요?\n\n$env", body)
    }

    @Test
    fun `본문 안내가 비어 있으면 환경 정보만 남는다`() {
        val url = ReportLink.issueUrl("제보", "", env)
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")

        assertEquals(env, body)
        assertFalse(body.startsWith("\n"))
    }

    @Test
    fun `메일과 이슈가 같은 본문을 쓴다`() {
        val prompt = "무슨 일이 있었나요?"
        val body = ReportLink.body(prompt, env)
        val fromUrl = URLDecoder.decode(
            ReportLink.issueUrl("제보", prompt, env).substringAfter("&body="), "UTF-8"
        )

        assertEquals(body, fromUrl)
        assertTrue(body.startsWith(prompt))
        assertTrue(body.endsWith(env))
    }

    @Test
    fun `메일 주소에 제목과 본문이 들어간다`() {
        val uri = ReportLink.mailtoUri("team@example.com", "앱에서 보낸 제보", "무슨 일이 있었나요?", env)

        assertTrue(uri.startsWith("mailto:team@example.com?subject="))
        assertTrue(uri.contains("&body="))
        // mailto 쿼리에서 + 는 공백이 아니라 글자 그대로다. 공백은 %20 으로 들어가야 한다.
        assertFalse(uri.contains("+"))
        assertFalse(uri.contains(" "))
        assertFalse(uri.contains("\n"))

        val subject = URLDecoder.decode(uri.substringAfter("?subject=").substringBefore("&body="), "UTF-8")
        val body = URLDecoder.decode(uri.substringAfter("&body="), "UTF-8")
        assertEquals("앱에서 보낸 제보", subject)
        assertEquals("무슨 일이 있었나요?\n\n$env", body)
    }

    @Test
    fun `빈 값이 와도 줄 수와 순서는 그대로다`() {
        val empty = ReportLink.environment("", "", 0, "", "", "")

        assertEquals(4, empty.lines().size)
        assertTrue(empty.startsWith("App version: "))
        assertTrue(empty.endsWith("App language: "))
    }
}

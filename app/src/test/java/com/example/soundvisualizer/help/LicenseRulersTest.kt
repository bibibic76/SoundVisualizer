package com.example.soundvisualizer.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 고지 창의 구분선 맞추기(#167). 80칸짜리 `====` 가 좁은 창에서 두세 줄로 꺾이지 않게 줄이되,
 * 구분선이 아닌 줄은 한 글자도 바꾸지 않는다.
 */
class LicenseRulersTest {

    private val ruler80 = "=".repeat(80)

    @Test
    fun `긴 구분선은 창에 들어가는 칸 수로 줄인다`() {
        assertEquals("=".repeat(35), fitRulers(ruler80, 35))
    }

    @Test
    fun `창이 더 넓으면 구분선을 늘리지 않는다`() {
        assertEquals(ruler80, fitRulers(ruler80, 120))
    }

    @Test
    fun `구분선이 아닌 줄은 그대로 둔다`() {
        val text = listOf(
            "1. ONNX Runtime (Android)",
            "a = b",
            "== 제목 ==",
            "=====",                       // 짧은 = 줄은 구분선이 아니라 내용으로 본다
            "    Permission is hereby granted, free of charge, to any person obtaining a"
        ).joinToString("\n")
        assertEquals(text, fitRulers(text, 20))
    }

    @Test
    fun `칸 수가 없으면 손대지 않는다`() {
        assertEquals(ruler80, fitRulers(ruler80, 0))
    }

    @Test
    fun `CRLF 로 읽혀도 창에 CR 이 남지 않는다`() {
        // Windows 작업 폴더(autocrlf)에서 빌드하면 APK 의 고지 파일이 CRLF 다. 남은 \r 이 한 칸을 차지해
        // 창 폭에 꽉 맞춘 구분선 뒤에서 넘쳐 빈 줄을 만들었다(#167).
        val shown = licenseDisplayText("머리말\r\n$ruler80\r\n1. ONNX Runtime\r\n", 35)
        assertEquals("머리말\n${"=".repeat(35)}\n1. ONNX Runtime\n", shown)
        assertFalse(shown.contains('\r'))
    }

    @Test
    fun `실제 고지 파일은 구분선만 바뀌고 나머지 줄은 그대로다`() {
        // 유닛 테스트는 app 모듈 폴더에서 돈다. Windows 에서는 이 파일이 CRLF 로 체크아웃된다.
        val notice = File("src/main/assets/licenses/NOTICE.txt").readText(Charsets.UTF_8)
        val before = notice.replace("\r\n", "\n").split('\n')
        val shown = licenseDisplayText(notice, 35)
        val after = shown.split('\n')

        assertFalse("창에 보일 글에 CR 이 남았습니다", shown.contains('\r'))
        assertEquals("줄 수", before.size, after.size)
        var rulers = 0
        for ((original, fitted) in before.zip(after)) {
            if (original.length >= 10 && original.all { it == '=' }) {
                rulers++
                assertEquals("=".repeat(35), fitted)
            } else {
                assertEquals(original, fitted)
            }
        }
        assertTrue("고지 파일에서 구분선을 찾지 못했습니다", rulers > 0)
    }
}

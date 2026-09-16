package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 실행 중 알림의 버튼이 보낸 것을 명령으로 바꾸는 규칙과, 그 버튼이 닿을 리시버가 선언돼 있는지를 고정한다.
 *
 * 유닛 테스트는 app 모듈 폴더에서 돈다.
 */
class NotificationCommandTest {

    @Test
    fun `중지 버튼은 멈춘다`() {
        assertEquals(
            NotificationCommand.Stop,
            NotificationCommand.parse(NotificationCommand.ACTION_STOP, -1, null)
        )
    }

    @Test
    fun `켜진 칩은 그 모드로 바꾼다`() {
        VisualMode.values().forEach { mode ->
            assertEquals(
                NotificationCommand.SetMode(mode),
                NotificationCommand.parse(NotificationCommand.ACTION_SET_MODE, mode.ordinal, true)
            )
        }
    }

    @Test
    fun `켜졌는지 알려 오지 않으면 켜진 것으로 본다`() {
        // Android 11 이하는 누른 칩 하나만 알려 오고 켜짐 값을 싣지 않는다.
        assertEquals(
            NotificationCommand.SetMode(VisualMode.Pad),
            NotificationCommand.parse(NotificationCommand.ACTION_SET_MODE, VisualMode.Pad.ordinal, null)
        )
    }

    @Test
    fun `꺼진 칩은 무시한다`() {
        // Android 12 이상에서 한 칩을 누르면 켜져 있던 칩이 꺼졌다는 것도 함께 온다.
        // 받아 주면 방금 끈 모드가 뒤늦게 덮어써 원래 모드로 돌아간다.
        VisualMode.values().forEach { mode ->
            assertEquals(
                NotificationCommand.Ignore,
                NotificationCommand.parse(NotificationCommand.ACTION_SET_MODE, mode.ordinal, false)
            )
        }
    }

    @Test
    fun `모르는 번호는 무시한다`() {
        // -1 은 번호가 실려 있지 않을 때 받는 쪽이 넣는 값이다.
        listOf(-1, VisualMode.values().size, Int.MAX_VALUE).forEach { ordinal ->
            assertEquals(
                NotificationCommand.Ignore,
                NotificationCommand.parse(NotificationCommand.ACTION_SET_MODE, ordinal, true)
            )
        }
    }

    @Test
    fun `모르는 동작은 무시한다`() {
        assertEquals(NotificationCommand.Ignore, NotificationCommand.parse(null, 0, true))
        assertEquals(NotificationCommand.Ignore, NotificationCommand.parse("", 0, true))
        assertEquals(NotificationCommand.Ignore, NotificationCommand.parse("com.example.other", 0, true))
    }

    @Test
    fun `버튼을 받을 리시버가 밖으로 열리지 않은 채 선언돼 있다`() {
        // 선언을 빠뜨리면 빌드는 되지만 알림 버튼이 전부 아무 일도 하지 않는다.
        val name = ".${NotificationActionReceiver::class.java.simpleName}"
        val receivers = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
            .getElementsByTagName("receiver")
        val matches = (0 until receivers.length)
            .map { receivers.item(it) as Element }
            .filter { it.getAttributeNS(ANDROID_NS, "name") == name }
        assertEquals("AndroidManifest.xml 에 $name 리시버가 하나 있어야 합니다", 1, matches.size)
        assertEquals("false", matches.single().getAttributeNS(ANDROID_NS, "exported"))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}

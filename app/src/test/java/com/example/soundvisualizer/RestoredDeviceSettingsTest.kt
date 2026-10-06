package com.example.soundvisualizer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 백업으로 다른 기기에서 옮겨 온 값 가리기 (#181).
 *
 * 자동 백업은 프리퍼런스 파일을 통째로 옮기고 규칙은 파일 단위라, 키 하나만 뺄 수 없다. 그대로 두면 새 폰에서
 * 앱을 처음 열었을 때 "꺼졌습니다" 안내가 뜬다. 그 기기에서는 켠 적이 없는데, 소리를 못 듣는 사용자에게는
 * "위협음 알림이 끊겼다" 는 뜻이라 일어나지도 않은 일로 그렇게 알리면 안 된다.
 */
class RestoredDeviceSettingsTest {

    /** 그 기기에서 쓰던 상태. 꺼짐 안내와 타일 추가 여부, 마이크 감도, 그리고 사용자 설정 하나. */
    private fun usedPrefs(): MemoryPrefs {
        val prefs = MemoryPrefs()
        prefs.edit()
            .putString(STOP_KEY, StopReason.ProjectionStopped.name)
            .putInt(SEQ_KEY, 3)
            .putBoolean(TILE_KEY, true)
            .putInt(MIC_KEY, 424)
            .putInt(COLOR_KEY, 0x112233)
            .apply()
        return prefs
    }

    @Test
    fun `다른 기기에서 복원하면 그 기기에만 뜻이 있는 값을 비운다`() {
        val prefs = usedPrefs()
        SettingsManager.load(prefs, deviceTag = "old-phone")
        SettingsManager.load(prefs, deviceTag = "new-phone")

        assertNull("복원한 폰에서 꺼짐 안내가 떴다", SettingsManager.lastUnexpectedStop.value)
        assertEquals(0, SettingsManager.lastUnexpectedStopSeq.value)
        assertFalse("타일이 없는 폰인데 추가된 것으로 본다", SettingsManager.tileAdded.value)
        assertEquals("마이크 감도는 그 폰의 마이크에 맞춘 값이다", MicSensitivity.DEFAULT, SettingsManager.micSensitivity.value)
        assertEquals("사용자 설정은 새 기기로 따라가야 한다", 0x112233, SettingsManager.colorAmbient.value)
    }

    @Test
    fun `같은 기기면 그대로 둔다`() {
        val prefs = usedPrefs()
        SettingsManager.load(prefs, deviceTag = "same-phone")
        // 프로세스가 죽었다 다시 뜬 경우
        SettingsManager.load(prefs, deviceTag = "same-phone")

        assertEquals(StopReason.ProjectionStopped, SettingsManager.lastUnexpectedStop.value)
        assertEquals(3, SettingsManager.lastUnexpectedStopSeq.value)
        assertTrue(SettingsManager.tileAdded.value)
        assertEquals(424, SettingsManager.micSensitivity.value)
    }

    @Test
    fun `기기 표시가 없던 예전 설치는 지우지 않고 표시만 남긴다`() {
        // 그냥 업데이트한 사용자. 지우면 멀쩡한 기기의 타일 설정과 안내가 사라진다.
        val prefs = usedPrefs()
        SettingsManager.load(prefs, deviceTag = "this-phone")

        assertEquals(StopReason.ProjectionStopped, SettingsManager.lastUnexpectedStop.value)
        assertTrue(SettingsManager.tileAdded.value)
        assertEquals("표시를 남겨야 다음에 옮겨 온 것을 가릴 수 있다", "this-phone", prefs.getString(DEVICE_KEY, null))
    }

    @Test
    fun `같은 기기에 다시 깔면 그 설치에만 뜻이 있는 값을 비운다`() {
        // 앱을 지우면 시스템이 빠른 설정 타일도 지우는데, 자동 백업이 "타일 추가됨" 을 되살려 설정 탭이 타일이 있다고
        // 말하고 추가 버튼을 숨겼다(#266). 기기 표시는 같아서 다른 기기에서 옮겨 온 경우로는 걸러지지 않는다.
        val prefs = usedPrefs()
        SettingsManager.load(prefs, deviceTag = "same-phone", installStamp = 1_000L)
        SettingsManager.load(prefs, deviceTag = "same-phone", installStamp = 2_000L)

        assertFalse("다시 깐 앱에는 타일이 없다", SettingsManager.tileAdded.value)
        assertNull("지난 설치의 꺼짐 안내가 떴다", SettingsManager.lastUnexpectedStop.value)
        assertEquals(0, SettingsManager.lastUnexpectedStopSeq.value)
        assertEquals("같은 폰의 마이크라 감도는 그대로 둔다", 424, SettingsManager.micSensitivity.value)
        assertEquals("사용자 설정은 그대로다", 0x112233, SettingsManager.colorAmbient.value)
    }

    @Test
    fun `같은 설치면 그대로 둔다`() {
        val prefs = usedPrefs()
        SettingsManager.load(prefs, deviceTag = "same-phone", installStamp = 1_000L)
        // 업데이트하거나 프로세스가 죽었다 다시 뜬 경우
        SettingsManager.load(prefs, deviceTag = "same-phone", installStamp = 1_000L)

        assertTrue(SettingsManager.tileAdded.value)
        assertEquals(StopReason.ProjectionStopped, SettingsManager.lastUnexpectedStop.value)
    }

    @Test
    fun `설치 표시가 없던 예전 설치는 지우지 않고 표시만 남긴다`() {
        // 이 표시가 생기기 전 버전에서 업데이트한 사용자. 지우면 멀쩡한 타일 설정과 안내가 사라진다(#204 와 같은 이유).
        val prefs = usedPrefs()
        SettingsManager.load(prefs, deviceTag = "this-phone", installStamp = 1_000L)

        assertTrue(SettingsManager.tileAdded.value)
        assertEquals(StopReason.ProjectionStopped, SettingsManager.lastUnexpectedStop.value)
        assertEquals("표시를 남겨야 다음에 다시 깐 것을 가릴 수 있다", 1_000L, prefs.getLong(INSTALL_KEY, 0L))
    }

    @After
    fun restoreSingleton() {
        // SettingsManager 는 싱글턴이라 테스트끼리 상태를 공유한다 (ScreenOffPauseTest 와 같은 이유).
        SettingsManager.load(MemoryPrefs())
    }

    private companion object {
        const val STOP_KEY = "last_unexpected_stop"
        const val SEQ_KEY = "last_unexpected_stop_seq"
        const val TILE_KEY = "tile_added"
        const val MIC_KEY = "mic_sensitivity"
        const val COLOR_KEY = "color_ambient"
        const val INSTALL_KEY = "install_stamp"
        const val DEVICE_KEY = "device_tag"
    }
}

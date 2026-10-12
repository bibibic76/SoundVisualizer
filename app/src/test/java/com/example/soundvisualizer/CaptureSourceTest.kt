package com.example.soundvisualizer

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 외부 사운드 모드가 고르는 소스와, 소스 하나가 함께 정하는 값들(#226).
 *
 * 모드를 끈 사용자는 오늘과 완전히 같아야 한다. 그리고 동의와 포그라운드 타입이 어긋난 조합은 Android 14 이상에서
 * 서비스를 죽이므로, 그런 조합이 없는지 여기서 고정한다.
 */
class CaptureSourceTest {

    @Test
    fun `모드를 끄면 지금까지와 같다`() {
        val source = CaptureSource.of(externalSoundMode = false)

        assertEquals(CaptureSource.InternalPlayback, source)
        assertTrue(source.needsProjectionConsent)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, source.foregroundServiceType)
        assertTrue(source.watchesBlockedCapture)
        assertTrue("폰 안의 소리는 화면이 꺼지면 쉬는 설정을 따른다", source.followsScreenOffPause)
        assertFalse("폰 안의 소리는 앱의 진동을 듣지 못한다", source.hearsOwnVibration)
    }

    @Test
    fun `모드를 켜면 동의 없이 마이크로 받는다`() {
        val source = CaptureSource.of(externalSoundMode = true)

        assertEquals(CaptureSource.Microphone, source)
        assertFalse("마이크 모드는 화면 녹화 동의 창을 띄우지 않는다", source.needsProjectionConsent)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, source.foregroundServiceType)
        assertFalse("마이크가 조용한 것으로 재생 중인 앱을 탓하지 않는다", source.watchesBlockedCapture)
        assertFalse("외부 사운드 모드는 화면이 꺼져도 듣는다(#260)", source.followsScreenOffPause)
        assertTrue("마이크는 앱의 진동을 소리로 듣는다(#290)", source.hearsOwnVibration)
    }

    @Test
    fun `동의 여부와 포그라운드 타입이 어긋난 소스가 없다`() {
        for (source in CaptureSource.entries) {
            val projectionType = source.foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            // 동의 없이 화면 녹화 타입을 시작하면 Android 14 이상에서 죽고,
            // 동의를 받고도 다른 타입으로 시작하면 getMediaProjection 이 거절된다.
            assertEquals(source.name, source.needsProjectionConsent, projectionType)
        }
    }

    @Test
    fun `포그라운드 타입은 하나씩만 넘긴다`() {
        for (source in CaptureSource.entries) {
            assertEquals(source.name, 1, Integer.bitCount(source.foregroundServiceType))
        }
    }

    @Test
    fun `저장된 설정이 없으면 내부 재생 소리를 받는다`() {
        assertEquals(CaptureSource.InternalPlayback, CaptureSource.of(SettingsManager.EXTERNAL_SOUND_MODE_DEFAULT))
    }
}

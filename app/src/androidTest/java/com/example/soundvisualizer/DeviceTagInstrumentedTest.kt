package com.example.soundvisualizer

import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 기기 표시의 출처 (#204).
 *
 * 백업으로 옮겨 온 값을 가려내는 표시가 무엇인지를 고정한다. 처음에는 [Build.FINGERPRINT] 를 썼는데
 * 그것은 OS 빌드가 바뀌면 달라져서, 업데이트를 받은 **같은 폰**을 "다른 기기" 로 보고 꺼짐 안내와
 * 타일 표시를 지웠다. OS 업데이트는 테스트로 흉내낼 수 없으니, 표시의 출처를 고정해 되돌리는 것을 막는다.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTagInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 기기_표시는_OS_빌드가_아니라_기기에서_온다() {
        val tag = SettingsManager.deviceTagOf(context)
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        if (androidId.isNullOrBlank()) {
            // 못 읽는 드문 기기에서는 예전처럼 물러난다. 그 경우까지 실패로 보지는 않는다.
            assertEquals("ANDROID_ID 를 못 읽으면 FINGERPRINT 로 물러나야 한다", Build.FINGERPRINT, tag)
            return
        }
        assertEquals(androidId, tag)
        assertNotEquals("OS 업데이트마다 바뀌는 값을 쓰고 있다", Build.FINGERPRINT, tag)
        assertTrue("표시가 비어 있으면 모든 기기가 같은 기기로 보인다", tag.isNotBlank())
    }

    @Test
    fun 같은_기기에서는_두_번_불러도_같다() {
        assertEquals(SettingsManager.deviceTagOf(context), SettingsManager.deviceTagOf(context))
    }
}

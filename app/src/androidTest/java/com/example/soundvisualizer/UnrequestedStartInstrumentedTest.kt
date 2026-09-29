package com.example.soundvisualizer

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 사용자가 켜지 않았는데 캡처 서비스가 새로 만들어지면, 포그라운드도 캡처도 시작하지 않고 내려가는지 본다.
 * ([CaptureStartToken])
 *
 * 전에는 onCreate 가 인텐트를 보기 전에 mediaProjection 포그라운드를 시작해서, Android 14 이상에서는 화면 녹화
 * 동의가 없어 이 프로세스가 죽었다. 그러면 테스트도 실패한다.
 */
@RunWith(AndroidJUnit4::class)
class UnrequestedStartInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // 떠 있으면 onCreate 없이 onStartCommand 로 가서 확인할 것이 없다.
        assumeFalse(AudioCaptureService.isRunning)
    }

    @Test
    fun oldNotificationButton_doesNotStartCapture() {
        // v1.4.0 이하의 알림 버튼은 동작 이름을 실어 서비스로 보냈다.
        startWithoutRequest(
            Intent(context, AudioCaptureService::class.java).setAction(NotificationCommand.ACTION_STOP)
        )
    }

    @Test
    fun plainStart_doesNotStartCapture() {
        startWithoutRequest(Intent(context, AudioCaptureService::class.java))
    }

    private fun startWithoutRequest(intent: Intent) {
        val before = AudioCaptureService.unrequestedStartCount
        context.startService(intent)

        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (AudioCaptureService.unrequestedStartCount == before && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_MS)
        }
        assertTrue("서비스가 걸러지지 않았습니다", AudioCaptureService.unrequestedStartCount > before)

        while (isServiceAlive() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_MS)
        }
        assertFalse("걸러진 서비스가 내려가지 않았습니다", isServiceAlive())
        assertFalse("걸러진 서비스가 실행 중으로 표시됐습니다", AudioCaptureService.isRunning)
        assertFalse("걸러진 서비스가 실행 중 알림을 올렸습니다", isRunningNotificationShown())
    }

    // getRunningServices 는 막혔지만 자기 앱의 서비스는 여전히 알려 준다.
    @Suppress("DEPRECATION")
    private fun isServiceAlive(): Boolean =
        context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == AudioCaptureService::class.java.name }

    private fun isRunningNotificationShown(): Boolean =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications.any { it.id == AudioCaptureService.NOTIFICATION_ID }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
    }
}

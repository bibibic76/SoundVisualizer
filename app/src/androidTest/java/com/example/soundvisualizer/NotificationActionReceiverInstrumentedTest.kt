package com.example.soundvisualizer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 캡처 서비스가 없을 때 실행 중 알림의 버튼이 눌려도 서비스를 새로 띄우지 않고, 남은 알림만 지우는지 본다.
 *
 * 서비스보다 오래 남은 알림은 손으로 만들기 어렵다. 그래서 같은 번호로 알림을 하나 올려 두고,
 * 알림 버튼이 보내는 것과 같은 브로드캐스트를 보낸다. 서비스를 띄우려 들면 Android 14 이상에서는
 * 화면 녹화 동의가 없어 이 프로세스가 죽으므로 테스트가 실패한다.
 */
@RunWith(AndroidJUnit4::class)
class NotificationActionReceiverInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        // 시각화가 켜져 있으면 진짜 실행 중 알림을 지우게 된다.
        assumeFalse(AudioCaptureService.isRunning)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        // 앱 알림을 꺼 둔 기기에서는 알림을 올릴 수 없어 확인할 것이 없다.
        assumeTrue(manager.areNotificationsEnabled())
        manager.createNotificationChannel(
            NotificationChannel(TEST_CHANNEL_ID, "test", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @After
    fun tearDown() {
        manager.cancel(AudioCaptureService.NOTIFICATION_ID)
        manager.deleteNotificationChannel(TEST_CHANNEL_ID)
    }

    @Test
    fun modeChipWithoutService_clearsNotificationAndDoesNotStartService() {
        pressWithoutService(
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationCommand.ACTION_SET_MODE)
                .putExtra(NotificationCommand.EXTRA_MODE_ORDINAL, VisualMode.Pad.ordinal)
        )
    }

    @Test
    fun stopWithoutService_clearsNotificationAndDoesNotStartService() {
        pressWithoutService(
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationCommand.ACTION_STOP)
        )
    }

    private fun pressWithoutService(button: Intent) {
        postLeftoverNotification()
        context.sendBroadcast(button)

        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (isLeftoverShown() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_MS)
        }
        assertFalse("남은 알림이 지워지지 않았습니다", isLeftoverShown())
        // 리시버는 메인 스레드에서 돈다. 서비스가 떴다면 onCreate 도 그 뒤에 메인 스레드에서 돈다.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertFalse("버튼 때문에 캡처 서비스가 떴습니다", AudioCaptureService.isRunning)
    }

    private fun postLeftoverNotification() {
        val notification = Notification.Builder(context, TEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("leftover")
            .build()
        manager.notify(AudioCaptureService.NOTIFICATION_ID, notification)
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!isLeftoverShown() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_MS)
        }
        assumeTrue("알림을 올리지 못했습니다", isLeftoverShown())
    }

    private fun isLeftoverShown(): Boolean =
        manager.activeNotifications.any { it.id == AudioCaptureService.NOTIFICATION_ID }

    private companion object {
        const val TEST_CHANNEL_ID = "NotificationActionReceiverTest"
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
    }
}

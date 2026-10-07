package com.example.soundvisualizer

import android.app.Notification
import android.app.NotificationManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 잠금 화면의 위협음 알림(#310)이 소리 없이, 잠금 화면에 보이게, 방해 금지에서도 보이는 알람으로 올라가는지.
 *
 * 알림을 실제로 올린다(소리·진동이 없는 채널). 사람이 쓰는 폰이 아니라 에뮬레이터에서 돌린다. 알림 권한이 필요하다(설치할 때 -g).
 */
@RunWith(AndroidJUnit4::class)
class DangerAlertInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @After
    fun cleanUp() {
        DangerAlert.cancel(context)
    }

    private fun posted(): Notification? =
        manager.activeNotifications.firstOrNull { it.id == 3 && it.packageName == context.packageName }?.notification

    /**
     * 알림이 올라가 있는지가 [present] 가 될 때까지 최대 2초 기다린다. 올리기와 치우기는 시스템(NotificationManagerService)이
     * 나중에 처리하고 `waitForIdleSync` 는 앱의 메인 스레드만 기다리므로, 바로 보면 아직 바뀌기 전일 수 있다(#347).
     */
    private fun awaitPosted(present: Boolean): Notification? {
        val deadline = SystemClock.uptimeMillis() + 2_000
        var notification = posted()
        while ((notification != null) != present && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(20)
            notification = posted()
        }
        return notification
    }

    @Test
    fun alertIsASilentPublicAlarmOnItsOwnChannel() {
        assumeTrue("알림이 꺼져 있으면 올리지 않는다", manager.areNotificationsEnabled())
        assertTrue(DangerAlert.show(context, "Siren"))

        val notification = awaitPosted(present = true)
        assertNotNull("알림이 올라가지 않았다", notification)
        assertEquals(Notification.CATEGORY_ALARM, notification!!.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue("들린 소리 이름이 문구에 있다: $text", text.contains("Siren"))

        val channel = manager.getNotificationChannel(notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull("소리가 없어야 한다", channel.sound)
        assertFalse("진동은 앱의 위협음 진동이 맡는다", channel.shouldVibrate())

        DangerAlert.cancel(context)
        assertNull("치우면 사라진다", awaitPosted(present = false))
    }

    /** 화면을 끈 뒤(adb shell input keyevent KEYCODE_SLEEP) 돌린다. 켜져 있으면 건너뛴다. */
    @Test
    fun wakesTheScreenWhenItIsOff() {
        val power = context.getSystemService(PowerManager::class.java)
        assumeTrue("화면이 꺼져 있을 때만 확인한다", !power.isInteractive)
        assumeTrue(manager.areNotificationsEnabled())
        assertTrue(DangerAlert.show(context, null))
        val deadline = SystemClock.uptimeMillis() + 2_000
        while (!power.isInteractive && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("꺼진 화면을 켜야 한다", power.isInteractive)
    }
}

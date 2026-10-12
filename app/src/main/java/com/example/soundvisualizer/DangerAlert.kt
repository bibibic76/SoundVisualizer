package com.example.soundvisualizer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * 폰이 잠겨 있거나 화면이 꺼진 동안 위협음이 들렸다고 잠금 화면에 알린다(#310). 언제 알릴지는 [DangerAlertPolicy] 가 정한다.
 *
 * 오버레이는 잠금 화면에 가려 보이지 않으므로, 그동안의 위협음은 이 알림과 앱의 위협음 진동으로 알린다.
 * 문구는 넘겨받은 [Context] 에서 꺼낸다. 캡처 서비스의 문구용 컨텍스트를 넘기면 Android 12 이하에서도 앱 언어로 나온다.
 */
object DangerAlert {

    /**
     * 실행 중 알림·꺼짐 알림과 다른 채널. 채널의 소리·진동은 처음 만든 뒤로 앱이 바꿀 수 없으므로, 둘을 바꾸려면 ID 도 바꿔야 한다.
     */
    private const val CHANNEL_ID = "DangerSoundAlertChannel"

    /** 실행 중 알림은 1 번, 꺼짐 알림은 2 번이다. */
    private const val NOTIFICATION_ID = 3

    private const val REQUEST_OPEN_APP = 12

    /** 화면을 켜 두는 시간. 그 뒤로는 폰의 화면 꺼짐 시간을 따른다(ON_AFTER_RELEASE). */
    private const val WAKE_MS = 3_000L

    /** 잠금을 풀지 않아도 이만큼 지나면 알림을 치운다. 오래된 "들렸다" 는 지금의 상황이 아니다. */
    private const val TIMEOUT_MS = 5 * 60_000L

    /**
     * 채널을 만든다. 여러 번 불러도 된다.
     *
     * 중요도는 HIGH 다. 잠금 화면과 꺼진 화면에서 알림이 보이고 팝업으로 내려오려면 HIGH 가 필요하다. 소리는 필요 없다.
     * 소리와 진동은 모두 끈다. 진동은 앱의 위협음 진동이 맡고, 시스템 알림 진동이 그것을 끊고 덮어쓰지 않게 한다
     * ([StopAlert.createChannel] 과 같은 이유).
     */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.danger_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.danger_alert_channel_desc)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * 잠금 화면에 알리고, 화면이 꺼져 있으면 잠깐 켠다. 알림을 올리지 못했으면(알림 권한·채널이 꺼짐) 화면도 켜지 않는다.
     *
     * @param soundName 들린 소리의 이름(앱 언어). 모르면 null 이고 이름 없이 알린다.
     * @return 알림을 올렸으면 true
     */
    fun show(context: Context, soundName: String?): Boolean {
        createChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (soundName.isNullOrBlank()) {
            context.getString(R.string.danger_alert_text)
        } else {
            context.getString(R.string.danger_alert_text_named, soundName)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.danger_alert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // 알람 카테고리는 기본 방해 금지 설정에서도 보인다(알람은 허용). 위협음은 그때도 알아야 한다.
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // 채널의 잠금 화면 공개 여부는 시스템이 앱 설정으로 덮어쓰므로 알림에도 적는다.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setTimeoutAfter(TIMEOUT_MS)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        val posted = StopAlert.post(context, CHANNEL_ID, NOTIFICATION_ID, notification)
        if (posted) wakeScreenIfOff(context)
        return posted
    }

    /** 잠금을 풀었거나 더는 맞지 않는 알림을 치운다. 여러 번 불러도 된다. */
    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /**
     * 화면이 꺼져 있으면 [WAKE_MS] 동안 켠다.
     *
     * 알림만으로는 꺼진 화면이 켜지지 않는다(폰의 "알림이 오면 화면 켜기" 설정과 방해 금지·절전에 따라 다르다).
     * 전체 화면 인텐트는 Android 14 부터 플레이 정책상 전화·알람 앱만 쓸 수 있어 쓰지 않는다.
     * ACQUIRE_CAUSES_WAKEUP 은 API 33 부터 deprecated 지만 여전히 동작하고, Android 14~17 은 TURN_SCREEN_ON 권한이 없어도
     * 허용한다(그 강제는 아직 켜지지 않았다). 시간을 정해 잡으므로 놓아 주는 것을 잊어도 저절로 풀린다.
     */
    @Suppress("DEPRECATION")
    private fun wakeScreenIfOff(context: Context) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        if (power.isInteractive) return
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "SoundVisualizer:DangerAlert"
        ).acquire(WAKE_MS)
    }
}

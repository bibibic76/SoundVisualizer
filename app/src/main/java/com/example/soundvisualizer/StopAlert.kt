package com.example.soundvisualizer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.example.soundvisualizer.feedback.HapticPlayer
import com.example.soundvisualizer.tile.StartVisualizerActivity

/**
 * 시각화가 사용자 모르게 꺼졌을 때 알린다. 무엇으로 알릴지는 [StopAlertPlan] 이 정한다.
 *
 * 문구는 넘겨받은 [Context] 에서 꺼낸다. 캡처 서비스를 넘기면 Android 12 이하에서도 앱 언어로 나온다.
 */
object StopAlert {

    /** 실행 중 알림(AudioCaptureService)과 다른 채널. 그쪽은 조용한 저중요도라 이 알림을 묻는다. */
    private const val CHANNEL_ID = "VisualizerStoppedChannel"

    /** 실행 중 알림은 1 번이다. */
    private const val NOTIFICATION_ID = 2

    private const val REQUEST_OPEN_APP = 10
    private const val REQUEST_TURN_ON = 11

    /**
     * 채널을 만든다. 여러 번 불러도 된다. 사용자가 바꾼 중요도·소리 설정은 시스템이 지킨다.
     *
     * 중요도는 HIGH 다. 청각장애 사용자는 알림음을 못 들으니, 게임 화면 위로 잠깐 내려오는 팝업(헤드업)이
     * 없으면 상태 표시줄 아이콘만으로는 꺼진 줄 모른다. 자주 뜨지 않는 알림이라 방해도 적다.
     * 채널 진동은 끈다. 시스템 알림 진동이 뒤따라 울리면 앞서 울린 고유 진동이 끊겨 그 알림 진동으로 바뀐다.
     */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.stopped_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.stopped_channel_desc)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** 다시 켜졌으면 지난 "꺼졌습니다" 알림은 틀린 정보라 치운다. */
    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /**
     * 멈춘 이유에 맞게 알린다. 사용자가 끈 경우에는 아무것도 하지 않는다. 메인 스레드에서 부른다(토스트).
     *
     * 진동은 진동 알림을 멈춘 뒤에 불러야 한다. 진동 알림의 cancel() 이 이 진동까지 끊는다.
     */
    fun show(context: Context, reason: StopReason) {
        val player = HapticPlayer(context)
        val plan = StopAlertPlan.decide(reason, player.hasVibrator)
        if (plan.vibrate) player.playStoppedAlert()
        val posted = plan.notify && post(context, CHANNEL_ID, NOTIFICATION_ID, buildNotification(context, reason))
        if (plan.toastAfter(posted)) {
            // 서비스는 곧 사라지므로 앱 컨텍스트로 띄우고, 문구는 앱 언어가 입혀진 넘겨받은 컨텍스트에서 꺼낸다.
            Toast.makeText(context.applicationContext, context.getString(R.string.stopped_toast), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 알림을 올릴 수 있으면 올리고 true, 없으면 올리지 않고 false.
     *
     * 올릴 수 없는 경우: Android 13 이상에서 알림 권한 거부, 앱 알림 끄기, 해당 채널 끄기.
     * 권한은 켤 때 묻지만 거부해도 앱은 돈다. 이때 notify() 는 예외 없이 조용히 버려지므로 미리 확인해야
     * 토스트로 대신할 수 있다.
     */
    fun post(context: Context, channelId: String, id: Int, notification: Notification): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        // POST_NOTIFICATIONS 는 Android 13 에 생긴 권한이다. 그 아래에서 물으면 항상 거부로 나오므로 버전으로 거른다.
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channelBlocked = manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE
        if (!permitted || !manager.areNotificationsEnabled() || channelBlocked) return false
        manager.notify(id, notification)
        return true
    }

    private fun buildNotification(context: Context, reason: StopReason): Notification {
        createChannel(context)
        val text = context.getString(textFor(reason))
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 빠른 설정 타일과 같은 켜기 흐름을 탄다. 화면 녹화 동의는 안드로이드 정책상 켤 때마다 다시 받아야 하므로
        // 서비스를 바로 되살릴 수 없고, 권한·동의를 받는 투명 화면을 연다. 닫히면 보던 게임으로 돌아간다.
        val turnOn = PendingIntent.getActivity(
            context,
            REQUEST_TURN_ON,
            Intent(context, StartVisualizerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.stopped_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            // 민감한 내용이 없으니 잠금 화면에서도 무엇이 꺼졌는지 보이게 한다.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .addAction(0, context.getString(R.string.stopped_turn_on), turnOn)
            .setAutoCancel(true)
            .build()
    }

    @StringRes
    private fun textFor(reason: StopReason): Int = when (reason) {
        StopReason.ProjectionStopped -> R.string.stopped_text_projection
        StopReason.CaptureError -> R.string.stopped_text_capture_error
        // 사용자가 끈 경우에는 알림을 만들지 않지만 when 을 빠짐없이 채우려고 가장 일반적인 문구를 둔다.
        StopReason.StartFailed, StopReason.UserRequested -> R.string.stopped_text_start_failed
    }
}

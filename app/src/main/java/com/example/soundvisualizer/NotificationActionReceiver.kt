package com.example.soundvisualizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

/**
 * 실행 중 알림의 버튼([중지], 모드 칩)을 받아 떠 있는 캡처 서비스에 전한다.
 *
 * 버튼을 서비스로 바로 보내지 않는다. 서비스로 보내는 PendingIntent 는 서비스가 떠 있지 않으면 새로 만들고,
 * 그러면 onCreate 가 화면 녹화 동의 없이 mediaProjection 포그라운드를 시작한다. Android 14 부터는 거기서 죽고,
 * 그 아래에서도 캡처 없이 알림만 떠 있는 서비스가 남는다. 브로드캐스트는 서비스를 만들지 않으므로,
 * 서비스가 없으면 남은 알림만 지운다([AudioCaptureService.handleNotificationCommand]).
 *
 * 밖으로 열지 않는다(exported=false). 알림의 PendingIntent 는 우리 앱 이름으로 보내지므로 그대로 닿는다.
 * 메인 스레드에서 돈다. 서비스의 onCreate·onDestroy 와 같은 스레드라 그 사이에 끼어들지 않는다.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 모드 칩이 켜졌는지는 Android 12 이상에서만 알림을 그리는 쪽이 채워 준다. ([NotificationCommand.parse])
        val checked = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && intent.hasExtra(RemoteViews.EXTRA_CHECKED)
        ) {
            intent.getBooleanExtra(RemoteViews.EXTRA_CHECKED, true)
        } else {
            null
        }
        val command = NotificationCommand.parse(
            intent.action,
            intent.getIntExtra(NotificationCommand.EXTRA_MODE_ORDINAL, -1),
            checked
        )
        AudioCaptureService.handleNotificationCommand(context, command)
    }
}

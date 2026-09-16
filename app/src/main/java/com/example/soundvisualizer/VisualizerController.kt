package com.example.soundvisualizer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * 시각화를 켜고 끄는 공용 진입점. 앱의 실행·실행 종료 버튼과 빠른 설정 타일이 같은 코드를 쓴다.
 *
 * 켜기는 두 단계다. 런타임 권한과 화면 녹화 동의는 액티비티에서만 받을 수 있으므로 호출하는 쪽이 받고,
 * 동의 결과를 [start] 에 넘기면 캡처와 오버레이 서비스를 띄운다.
 */
object VisualizerController {

    val isRunning: Boolean get() = AudioCaptureService.isRunning

    // POST_NOTIFICATIONS 는 문자열 상수라 구버전에서 참조해도 안전하고, sdkInt 로 걸러낸다. (Lint InlinedApi)
    /**
     * 켜기 전에 요청해야 하는 런타임 권한 중 아직 없는 것.
     * RECORD_AUDIO 는 내부 오디오 캡처(AudioPlaybackCapture)에 필수이고,
     * POST_NOTIFICATIONS 는 실행 중 알림 표시용(Android 13+)이라 거부해도 켤 수 있다.
     */
    @SuppressLint("InlinedApi")
    fun requiredPermissions(sdkInt: Int, isGranted: (String) -> Boolean): Array<String> {
        val needed = ArrayList<String>(2)
        if (!isGranted(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO)
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU && !isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return needed.toTypedArray()
    }

    /**
     * 시스템 권한 창보다 마이크 권한이 필요한 이유를 먼저 설명할지.
     * 알림 권한만 남았으면 오해할 일이 없으니 설명 없이 바로 묻는다. ([CapturePermissionFlow])
     */
    fun needsMicRationale(needed: Array<String>): Boolean = Manifest.permission.RECORD_AUDIO in needed

    /**
     * 캡처에 꼭 필요한 권한이 있는지. 권한 요청 결과 맵에는 이번에 물어본 권한만 들어 있어서,
     * 알림 권한만 물어본 경우에도 맞게 판단하려면 결과 맵 대신 실제 상태를 본다.
     */
    fun hasCapturePermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 화면 녹화 동의 결과로 캡처와 오버레이를 시작한다. 실행 상태는 서비스가 실제로 뜨면서 스스로 알린다. */
    fun start(context: Context, resultCode: Int, data: Intent) {
        val capture = Intent(context, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
        }
        context.startForegroundService(capture)
        context.startService(Intent(context, OverlayService::class.java))
    }

    // stopService 는 Intent 의 대상 컴포넌트로 서비스를 찾으므로 새로 만든 Intent 로 멈추는 게 맞다.
    // Lint(ImplicitSamInstance)는 새 인스턴스라 아무것도 멈추지 못한다고 보지만 오탐이다.
    /** 캡처와 오버레이를 멈춘다. 이미 꺼져 있어도 안전하다. */
    @SuppressLint("ImplicitSamInstance")
    fun stop(context: Context) {
        // stopService 는 onDestroy 까지 시간이 걸린다. 그사이 서비스가 실행 중 알림을 다시 올리지 않도록
        // 내리기 전에 알린다. (AudioCaptureService.stopRequested)
        AudioCaptureService.markStopRequested()
        context.stopService(Intent(context, AudioCaptureService::class.java))
        context.stopService(Intent(context, OverlayService::class.java))
        SettingsManager.setServiceRunning(false)
    }
}

package com.example.soundvisualizer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri

/** 권한 흐름에서 지금 떠 있는 안내 창. */
enum class CapturePermissionDialog {
    None,

    /** 앱 목록만 뜨는 오버레이 권한 설정 화면으로 보내기 전에, 무엇을 켜야 하는지 설명한다. */
    Overlay,

    /** 시스템 권한 창을 띄우기 전에 마이크 권한이 필요한 이유를 설명한다. */
    MicRationale,

    /** 거부된 뒤 시스템이 더는 권한 창을 띄우지 않는 것으로 보여, 설정 화면에서 허용하도록 안내한다. */
    MicSettings
}

/**
 * 시각화를 켜기 전에 권한을 받는 흐름. 앱의 실행 버튼([MainActivity])과
 * 빠른 설정 타일의 투명 화면(`tile/StartVisualizerActivity`)이 같은 흐름과 안내 창을 쓴다.
 *
 * - 오버레이 권한(다른 앱 위에 표시)이 없으면 설정 화면으로 보내기 전에 무엇을 해야 하는지 설명한다.
 *   그 화면은 앱 목록만 뜨고 어느 앱을 켜야 하는지 알려주지 않아서, 처음 보면 그냥 나가기 쉽다.
 *   (타일의 투명 화면은 여기까지 오지 않는다. 설정 화면이 필요해 앱을 열어 안내한다.)
 * - 마이크 권한이 필요하면 시스템 창보다 이유를 먼저 설명한다. 시스템 창에는 "마이크"라고만 떠서
 *   녹음 앱으로 오해하고 거부하기 쉽다.
 * - 거부됐는데 시스템이 더는 창을 띄우지 않는 상태면 설정 화면에서 허용하도록 안내한다.
 *   그대로 두면 다시 켜려 해도 창 없이 거부만 돌아와 사용자가 할 수 있는 게 없다.
 *
 * 권한 요청 결과를 받을 콜백은 액티비티가 시작되기 전에 등록돼야 하므로 액티비티의 필드로 만든다.
 *
 * @param onGranted 캡처에 필요한 권한이 모두 있을 때. 이어서 화면 녹화 동의를 받는다.
 * @param onStopped 켜지 않고 끝났을 때 (취소, 거부, 설정 화면으로 보냄). 투명 화면은 여기서 닫는다.
 * @param onOverlaySettings 오버레이 권한 설정 화면으로 보냈을 때. 허용하고 돌아오면 실행을 이어가려고 기억해 둔다([PendingStart]).
 */
class CapturePermissionFlow(
    private val activity: ComponentActivity,
    private val onGranted: () -> Unit,
    private val onStopped: () -> Unit = {},
    private val onOverlaySettings: () -> Unit = {}
) {
    /** 액티비티의 Compose 화면이 [CapturePermissionDialogs] 로 그린다. */
    var dialog by mutableStateOf(CapturePermissionDialog.None)
        private set

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        onRequestResult(micAsked = Manifest.permission.RECORD_AUDIO in result)
    }

    init {
        // 창 상태를 rememberSaveable 이 아니라 액티비티의 저장 상태에 둔다. 회전 뒤 권한 요청 결과는
        // 화면을 처음 그리기 전(onStart)에 도착할 수 있어서 Compose 안의 상태로는 받을 곳이 없다.
        activity.savedStateRegistry.registerSavedStateProvider(SAVED_STATE_KEY) {
            Bundle().apply { putString(KEY_DIALOG, dialog.name) }
        }
        // 저장 상태는 onCreate 에서 복원된 뒤에만 꺼낼 수 있고, 이 콜백이 그 시점에 불린다.
        activity.addOnContextAvailableListener {
            activity.savedStateRegistry.consumeRestoredStateForKey(SAVED_STATE_KEY)
                ?.getString(KEY_DIALOG)
                ?.let { name -> dialog = CapturePermissionDialog.valueOf(name) }
        }
    }

    /** 권한이 모두 있으면 바로 [onGranted], 아니면 안내 창이나 시스템 권한 창부터 띄운다. */
    fun start() {
        if (dialog != CapturePermissionDialog.None) return
        // 오버레이 권한부터 본다. 이것이 없으면 캡처까지 다 받아도 화면에 아무것도 그리지 못한다.
        if (!Settings.canDrawOverlays(activity)) {
            dialog = CapturePermissionDialog.Overlay
            return
        }
        val needed = neededPermissions()
        when {
            needed.isEmpty() -> onGranted()
            VisualizerController.needsMicRationale(needed) -> dialog = CapturePermissionDialog.MicRationale
            else -> launcher.launch(needed)
        }
    }

    /**
     * 오버레이 권한 안내 창의 ‘설정 열기’. 앱 목록에서 이 앱을 찾아 스위치를 켜는 화면으로 보낸다.
     *
     * 허용하고 돌아왔을 때 이어서 켜는 것은 부른 쪽의 몫이다([onOverlaySettings], `MainActivity.onResume`).
     * 이 화면은 권한 요청 결과를 돌려주지 않아서 여기서는 돌아온 때를 알 수 없다.
     */
    fun openOverlaySettings() {
        if (dialog != CapturePermissionDialog.Overlay) return
        dialog = CapturePermissionDialog.None
        activity.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${activity.packageName}".toUri())
        )
        onOverlaySettings()
    }

    /** 이유 안내 창의 ‘계속’. */
    fun continueRequest() {
        // 연달아 눌러도 시스템 권한 창은 한 번만 띄운다.
        if (dialog != CapturePermissionDialog.MicRationale) return
        dialog = CapturePermissionDialog.None
        // 물어볼 목록은 저장해 두지 않고 누를 때 다시 구한다. 회전 뒤에도 그대로 쓸 수 있고, 그사이 허용된 권한은 빠진다.
        val needed = neededPermissions()
        if (needed.isEmpty()) onGranted() else launcher.launch(needed)
    }

    /** 설정 안내 창의 ‘설정 열기’. 앱 정보 화면의 권한에서 마이크를 허용할 수 있다. */
    fun openAppSettings() {
        if (dialog != CapturePermissionDialog.MicSettings) return
        dialog = CapturePermissionDialog.None
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${activity.packageName}".toUri())
        )
        onStopped()
    }

    /** 안내 창의 취소·닫기, 또는 뒤로 가기나 창 바깥을 눌러 닫았을 때. */
    fun dismiss() {
        if (dialog == CapturePermissionDialog.None) return
        dialog = CapturePermissionDialog.None
        onStopped()
    }

    private fun onRequestResult(micAsked: Boolean) {
        when {
            VisualizerController.hasCapturePermission(activity) -> onGranted()
            // 거부 직후에도 이 값이 false 면 시스템이 더는 창을 띄우지 않는 상태로 본다(두 번 거부, "다시 묻지 않음").
            // 처음 뜬 창을 고르지 않고 뒤로 가기나 바깥을 눌러 닫아도 false 라 구분할 수 없으니, 안내 문구는 두 경우 모두 맞게 쓴다.
            // 요청이 중간에 끊겨 결과가 비어 있으면 사용자가 거부한 게 아니므로 설정 안내를 띄우지 않는다.
            micAsked && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                dialog = CapturePermissionDialog.MicSettings
            else -> {
                // 투명 화면은 바로 닫히므로 앱 컨텍스트로 띄운다. 문구는 앱 언어가 입혀진 액티비티에서 꺼낸다.
                // (Android 12 이하에서는 앱 컨텍스트가 폰 언어 그대로다. AppLanguage 참고)
                Toast.makeText(
                    activity.applicationContext,
                    activity.getString(R.string.permission_record_audio_required),
                    Toast.LENGTH_LONG
                ).show()
                onStopped()
            }
        }
    }

    private fun neededPermissions(): Array<String> =
        VisualizerController.requiredPermissions(Build.VERSION.SDK_INT) {
            activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private companion object {
        const val SAVED_STATE_KEY = "com.example.soundvisualizer.CapturePermissionFlow"
        const val KEY_DIALOG = "dialog"
    }
}

/** [CapturePermissionFlow] 의 안내 창. 떠 있는 창이 없으면 아무것도 그리지 않는다. */
@Composable
fun CapturePermissionDialogs(flow: CapturePermissionFlow) {
    when (flow.dialog) {
        CapturePermissionDialog.None -> Unit
        CapturePermissionDialog.Overlay -> PermissionAlert(
            title = stringResource(R.string.permission_overlay_title),
            message = stringResource(R.string.permission_overlay_message),
            confirmLabel = stringResource(R.string.permission_open_settings),
            dismissLabel = stringResource(R.string.permission_cancel),
            onConfirm = flow::openOverlaySettings,
            onDismiss = flow::dismiss
        )
        CapturePermissionDialog.MicRationale -> PermissionAlert(
            title = stringResource(R.string.permission_mic_rationale_title),
            message = stringResource(R.string.permission_mic_rationale_message),
            confirmLabel = stringResource(R.string.permission_continue),
            dismissLabel = stringResource(R.string.permission_cancel),
            onConfirm = flow::continueRequest,
            onDismiss = flow::dismiss
        )
        CapturePermissionDialog.MicSettings -> PermissionAlert(
            title = stringResource(R.string.permission_mic_settings_title),
            message = stringResource(R.string.permission_mic_settings_message),
            confirmLabel = stringResource(R.string.permission_open_settings),
            dismissLabel = stringResource(R.string.permission_close),
            onConfirm = flow::openAppSettings,
            onDismiss = flow::dismiss
        )
    }
}

/** 색상 선택 창과 같은 모양의 확인·취소 창. */
@Composable
private fun PermissionAlert(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor,
        title = { Text(title, color = PrimaryTextColor, fontWeight = FontWeight.Bold) },
        text = { Text(message, color = SecondaryTextColor, fontSize = 15.sp, lineHeight = 24.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = AccentColor, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel, color = SecondaryTextColor) }
        }
    )
}

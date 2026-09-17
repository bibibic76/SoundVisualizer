package com.example.soundvisualizer.tile

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.example.soundvisualizer.CapturePermissionDialogs
import com.example.soundvisualizer.CapturePermissionFlow
import com.example.soundvisualizer.MainActivity
import com.example.soundvisualizer.R
import com.example.soundvisualizer.VisualizerController
import com.example.soundvisualizer.language.AppLanguage
import com.example.soundvisualizer.ui.theme.SoundVisualizerTheme

/**
 * 빠른 설정 타일에서 시각화를 켤 때 잠깐 뜨는 투명 화면.
 *
 * 권한 팝업과 화면 녹화 동의 창은 액티비티에서만 띄울 수 있어서 타일이 이 화면을 연다.
 * 권한 안내 창 말고는 그리는 게 없고, 끝나면 바로 닫혀 보던 앱으로 돌아간다.
 *
 * - 오버레이 권한은 시스템 설정 화면에서만 켤 수 있으므로 앱을 열어 안내한다.
 * - 마이크·알림 권한은 앱의 실행 버튼과 같은 흐름([CapturePermissionFlow])으로 받고 이어서 켠다.
 */
class StartVisualizerActivity : ComponentActivity() {

    // 켜지 않고 끝나면(취소, 거부, 설정 화면으로 보냄) 이 화면도 닫는다.
    private val capturePermission = CapturePermissionFlow(this, onGranted = ::requestProjection, onStopped = ::finish)

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            VisualizerController.start(this, result.resultCode, data)
        }
        finish()
    }

    // Android 12 이하에서 권한 안내 창과 토스트 문구를 앱 언어로 보여준다.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 회전 뒤에도 떠 있던 안내 창을 다시 그려야 하므로 아래의 재생성 검사보다 먼저 붙인다.
        setContent {
            // 안내 창만 그린다. 테마가 창의 바를 건드리지 않으므로 안내 창이 떠 있을 때만 씌울 까닭이 없다.
            SoundVisualizerTheme { CapturePermissionDialogs(capturePermission) }
        }

        // 회전 등으로 다시 만들어진 경우, 이미 띄운 권한·동의 창의 결과는 이 인스턴스로 전달된다.
        // 여기서 다시 요청하면 창이 두 번 뜬다.
        if (savedInstanceState != null) return

        when {
            VisualizerController.isRunning -> finish()
            !Settings.canDrawOverlays(this) -> {
                // 이 화면은 바로 닫히므로 앱 컨텍스트로 띄우되, 문구는 앱 언어가 입혀진 이 화면에서 꺼낸다.
                Toast.makeText(applicationContext, getString(R.string.tile_overlay_permission_needed), Toast.LENGTH_LONG).show()
                // 이 화면은 따로 떨어진 작업(taskAffinity="")이라, 앱은 새 작업으로 띄워야 앱 쪽 작업에 붙는다.
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
            else -> capturePermission.start()
        }
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}

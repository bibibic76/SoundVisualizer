package com.example.soundvisualizer.tile

import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.soundvisualizer.MainActivity
import com.example.soundvisualizer.R
import com.example.soundvisualizer.VisualizerController

/**
 * 빠른 설정 타일에서 시각화를 켤 때 잠깐 뜨는 투명 화면.
 *
 * 권한 팝업과 화면 녹화 동의 창은 액티비티에서만 띄울 수 있어서 타일이 이 화면을 연다.
 * 화면 내용은 없고, 끝나면 바로 닫혀 보던 앱으로 돌아간다.
 *
 * - 오버레이 권한은 시스템 설정 화면에서만 켤 수 있으므로 앱을 열어 안내한다.
 * - 마이크·알림 권한은 팝업으로 바로 묻고 이어서 켠다.
 */
class StartVisualizerActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (VisualizerController.hasCapturePermission(this)) {
            requestProjection()
        } else {
            Toast.makeText(applicationContext, R.string.permission_record_audio_required, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            VisualizerController.start(this, result.resultCode, data)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 회전 등으로 다시 만들어진 경우, 이미 띄운 권한·동의 창의 결과는 이 인스턴스로 전달된다.
        // 여기서 다시 요청하면 창이 두 번 뜬다.
        if (savedInstanceState != null) return

        when {
            VisualizerController.isRunning -> finish()
            !Settings.canDrawOverlays(this) -> {
                Toast.makeText(applicationContext, R.string.tile_overlay_permission_needed, Toast.LENGTH_LONG).show()
                // 이 화면은 따로 떨어진 작업(taskAffinity="")이라, 앱은 새 작업으로 띄워야 앱 쪽 작업에 붙는다.
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
            else -> {
                val needed = VisualizerController.requiredPermissions(Build.VERSION.SDK_INT) {
                    checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                }
                if (needed.isEmpty()) requestProjection() else permissionLauncher.launch(needed)
            }
        }
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}

package com.example.soundvisualizer.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.soundvisualizer.R
import com.example.soundvisualizer.SettingsManager
import com.example.soundvisualizer.VisualizerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 알림창 빠른 설정의 "소리 시각화" 타일.
 *
 * 누르면 꺼져 있을 때는 [StartVisualizerActivity] 를 열어 켜고, 켜져 있을 때는 바로 끈다.
 * 시스템은 알림창이 열려 있는 동안만 이 서비스를 붙잡고 있으므로, 그 사이에만 실행 상태를 구독한다.
 */
class VisualizerTileService : TileService() {

    private val scope = MainScope()
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        SettingsManager.init(applicationContext)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 홈 화면의 "빠른 설정에 추가" 버튼을 숨길지 정하는 데 쓴다. */
    override fun onTileAdded() {
        super.onTileAdded()
        SettingsManager.setTileAdded(true)
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        SettingsManager.setTileAdded(false)
    }

    override fun onStartListening() {
        super.onStartListening()
        // 알림창이 열려 있는 동안 알림의 중지나 앱의 실행 종료로 꺼져도 타일에 바로 반영한다.
        stateJob?.cancel()
        stateJob = scope.launch {
            SettingsManager.isServiceRunning.collect { render(it) }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (VisualizerController.isRunning) {
            VisualizerController.stop(this)
            render(false)
            return
        }
        // 잠금 화면 위에서 동의 창을 띄우지 않는다. 잠금을 풀면 이어서 진행된다.
        if (isLocked) unlockAndRun { launchStart() } else launchStart()
    }

    private fun launchStart() {
        val intent = Intent(this, StartVisualizerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            // Android 14 부터 Intent 버전은 targetSdk 34 이상 앱에서 예외를 던진다. 13 이하에서만 쓴다.
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(running: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(if (running) R.string.tile_state_on else R.string.tile_state_off)
        tile.updateTile()
    }
}

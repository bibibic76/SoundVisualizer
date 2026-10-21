package com.example.soundvisualizer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.soundvisualizer.language.AppLanguage

class OverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private companion object {
        const val TAG = "OverlayService"
    }

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var unlockReceiverRegistered = false

    /** 잠금이 풀리면 잠금 화면이 숨긴 오버레이 창을 다시 보이게 한다([showAgainAfterUnlock], #401). */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) showAgainAfterUnlock()
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    // 오버레이는 지금 글자를 그리지 않지만, 앞으로 그릴 글자도 앱 언어를 따르도록 다른 화면과 같게 입힌다(Android 12 이하).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        SettingsManager.init(applicationContext)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        // Android 12 부터 다른 앱 위에 겹친 창은 불투명도가 시스템 기준값(기본 0.8)보다 높으면
        // FLAG_NOT_TOUCHABLE 이어도 아래 앱으로 가는 터치가 막힌다(신뢰할 수 없는 터치 차단).
        // 창 전체 불투명도를 그 기준값으로 맞춰야 오버레이를 켠 채로 게임을 조작할 수 있다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.alpha = getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
        }
        // 노치/상태바/내비게이션 영역까지 덮어서 파도가 화면 실제 테두리에서 시작하도록 한다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            params.fitInsetsTypes = 0
        } else {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                VisualizerOverlay()
            }
        }
        // 권한이 도중에 회수됐거나 시스템이 서비스를 되살린 경우 addView 가 BadTokenException 을
        // 던지고, onCreate 에서 터지면 프로세스가 죽는다. 확인 + 방어를 모두 건다.
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission not granted; stopping")
            stopEverything()
            return
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "failed to add overlay view", e)
            // composeView 를 비워 onDestroy 의 removeViewImmediate 를 건너뛰게 한다.
            composeView = null
            stopEverything()
            return
        }

        composeView = view
        overlayParams = params
        // 잠금 풀림(USER_PRESENT)은 시스템 서버가 아니라 시스템 UI 앱이 보내서, 내보내지 않은 수신기에는 오지 않는다(#385).
        // 보호된 방송이라 일반 앱은 보낼 수 없으므로 내보내도 다른 앱이 이 수신기를 부를 수 없다.
        ContextCompat.registerReceiver(this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
        unlockReceiverRegistered = true
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /**
     * 잠금 화면이 숨긴 오버레이 창을 다시 보이게 한다(#401).
     *
     * 잠금 화면이 뜨면 안드로이드는 다른 앱의 오버레이 창을 숨긴다. Galaxy S25+(Android 17)는 잠금을 풀어도 다시
     * 보이게 하지 않아(`dumpsys window` 의 `mPolicyVisibility=false`), 우리 앱 화면을 열기 전까지 오버레이가 보이지
     * 않고 프레임도 오지 않았다. 창의 배치를 다시 요청하면 WindowManager 가 잠금 화면 정책을 다시 따진다.
     */
    private fun showAgainAfterUnlock() {
        val view = composeView ?: return
        val params = overlayParams ?: return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            // 창이 이미 떨어졌다. 서비스가 내려가는 중이다.
        }
    }

    /**
     * 오버레이를 띄울 수 없으면 오디오 캡처도 의미가 없으므로 같이 정리한다.
     * 동의까지 받았는데 켜지지 않은 것이라 사용자가 끈 게 아니다. 캡처 서비스가 멈추며 알리도록 이유를 넘긴다.
     * 소리 받기는 대개 이미 시작된 뒤라, 소리가 아니라 오버레이가 문제라고 알린다.
     */
    private fun stopEverything() {
        AudioCaptureService.stopForFailure(this, StopReason.OverlayFailed)
        SettingsManager.setServiceRunning(false)
        stopSelf()
    }

    /** 프로세스가 죽었다가 시스템이 되살리면 오디오 없는 오버레이만 남는다. 되살리지 않는다. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        if (unlockReceiverRegistered) {
            unregisterReceiver(unlockReceiver)
            unlockReceiverRegistered = false
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        composeView?.let { view ->
            try {
                windowManager.removeViewImmediate(view)
            } catch (e: IllegalArgumentException) {
                // 이미 제거된 경우
            }
        }
        composeView = null
        store.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

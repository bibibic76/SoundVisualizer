package com.example.soundvisualizer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
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
import kotlinx.coroutines.delay

class OverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                VisualizerOverlay()
            }
        }

        windowManager.addView(composeView, params)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        windowManager.removeView(composeView)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

enum class VisualMode { Wave, Pad, Outline, CircleRipple }

@Composable
fun VisualizerOverlay() {
    val spectrogramData = mutableStateOf<FloatArray?>(null)
    val audioLevels = mutableStateOf(FloatArray(2) { 0f })
    val aiStateColor = mutableStateOf(Color.Green) // Green = Ambient, Red = Danger
    val currentMode = mutableStateOf(VisualMode.Wave) // Toggle between 4 modes

    LaunchedEffect(Unit) {
        while (true) {
            val spectrogram = AudioEngine.getSpectrogram()
            val levels = AudioEngine.getAudioLevels()
            if (spectrogram != null) {
                spectrogramData.value = spectrogram
            }
            if (levels != null) {
                audioLevels.value = levels
            }
            delay(33) // ~30fps
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val data = spectrogramData.value
        val leftRms = audioLevels.value[0] * 100f // Scale for visibility
        val rightRms = audioLevels.value[1] * 100f
        
        // 8 channel depths mapping: 0=Top, 1=TopRight, 2=Right, 3=BottomRight, 4=Bottom, 5=BottomLeft, 6=Left, 7=TopLeft
        val depths = FloatArray(8) { 0f }
        depths[6] = leftRms
        depths[2] = rightRms

        val activeColor = aiStateColor.value

        when (currentMode.value) {
            VisualMode.Outline -> {
                val maxDepth = leftRms.coerceAtLeast(rightRms)
                if (maxDepth > 0.1f) {
                    val strokeWidth = 10f + maxDepth * 0.5f
                    drawRoundRect(
                        color = activeColor.copy(alpha = 0.6f + (maxDepth/200f).coerceAtMost(0.4f)),
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(30f, 30f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                    )
                }
            }
            VisualMode.Pad -> {
                // Left Pad
                if (leftRms > 0.1f) {
                    drawRect(
                        color = activeColor.copy(alpha = 0.5f),
                        topLeft = Offset(0f, height * 0.2f),
                        size = androidx.compose.ui.geometry.Size(leftRms, height * 0.6f)
                    )
                }
                // Right Pad
                if (rightRms > 0.1f) {
                    drawRect(
                        color = activeColor.copy(alpha = 0.5f),
                        topLeft = Offset(width - rightRms, height * 0.2f),
                        size = androidx.compose.ui.geometry.Size(rightRms, height * 0.6f)
                    )
                }
            }
            VisualMode.CircleRipple -> {
                val cx = width / 2f
                val cy = height / 2f
                val baseRadius = (width.coerceAtMost(height)) * 0.15f
                
                // Draw inner hole
                drawCircle(
                    color = activeColor.copy(alpha = 0.2f),
                    radius = baseRadius,
                    center = Offset(cx, cy)
                )

                // Draw 8 directional ripples (only left and right are active in stereo)
                for (i in 0 until 8) {
                    val angle = (i.toFloat() / 8) * 2.0 * Math.PI - (Math.PI / 2) // Start from top
                    val magnitude = depths[i]
                    if (magnitude > 0) {
                        drawCircle(
                            color = activeColor.copy(alpha = 0.4f),
                            radius = magnitude,
                            center = Offset(
                                cx + (Math.cos(angle) * (baseRadius + magnitude/2)).toFloat(),
                                cy + (Math.sin(angle) * (baseRadius + magnitude/2)).toFloat()
                            )
                        )
                    }
                }
            }
            VisualMode.Wave -> {
                // Use FFT data to draw the wave at the bottom, just like the fallback original
                if (data != null && data.isNotEmpty()) {
                    val barWidth = width / data.size.toFloat()
                    for (i in data.indices) {
                        val barHeight = (data[i] * 10f).coerceAtMost(height / 2)
                        drawLine(
                            color = activeColor.copy(alpha = 0.8f),
                            start = Offset(i * barWidth, height),
                            end = Offset(i * barWidth, height - barHeight),
                            strokeWidth = barWidth * 0.8f
                        )
                    }
                }
            }
        }
    }
}

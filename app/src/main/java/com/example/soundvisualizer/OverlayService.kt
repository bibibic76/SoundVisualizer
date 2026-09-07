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
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidPath
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

enum class VisualMode(val displayName: String) { 
    Wave("파도"), 
    Pad("패드"), 
    CircleRipple("원형"), 
    Outline("외곽선") 
}

// ----------------- Math Utilities -----------------
private fun getEdgePosition(distIn: Float, w: Float, h: Float, P: Float): Offset {
    val dist = ((distIn % P) + P) % P
    if (dist <= w / 2f) return Offset(w / 2f + dist, 0f)
    if (dist <= w / 2f + h) return Offset(w, dist - w / 2f)
    if (dist <= w / 2f + h + w) return Offset(w - (dist - (w / 2f + h)), h)
    if (dist <= w / 2f + h + w + h) return Offset(0f, h - (dist - (w / 2f + h + w)))
    return Offset(dist - (w / 2f + h + w + h), 0f)
}

private fun interpolateDepthCatmullRom(tIn: Float, depths: FloatArray, positions: FloatArray): Float {
    val t = ((tIn % 1f) + 1f) % 1f
    val n = positions.size
    var idx1 = 0
    for (i in 0 until n) {
        if (positions[i] <= t) idx1 = i
    }
    val idx0 = (idx1 - 1 + n) % n
    val idx2 = (idx1 + 1) % n
    val idx3 = (idx1 + 2) % n

    val p1 = positions[idx1]
    var p2 = positions[idx2]
    if (p2 <= p1) p2 += 1f

    var at = t
    if (at < p1) at += 1f

    val segLen = p2 - p1
    var lt = if (segLen > 0) (at - p1) / segLen else 0f
    lt = maxOf(0f, minOf(1f, lt))

    val d0 = depths[idx0]
    val d1 = depths[idx1]
    val d2 = depths[idx2]
    val d3 = depths[idx3]

    val a = -0.5f * d0 + 1.5f * d1 - 1.5f * d2 + 0.5f * d3
    val b = d0 - 2.5f * d1 + 2.0f * d2 - 0.5f * d3
    val c = -0.5f * d0 + 0.5f * d2
    val dv = d1

    return maxOf(0f, a * lt * lt * lt + b * lt * lt + c * lt + dv)
}

private fun getWaveDepth(t: Float, depths: FloatArray, positions: FloatArray, maxDepth: Float): Float {
    return minOf(maxDepth, maxOf(0f, interpolateDepthCatmullRom(t, depths, positions)))
}

private fun getRoundedInnerPoint(
    dist: Float, w: Float, h: Float, P: Float, d: Float,
    d_tr: Float, d_br: Float, d_bl: Float, d_tl: Float
): Offset {
    val c_tr = w / 2f
    val c_br = w / 2f + h
    val c_bl = c_br + w
    val c_tl = c_bl + h

    val R_tr = minOf(d_tr * 2f, minOf(w / 2f, h / 2f))
    val R_br = minOf(d_br * 2f, minOf(w / 2f, h / 2f))
    val R_bl = minOf(d_bl * 2f, minOf(w / 2f, h / 2f))
    val R_tl = minOf(d_tl * 2f, minOf(w / 2f, h / 2f))

    var distToTL = dist - c_tl
    if (dist < w / 2f) distToTL = dist + P - c_tl

    // 1) Top-Right
    if (Math.abs(dist - c_tr) <= R_tr) {
        val t = (dist - c_tr + R_tr) / (2 * R_tr)
        val p0x = w - R_tr; val p0y = d
        val p1x = w - d; val p1y = d
        val p2x = w - d; val p2y = R_tr
        val inv = 1 - t
        return Offset(
            inv * inv * p0x + 2 * inv * t * p1x + t * t * p2x,
            inv * inv * p0y + 2 * inv * t * p1y + t * t * p2y
        )
    }
    // 2) Bottom-Right
    if (Math.abs(dist - c_br) <= R_br) {
        val t = (dist - c_br + R_br) / (2 * R_br)
        val p0x = w - d; val p0y = h - R_br
        val p1x = w - d; val p1y = h - d
        val p2x = w - R_br; val p2y = h - d
        val inv = 1 - t
        return Offset(
            inv * inv * p0x + 2 * inv * t * p1x + t * t * p2x,
            inv * inv * p0y + 2 * inv * t * p1y + t * t * p2y
        )
    }
    // 3) Bottom-Left
    if (Math.abs(dist - c_bl) <= R_bl) {
        val t = (dist - c_bl + R_bl) / (2 * R_bl)
        val p0x = R_bl; val p0y = h - d
        val p1x = d; val p1y = h - d
        val p2x = d; val p2y = h - R_bl
        val inv = 1 - t
        return Offset(
            inv * inv * p0x + 2 * inv * t * p1x + t * t * p2x,
            inv * inv * p0y + 2 * inv * t * p1y + t * t * p2y
        )
    }
    // 4) Top-Left
    if (Math.abs(distToTL) <= R_tl) {
        val t = (distToTL + R_tl) / (2 * R_tl)
        val p0x = d; val p0y = R_tl
        val p1x = d; val p1y = d
        val p2x = R_tl; val p2y = d
        val inv = 1 - t
        return Offset(
            inv * inv * p0x + 2 * inv * t * p1x + t * t * p2x,
            inv * inv * p0y + 2 * inv * t * p1y + t * t * p2y
        )
    }

    val edgePos = getEdgePosition(dist, w, h, P)
    if (dist <= c_tr || dist > c_tl) return Offset(maxOf(d, minOf(w - d, edgePos.x)), d) // Top
    if (dist <= c_br) return Offset(w - d, maxOf(d, minOf(h - d, edgePos.y))) // Right
    if (dist <= c_bl) return Offset(maxOf(d, minOf(w - d, edgePos.x)), h - d) // Bottom
    return Offset(d, maxOf(d, minOf(h - d, edgePos.y))) // Left
}

@Composable
fun VisualizerOverlay() {
    val audioLevels = mutableStateOf(FloatArray(2) { 0f })
    val aiStateColor = mutableStateOf(Color.Green) // Green = Ambient, Red = Danger
    
    val currentMode by SettingsManager.visualMode.collectAsState()
    val waveSettings by SettingsManager.waveMode.collectAsState()
    val padSettings by SettingsManager.padMode.collectAsState()
    val circleSettings by SettingsManager.circleMode.collectAsState()
    val outlineSettings by SettingsManager.outlineMode.collectAsState()

    // State arrays for animations
    val recentTargetsCircle = remember { FloatArray(8) { 0f } }
    val padThicknesses = remember { FloatArray(8) { 0f } }
    val smoothedDepths = remember { FloatArray(8) { 0f } }

    LaunchedEffect(Unit) {
        while (true) {
            val levels = AudioEngine.getAudioLevels()
            if (levels != null) {
                audioLevels.value = levels
            }
            delay(16) // ~60fps for smoother bezier animations
        }
    }
    
    // History buffer for fake spatial depth (Delaying rear channels)
    val historySize = 5
    val leftHistory = remember { FloatArray(historySize) { 0f } }
    val rightHistory = remember { FloatArray(historySize) { 0f } }
    var historyIndex by remember { mutableStateOf(0) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val P = 2f * (w + h)

        val activeSettings = when (currentMode) {
            VisualMode.Wave -> waveSettings
            VisualMode.Pad -> padSettings
            VisualMode.CircleRipple -> circleSettings
            VisualMode.Outline -> outlineSettings
        }
        val useRippleDelay = activeSettings.useRippleDelay

        // 적용: Sensitivity (민감도)
        val leftRms = audioLevels.value[0] * activeSettings.sensitivity * 30f 
        val rightRms = audioLevels.value[1] * activeSettings.sensitivity * 30f

        // Update history buffer for delay effect
        leftHistory[historyIndex] = leftRms
        rightHistory[historyIndex] = rightRms
        val delayedIndex = (historyIndex - (historySize - 1) + historySize) % historySize
        historyIndex = (historyIndex + 1) % historySize

        val delayedLeft = leftHistory[delayedIndex]
        val delayedRight = rightHistory[delayedIndex]

        // Virtual Surround Upmixing (Mid-Side Processing)
        val targetDepths = FloatArray(8)
        val minRms = minOf(leftRms, rightRms)
        val sideLeft = maxOf(0f, leftRms - rightRms)
        val sideRight = maxOf(0f, rightRms - leftRms)
        
        // Front uses real-time
        targetDepths[0] = minRms * 1.2f   // FC
        targetDepths[1] = rightRms * 0.9f // FR
        targetDepths[7] = leftRms * 0.9f  // FL
        targetDepths[2] = sideRight * 1.5f// SR
        targetDepths[6] = sideLeft * 1.5f // SL

        if (useRippleDelay) {
            // Rear uses delayed signals to simulate spatial travel
            val delayedMin = minOf(delayedLeft, delayedRight)
            val delayedSideLeft = maxOf(0f, delayedLeft - delayedRight)
            val delayedSideRight = maxOf(0f, delayedRight - delayedLeft)

            targetDepths[3] = delayedSideRight * 1.2f // BR
            targetDepths[4] = delayedMin * 0.8f       // BC
            targetDepths[5] = delayedSideLeft * 1.2f  // BL
        } else {
            // Rear uses real-time
            targetDepths[3] = sideRight * 1.2f // BR
            targetDepths[4] = minRms * 0.8f    // BC
            targetDepths[5] = sideLeft * 1.2f  // BL
        }

        // 적용: Speed (속도)
        val speedFactor = (activeSettings.speed / 20f) * 0.3f
        for (i in 0 until 8) {
            smoothedDepths[i] += (targetDepths[i] - smoothedDepths[i]) * speedFactor
        }

        val depths = smoothedDepths
        val activeColor = aiStateColor.value

        // 적용: IntensityAsOpacity (크기 고정) & Opacity & Intensity
        val baseOpacity = 1f - (activeSettings.opacity / 100f)
        val activeIntensity = if (activeSettings.intensityAsOpacity) {
            activeSettings.opacityFixedSize / 10f
        } else {
            activeSettings.intensity / 50f
        }
        
        val activeOpacity = if (activeSettings.intensityAsOpacity) {
            val maxAlpha = activeSettings.opacityFixedMaxOpacity / 100f
            val currentAudioAvg = depths.average().toFloat() / 100f
            minOf(baseOpacity, currentAudioAvg * maxAlpha)
        } else {
            baseOpacity
        }

        // Draw Helper (Glow 적용 용도)
        val glowRadius = if (activeSettings.isGlowMode) activeSettings.glowIntensity else 0f
        
        fun drawGlowPath(path: androidx.compose.ui.graphics.Path, color: Color, isStroke: Boolean = false, strokeWidth: Float = 8f) {
            drawContext.canvas.apply {
                val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
                    isAntiAlias = true
                    this.color = color.toArgb()
                    this.style = if (isStroke) android.graphics.Paint.Style.STROKE else android.graphics.Paint.Style.FILL
                    if (isStroke) this.strokeWidth = strokeWidth
                    if (glowRadius > 0f) {
                        setShadowLayer(glowRadius, 0f, 0f, activeColor.toArgb()) // Use full activeColor for neon effect
                    }
                }
                nativeCanvas.drawPath(path.asAndroidPath(), paint)
            }
        }

        when (currentMode) {
            VisualMode.Wave, VisualMode.Outline -> {
                val isWave = currentMode == VisualMode.Wave
                
                var anyActive = false
                for (d in depths) {
                    if (d * activeIntensity > 3f) { anyActive = true; break }
                }
                if (!anyActive && !activeSettings.intensityAsOpacity) return@Canvas

                val channelPos = FloatArray(8)
                channelPos[0] = 0f / P
                channelPos[1] = (w / 2f) / P
                channelPos[2] = (w / 2f + h / 2f) / P
                channelPos[3] = (w / 2f + h) / P
                channelPos[4] = (w / 2f + h + w / 2f) / P
                channelPos[5] = (w / 2f + h + w) / P
                channelPos[6] = (w / 2f + h + w + h / 2f) / P
                channelPos[7] = (w / 2f + h + w + h) / P

                val baseDepth = minOf(w, h) * 0.4f
                val scaledDepths = depths.map { it * activeIntensity }.toFloatArray()
                
                val d_tr = getWaveDepth((w / 2f) / P, scaledDepths, channelPos, baseDepth)
                val d_br = getWaveDepth((w / 2f + h) / P, scaledDepths, channelPos, baseDepth)
                val d_bl = getWaveDepth((w / 2f + h + w) / P, scaledDepths, channelPos, baseDepth)
                val d_tl = getWaveDepth((w / 2f + h + w + h) / P, scaledDepths, channelPos, baseDepth)

                val N = 150
                val innerPts = Array(N) { Offset.Zero }

                for (i in 0 until N) {
                    val dist = (P * i) / N
                    val t = dist / P
                    val d = getWaveDepth(t, scaledDepths, channelPos, baseDepth)
                    innerPts[i] = getRoundedInnerPoint(dist, w, h, P, d, d_tr, d_br, d_bl, d_tl)
                }

                val path = androidx.compose.ui.graphics.Path()
                
                if (isWave) {
                    path.moveTo(0f, 0f)
                    path.lineTo(w, 0f)
                    path.lineTo(w, h)
                    path.lineTo(0f, h)
                    path.lineTo(0f, 0f)

                    path.moveTo(innerPts[0].x, innerPts[0].y)
                    for (i in 0 until N) {
                        val p0 = innerPts[(i - 1 + N) % N]
                        val p1 = innerPts[i]
                        val p2 = innerPts[(i + 1) % N]
                        val p3 = innerPts[(i + 2) % N]

                        val cp1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
                        val cp2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)

                        path.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, p2.x, p2.y)
                    }
                    path.fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd

                    // Wave uses gradient, but if glow is needed we can draw a base glow
                    if (glowRadius > 0f) {
                        drawGlowPath(path, activeColor.copy(alpha = 0f)) // Only shadows
                    }
                    drawPath(
                        path = path,
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(Color.Transparent, activeColor.copy(alpha = activeOpacity * 0.6f), activeColor.copy(alpha = activeOpacity)),
                            center = Offset(w/2f, h/2f),
                            radius = maxOf(w, h) / 2f
                        )
                    )
                } else {
                    path.moveTo(innerPts[0].x, innerPts[0].y)
                    for (i in N - 1 downTo 0) {
                        val prev = (i - 1 + N) % N
                        val next = (i + 1) % N
                        val nnext = (i + 2) % N

                        val p0 = innerPts[nnext]
                        val p1 = innerPts[next]
                        val p2 = innerPts[i]
                        val p3 = innerPts[prev]

                        val cp1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
                        val cp2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)

                        path.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, p2.x, p2.y)
                    }
                    drawGlowPath(path, activeColor.copy(alpha = activeOpacity), isStroke = true, strokeWidth = 8f)
                }
            }
            VisualMode.Pad -> {
                val centerDists = FloatArray(8)
                centerDists[0] = 0f                             // FC
                centerDists[1] = w / 2f                         // FR
                centerDists[2] = w / 2f + h / 2f                // SR
                centerDists[3] = w / 2f + h                     // BR
                centerDists[4] = w / 2f + h + w / 2f            // BC
                centerDists[5] = w / 2f + h + w                 // BL
                centerDists[6] = w / 2f + h + w + h / 2f        // SL
                centerDists[7] = w / 2f + h + w + h             // FL

                val path = androidx.compose.ui.graphics.Path()
                path.fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                var isAnyVisible = false

                val N = 16
                val outerPts = Array(N + 1) { Offset.Zero }
                val innerPts = Array(N + 1) { Offset.Zero }

                for (c in 0 until 8) {
                    var targetThickness = depths[c] * activeIntensity * 0.25f
                    if (targetThickness > 100f) targetThickness = 100f

                    padThicknesses[c] += (targetThickness - padThicknesses[c]) * speedFactor
                    if (padThicknesses[c] < 0.5f && !activeSettings.intensityAsOpacity) continue

                    isAnyVisible = true
                    val centerDist = centerDists[c]
                    val maxThickness = padThicknesses[c]

                    val barLen = h / 4f
                    val startDist = centerDist - barLen / 2f

                    for (i in 0..N) {
                        val distPos = startDist + (barLen * i) / N
                        val edgePoint = getEdgePosition(distPos, w, h, P)
                        outerPts[i] = edgePoint

                        val t = i.toFloat() / N
                        var ease = Math.sin(t * Math.PI)
                        ease = Math.pow(ease, 0.6)
                        val currentThickness = (maxThickness * ease).toFloat()

                        val dMod = ((distPos % P) + P) % P
                        var ix = edgePoint.x
                        var iy = edgePoint.y

                        if (dMod <= w / 2f || dMod > w / 2f + h + w + h) {
                            iy += currentThickness
                            ix = maxOf(currentThickness, minOf(w - currentThickness, ix))
                        } else if (dMod <= w / 2f + h) {
                            ix -= currentThickness
                            iy = maxOf(currentThickness, minOf(h - currentThickness, iy))
                        } else if (dMod <= w / 2f + h + w) {
                            iy -= currentThickness
                            ix = maxOf(currentThickness, minOf(w - currentThickness, ix))
                        } else {
                            ix += currentThickness
                            iy = maxOf(currentThickness, minOf(h - currentThickness, iy))
                        }

                        innerPts[i] = Offset(ix, iy)
                    }

                    path.moveTo(outerPts[0].x, outerPts[0].y)
                    for (i in 1..N) path.lineTo(outerPts[i].x, outerPts[i].y)
                    for (i in N downTo 0) path.lineTo(innerPts[i].x, innerPts[i].y)
                    path.close()
                }

                if (isAnyVisible || activeSettings.intensityAsOpacity) {
                    drawGlowPath(path, activeColor.copy(alpha = activeOpacity))
                }
            }
            VisualMode.CircleRipple -> {
                val cx = w / 2f
                val cy = h / 2f
                val radiusRatio = 0.05f + (activeSettings.circleRadius - 10f) / 90f * 0.35f
                val baseRadius = minOf(w, h) * radiusRatio

                var isAnyVisible = false
                for (i in 0 until 8) {
                    var target = depths[i] * activeIntensity * 0.35f
                    if (target > h * 0.4f) target = h * 0.4f

                    recentTargetsCircle[i] += (target - recentTargetsCircle[i]) * speedFactor
                    if (recentTargetsCircle[i] > 1f) isAnyVisible = true
                }

                if (!isAnyVisible && !activeSettings.intensityAsOpacity) return@Canvas

                val N = 64
                val path = androidx.compose.ui.graphics.Path()
                path.fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                
                val outerPts = Array(N) { Offset.Zero }
                val innerPts = Array(N) { Offset.Zero }

                for (i in 0 until N) {
                    val angle = (2.0 * Math.PI * i) / N
                    var mappedIndex = (angle / (2.0 * Math.PI)) * 8.0 + 2.0
                    if (mappedIndex >= 8.0) mappedIndex -= 8.0

                    val i0 = Math.floor(mappedIndex).toInt() % 8
                    val i1 = (i0 + 1) % 8
                    val t = mappedIndex - Math.floor(mappedIndex)
                    val ft = (1.0 - Math.cos(t * Math.PI)) / 2.0
                    val depth = (recentTargetsCircle[i0] * (1 - ft) + recentTargetsCircle[i1] * ft).toFloat()

                    val r = baseRadius + depth
                    outerPts[i] = Offset(
                        cx + (Math.cos(angle) * r).toFloat(),
                        cy + (Math.sin(angle) * r).toFloat()
                    )
                }

                path.moveTo(outerPts[0].x, outerPts[0].y)
                for (i in 1 until N) path.lineTo(outerPts[i].x, outerPts[i].y)
                path.close()

                for (i in 0 until N) {
                    val angle = 2.0 * Math.PI * (N - 1 - i) / N
                    innerPts[i] = Offset(
                        cx + (Math.cos(angle) * baseRadius).toFloat(),
                        cy + (Math.sin(angle) * baseRadius).toFloat()
                    )
                }

                path.moveTo(innerPts[0].x, innerPts[0].y)
                for (i in 1 until N) path.lineTo(innerPts[i].x, innerPts[i].y)
                path.close()

                drawGlowPath(path, activeColor.copy(alpha = activeOpacity))
            }
        }
    }
}

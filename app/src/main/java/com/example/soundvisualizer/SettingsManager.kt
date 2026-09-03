package com.example.soundvisualizer

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ModeSettings(
    var intensity: Float = 50f,
    var speed: Float = 20f,
    var opacity: Float = 60f,
    var circleRadius: Float = 40f
)

object SettingsManager {
    private const val PREFS_NAME = "SoundVisualizerPrefs"
    private lateinit var prefs: SharedPreferences

    private val _visualMode = MutableStateFlow(VisualMode.Wave)
    val visualMode: StateFlow<VisualMode> = _visualMode

    private val _waveMode = MutableStateFlow(ModeSettings())
    val waveMode: StateFlow<ModeSettings> = _waveMode

    private val _padMode = MutableStateFlow(ModeSettings())
    val padMode: StateFlow<ModeSettings> = _padMode

    private val _circleMode = MutableStateFlow(ModeSettings(circleRadius = 40f))
    val circleMode: StateFlow<ModeSettings> = _circleMode

    private val _outlineMode = MutableStateFlow(ModeSettings())
    val outlineMode: StateFlow<ModeSettings> = _outlineMode

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        _visualMode.value = VisualMode.values()[prefs.getInt("visualMode", 0)]
        
        _waveMode.value = ModeSettings(
            intensity = prefs.getFloat("wave_intensity", 50f),
            speed = prefs.getFloat("wave_speed", 20f),
            opacity = prefs.getFloat("wave_opacity", 60f)
        )
        _padMode.value = ModeSettings(
            intensity = prefs.getFloat("pad_intensity", 50f),
            speed = prefs.getFloat("pad_speed", 20f),
            opacity = prefs.getFloat("pad_opacity", 60f)
        )
        _circleMode.value = ModeSettings(
            intensity = prefs.getFloat("circle_intensity", 50f),
            speed = prefs.getFloat("circle_speed", 20f),
            opacity = prefs.getFloat("circle_opacity", 60f),
            circleRadius = prefs.getFloat("circle_radius", 40f)
        )
        _outlineMode.value = ModeSettings(
            intensity = prefs.getFloat("outline_intensity", 50f),
            speed = prefs.getFloat("outline_speed", 20f),
            opacity = prefs.getFloat("outline_opacity", 60f)
        )
    }

    fun setVisualMode(mode: VisualMode) {
        _visualMode.value = mode
        prefs.edit().putInt("visualMode", mode.ordinal).apply()
    }

    fun updateWaveMode(update: ModeSettings.() -> Unit) {
        val current = _waveMode.value.copy().apply(update)
        _waveMode.value = current
        prefs.edit()
            .putFloat("wave_intensity", current.intensity)
            .putFloat("wave_speed", current.speed)
            .putFloat("wave_opacity", current.opacity)
            .apply()
    }
    
    fun updatePadMode(update: ModeSettings.() -> Unit) {
        val current = _padMode.value.copy().apply(update)
        _padMode.value = current
        prefs.edit()
            .putFloat("pad_intensity", current.intensity)
            .putFloat("pad_speed", current.speed)
            .putFloat("pad_opacity", current.opacity)
            .apply()
    }

    fun updateCircleMode(update: ModeSettings.() -> Unit) {
        val current = _circleMode.value.copy().apply(update)
        _circleMode.value = current
        prefs.edit()
            .putFloat("circle_intensity", current.intensity)
            .putFloat("circle_speed", current.speed)
            .putFloat("circle_opacity", current.opacity)
            .putFloat("circle_radius", current.circleRadius)
            .apply()
    }

    fun updateOutlineMode(update: ModeSettings.() -> Unit) {
        val current = _outlineMode.value.copy().apply(update)
        _outlineMode.value = current
        prefs.edit()
            .putFloat("outline_intensity", current.intensity)
            .putFloat("outline_speed", current.speed)
            .putFloat("outline_opacity", current.opacity)
            .apply()
    }

    fun setServiceRunning(isRunning: Boolean) {
        _isServiceRunning.value = isRunning
    }
}

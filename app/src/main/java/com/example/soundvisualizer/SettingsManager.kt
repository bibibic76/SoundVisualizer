package com.example.soundvisualizer

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ModeSettings(
    var intensity: Float = 50f,
    var speed: Float = 20f,
    var opacity: Float = 60f,
    var circleRadius: Float = 40f,
    var useRippleDelay: Boolean = true,
    
    // New Settings
    var sensitivity: Float = 10f,
    var isGlowMode: Boolean = false,
    var glowIntensity: Float = 0f,
    var intensityAsOpacity: Boolean = false,
    var opacityFixedSize: Float = 30f,
    var opacityFixedMaxOpacity: Float = 0f
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

    // AI Classification Display Settings
    private val _showAmbient = MutableStateFlow(true)
    val showAmbient: StateFlow<Boolean> = _showAmbient
    private val _colorAmbient = MutableStateFlow(Color.parseColor("#FFFFFFFF"))
    val colorAmbient: StateFlow<Int> = _colorAmbient

    private val _showSpeech = MutableStateFlow(true)
    val showSpeech: StateFlow<Boolean> = _showSpeech
    private val _colorSpeech = MutableStateFlow(Color.parseColor("#FFFFFF00"))
    val colorSpeech: StateFlow<Int> = _colorSpeech

    private val _showDanger = MutableStateFlow(true)
    val showDanger: StateFlow<Boolean> = _showDanger
    private val _colorDanger = MutableStateFlow(Color.parseColor("#FFFF0000"))
    val colorDanger: StateFlow<Int> = _colorDanger

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        _visualMode.value = VisualMode.values()[prefs.getInt("visualMode", 0)]
        
        fun loadMode(prefix: String, defaultRadius: Float = 40f): ModeSettings {
            return ModeSettings(
                intensity = prefs.getFloat("${prefix}_intensity", 50f),
                speed = prefs.getFloat("${prefix}_speed", 20f),
                opacity = prefs.getFloat("${prefix}_opacity", 60f),
                circleRadius = prefs.getFloat("${prefix}_radius", defaultRadius),
                useRippleDelay = prefs.getBoolean("${prefix}_ripple", true),
                sensitivity = prefs.getFloat("${prefix}_sensitivity", 10f),
                isGlowMode = prefs.getBoolean("${prefix}_glow", false),
                glowIntensity = prefs.getFloat("${prefix}_glow_intensity", 0f),
                intensityAsOpacity = prefs.getBoolean("${prefix}_intensity_as_opacity", false),
                opacityFixedSize = prefs.getFloat("${prefix}_opacity_fixed_size", 30f),
                opacityFixedMaxOpacity = prefs.getFloat("${prefix}_opacity_fixed_max", 0f)
            )
        }

        _waveMode.value = loadMode("wave")
        _padMode.value = loadMode("pad")
        _circleMode.value = loadMode("circle", 40f)
        _outlineMode.value = loadMode("outline")

        _showAmbient.value = prefs.getBoolean("show_ambient", true)
        _colorAmbient.value = prefs.getInt("color_ambient", Color.parseColor("#FFFFFFFF"))

        _showSpeech.value = prefs.getBoolean("show_speech", true)
        _colorSpeech.value = prefs.getInt("color_speech", Color.parseColor("#FFFFFF00"))

        _showDanger.value = prefs.getBoolean("show_danger", true)
        _colorDanger.value = prefs.getInt("color_danger", Color.parseColor("#FFFF0000"))
    }

    fun setVisualMode(mode: VisualMode) {
        _visualMode.value = mode
        prefs.edit().putInt("visualMode", mode.ordinal).apply()
    }

    private fun saveMode(prefix: String, settings: ModeSettings) {
        prefs.edit()
            .putFloat("${prefix}_intensity", settings.intensity)
            .putFloat("${prefix}_speed", settings.speed)
            .putFloat("${prefix}_opacity", settings.opacity)
            .putFloat("${prefix}_radius", settings.circleRadius)
            .putBoolean("${prefix}_ripple", settings.useRippleDelay)
            .putFloat("${prefix}_sensitivity", settings.sensitivity)
            .putBoolean("${prefix}_glow", settings.isGlowMode)
            .putFloat("${prefix}_glow_intensity", settings.glowIntensity)
            .putBoolean("${prefix}_intensity_as_opacity", settings.intensityAsOpacity)
            .putFloat("${prefix}_opacity_fixed_size", settings.opacityFixedSize)
            .putFloat("${prefix}_opacity_fixed_max", settings.opacityFixedMaxOpacity)
            .apply()
    }

    fun updateWaveMode(update: ModeSettings.() -> Unit) {
        val current = _waveMode.value.copy().apply(update)
        _waveMode.value = current
        saveMode("wave", current)
    }
    
    fun updatePadMode(update: ModeSettings.() -> Unit) {
        val current = _padMode.value.copy().apply(update)
        _padMode.value = current
        saveMode("pad", current)
    }

    fun updateCircleMode(update: ModeSettings.() -> Unit) {
        val current = _circleMode.value.copy().apply(update)
        _circleMode.value = current
        saveMode("circle", current)
    }

    fun updateOutlineMode(update: ModeSettings.() -> Unit) {
        val current = _outlineMode.value.copy().apply(update)
        _outlineMode.value = current
        saveMode("outline", current)
    }

    fun updateAISettings(
        showAmbient: Boolean = _showAmbient.value,
        colorAmbient: Int = _colorAmbient.value,
        showSpeech: Boolean = _showSpeech.value,
        colorSpeech: Int = _colorSpeech.value,
        showDanger: Boolean = _showDanger.value,
        colorDanger: Int = _colorDanger.value
    ) {
        _showAmbient.value = showAmbient
        _colorAmbient.value = colorAmbient
        _showSpeech.value = showSpeech
        _colorSpeech.value = colorSpeech
        _showDanger.value = showDanger
        _colorDanger.value = colorDanger

        prefs.edit()
            .putBoolean("show_ambient", showAmbient)
            .putInt("color_ambient", colorAmbient)
            .putBoolean("show_speech", showSpeech)
            .putInt("color_speech", colorSpeech)
            .putBoolean("show_danger", showDanger)
            .putInt("color_danger", colorDanger)
            .apply()
    }

    fun setServiceRunning(isRunning: Boolean) {
        _isServiceRunning.value = isRunning
    }
}

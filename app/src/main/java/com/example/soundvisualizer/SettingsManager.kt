package com.example.soundvisualizer

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.soundvisualizer.feedback.HapticSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ModeSettings(
    /** 크기 (0~100). 100 이면 파도가 화면 중앙 한계선까지 닿는다. */
    var intensity: Float = 50f,
    /** 속도 (0~100). 방향 분포가 새 소리 위치로 옮겨가는 속도. */
    var speed: Float = 20f,
    /** 진하기 (0~100). 값이 클수록 진하게 보인다. */
    var opacity: Float = 50f,
    var circleRadius: Float = 40f,
    /** 후면 채널에 지연을 줘서 앞→뒤로 퍼지는 느낌을 낸다. */
    var useRippleDelay: Boolean = true,

    /** 민감도 (0~100). 전체 크기가 소리를 따라붙는 반응 속도. 내부 계수 3.75 의 x4 표시값 = 15. */
    var sensitivity: Float = 15f,
    var isGlowMode: Boolean = false,
    var glowIntensity: Float = 0f,
    var intensityAsOpacity: Boolean = false,
    var opacityFixedSize: Float = 30f,
    var opacityFixedMaxOpacity: Float = 100f
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
    // 기본색 (ARGB): 환경음 흰색, 대화음 노란색, 위협음 빨간색
    private const val DEFAULT_COLOR_AMBIENT = 0xFFFFFFFF.toInt()
    private const val DEFAULT_COLOR_SPEECH = 0xFFFFFF00.toInt()
    private const val DEFAULT_COLOR_DANGER = 0xFFFF0000.toInt()

    private val _showAmbient = MutableStateFlow(true)
    val showAmbient: StateFlow<Boolean> = _showAmbient
    private val _colorAmbient = MutableStateFlow(DEFAULT_COLOR_AMBIENT)
    val colorAmbient: StateFlow<Int> = _colorAmbient

    private val _showSpeech = MutableStateFlow(true)
    val showSpeech: StateFlow<Boolean> = _showSpeech
    private val _colorSpeech = MutableStateFlow(DEFAULT_COLOR_SPEECH)
    val colorSpeech: StateFlow<Int> = _colorSpeech

    private val _showDanger = MutableStateFlow(true)
    val showDanger: StateFlow<Boolean> = _showDanger
    private val _colorDanger = MutableStateFlow(DEFAULT_COLOR_DANGER)
    val colorDanger: StateFlow<Int> = _colorDanger

    // 소리 종류별 진동 설정. 키는 AiClassification 라벨.
    private val hapticFlows: Map<String, MutableStateFlow<HapticSettings>> =
        listOf(AiClassification.AMBIENT, AiClassification.SPEECH, AiClassification.DANGER)
            .associateWith { MutableStateFlow(HapticSettings.defaultFor(it)) }

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning

    /** 액티비티/서비스 어디서든 호출 가능. 최초 한 번만 프리퍼런스를 읽는다. */
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // 저장된 ordinal 이 현재 enum 범위를 벗어나면(모드 추가/삭제 후) 크래시하지 않고 기본값으로.
        _visualMode.value = VisualMode.values().getOrElse(prefs.getInt("visualMode", 0)) { VisualMode.Wave }
        
        fun loadMode(prefix: String, defaultRadius: Float = 40f): ModeSettings {
            return ModeSettings(
                intensity = prefs.getFloat("${prefix}_intensity", 50f),
                speed = prefs.getFloat("${prefix}_speed", 20f),
                opacity = prefs.getFloat("${prefix}_opacity", 50f),
                circleRadius = prefs.getFloat("${prefix}_radius", defaultRadius),
                useRippleDelay = prefs.getBoolean("${prefix}_ripple", true),
                sensitivity = prefs.getFloat("${prefix}_sensitivity", 15f),
                isGlowMode = prefs.getBoolean("${prefix}_glow", false),
                glowIntensity = prefs.getFloat("${prefix}_glow_intensity", 0f),
                intensityAsOpacity = prefs.getBoolean("${prefix}_intensity_as_opacity", false),
                opacityFixedSize = prefs.getFloat("${prefix}_opacity_fixed_size", 30f),
                opacityFixedMaxOpacity = prefs.getFloat("${prefix}_opacity_fixed_max", 100f)
            )
        }

        _waveMode.value = loadMode("wave")
        _padMode.value = loadMode("pad")
        _circleMode.value = loadMode("circle", 40f)
        _outlineMode.value = loadMode("outline")

        _showAmbient.value = prefs.getBoolean("show_ambient", true)
        _colorAmbient.value = prefs.getInt("color_ambient", DEFAULT_COLOR_AMBIENT)

        _showSpeech.value = prefs.getBoolean("show_speech", true)
        _colorSpeech.value = prefs.getInt("color_speech", DEFAULT_COLOR_SPEECH)

        _showDanger.value = prefs.getBoolean("show_danger", true)
        _colorDanger.value = prefs.getInt("color_danger", DEFAULT_COLOR_DANGER)

        // enum 은 이름으로 저장한다. 모르는 이름(항목을 바꾼 뒤 등)이면 기본값으로 떨어진다.
        hapticFlows.forEach { (label, flow) ->
            val default = HapticSettings.defaultFor(label)
            flow.value = HapticSettings(
                enabled = prefs.getBoolean("haptic_${label}_enabled", default.enabled),
                strength = enumByName(prefs.getString("haptic_${label}_strength", null), default.strength),
                pattern = enumByName(prefs.getString("haptic_${label}_pattern", null), default.pattern)
            )
        }
    }

    private inline fun <reified T : Enum<T>> enumByName(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    fun setVisualMode(mode: VisualMode) {
        _visualMode.value = mode
        prefs.edit { putInt("visualMode", mode.ordinal) }
    }

    private fun saveMode(prefix: String, settings: ModeSettings) {
        prefs.edit {
            putFloat("${prefix}_intensity", settings.intensity)
            putFloat("${prefix}_speed", settings.speed)
            putFloat("${prefix}_opacity", settings.opacity)
            putFloat("${prefix}_radius", settings.circleRadius)
            putBoolean("${prefix}_ripple", settings.useRippleDelay)
            putFloat("${prefix}_sensitivity", settings.sensitivity)
            putBoolean("${prefix}_glow", settings.isGlowMode)
            putFloat("${prefix}_glow_intensity", settings.glowIntensity)
            putBoolean("${prefix}_intensity_as_opacity", settings.intensityAsOpacity)
            putFloat("${prefix}_opacity_fixed_size", settings.opacityFixedSize)
            putFloat("${prefix}_opacity_fixed_max", settings.opacityFixedMaxOpacity)
        }
    }

    /**
     * 모드 설정 변경은 화면(StateFlow)에 즉시 반영하고 저장은 미룬다.
     * 슬라이더는 끄는 동안 값이 계속 바뀌어서 그때마다 저장하면 쓰기가 줄줄이 예약된다.
     * 설정 화면이 손을 뗄 때와 화면을 벗어날 때 [flushModeSettings] 를 부른다.
     */
    private val dirtyModes = mutableSetOf<String>()

    fun updateWaveMode(update: ModeSettings.() -> Unit) {
        _waveMode.value = _waveMode.value.copy().apply(update)
        dirtyModes += "wave"
    }
    
    fun updatePadMode(update: ModeSettings.() -> Unit) {
        _padMode.value = _padMode.value.copy().apply(update)
        dirtyModes += "pad"
    }

    fun updateCircleMode(update: ModeSettings.() -> Unit) {
        _circleMode.value = _circleMode.value.copy().apply(update)
        dirtyModes += "circle"
    }

    fun updateOutlineMode(update: ModeSettings.() -> Unit) {
        _outlineMode.value = _outlineMode.value.copy().apply(update)
        dirtyModes += "outline"
    }

    /** 미뤄둔 모드 설정을 저장한다. 바뀐 것이 없으면 아무것도 하지 않는다. 메인 스레드에서 부른다. */
    fun flushModeSettings() {
        if (dirtyModes.isEmpty()) return
        for (prefix in dirtyModes) {
            val settings = when (prefix) {
                "wave" -> _waveMode.value
                "pad" -> _padMode.value
                "circle" -> _circleMode.value
                else -> _outlineMode.value
            }
            saveMode(prefix, settings)
        }
        dirtyModes.clear()
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

        prefs.edit {
            putBoolean("show_ambient", showAmbient)
            putInt("color_ambient", colorAmbient)
            putBoolean("show_speech", showSpeech)
            putInt("color_speech", colorSpeech)
            putBoolean("show_danger", showDanger)
            putInt("color_danger", colorDanger)
        }
    }

    /** 소리 종류별 진동 설정. 모르는 라벨은 환경음 설정을 돌려준다 (AiClassification 과 같은 규칙). */
    fun hapticSettings(label: String): StateFlow<HapticSettings> =
        hapticFlows[label] ?: hapticFlows.getValue(AiClassification.AMBIENT)

    fun updateHaptic(label: String, settings: HapticSettings) {
        val flow = hapticFlows[label] ?: return
        flow.value = settings
        prefs.edit {
            putBoolean("haptic_${label}_enabled", settings.enabled)
            putString("haptic_${label}_strength", settings.strength.name)
            putString("haptic_${label}_pattern", settings.pattern.name)
        }
    }

    fun setServiceRunning(isRunning: Boolean) {
        _isServiceRunning.value = isRunning
    }
}

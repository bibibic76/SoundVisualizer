package com.example.soundvisualizer

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.soundvisualizer.feedback.HapticSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 표현 모드 하나의 설정.
 *
 * 기본값은 이 생성자 한 곳에만 둔다. 저장값이 없는 새 설치도 [SettingsManager.loadMode] 가
 * 여기 기본값을 그대로 받으므로, 기본값을 바꿀 때는 이곳과 ModeSettingsTest 만 고치면 된다.
 */
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

    private val _circleMode = MutableStateFlow(ModeSettings())
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

    // 빠른 설정 타일이 알림창에 추가돼 있는지. 타일 서비스가 추가·제거될 때 알려준다.
    private val _tileAdded = MutableStateFlow(false)
    val tileAdded: StateFlow<Boolean> = _tileAdded

    // 화면이 꺼지면 캡처·AI·진동을 쉴지. 배터리를 아끼는 쪽이 기본이다. (ScreenOffPause)
    private val _pauseWhenScreenOff = MutableStateFlow(true)
    val pauseWhenScreenOff: StateFlow<Boolean> = _pauseWhenScreenOff

    /**
     * 이번 실행에서 소리 종류 구분(AI)을 쓸 수 있는지. 캡처 서비스가 알려주며 저장하지 않는다.
     *
     * 모델 로딩이 실패해도 캡처와 시각화는 돈다. 그러면 모든 소리가 환경음 색으로 그려지고 진동 알림은 아예 돌지 않는데,
     * 설정 화면은 위협음 진동이 켜진 것처럼 보인다. 홈과 설정 화면이 이 값으로 그 사실을 알린다.
     * 로딩 중에는 true 로 둔다(보통 1초 안팎). 꺼져 있을 때도 true 다.
     */
    private val _aiAvailable = MutableStateFlow(true)
    val aiAvailable: StateFlow<Boolean> = _aiAvailable

    /** 화면이 꺼져 캡처를 쉬는 중인지. 오버레이가 이 동안 폴링을 멈춘다. 저장하지 않는다. */
    private val _isCapturePaused = MutableStateFlow(false)
    val isCapturePaused: StateFlow<Boolean> = _isCapturePaused

    /**
     * 사용자가 끄지 않았는데 마지막으로 꺼진 이유. 없으면 null. 홈 화면이 앱을 열었을 때 보여준다. (StopAlert)
     *
     * 앱 알림을 꺼 두면 꺼짐 알림도 올라가지 않고, 게임 위에서는 토스트도 시스템이 막아 진동만 남는다.
     * 무엇이 꺼졌는지 나중에라도 알 수 있게, 프로세스가 끝나도 남도록 저장한다. 다시 켜거나 홈에서 닫으면 지운다.
     */
    private val _lastUnexpectedStop = MutableStateFlow<StopReason?>(null)
    val lastUnexpectedStop: StateFlow<StopReason?> = _lastUnexpectedStop

    /** 액티비티/서비스 어디서든 호출 가능. 최초 한 번만 프리퍼런스를 읽는다. */
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // 저장된 ordinal 이 현재 enum 범위를 벗어나면(모드 추가/삭제 후) 크래시하지 않고 기본값으로.
        _visualMode.value = VisualMode.values().getOrElse(prefs.getInt("visualMode", 0)) { VisualMode.Wave }

        _waveMode.value = loadMode(prefs, "wave")
        _padMode.value = loadMode(prefs, "pad")
        _circleMode.value = loadMode(prefs, "circle")
        _outlineMode.value = loadMode(prefs, "outline")

        _showAmbient.value = prefs.getBoolean("show_ambient", true)
        _colorAmbient.value = prefs.getInt("color_ambient", DEFAULT_COLOR_AMBIENT)

        _showSpeech.value = prefs.getBoolean("show_speech", true)
        _colorSpeech.value = prefs.getInt("color_speech", DEFAULT_COLOR_SPEECH)

        _showDanger.value = prefs.getBoolean("show_danger", true)
        _colorDanger.value = prefs.getInt("color_danger", DEFAULT_COLOR_DANGER)

        _tileAdded.value = prefs.getBoolean("tile_added", false)
        _pauseWhenScreenOff.value = prefs.getBoolean("pause_when_screen_off", true)
        // 이름으로 저장한다. 모르는 이름이면 알릴 것이 없는 것으로 본다.
        _lastUnexpectedStop.value = prefs.getString("last_unexpected_stop", null)
            ?.let { name -> StopReason.values().firstOrNull { it.name == name } }

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

    /**
     * [prefix] 모드의 저장값을 읽는다. 저장된 적 없는 항목은 [ModeSettings] 의 기본값을 쓴다.
     *
     * 기본값을 여기에 숫자로 한 번 더 적어두면, 데이터 클래스만 고쳤을 때 테스트는 통과해도
     * 새로 설치한 사용자는 옛 값을 받는다. 그래서 기본값은 데이터 클래스 한 곳에만 둔다.
     * 원형 모드의 반지름도 [ModeSettings.circleRadius] 기본값을 그대로 쓴다.
     *
     * 기기 없이 저장·복원을 검사할 수 있게 프리퍼런스를 인자로 받는다 (ModeSettingsTest).
     */
    internal fun loadMode(source: SharedPreferences, prefix: String): ModeSettings {
        val d = ModeSettings()
        return ModeSettings(
            intensity = source.getFloat("${prefix}_intensity", d.intensity),
            speed = source.getFloat("${prefix}_speed", d.speed),
            opacity = source.getFloat("${prefix}_opacity", d.opacity),
            circleRadius = source.getFloat("${prefix}_radius", d.circleRadius),
            useRippleDelay = source.getBoolean("${prefix}_ripple", d.useRippleDelay),
            sensitivity = source.getFloat("${prefix}_sensitivity", d.sensitivity),
            isGlowMode = source.getBoolean("${prefix}_glow", d.isGlowMode),
            glowIntensity = source.getFloat("${prefix}_glow_intensity", d.glowIntensity),
            intensityAsOpacity = source.getBoolean("${prefix}_intensity_as_opacity", d.intensityAsOpacity),
            opacityFixedSize = source.getFloat("${prefix}_opacity_fixed_size", d.opacityFixedSize),
            opacityFixedMaxOpacity = source.getFloat("${prefix}_opacity_fixed_max", d.opacityFixedMaxOpacity)
        )
    }

    /** [loadMode] 와 같은 키로 적는다. 키를 바꾸면 기존 사용자 설정이 기본값으로 돌아간다. */
    internal fun putMode(editor: SharedPreferences.Editor, prefix: String, settings: ModeSettings) {
        editor.putFloat("${prefix}_intensity", settings.intensity)
        editor.putFloat("${prefix}_speed", settings.speed)
        editor.putFloat("${prefix}_opacity", settings.opacity)
        editor.putFloat("${prefix}_radius", settings.circleRadius)
        editor.putBoolean("${prefix}_ripple", settings.useRippleDelay)
        editor.putFloat("${prefix}_sensitivity", settings.sensitivity)
        editor.putBoolean("${prefix}_glow", settings.isGlowMode)
        editor.putFloat("${prefix}_glow_intensity", settings.glowIntensity)
        editor.putBoolean("${prefix}_intensity_as_opacity", settings.intensityAsOpacity)
        editor.putFloat("${prefix}_opacity_fixed_size", settings.opacityFixedSize)
        editor.putFloat("${prefix}_opacity_fixed_max", settings.opacityFixedMaxOpacity)
    }

    private fun saveMode(prefix: String, settings: ModeSettings) {
        prefs.edit { putMode(this, prefix, settings) }
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

    fun setTileAdded(added: Boolean) {
        _tileAdded.value = added
        prefs.edit { putBoolean("tile_added", added) }
    }

    fun setPauseWhenScreenOff(enabled: Boolean) {
        _pauseWhenScreenOff.value = enabled
        prefs.edit { putBoolean("pause_when_screen_off", enabled) }
    }

    /** 어느 스레드에서 불러도 된다 (AI 초기화 스레드가 부른다). */
    fun setAiAvailable(available: Boolean) {
        _aiAvailable.value = available
    }

    fun setCapturePaused(paused: Boolean) {
        _isCapturePaused.value = paused
    }

    /** [reason] 이 null 이면 지운다. */
    fun setLastUnexpectedStop(reason: StopReason?) {
        _lastUnexpectedStop.value = reason
        prefs.edit {
            if (reason == null) remove("last_unexpected_stop") else putString("last_unexpected_stop", reason.name)
        }
    }
}

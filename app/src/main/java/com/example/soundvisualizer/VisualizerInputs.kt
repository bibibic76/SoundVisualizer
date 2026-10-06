package com.example.soundvisualizer

/**
 * [VisualizerEngine] 이 프레임마다 읽는 바깥 세계.
 *
 * 엔진 자체는 산술만 하는데, 값의 출처는 네이티브 라이브러리와 SharedPreferences 와
 * 분류기다. 그 셋을 직접 부르면 기기 없이는 엔진을 만들 수조차 없으므로 한 겹으로 묶었다.
 * 실제 구동은 [LiveVisualizerInputs], 테스트는 가짜 구현을 넣는다.
 */
interface VisualizerInputs {
    /** [out] (크기 3) 에 `[좌 피크, 우 피크, 도착한 버퍼 수]` 를 채운다. */
    fun readPeaks(out: FloatArray)
    fun currentMode(): VisualMode
    fun settingsFor(mode: VisualMode): ModeSettings
    /** [AiClassification] 의 라벨 중 하나. */
    fun coarseLabel(): String
    fun colorFor(label: String): Int
    fun isShown(label: String): Boolean

    /**
     * 1초에 그릴 프레임 수. 0 이면 화면 주사율 그대로.
     *
     * 프레임마다 읽으므로 설정에서 바꾸면 실행 중에도 바로 적용된다.
     * 기본값을 둬서 테스트의 가짜 구현이 이 값을 신경 쓰지 않아도 되게 한다.
     */
    fun framesPerSecond(): Int = VisualizerEngine.FULL_FPS
}

/** 실제 구동 배선. */
object LiveVisualizerInputs : VisualizerInputs {
    override fun readPeaks(out: FloatArray) = AudioEngine.readPeaks(out)

    override fun currentMode(): VisualMode = SettingsManager.visualMode.value

    override fun settingsFor(mode: VisualMode): ModeSettings = when (mode) {
        VisualMode.Wave -> SettingsManager.waveMode.value
        VisualMode.Pad -> SettingsManager.padMode.value
        VisualMode.CircleRipple -> SettingsManager.circleMode.value
        VisualMode.Outline -> SettingsManager.outlineMode.value
    }

    override fun coarseLabel(): String = AiClassification.coarse()

    override fun colorFor(label: String): Int = when (label) {
        AiClassification.DANGER -> SettingsManager.colorDanger.value
        AiClassification.SPEECH -> SettingsManager.colorSpeech.value
        else -> SettingsManager.colorAmbient.value
    }

    override fun isShown(label: String): Boolean = when (label) {
        AiClassification.DANGER -> SettingsManager.showDanger.value
        AiClassification.SPEECH -> SettingsManager.showSpeech.value
        else -> SettingsManager.showAmbient.value
    }

    override fun framesPerSecond(): Int =
        if (SettingsManager.reducedFrameRate.value) VisualizerEngine.REDUCED_FPS else VisualizerEngine.FULL_FPS
}

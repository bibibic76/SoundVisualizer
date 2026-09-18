package com.example.soundvisualizer.ai

/** Log-mel frontend 한 종류. 테스트에서는 실제 DSP 대신 작은 가짜 구현을 넣는다. */
internal fun interface LogMelFrontend {
    fun compute(mono16k: FloatArray): FloatArray
}

/**
 * 실시간 tick이 선택한 frontend 하나만 만들고 보관한다.
 *
 * 두 전처리기는 큰 작업 버퍼를 가지며 thread-safe하지 않다. 이 객체도 별도 동기화를 하지 않으므로
 * [RealtimeAiPipeline]의 inference lock 안에서만 호출한다. 선택이 바뀌면 이전 frontend 참조를 버려
 * 두 구현의 작업 버퍼를 동시에 계속 보관하지 않는다.
 */
internal class AiFrontendSwitcher(
    private val currentFactory: () -> LogMelFrontend = {
        val preprocessor = AudioPreprocessor()
        LogMelFrontend { mono16k -> preprocessor.computeLogMelSpectrogram(mono16k) }
    },
    private val qualcommSourceFactory: () -> LogMelFrontend = {
        val preprocessor = QualcommSourceAudioPreprocessor()
        LogMelFrontend { mono16k -> preprocessor.computeLogMelSpectrogram(mono16k) }
    }
) {
    private var activeMode: AiFrontendMode? = null
    private var activeFrontend: LogMelFrontend? = null

    fun compute(mode: AiFrontendMode, mono16k: FloatArray): FloatArray {
        if (activeMode != mode || activeFrontend == null) {
            activeFrontend = when (mode) {
                AiFrontendMode.CURRENT -> currentFactory()
                AiFrontendMode.QUALCOMM_SOURCE -> qualcommSourceFactory()
            }
            activeMode = mode
        }
        return checkNotNull(activeFrontend).compute(mono16k)
    }
}

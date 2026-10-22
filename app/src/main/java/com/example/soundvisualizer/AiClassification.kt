package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiClassificationResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 분류기(AudioCaptureService 소유)와 오버레이 렌더러를 잇는 지점.
 *
 * 두 서비스는 서로를 모르고 수명도 다르므로, 캡처 쪽이 결과를 읽는 함수를 걸어두고
 * 렌더 쪽은 그것만 호출한다. 분류기가 없으면(캡처 미실행, 모델 로드 실패 등)
 * 항상 [AMBIENT] 로 떨어져서 시각화는 그대로 동작한다.
 *
 * 렌더 스레드가 프레임마다 읽으므로 락을 잡지 않는다. 뒤에 있는
 * RealtimeAiPipeline.lastClassification() 이 AtomicReference 읽기라 그대로 안전하다.
 */
object AiClassification {

    // ai/ 코드는 이 상수를 참조하지 않고 같은 문자열을 직접 쓴다. 화면·진동은 모르는 라벨을 환경음으로
    // 처리해서 이름이 어긋나도 오류가 나지 않으므로, 둘이 같은지는 AiLabelContractTest 가 확인한다.
    const val AMBIENT = "ambient"
    const val SPEECH = "speech"
    const val DANGER = "danger"

    @Volatile
    private var source: (() -> AiClassificationResult?)? = null
    @Volatile private var displaySource: (() -> AiClassificationResult?)? = null

    // Wake-up only: observers always call latest(), so a queued event cannot revive an old result.
    private val changed = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val changes = changed.asSharedFlow()

    /** Non-suspending; never execute HUD work on the inference thread. */
    fun notifyResultChanged() { changed.tryEmit(Unit) }

    /** 캡처 서비스가 분류기를 시작한 뒤(마지막 결과를 비운 뒤) 호출한다. */
    fun attach(source: () -> AiClassificationResult?) {
        this.source = source
        displaySource = source
        notifyResultChanged()
    }

    fun attachDisplay(source: () -> AiClassificationResult?) { displaySource = source; notifyResultChanged() }

    /**
     * 분류기가 멈추면 반드시 호출한다. 이후 읽기는 [AMBIENT] 로 떨어진다.
     * 화면이 꺼져 쉬는 동안에도 뗀다. 파이프라인은 멈춰도 마지막 결과를 들고 있어서,
     * 붙여 둔 채로 두면 다시 켠 첫 소리를 쉬기 직전 라벨(색·표시 여부)로 그린다.
     */
    fun detach() {
        source = null
        displaySource = null
        notifyResultChanged()
    }

    /**
     * 가장 최근 라벨. [AMBIENT] / [SPEECH] / [DANGER] 중 하나.
     *
     * 파이프라인이 상위 후보 투표와 안전 신호 보존을 거친 라벨에 확신도 기준과 히스테리시스까지 적용해
     * 확정한 값을 내보내므로, 여기서 추가로 흔들림을 잡을 필요는 없다.
     */
    fun coarse(): String = latest()?.coarse ?: AMBIENT

    /**
     * 가장 최근 결과 전체. 분류기가 없거나 첫 추론이 끝나지 않았으면 null.
     *
     * 오버레이와 진동은 라벨 하나로 충분하다. 나머지 필드는 두 곳이 읽는다. 개발자 모드는 "분류가 맞는지" 를
     * 사람이 채점하려고 확정 이름, 확신도, 임계값 통과 여부, 안전 신호 승격, 상위 5개, 단계별 소요시간까지 읽고,
     * 잠금 화면 위협음 알림은 들린 소리 이름을 고르려고 라벨과 확정 이름, 상위 5개를 읽는다([DangerAlertPolicy]).
     * 필드끼리는 한 번 읽은 결과 안에서만 짝이 맞으므로 여러 필드를 쓰는 쪽은 한 번만 읽는다.
     * 같은 AtomicReference 읽기라 락은 여기서도 필요 없다.
     */
    fun latest(): AiClassificationResult? = source?.invoke()
    fun latestForDisplay(): AiClassificationResult? = displaySource?.invoke()
}

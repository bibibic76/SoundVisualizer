package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiClassificationResult

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

    /** 캡처 서비스가 분류기를 시작한 뒤(마지막 결과를 비운 뒤) 호출한다. */
    fun attach(source: () -> AiClassificationResult?) {
        this.source = source
    }

    /**
     * 분류기가 멈추면 반드시 호출한다. 이후 읽기는 [AMBIENT] 로 떨어진다.
     * 화면이 꺼져 쉬는 동안에도 뗀다. 파이프라인은 멈춰도 마지막 결과를 들고 있어서,
     * 붙여 둔 채로 두면 다시 켠 첫 소리를 쉬기 직전 라벨(색·표시 여부)로 그린다.
     */
    fun detach() {
        source = null
    }

    /**
     * 가장 최근 라벨. [AMBIENT] / [SPEECH] / [DANGER] 중 하나.
     *
     * 파이프라인이 히스테리시스와 위협음 프리뷰까지 반영한 값을 내보내므로
     * 여기서 추가로 흔들림을 잡을 필요는 없다.
     */
    fun coarse(): String = latest()?.coarse ?: AMBIENT

    /**
     * 가장 최근 결과 전체. 분류기가 없거나 첫 추론이 끝나지 않았으면 null.
     *
     * 오버레이와 진동은 [coarse] 하나로 충분하다. 이쪽은 개발자 모드에서 "분류가 맞는지" 를
     * 사람이 채점하려고 나머지 필드(모델이 말한 이름, 확신도, 임계값 통과 여부, 부스터 개입,
     * 단계별 소요시간)까지 읽는 용도다. 같은 AtomicReference 읽기라 락은 여기서도 필요 없다.
     */
    fun latest(): AiClassificationResult? = source?.invoke()
}

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

    const val AMBIENT = "ambient"
    const val SPEECH = "speech"
    const val DANGER = "danger"

    @Volatile
    private var source: (() -> AiClassificationResult?)? = null

    /** 캡처 서비스가 분류기를 띄운 뒤 호출한다. */
    fun attach(source: () -> AiClassificationResult?) {
        this.source = source
    }

    /** 캡처가 멈추면 반드시 호출한다. 이후 읽기는 [AMBIENT] 로 떨어진다. */
    fun detach() {
        source = null
    }

    /**
     * 가장 최근 라벨. [AMBIENT] / [SPEECH] / [DANGER] 중 하나.
     *
     * 파이프라인이 히스테리시스와 위협음 프리뷰까지 반영한 값을 내보내므로
     * 여기서 추가로 흔들림을 잡을 필요는 없다.
     */
    fun coarse(): String = source?.invoke()?.coarse ?: AMBIENT
}

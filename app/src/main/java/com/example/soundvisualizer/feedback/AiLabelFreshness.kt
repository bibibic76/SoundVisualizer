package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.ai.AiClassificationResult

/**
 * 외부 사운드 모드에서 진동과 잠금 화면 위협음 알림이 쓰는 라벨(#352). AI 가 [idleMs] 동안 새 결과를 내지 않으면 라벨이
 * 없는 것(null)으로 본다. 안드로이드에 의존하지 않아 JVM 에서 테스트한다. 한 스레드에서만 부른다.
 *
 * AI 는 조용해지면 쉰다. 마지막으로 0.01 을 넘은 소리가 창(0.975초)을 빠져나간 뒤 추론을 두 번 더 하고 멈추는데, 그 두 번이
 * 모두 확신 있는 환경음이 아니면 후처리가 종류를 바꾸지 못해 쉬기 직전 라벨(예: 대화음)이 그대로 남는다. AI 는 마이크 감도가
 * 걸리지 않은 소리로 쉴지 정하지만 진동은 감도를 곱한 크기로 소리가 나는지 본다. 그래서 감도를 높이면(×32) 사무실 잡음에도
 * 소리가 나는 것으로 보여, 그 남은 라벨로 진동이 끝없이 울렸다. 우리 진동 소리가 없어도 그랬다(시뮬레이션).
 *
 * 새 결과는 객체가 바뀐 것으로 안다. 파이프라인은 추론할 때마다 새 결과 객체를 내고, 쉬는 동안에는 같은 객체를 돌려준다.
 * 결과의 시각(`timestampMs`)은 벽시계라 건너뛸 수 있어 쓰지 않는다.
 *
 * 대가: AI 가 듣지 않는 아주 작은 소리(원래 크기 0.01 아래)에는 감도를 높여도 쉬기 직전 종류로 울리지 않는다. 쉬던 AI 가 다시
 * 깨면 새 결과 하나(0.3초 안팎)를 기다렸다 울린다. 오버레이 색은 이 규칙을 따르지 않는다.
 */
class AiLabelFreshness(private val idleMs: Long = HapticTuning.AI_LABEL_IDLE_MS) {

    private var last: AiClassificationResult? = null
    private var newAtMs = Long.MIN_VALUE

    /**
     * 지금 쓸 라벨. 결과가 없거나 [idleMs] 동안 새 결과가 없었으면 null 이다. null 을 다른 값으로 메우지 않는다.
     * @param nowMs 단조 증가하는 시각(elapsedRealtime)
     * @param result 지금 AI 가 돌려주는 결과([com.example.soundvisualizer.AiClassification.latest])
     */
    fun label(nowMs: Long, result: AiClassificationResult?): String? {
        if (result == null) {
            last = null
            return null
        }
        if (result !== last) {
            last = result
            newAtMs = nowMs
        }
        return if (nowMs - newAtMs >= idleMs) null else result.coarse
    }

    /** 지난 실행의 결과를 잊는다. 다음 결과는 새 결과로 본다. */
    fun reset() {
        last = null
        newAtMs = Long.MIN_VALUE
    }
}

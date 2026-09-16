package com.example.soundvisualizer.ai

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * close() 의 잠금·세션 상태는 [RealtimeAiPipeline] 안에 숨어 있고, 메인 코드에는 테스트 훅을 두지
 * 않기로 했다 (#49). 그래서 계측 테스트 쪽에서만 리플렉션으로 같은 필드를 들여다본다.
 *
 * 이렇게까지 하는 이유: "추론 중 close", "시간 초과 후 세션 누수", "종료 중 틱 거부" 세 갈래는
 * 밖에서 타이밍을 흔드는 것만으로는 우연히 걸릴 뿐이라, 회귀를 막아 주지 못한다.
 * 필드 이름이 바뀌면 여기서 NoSuchFieldException 으로 바로 드러나니 이름만 맞춰 주면 된다.
 */
internal object PipelineInternals {

    /** 추론을 직렬화하는 락. 테스트가 직접 잡아 "오래 걸리는 추론"을 정확한 길이로 흉내 낸다. */
    fun inferLock(pipeline: RealtimeAiPipeline): ReentrantLock = read(pipeline, "inferLock")

    /**
     * 링 버퍼의 내부 모니터. 틱을 "closed 검사 통과 직후 ~ tryLock 직전"에 세워 둘 수 있는
     * 유일한 지점이라, 락 안 closed 재확인을 시험할 때 쓴다.
     */
    fun ringMonitor(pipeline: RealtimeAiPipeline): Any {
        val buffer: AiAudioBuffer = read(pipeline, "audioBuffer")
        return read(buffer, "lock")
    }

    /** close() 가 closed 를 세운 순간만 흉내 낸다 — 세션은 살려 둔다. */
    fun setClosedFlag(pipeline: RealtimeAiPipeline, value: Boolean) {
        read<AtomicBoolean>(pipeline, "closed").set(value)
    }

    /**
     * ONNX 세션이 아직 살아 있으면 null, 닫혀 있으면 ORT 가 던진 예외를 돌려준다.
     * 닫힌 세션은 네이티브로 내려가기 전에 자바 쪽에서 막히므로("Trying to score a closed
     * OrtSession.") 이 확인 자체가 크래시를 내지는 않는다.
     */
    fun sessionFailure(pipeline: RealtimeAiPipeline): Throwable? {
        val yamnet: YamnetInference = read(pipeline, "yamnet")
        val booster: GunshotBoosterInference = read(pipeline, "booster")
        return try {
            val result = yamnet.inferFromLogMelFlat(FloatArray(YamnetInference.LOG_MEL_SIZE))
            booster.score(result.probabilities)
            null
        } catch (t: Throwable) {
            t
        }
    }

    /** 시간 초과로 일부러 누수시킨 세션을 테스트가 끝난 뒤 정리한다 (그대로 두면 프로세스 내내 남는다). */
    fun releaseLeakedSessions(pipeline: RealtimeAiPipeline) {
        val yamnet: YamnetInference = read(pipeline, "yamnet")
        val booster: GunshotBoosterInference = read(pipeline, "booster")
        runCatching { yamnet.close() }
        runCatching { booster.close() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(owner: Any, name: String): T {
        val field = owner.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(owner) as T
    }
}

package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiClassificationResult

/**
 * AI 판정을 받아 줄로 쌓는다 (#193). 어디에 쌓느냐는 [sink] 가 정한다 — 파일이든 테스트의 문자열이든.
 *
 * 세 가지를 여기서 지킨다.
 *  - **머리글은 한 번만.** 첫 줄을 쓸 때 같이 나간다. 결과가 한 번도 없으면 빈 파일이 된다.
 *  - **같은 결과는 한 번만.** HUD 는 100ms 마다 읽지만 추론은 250ms 주기라 같은 결과를 두세 번 본다.
 *    [AiClassificationResult.timestampMs] 가 앞서 쓴 것과 같으면 건너뛴다.
 *  - **상한에 닿으면 멈춘다.** 초당 네 줄이 하루면 수십 MB 가 된다. 켜 둔 것을 잊은 기기에서
 *    저장 공간을 먹는 것을 막는다. 멈춘 뒤에는 조용히 아무것도 쓰지 않는다([stopped] 로 알 수 있다).
 *
 * 안드로이드에 의존하지 않아 기기 없이 검사한다 ([AiDebugLogWriterTest]).
 */
class AiDebugLogWriter(
    private val sink: Appendable,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {

    /** 쓴 줄 수(머리글은 세지 않는다). */
    var rows: Int = 0
        private set

    /** 상한에 닿아 그만 쓰기로 했는지. */
    var stopped: Boolean = false
        private set

    private var wroteHeader = false
    private var written: Long = 0L
    private var lastResultMs: Long? = null

    /**
     * 결과 하나를 쌓는다.
     *
     * @return 실제로 한 줄을 썼으면 true. 같은 결과라 건너뛰었거나 상한에 닿았으면 false.
     */
    fun write(result: AiClassificationResult, nowMs: Long, level: Float, shown: Boolean): Boolean {
        if (stopped) return false
        if (result.timestampMs == lastResultMs) return false

        if (!wroteHeader) {
            append(AiDebugCsv.HEADER)
            wroteHeader = true
        }
        append(AiDebugCsv.row(result, nowMs, level, shown))
        lastResultMs = result.timestampMs
        rows++
        if (written >= maxBytes) stopped = true
        return true
    }

    private fun append(line: String) {
        sink.append(line).append('\n')
        // 한 글자 = 한 바이트로 센다. 이름은 아스키라 거의 맞고, 상한은 정확할 필요가 없다.
        written += line.length + 1
    }

    companion object {
        /** 기본 상한 20MB. 초당 네 줄(한 줄 약 350바이트)이면 하루쯤 담긴다. */
        const val DEFAULT_MAX_BYTES = 20L * 1024 * 1024
    }
}

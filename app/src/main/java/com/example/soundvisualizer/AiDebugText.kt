package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiClassificationResult
import java.util.Locale

/**
 * 개발자 모드 오버레이에 뿌릴 글자들. 화면 배치는 `AiDebugOverlay` 가 한다.
 *
 * @param colorLabel 점을 칠할 색을 고르는 데 쓰는 [AiClassification] 라벨. 결과가 없으면 null
 * @param coarse 소리 종류를 대문자로. 결과가 없으면 왜 없는지를 대신 넣는다
 * @param label 모델이 말한 이름 (raw YAMNet 클래스명)
 * @param confidence 확신도 두 자리
 * @param detail 임계값·부스터·프리뷰·나이
 * @param timing 소리 크기·표시 여부·단계별 소요시간
 */
data class AiDebugLines(
    val colorLabel: String?,
    val coarse: String,
    val label: String,
    val confidence: String,
    val detail: String,
    val timing: String
)

/**
 * AI 분류 결과를 개발자 모드 오버레이의 글자로 바꾼다.
 *
 * 이 도구의 목적은 **분류가 맞는지 사람이 눈으로 채점하는 것**이라, 모델이 실제로 말한 것을
 * 손대지 않고 보여준다. 라벨을 번역하지 않는 이유도 같다. 확신도가 낮을 때도 숨기지 않는다.
 * 무엇을 왜 보여주는지는 docs/ARCHITECTURE.md 4장에 적어 두었다.
 *
 * 안드로이드에 의존하지 않아 기기 없이 검사한다 ([AiDebugTextTest]).
 */
object AiDebugText {

    /** 값이 없거나 뜻이 없을 때. 숫자 자리에 NaN 이 그대로 뜨는 것을 막는다. */
    const val NONE = "-"

    /** 모델을 못 불러와 분류가 아예 돌지 않는 상태. */
    const val COARSE_AI_OFF = "AI OFF"

    /** 분류는 돌지만 첫 결과가 아직 없는 상태. 캡처 직후와 화면을 다시 켠 직후가 여기다. */
    const val COARSE_WAITING = "WAIT"

    fun format(
        result: AiClassificationResult?,
        nowMs: Long,
        level: Float,
        shown: Boolean,
        aiAvailable: Boolean
    ): AiDebugLines {
        val levelText = "lvl ${twoDecimals(level)}"

        if (result == null) {
            // 붙은 분류기가 없거나 첫 추론이 끝나지 않았다. 낡은 줄을 남겨 두면 그것을 지금 결과로
            // 착각하므로, 값이 없다는 것을 그대로 보여준다.
            return AiDebugLines(
                colorLabel = null,
                coarse = if (aiAvailable) COARSE_WAITING else COARSE_AI_OFF,
                label = if (aiAvailable) "no result yet" else "model load failed",
                confidence = NONE,
                detail = NONE,
                timing = levelText
            )
        }

        return AiDebugLines(
            colorLabel = result.coarse,
            coarse = result.coarse.uppercase(Locale.US),
            // 확정된 이름이 아직 없으면 빈 문자열이 온다. 임계값을 한 번도 넘지 못한 상태다.
            label = result.display.ifEmpty { "(none)" },
            confidence = twoDecimals(result.confidence),
            detail = detailOf(result, nowMs),
            timing = "$levelText   shown ${yesNo(shown)}   ${timingOf(result)}"
        )
    }

    private fun detailOf(result: AiClassificationResult, nowMs: Long): String {
        // 부스터가 채택되면 종류뿐 아니라 이름까지 총소리 클래스명으로 갈아치운다. 그래서 pre(부스터 전
        // 종류)와 bst(채택 여부·점수)가 없으면, 모델이 하지 않은 말을 모델 탓으로 채점하게 된다.
        val booster = if (result.boosterAvailable) {
            "bst ${yesNo(result.boosterAccepted)} ${twoDecimals(result.gunshotScore)}"
        } else {
            "bst off"
        }
        return "thr ${yesNo(result.meetsThreshold)}   pre ${result.preBoosterCoarse}   " +
            "$booster   prev ${yesNo(result.useBoosterDangerPreview)}   age ${ageText(result.timestampMs, nowMs)}"
    }

    private fun timingOf(result: AiClassificationResult): String =
        "${wholeMillis(result.totalMs)}ms " +
            "(${wholeMillis(result.preprocessMs)}/${wholeMillis(result.yamnetMs)}/${wholeMillis(result.boosterMs)})"

    /**
     * 결과가 나온 뒤 흐른 시간. 무음이면 추론을 건너뛰고 마지막 결과를 그대로 들고 있으므로,
     * 이 값이 계속 자라는 것이 "지금 추론이 돌지 않는다" 는 신호다.
     *
     * 벽시계로 찍힌 시각이라 시계가 뒤로 조정되면 음수가 될 수 있다. 그때는 0 으로 본다.
     */
    private fun ageText(timestampMs: Long, nowMs: Long): String {
        val elapsed = (nowMs - timestampMs).coerceAtLeast(0L)
        return "${String.format(Locale.US, "%.1f", elapsed / 1000.0)}s"
    }

    /** 앱이 기본 로캘을 고른 언어로 바꾸므로 서식을 [Locale.US] 로 못박는다. 아랍어에서 아랍 숫자가 된다. */
    private fun twoDecimals(value: Float): String =
        if (value.isNaN()) NONE else String.format(Locale.US, "%.2f", value)

    private fun wholeMillis(value: Double): String =
        if (value.isNaN()) NONE else String.format(Locale.US, "%.0f", value)

    private fun yesNo(value: Boolean): String = if (value) "Y" else "N"
}

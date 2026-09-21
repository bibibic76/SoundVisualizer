package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiClassificationResult
import java.util.Locale

/**
 * AI 판정 하나를 CSV 한 줄로 바꾼다 (#193). 파일로 쓰는 일은 [AiDebugRecorder] 가 한다.
 *
 * 개발자 모드 HUD 는 지금 순간만 보여주고, `AI_RESULT` 로그는 `debuggable` 빌드에만 남으면서
 * 2초에 한 줄로 묶인다. 이 기록은 HUD 가 읽는 값을 그대로 쓰므로 배포판에서도 남고 추론 주기를 따라간다.
 *
 * 안드로이드에 의존하지 않아 기기 없이 검사한다 ([AiDebugCsvTest]).
 */
object AiDebugCsv {

    /** top-5 를 몇 칸까지 적는지. 모델이 덜 내놓으면 남은 칸은 비워 둔다. */
    const val TOP_SLOTS = 5

    /** 값이 없을 때 넣는 것. 빈 칸으로 두면 표 프로그램이 0 으로 읽는 일이 있다. */
    const val NONE = ""

    val COLUMNS: List<String> = buildList {
        add("time_ms")
        add("result_ms")
        add("age_ms")
        add("coarse")
        add("display")
        add("confidence")
        // 임계값 자체는 결과에 실리지 않는다(후처리 안에만 있다). 넘었는지만 적는다.
        add("meets_threshold")
        add("pre_booster_coarse")
        add("booster_enabled")
        add("booster_available")
        add("booster_accepted")
        add("booster_reason")
        add("gunshot_score")
        add("gunshot_evidence")
        add("danger_cue_promoted")
        add("danger_preview")
        for (slot in 1..TOP_SLOTS) {
            add("top${slot}_name")
            add("top${slot}_prob")
        }
        add("frontend")
        add("level")
        add("shown")
        add("preprocess_ms")
        add("yamnet_ms")
        add("booster_ms")
        add("total_ms")
    }

    /** 머리글 한 줄. 파일을 새로 열 때 한 번만 쓴다. */
    val HEADER: String = COLUMNS.joinToString(",")

    /**
     * 결과 하나를 한 줄로.
     *
     * @param nowMs 읽은 시각. [AiClassificationResult.timestampMs] 와의 차이가 age 다. 무음 게이트가 닫히면
     *   추론을 건너뛰고 마지막 결과를 들고 있으므로, 나이가 자라는 것이 보여야 "추론이 멈췄다" 를 알 수 있다.
     * @param level 그때의 소리 크기(`AudioEngine.currentLevel()`). 진동 게이트가 보는 값이라
     *   "왜 진동이 안 왔나" 를 여기서 가른다.
     * @param shown 그 종류 표시가 켜져 있었는지. 꺼 두면 화면에 아무것도 그리지 않으므로,
     *   이것 없이는 설정 상태를 "AI 가 못 잡았다" 로 오독한다.
     */
    fun row(result: AiClassificationResult, nowMs: Long, level: Float, shown: Boolean): String {
        val cells = ArrayList<String>(COLUMNS.size)
        cells += nowMs.toString()
        cells += result.timestampMs.toString()
        // 시계가 뒤로 튈 수 있다(System.currentTimeMillis). 음수 나이는 뜻이 없으니 0 으로 붙인다.
        cells += (nowMs - result.timestampMs).coerceAtLeast(0L).toString()
        cells += result.coarse
        cells += result.display
        cells += prob(result.confidence)
        cells += result.meetsThreshold.toString()
        cells += result.preBoosterCoarse
        cells += result.boosterEnabled.toString()
        cells += result.boosterAvailable.toString()
        cells += result.boosterAccepted.toString()
        cells += result.boosterReason
        // 부스터를 못 불러왔으면 점수 자리는 NaN 이다. 그대로 적으면 표에 NaN 이 뜨므로 비운다.
        cells += if (result.boosterAvailable) prob(result.gunshotScore) else NONE
        cells += if (result.boosterAvailable) prob(result.gunshotEvidence) else NONE
        cells += result.dangerCuePromoted.toString()
        cells += result.useBoosterDangerPreview.toString()
        for (slot in 0 until TOP_SLOTS) {
            val hit = result.top5.getOrNull(slot)
            cells += hit?.name ?: NONE
            cells += hit?.let { prob(it.probability) } ?: NONE
        }
        cells += result.frontendMode.diagnosticName
        cells += prob(level)
        cells += shown.toString()
        cells += millis(result.preprocessMs)
        cells += millis(result.yamnetMs)
        cells += millis(result.boosterMs)
        cells += millis(result.totalMs)
        return cells.joinToString(",") { quote(it) }
    }

    /**
     * 칸 하나를 CSV 로 감싼다.
     *
     * YAMNet 이름에는 쉼표가 들어간다("Gunshot, gunfire"). 감싸지 않으면 그 줄만 칸이 하나 밀려
     * 표 전체가 어긋난다. 따옴표도 이름에 들어올 수 있어 두 번 적어 피한다.
     */
    private fun quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    /**
     * 확률·소리 크기를 다섯 자리로. **로캘을 US 로 고정한다** — 앱이 기본 로캘을 사용자가 고른 언어로
     * 바꾸므로, 그대로 두면 아랍어에서 소수점이 아랍 숫자로 나가 표가 읽히지 않는다.
     */
    private fun prob(value: Float): String =
        if (value.isNaN()) NONE else String.format(Locale.US, "%.5f", value)

    /** 소요 시간은 한 자리면 된다. 여기도 로캘을 고정한다. */
    private fun millis(value: Double): String =
        if (value.isNaN()) NONE else String.format(Locale.US, "%.1f", value)
}

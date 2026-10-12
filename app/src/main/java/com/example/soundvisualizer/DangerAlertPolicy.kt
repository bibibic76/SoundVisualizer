package com.example.soundvisualizer

import com.example.soundvisualizer.ai.YamnetCoarseClassifier
import com.example.soundvisualizer.feedback.HapticPolicy

/**
 * 폰이 잠겨 있거나 화면이 꺼진 동안 위협음이 들리면 잠금 화면에 알릴지 정한다(#310). 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 그때는 오버레이가 잠금 화면에 가려 보이지 않는다. 쓰는 중이면 오버레이가 이미 보이므로 알리지 않는다.
 *
 * - **라벨만 보지 않는다.** AI 는 소리가 끝나도 마지막 라벨을 들고 있다. 그래서 진동([HapticPolicy])처럼
 *   "위협음 라벨이면서 실제로 소리가 난다" 를 함께 본다. 소리 크기는 캡처 서비스의 확인 틱이 모은 지난 틱 이후의 최대값이다.
 * - **위협음이 이어지는 동안을 한 사건으로 본다.** [episodeGapMs] 동안 위협음이 없으면 사건이 끝난다.
 * - **한 사건에 한 번만 알린다.** 잠겼거나 꺼졌는지는 아직 알리지 않은 사건 동안에만 묻는다. 그래서 사이렌이 이어지는 중에
 *   폰을 잠그면 그때 알린다.
 * - **알린 뒤 [cooldownMs] 안에는 다시 알리지 않는다.** 위협음이 짧게 끊겼다 이어지는 장면에서 알림이 쏟아지지 않게 한다.
 *
 * 알림에 적을 소리는 [alertSoundIndex] 가 고른다.
 */
class DangerAlertPolicy(
    private val episodeGapMs: Long = EPISODE_GAP_MS,
    private val cooldownMs: Long = COOLDOWN_MS,
    private val levelThreshold: Float = HapticPolicy.LEVEL_THRESHOLD
) {
    private var lastDangerMs: Long? = null
    private var firedThisEpisode = false
    private var lastFiredMs: Long? = null

    /**
     * 확인 틱마다 부른다.
     *
     * @param label 지금의 AI 라벨([AiClassification.coarse])
     * @param peak 지난 틱 이후 들어온 소리의 최대 크기(0..1)
     * @param enabled 위협음 표시가 켜져 있고 AI 가 동작하는지
     * @param lockedOrOff 잠겨 있거나 화면이 꺼져 있는지. 알릴 차례일 때만 묻는다.
     * @return 지금 알려야 하면 true
     */
    fun onTick(nowMs: Long, label: String, peak: Float, enabled: Boolean, lockedOrOff: () -> Boolean): Boolean {
        val last = lastDangerMs
        if (last != null && nowMs - last > episodeGapMs) {
            lastDangerMs = null
            firedThisEpisode = false
        }
        val dangerNow = enabled && label == AiClassification.DANGER && peak > levelThreshold
        if (!dangerNow) return false
        lastDangerMs = nowMs
        if (firedThisEpisode) return false
        val lastFired = lastFiredMs
        if (lastFired != null && nowMs - lastFired < cooldownMs) return false
        if (!lockedOrOff()) return false
        firedThisEpisode = true
        lastFiredMs = nowMs
        return true
    }

    /** 캡처를 새로 시작할 때 부른다. 지난 실행의 사건을 이어 보지 않는다. */
    fun reset() {
        lastDangerMs = null
        firedThisEpisode = false
        lastFiredMs = null
    }

    companion object {
        /** 위협음이 이만큼 없으면 사건이 끝난 것으로 본다. */
        const val EPISODE_GAP_MS = 3_000L

        /** 알린 뒤 이만큼은 다시 알리지 않는다. */
        const val COOLDOWN_MS = 30_000L

        /**
         * 상위 후보에서 이름을 고를 때의 확률 하한([alertSoundIndex] 의 두 번째 규칙).
         *
         * AI 가 사이렌·경보 같은 강한 단서를 위협음으로 올리는 하한(ai/ 의 YamnetSafetyCueDecision 이 쓰는 0.05)과 같은
         * 값이다. 그 상수는 공개되어 있지 않아 여기 따로 둔다. AI 가 단서로도 치지 않는 약한 후보를 "들린 소리" 로 적지
         * 않으려는 것이다. 총소리 녹음의 마지막 후보 Smash, crash(0.028)가 그런 예다(#356).
         */
        const val CANDIDATE_MIN_PROBABILITY = 0.05f

        /**
         * 알림에 적을 소리의 YAMNet 클래스 번호. 앱 언어의 이름은 이 번호로 sound_names 에서 꺼낸다.
         * 적을 소리가 없으면 null 이고, 그때는 이름 없이 알린다.
         *
         * 1. AI 가 확정한 이름([display])이 지금 위협음 종류면 그 이름을 쓴다. 알릴지는 여러 프레임을 거쳐 확정된 라벨로
         *    정하는데, 이 이름은 확신도 기준을 넘긴 위협음 프레임 가운데 가장 최근 것의 이름이라 지금의 위협음을 만든
         *    소리다. [top5] 는 가장 최근 한 프레임뿐이라, 사이렌이 이어지는 중에도 그 프레임에는 사이렌이 없을 수 있다.
         *    그것만 보던 때는 구급차 녹음에 Siren 대신 Train whistle(0.06)을 적었다(#356).
         * 2. 아니면 [top5] 에서 지금 위협음 종류이면서 확률이 [CANDIDATE_MIN_PROBABILITY] 이상인 첫 소리를 쓴다. 상위
         *    3개의 투표로 위협음이 된 프레임은 1순위가 위협음이 아니어도 그 1순위가 확정 이름으로 남기 때문이다
         *    (1위 Vehicle 에 2·3위 Train whistle·Reversing beeps).
         * 3. 둘 다 없으면 null.
         *
         * 종류는 [typeOf] 로 묻는다. 사용자가 분류 탭에서 바꾼 종류를 따라야 AI 판정과 맞는다(#325). 위협음에서 뺀 소리의
         * 이름으로 알리지 않고, 위협음으로 새로 지정한 소리의 이름으로 알린다.
         *
         * @param display AI 가 확정한 이름([com.example.soundvisualizer.ai.AiClassificationResult.display])
         * @param top5 같은 결과에 든 가장 최근 프레임의 상위 후보(확률 순)
         * @param typeOf 소리 이름의 지금 종류
         * @param indexOf 소리 이름의 클래스 번호. 확정 이름의 번호를 모르면 다른 소리를 적지 않고 이름 없이 알린다.
         */
        fun alertSoundIndex(
            display: String,
            top5: List<YamnetCoarseClassifier.TopClassHit>,
            typeOf: (String) -> String,
            indexOf: (String) -> Int?
        ): Int? {
            if (display.isNotBlank() && typeOf(display) == AiClassification.DANGER) return indexOf(display)
            return top5.firstOrNull {
                it.probability >= CANDIDATE_MIN_PROBABILITY && typeOf(it.name) == AiClassification.DANGER
            }?.index
        }
    }
}

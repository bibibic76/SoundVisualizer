package com.example.soundvisualizer

/**
 * "폰은 소리를 내는데 우리는 받지 못한다" 를 알아본다. 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 안드로이드는 앱이 자기 소리를 다른 앱에 넘기지 않도록 막을 수 있다(`allowAudioPlaybackCapture="false"`,
 * DRM 이 걸린 영상 등). 그런 앱의 소리는 우리에게 **무음**으로 들어온다. 오버레이는 소리가 없으면 아무것도
 * 그리지 않으므로, 청각장애 사용자는 "조용한 장면"과 "받을 수 없는 소리"를 스스로 구분할 방법이 없다.
 * 알리지 않으면 앱이 고장 났다고 여긴다.
 *
 * 판단 재료는 두 가지다.
 * - 폰이 지금 미디어를 재생 중인지 (`AudioManager.isMusicActive`)
 * - 우리가 실제로 받은 최대 진폭 (`AudioEngine.currentLevel`)
 *
 * 둘이 어긋난 상태가 [holdMs] 동안 끊기지 않고 이어지면 보호된 소리로 본다.
 *
 * **틀린 안내는 침묵보다 나쁘다.** "이 앱이 막고 있다" 고 잘못 말하면 사용자는 멀쩡한 앱을 탓하고 설정을
 * 헤매게 된다. 그래서 조금이라도 애매하면 띄우지 않는 쪽으로 판단한다. 재생 중이 아니거나, 받은 소리가
 * 조금이라도 있거나, 판단할 수 없는 상황([onTick] 의 `canJudge`)이면 그 즉시 처음부터 다시 센다.
 *
 * 메인 스레드에서만 부른다.
 *
 * @param silenceLevel 이 값 이하만 받았으면 "아무것도 못 받았다" 로 본다
 * @param holdMs 어긋난 상태가 이만큼 이어져야 안내를 띄운다
 * @param clearMs 재생이 끝난 뒤 이만큼 지나면 안내를 내린다
 */
class BlockedCaptureNotice(
    private val silenceLevel: Float = SILENCE_LEVEL,
    private val holdMs: Long = HOLD_MS,
    private val clearMs: Long = CLEAR_MS
) {

    /** 지금 "받을 수 없다" 안내를 띄워야 하는지. */
    var isBlocked: Boolean = false
        private set

    /** 어긋난 상태가 끊기지 않고 이어진 시작 시각. 아직 아니면 [NONE]. */
    private var mismatchSinceMs: Long = NONE

    /** 안내를 띄운 뒤 재생이 멈춘 시각. 멈추지 않았으면 [NONE]. */
    private var idleSinceMs: Long = NONE

    /** 받은 소리가 있는지. 부르는 쪽이 이걸로 먼저 걸러 시스템에 묻는 비용을 아낀다. */
    fun hasSound(level: Float): Boolean = level > silenceLevel

    /**
     * 한 틱.
     *
     * @param nowMs 단조 증가하는 시각 (elapsedRealtime)
     * @param canJudge 지금 판단해도 되는 상태인지. 볼륨 0, 통화 중, 헤드셋 연결, 화면 꺼짐 일시정지처럼
     *   무음의 이유가 따로 있을 수 있으면 false 를 넘긴다
     * @param mediaPlaying 폰이 지금 미디어를 재생 중인지 (`AudioManager.isMusicActive`)
     * @param level 우리가 받은 가장 최근 버퍼의 최대 진폭 (0..1)
     * @return [isBlocked] 가 이번 틱에 바뀌었으면 true. 바뀔 때만 알림과 화면을 건드리면 된다
     */
    fun onTick(nowMs: Long, canJudge: Boolean, mediaPlaying: Boolean, level: Float): Boolean {
        // 소리가 조금이라도 들어왔으면 받을 수 있다는 증거다. 기다리지 않고 바로 내린다.
        if (!canJudge || hasSound(level)) return reset()

        if (!mediaPlaying) {
            // 폰이 아무 소리도 내지 않는 중이다. 못 받는 게 아니라 낼 소리가 없는 것이므로 세던 것을 버린다.
            mismatchSinceMs = NONE
            if (!isBlocked) return false
            // 이미 띄운 안내는 곧바로 내리지 않는다. 화면 전환·다음 화 넘어가기처럼 재생이 잠깐 끊기는 동안
            // 껐다 켰다 하면 읽기도 전에 사라진다.
            if (idleSinceMs == NONE) idleSinceMs = nowMs
            return if (nowMs - idleSinceMs >= clearMs) reset() else false
        }

        idleSinceMs = NONE
        if (isBlocked) return false
        if (mismatchSinceMs == NONE) {
            mismatchSinceMs = nowMs
            return false
        }
        if (nowMs - mismatchSinceMs < holdMs) return false
        isBlocked = true
        return true
    }

    /**
     * 안내를 내리고 세던 것을 모두 버린다. 캡처가 멈출 때(종료, 화면 꺼짐 일시정지)도 부른다.
     *
     * @return 안내가 떠 있었으면 true
     */
    fun reset(): Boolean {
        mismatchSinceMs = NONE
        idleSinceMs = NONE
        val wasBlocked = isBlocked
        isBlocked = false
        return wasBlocked
    }

    companion object {
        /**
         * 이 값(약 -54dBFS) 이하만 받았으면 아무것도 못 받은 것으로 본다.
         *
         * 막힌 앱의 소리는 정확히 0 으로 들어오지만 0 과만 비교하지는 않는다. 같이 돌고 있는 다른 앱의
         * 아주 작은 소리 한 샘플이 섞여도 안내가 영영 뜨지 않기 때문이다. 반대로 너무 높이 잡으면 조용한
         * 장면을 "못 받았다" 로 오해한다. 그래서 진동·그리기 기준(`HapticPolicy.LEVEL_THRESHOLD` 0.01)보다
         * 다섯 배 낮게 두어, **눈에 보이지도 않을 만큼 작은 소리라도 들어왔으면 받을 수 있는 것**으로 친다.
         */
        const val SILENCE_LEVEL = 0.002f

        /**
         * 어긋난 상태가 이만큼 이어져야 안내를 띄운다.
         *
         * 재생 중에 잠깐 조용한 구간은 흔하다. 로딩 화면, 장면 전환, 광고 사이, 메뉴에서 소리만 멈춘 경우가
         * 모두 몇 초 안에 끝난다. 6초는 그런 구간을 넉넉히 지나 보내면서, 사용자가 "고장 났나" 하고
         * 앱을 끄기 전에는 뜨는 길이다. 더 길게 잡으면 안내가 필요한 순간을 놓친다.
         */
        const val HOLD_MS = 6000L

        /**
         * 안내를 띄운 뒤 재생이 이만큼 멈춰 있으면 내린다.
         *
         * 재생이 끝나면 안내를 뒷받침할 근거가 사라진다. 다만 다음 곡·다음 화로 넘어가는 1초 남짓한 틈마다
         * 안내가 깜빡이면 읽을 수 없으므로 그보다는 길게 둔다.
         */
        const val CLEAR_MS = 2000L

        /** 아직 세기 시작하지 않음. 시각과 섞이지 않게 [Long.MIN_VALUE] 를 쓴다. */
        private const val NONE = Long.MIN_VALUE
    }
}

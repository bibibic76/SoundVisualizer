package com.example.soundvisualizer

/**
 * "폰은 소리를 내는데 우리에게는 아무것도 들어오지 않는다" 를 알아본다. 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 안드로이드는 앱이 자기 소리를 다른 앱에 넘기지 않도록 막을 수 있다(`allowAudioPlaybackCapture="false"`,
 * DRM 이 걸린 영상 등). 그런 앱의 소리는 우리에게 **아무것도** 들어오지 않는다. 오버레이는 소리가 없으면
 * 아무것도 그리지 않으므로, 청각장애 사용자는 "조용한 장면"과 "받을 수 없는 소리"를 스스로 구분할 방법이
 * 없다. 알리지 않으면 앱이 고장 났다고 여긴다.
 *
 * 판단 재료는 세 가지다.
 * - 우리가 받기로 한 소리(미디어·게임)를 지금 누가 내고 있는지 (`AudioManager.getActivePlaybackConfigurations`)
 * - 그 사이 우리가 받은 최대 진폭 (`AudioEngine.takePeakSinceLastCheck` 의 피크)
 * - 그 사이 버퍼가 실제로 도착했는지 (같은 호출의 버퍼 수)
 *
 * 어긋난 상태가 [holdMs] 동안 끊기지 않고 이어지면 "받지 못하고 있다" 로 본다.
 *
 * **여기서 가려낼 수 없는 것이 하나 있다.** 앱이 스스로 음소거한 경우(피드의 자동 재생 영상, 게임 안의
 * 음악 슬라이더 0)도 우리에게는 똑같이 아무것도 들어오지 않는다. 시스템은 둘을 구분해 주지 않는다.
 * 그래서 문구는 원인을 단정하지 않고 두 가지를 모두 말한다(`home_capture_blocked`).
 *
 * **틀린 안내는 침묵보다 나쁘다.** "저 앱이 막고 있다" 고 잘못 말하면 사용자는 멀쩡한 앱을 탓하고 설정을
 * 헤매게 된다. 그래서 조금이라도 애매하면 띄우지 않는 쪽으로 판단한다. 재생 중이 아니거나, 받은 소리가
 * 조금이라도 있거나, 버퍼가 오지 않았거나, 판단할 수 없는 상황([onTick] 의 `canJudge`)이면 그 즉시
 * 처음부터 다시 센다.
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

    /** 지금 "받지 못하고 있다" 안내를 띄워야 하는지. */
    var isBlocked: Boolean = false
        private set

    /** 어긋난 상태가 끊기지 않고 이어진 시작 시각. 아직 아니면 [NONE]. */
    private var mismatchSinceMs: Long = NONE

    /** 안내를 띄운 뒤 재생이 멈춘 시각. 멈추지 않았으면 [NONE]. */
    private var idleSinceMs: Long = NONE

    /** 받은 소리가 있는지. 부르는 쪽이 이걸로 먼저 걸러 시스템에 묻는 비용을 아낀다. */
    fun hasSound(peak: Float): Boolean = peak > silenceLevel

    /**
     * 한 틱.
     *
     * @param nowMs 단조 증가하는 시각 (elapsedRealtime)
     * @param canJudge 지금 판단해도 되는 상태인지. 미디어 볼륨 0, 통화 중, 화면 꺼짐 일시정지처럼 무음의
     *   이유가 따로 있을 수 있으면 false 를 넘긴다
     * @param mediaPlaying 우리가 받기로 한 소리(미디어·게임)를 지금 누가 내고 있는지
     * @param peak 지난 틱 이후 우리가 받은 구간 전체의 최대 진폭 (0..1)
     * @param buffers 지난 틱 이후 도착한 오디오 버퍼 수
     * @return [isBlocked] 가 이번 틱에 바뀌었으면 true. 바뀔 때만 알림과 화면을 건드리면 된다
     */
    fun onTick(
        nowMs: Long,
        canJudge: Boolean,
        mediaPlaying: Boolean,
        peak: Float,
        buffers: Int
    ): Boolean {
        // 버퍼가 한 개도 오지 않았다. 조용한 원인이 앱이 아니라 우리 쪽(캡처가 멈춤)일 수 있다.
        // 그 상태로 앱을 탓하면 사용자는 엉뚱한 곳을 고치러 간다. 이때 필요한 것은 다시 켜기다.
        if (buffers <= 0) return reset()

        // 소리가 조금이라도 들어왔으면 받을 수 있다는 증거다. 기다리지 않고 바로 내린다.
        if (!canJudge || hasSound(peak)) return reset()

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
         * 이 값 이하만 받았으면 아무것도 못 받은 것으로 본다. 사실상 **디지털 무음**(정확히 0)만 걸러낸다.
         *
         * "작으면 못 받은 것" 으로 잡으면 안 된다. 우리가 받는 소리는 **미디어 볼륨이 곱해진 뒤**의 소리라서
         * (볼륨 0 에서 정확히 0 이 들어오는 이유가 이것이다), 15단계 중 1~2단계에서는 보통의 영상도 40dB
         * 넘게 줄어 0.002 언저리로 들어온다. 화면을 보려고 볼륨을 낮춰 두는 것은 이 앱 사용자에게 아주 흔한
         * 사용법이므로, 그 정도를 무음으로 치면 멀쩡한 앱을 탓하게 된다. 반대로 막힌 앱의 소리는 볼륨과
         * 상관없이 우리 쪽 믹스에 섞이지 않아 **정확히 0** 으로 들어온다. 그래서 기준을 약 -120dBFS 까지
         * 내려, 아주 작게라도 값이 들어왔으면 받을 수 있는 것으로 친다.
         *
         * 0 하고만 비교하지는 않는다. 떠도는 아주 작은 값 하나 때문에 안내가 영영 뜨지 않을 수 있고,
         * 부동소수점 반올림 찌꺼기를 소리로 세지 않기 위해서다.
         */
        const val SILENCE_LEVEL = 0.000001f

        /**
         * 어긋난 상태가 이만큼 이어져야 안내를 띄운다.
         *
         * 재생 중에 잠깐 조용한 구간은 흔하다. 로딩 화면, 장면 전환, 광고 사이, 메뉴에서 소리만 멈춘 경우가
         * 모두 몇 초 안에 끝난다. 더 중요한 이유는 따로 있다. 피드의 자동 재생 영상은 소리를 끈 채로도
         * 재생 중으로 잡히는데, 그 위를 몇 초 훑고 지나가는 것은 너무 흔해서 6초로는 그것만으로도 안내가
         * 뜬다. 15초는 스크롤로는 좀처럼 닿지 않으면서, 막힌 앱을 틀어 둔 사용자가 "고장 났나" 하고 앱을
         * 끄기 전에는 뜨는 길이다. 더 길게 잡으면 안내가 필요한 순간을 놓친다.
         */
        const val HOLD_MS = 15000L

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

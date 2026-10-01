package com.example.soundvisualizer

/**
 * 마이크가 막혀 아무것도 듣지 못하는지 알아본다(#226). 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 통화 중이거나, 다른 앱이 마이크를 가져갔거나, 빠른 설정에서 마이크 사용을 막아 두면 `AudioRecord` 는 오류 없이
 * 0 만 돌려준다. 오버레이는 소리가 없으면 아무것도 그리지 않으므로 "조용한 방" 과 구분할 수 없다. 청각장애 사용자는
 * 방 소리를 들어 확인할 수 없으니 알려야 한다.
 *
 * 마이크는 조용한 곳에서도 잡음이 조금은 들어온다(S25+ 사무실에서 피크 -40dBFS 안팎). 그래서 한 틱 동안의 피크가
 * 사실상 0 이 [holdMs] 동안 이어지면 막힌 것으로 본다. 샘플 몇 개가 정확히 0 인 것은 흔하므로(16비트 값을 옮긴
 * float) 샘플이 아니라 틱 전체의 피크로 본다. 시스템이 막았다고 알려 주면(`isClientSilenced`) 기다리지 않는다.
 *
 * [BlockedCaptureNotice] 와 달리 재생 중인 앱은 보지 않는다. 마이크가 조용한 이유는 재생 중인 앱과 상관없다.
 *
 * 메인 스레드에서만 부른다.
 *
 * @param holdMs 0 이 이만큼 이어져야 안내를 띄운다
 */
class MicSilenceNotice(private val holdMs: Long = HOLD_MS) {

    /** 지금 "마이크로 아무것도 들어오지 않는다" 안내를 띄워야 하는지. */
    var isSilenced: Boolean = false
        private set

    /** 0 이 끊기지 않고 이어진 시작 시각. 아직 아니면 [NONE]. */
    private var silentSinceMs: Long = NONE

    /**
     * 한 틱.
     *
     * @param nowMs 단조 증가하는 시각 (elapsedRealtime)
     * @param silencedBySystem 시스템이 이 녹음을 막았다고 알렸는지 (`AudioRecordingConfiguration.isClientSilenced`)
     * @param peak 지난 틱 이후 받은 구간 전체의 최대 진폭 (0..1)
     * @param buffers 지난 틱 이후 도착한 오디오 버퍼 수
     * @return [isSilenced] 가 이번 틱에 바뀌었으면 true. 바뀔 때만 알림과 화면을 건드리면 된다
     */
    fun onTick(nowMs: Long, silencedBySystem: Boolean, peak: Float, buffers: Int): Boolean {
        // 버퍼가 한 개도 오지 않았다. 마이크가 아니라 캡처가 멈춘 것이라 이 안내로 알릴 일이 아니다.
        if (buffers <= 0) return reset()
        if (silencedBySystem) return show()
        // 잡음이라도 들어왔으면 마이크는 열려 있다. 기다리지 않고 바로 내린다.
        if (peak > SILENCE_LEVEL) return reset()
        if (silentSinceMs == NONE) silentSinceMs = nowMs
        return if (nowMs - silentSinceMs >= holdMs) show() else false
    }

    /**
     * 안내를 내리고 세던 것을 버린다. 캡처가 멈출 때(종료, 화면 꺼짐 일시정지)도 부른다.
     *
     * @return 안내가 떠 있었으면 true
     */
    fun reset(): Boolean {
        silentSinceMs = NONE
        val wasSilenced = isSilenced
        isSilenced = false
        return wasSilenced
    }

    private fun show(): Boolean {
        if (isSilenced) return false
        isSilenced = true
        return true
    }

    companion object {
        /** 이 값 이하만 받았으면 아무것도 못 받은 것으로 본다. 디지털 무음만 거르는 [BlockedCaptureNotice.SILENCE_LEVEL] 과 같다. */
        const val SILENCE_LEVEL = BlockedCaptureNotice.SILENCE_LEVEL

        /**
         * 0 이 이만큼 이어져야 안내를 띄운다.
         *
         * 마이크를 여는 순간 잠깐 0 이 들어온다(S25+ 에서 처음 0.3초 안). 그보다 넉넉히 길고, 안내가 필요한 사람이
         * "고장 났나" 하고 끄기 전에는 뜨는 길이다.
         */
        const val HOLD_MS = 3000L

        /** 아직 세기 시작하지 않음. 시각과 섞이지 않게 [Long.MIN_VALUE] 를 쓴다. */
        private const val NONE = Long.MIN_VALUE
    }
}

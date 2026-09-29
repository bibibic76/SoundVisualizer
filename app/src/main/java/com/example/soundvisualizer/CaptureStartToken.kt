package com.example.soundvisualizer

/**
 * 캡처 서비스가 사용자가 켜서 뜬 것인지 가리고, 그 실행이 어디서 소리를 받을지([CaptureSource]) 전한다.
 * 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 서비스는 onCreate 에서 인텐트를 보기 전에 포그라운드를 시작한다(Android 14+ 는 캡처를 열기 전에 포그라운드가
 * 떠 있어야 한다). 그래서 누가 띄웠는지 모르는 채로 시작하게 되는데, 사용자가 켜지 않은 시작이면 문제가 된다.
 * 서비스로 보내던 옛 버전 알림의 버튼이 서비스를 새로 만들면 Android 14 이상은 화면 녹화 동의가 없어 프로세스가
 * 죽고, 그 아래는 캡처 없는 포그라운드 서비스가 떴다 내려간다. 마이크 모드였다면 사용자가 켜지 않았는데 마이크
 * 표시가 뜬다(#224, #226).
 *
 * [VisualizerController] 가 서비스를 띄우기 바로 앞에서 [issue] 하고, onCreate 가 포그라운드를 시작하기 전에
 * [consume] 한다. 표는 프로세스 메모리에만 있으므로 프로세스가 새로 떠서 서비스를 만든 경우에는 있을 수 없다.
 * 소스도 표에 실어 보낸다. 포그라운드 타입은 onCreate 에서 정해야 하는데, 설정을 거기서 다시 읽으면 켜기를 누른
 * 쪽이 동의 창을 건너뛸지 정한 값과 어긋날 수 있기 때문이다.
 *
 * 메인 스레드에서만 부른다.
 */
class CaptureStartToken {

    /** 발급한 시각. 발급한 표가 없으면 null. */
    private var issuedAtMs: Long? = null

    /** 발급한 표가 실어 보낸 소스. */
    private var source: CaptureSource = CaptureSource.InternalPlayback

    /** 사용자가 켜기를 눌렀다. 서비스를 띄우기 바로 앞에서 부른다. */
    fun issue(nowMs: Long, source: CaptureSource) {
        issuedAtMs = nowMs
        this.source = source
    }

    /**
     * 표를 쓴다. 한 번 쓰면 사라져서, 같은 표로 서비스가 두 번 뜨지 않는다.
     *
     * @param nowMs [issue] 와 같은 시계로 잰 지금 시각
     * @return 사용자가 방금 켠 것이면 그때 고른 소스. 아니면 null
     */
    fun consume(nowMs: Long): CaptureSource? {
        val issued = issuedAtMs ?: return null
        issuedAtMs = null
        return if (nowMs - issued in 0..MAX_AGE_MS) source else null
    }

    companion object {
        /**
         * 발급한 표를 받아 주는 시간.
         *
         * 띄우기 직전에 발급하므로 보통 수십 ms 안에 쓴다. 띄우다 실패해 남은 표가 한참 뒤의 다른 시작을
         * 통과시키지 않게 둔다. 시스템이 startForegroundService 뒤에 포그라운드 시작을 기다려 주는 시간과 같아,
         * 이보다 늦게 onCreate 가 돌면 표와 상관없이 시스템이 먼저 앱을 내린다.
         */
        const val MAX_AGE_MS = 10_000L
    }
}

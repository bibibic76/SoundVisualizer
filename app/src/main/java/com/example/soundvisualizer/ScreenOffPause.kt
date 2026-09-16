package com.example.soundvisualizer

/**
 * 화면이 꺼진 동안 캡처·AI·진동을 쉴지 정하는 상태. 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 화면이 꺼지면 게임과 대부분의 영상은 멈추고, 오버레이는 보이지도 않는다. 그래도 캡처가 돌면
 * 오디오 서버가 앱 몫의 wake lock 을 쥐어 CPU 가 잠들지 못하고, AI 는 0.25초마다 추론하고,
 * 주머니 속에서 음악의 사이렌 소리에 위협음 진동이 울릴 수도 있다.
 *
 * 쉴지는 화면이 꺼지는 순간의 설정으로 정한다. 설정은 화면이 켜져 있을 때만 바꿀 수 있지만,
 * 쉬는 동안 설정이 꺼지더라도 화면이 켜지면 쉬던 것은 반드시 다시 켠다.
 * 그러지 않으면 캡처가 멈춘 채 "실행 중"으로 남는다.
 *
 * 쉬기는 사용자가 끈 것도, 실패도 아니므로 멈춤 알림([StopReason])과 상관없다.
 *
 * 메인 스레드에서만 부른다.
 */
class ScreenOffPause {

    /** 지금 화면이 꺼져 쉬는 중인지. */
    var isPaused: Boolean = false
        private set

    /**
     * 화면이 꺼졌다.
     *
     * @param pauseEnabled 설정의 "화면이 꺼지면 일시정지"
     * @return 지금 쉬기 시작해야 하면 true. 이미 쉬는 중이거나 설정이 꺼져 있으면 false
     */
    fun onScreenOff(pauseEnabled: Boolean): Boolean {
        if (isPaused || !pauseEnabled) return false
        isPaused = true
        return true
    }

    /**
     * 화면이 켜졌다.
     *
     * @return 쉬던 것을 지금 다시 켜야 하면 true
     */
    fun onScreenOn(): Boolean {
        if (!isPaused) return false
        isPaused = false
        return true
    }
}

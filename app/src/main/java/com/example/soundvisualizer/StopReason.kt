package com.example.soundvisualizer

/**
 * 시각화(캡처 서비스)가 멈춘 이유.
 *
 * 청각장애 사용자는 "조용한 장면"과 "꺼진 상태"를 구분할 수 없다. 오버레이는 소리가 없으면 아무것도 그리지 않으므로
 * 사용자가 직접 끈 게 아니면 반드시 알려야 한다. 이 파일은 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 */
enum class StopReason {
    /** 앱의 실행 종료 버튼, 켜진 타일 누르기, 실행 중 알림의 중지 버튼. 알리지 않는다. */
    UserRequested,

    /**
     * 시스템이 화면 녹화 권한(MediaProjection)을 거둬 갔다. 다른 앱이 화면 녹화·공유·전송을 시작하면
     * 안드로이드는 한 번에 한 앱만 허용하므로 우리 것을 끝낸다. 사용자가 시스템 UI 에서 끈 경우와
     * 콜백만으로는 구분할 수 없어서, 알림 문구는 누구 탓도 하지 않는다.
     */
    ProjectionStopped,

    /** 오디오 서버 재시작 등으로 캡처를 계속 읽을 수 없다. */
    CaptureError,

    /** 동의까지 받았는데 캡처나 오버레이를 시작하지 못했다. 사용자는 아무 일도 안 일어난 것처럼 보게 된다. */
    StartFailed;

    companion object {
        /**
         * onDestroy 에서 최종 이유를 고른다.
         *
         * @param recorded 서비스가 스스로 멈추며 남긴 이유. 먼저 난 실패가 뒤따르는 부수 실패보다 정확하다.
         * @param external 서비스 밖(오버레이)이 실패로 멈추며 남긴 이유
         * @return 둘 다 없으면 stopService 로 밖에서 내린 것이고, 그런 호출은 사용자의 끄기뿐이다.
         */
        fun resolve(recorded: StopReason?, external: StopReason?): StopReason =
            recorded ?: external ?: UserRequested
    }
}

/**
 * 멈췄을 때 사용자에게 알리는 방법.
 *
 * - 진동: 게임 중이거나 폰이 주머니에 있어도 느낄 수 있다. 소리 종류별 진동 설정과 상관없이 울린다.
 * - 알림: 무엇이 꺼졌는지와 "다시 켜기" 버튼을 남긴다.
 * - 토스트: 알림을 올릴 수 없을 때(권한 거부, 앱·채널 알림 끄기)만 대신 띄운다. 알림 권한은 거부해도 앱이 돌기 때문이다.
 *
 * @property vibrate 고유한 진동을 울릴지
 * @property notify 알림을 올려 볼지. 실제로 올라갔는지는 [toastAfter] 로 넘긴다.
 */
data class StopAlertPlan(val vibrate: Boolean, val notify: Boolean) {

    /** 알림을 올려 봤는데 올리지 못했으면 토스트로 대신한다. */
    fun toastAfter(posted: Boolean): Boolean = notify && !posted

    companion object {
        private val SILENT = StopAlertPlan(vibrate = false, notify = false)

        /**
         * @param reason 멈춘 이유
         * @param hasVibrator 진동 모터가 있는지. 없으면 진동만 뺀다.
         */
        fun decide(reason: StopReason, hasVibrator: Boolean): StopAlertPlan =
            if (reason == StopReason.UserRequested) SILENT
            else StopAlertPlan(vibrate = hasVibrator, notify = true)
    }
}

package com.example.soundvisualizer

/**
 * 실행 중 알림의 버튼이 보낸 명령. 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 버튼은 서비스가 아니라 [NotificationActionReceiver] 로 보낸다. 받는 쪽이 인텐트를 읽어 여기로 넘긴다.
 */
sealed interface NotificationCommand {

    /** [중지] 버튼. 사용자가 끈 것으로 본다. */
    data object Stop : NotificationCommand

    /** 모드 칩. 캡처와 AI 는 건드리지 않고 모드만 바꾼다. */
    data class SetMode(val mode: VisualMode) : NotificationCommand

    /** 할 일이 없다. 꺼진 칩, 모르는 번호, 모르는 동작. */
    data object Ignore : NotificationCommand

    companion object {
        /** 실행 중 알림의 중지 버튼. */
        const val ACTION_STOP = "com.example.soundvisualizer.action.STOP"

        /** 실행 중 알림의 모드 칩. */
        const val ACTION_SET_MODE = "com.example.soundvisualizer.action.SET_MODE"

        /** [ACTION_SET_MODE] 가 고른 모드의 [Enum.ordinal]. */
        const val EXTRA_MODE_ORDINAL = "com.example.soundvisualizer.extra.MODE_ORDINAL"

        /**
         * 버튼이 보낸 것을 명령으로 바꾼다.
         *
         * [checked] 는 모드 칩이 켜졌는지다. Android 12 이상에서는 칩이 라디오 버튼이라, 한 칩을 누르면
         * 켜진 칩과 함께 **꺼진 칩도** 알려 온다. 꺼진 쪽까지 받아 주면 방금 끈 모드가 뒤늦게 덮어써서,
         * 무엇을 눌러도 원래 모드로 돌아간다. 그 아래 버전은 누른 칩 하나만 알려 오므로 값이 없고(null),
         * 그때는 켜진 것으로 본다.
         *
         * 모르는 번호는 무시한다. 알림은 앱이 바뀐 뒤에도 알림창에 남아 있을 수 있다([VisualMode.fromOrdinal]).
         *
         * @param modeOrdinal [EXTRA_MODE_ORDINAL] 값. 없으면 -1
         */
        fun parse(action: String?, modeOrdinal: Int, checked: Boolean?): NotificationCommand =
            when (action) {
                ACTION_STOP -> Stop
                ACTION_SET_MODE -> {
                    val mode = VisualMode.fromOrdinal(modeOrdinal)
                    if (mode == null || checked == false) Ignore else SetMode(mode)
                }
                else -> Ignore
            }
    }
}

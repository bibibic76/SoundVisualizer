package com.example.soundvisualizer

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo

// FOREGROUND_SERVICE_TYPE_MICROPHONE 는 Android 11 상수라 minSdk 29 에서 참조하면 Lint(InlinedApi)가 알린다.
// 컴파일할 때 숫자로 박히는 상수이고, Android 10 은 매니페스트에 적힌 타입인지만 본다.
/**
 * 소리를 어디서 받는지. 홈의 "외부 사운드 모드" 스위치가 고른다(#226). 안드로이드 상수만 쓰므로 JVM 에서 테스트한다.
 *
 * 모드 하나가 여러 곳의 결정을 함께 정한다. 흩어 두면 한쪽만 바뀌어, 예를 들어 화면 녹화 동의를 건너뛰면서
 * `mediaProjection` 포그라운드를 시작하는(Android 14 이상에서 죽는) 조합이 생길 수 있다. 그래서 한곳에 모은다.
 *
 * 켜기를 누른 쪽이 정해 시작 표([CaptureStartToken])에 실어 보내고, 서비스는 onCreate 에서 그 표로 받아 그 실행이
 * 끝날 때까지 바꾸지 않는다. 포그라운드 타입은 인텐트를 보기 전에 넘겨야 하기 때문이다.
 */
@SuppressLint("InlinedApi")
enum class CaptureSource(
    /** 켜기 전에 화면 녹화 동의(MediaProjection)를 받아야 하는지. */
    val needsProjectionConsent: Boolean,
    /**
     * 서비스가 onCreate 에서 넘기는 포그라운드 서비스 타입. **하나만** 넘긴다.
     *
     * Android 14 이상은 타입마다 조건을 본다. 쓰지 않는 타입까지 합쳐 넘기면 그 조건까지 떠안는다. 예를 들어
     * 화면 녹화 타입은 동의 없이는 시작할 수 없고, 마이크 타입은 앱이 사용자 앞에 있을 때만 시작할 수 있다.
     */
    val foregroundServiceType: Int,
    /**
     * "재생 중인데 받지 못한다" 안내([BlockedCaptureNotice])를 돌릴지.
     *
     * 그 안내는 재생 중인 앱이 소리 공유를 막았다고 본다. 마이크가 조용한 이유는 재생 중인 앱과 상관이 없어서,
     * 마이크 모드에서 돌리면 이어폰으로 음악을 듣는 동안 엉뚱한 앱을 탓한다. 마이크는 [MicSilenceNotice] 가 본다.
     */
    val watchesBlockedCapture: Boolean,
    /**
     * 설정의 "화면이 꺼지면 일시정지"([ScreenOffPause])를 따르는지.
     *
     * 그 설정의 이유는 폰 안의 소리에만 맞는다. 화면이 꺼지면 게임과 대부분의 영상이 멈추고 오버레이도 보이지 않는다.
     * 외부 사운드 모드는 반대로, 폰을 내려놓아 화면이 꺼진 동안 초인종·화재경보·부르는 소리를 알리려는 모드다.
     * 그래서 마이크는 따르지 않고 화면이 꺼져도 듣는다(#260). 그 대신 마이크 권한 안내와 도움말에 그렇다고 적는다.
     */
    val followsScreenOffPause: Boolean,
    /**
     * 앱이 울린 진동을 이 소스가 소리로 다시 듣는지(#290).
     *
     * 마이크는 폰의 진동을 그대로 듣는다. 진동은 "그 종류의 소리가 이어지는 동안" 울리므로, 듣고만 있으면 자기 진동 소리가
     * 진동을 이어 가 멈추지 않을 수 있다. 그래서 진동 판단은 앱이 울린 진동 사이의 빈틈에서만 소리를 보고, 빈틈이 짧거나
     * 없는 빠른 방식은 종류마다의 상한으로 낮춰 울린다([com.example.soundvisualizer.feedback.HapticSettings.externalCap],
     * #354). 폰 안의 소리는 진동을 듣지 못하므로 지금까지와 같다.
     */
    val hearsOwnVibration: Boolean
) {
    /** 폰에서 재생되는 소리(AudioPlaybackCapture). 외부 사운드 모드를 끈, 지금까지의 동작이다. */
    InternalPlayback(
        needsProjectionConsent = true,
        foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        watchesBlockedCapture = true,
        followsScreenOffPause = true,
        hearsOwnVibration = false
    ),

    /** 마이크로 듣는 주변 소리. 외부 사운드 모드를 켜면 쓴다. */
    Microphone(
        needsProjectionConsent = false,
        foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        watchesBlockedCapture = false,
        followsScreenOffPause = false,
        hearsOwnVibration = true
    );

    companion object {
        /** 설정의 외부 사운드 모드에 맞는 소스. */
        fun of(externalSoundMode: Boolean): CaptureSource =
            if (externalSoundMode) Microphone else InternalPlayback
    }
}

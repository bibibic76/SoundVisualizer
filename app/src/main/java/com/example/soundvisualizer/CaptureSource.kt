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
    val watchesBlockedCapture: Boolean
) {
    /** 폰에서 재생되는 소리(AudioPlaybackCapture). 외부 사운드 모드를 끈, 지금까지의 동작이다. */
    InternalPlayback(
        needsProjectionConsent = true,
        foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        watchesBlockedCapture = true
    ),

    /** 마이크로 듣는 주변 소리. 외부 사운드 모드를 켜면 쓴다. */
    Microphone(
        needsProjectionConsent = false,
        foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        watchesBlockedCapture = false
    );

    companion object {
        /** 설정의 외부 사운드 모드에 맞는 소스. */
        fun of(externalSoundMode: Boolean): CaptureSource =
            if (externalSoundMode) Microphone else InternalPlayback
    }
}

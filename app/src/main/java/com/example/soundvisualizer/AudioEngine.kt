package com.example.soundvisualizer

import java.nio.ByteBuffer

/**
 * 오디오 레벨 측정을 담당하는 네이티브 브릿지.
 *
 * 캡처 스레드가 [pushAudioBuffer] 로 PCM 을 넘기고, 렌더 스레드가 [readPeaks] 로
 * 그 사이의 채널별 최대 진폭을 읽는다. 둘 다 힙 할당 없이 동작한다.
 */
object AudioEngine {
    init {
        System.loadLibrary("soundvisualizer")
    }

    /**
     * 인터리브 스테레오 float PCM 을 direct [ByteBuffer] 로 넘긴다 (복사 없음).
     * [floatCount] 는 인덱스 0 부터의 float 개수다.
     */
    external fun pushAudioBuffer(buffer: ByteBuffer, floatCount: Int)

    /**
     * [out] (크기 3 이상) 에 `[좌 피크, 우 피크, 마지막 호출 이후 도착한 버퍼 수]` 를 채우고
     * 네이티브 누적값을 0 으로 되돌린다. 피크는 0..1 범위의 max|sample| 이다.
     * 호출당 할당이 없다.
     */
    external fun readPeaks(out: FloatArray)

    /**
     * 가장 최근 버퍼의 좌우 중 큰 피크 (0..1).
     * [readPeaks] 와 달리 읽어도 초기화하지 않아서, 여러 곳에서 함께 읽을 수 있다. 개발자 모드 HUD 가 쓴다.
     * 버퍼 하나(11.6ms)만 담고 있으므로 "그사이 소리가 났는가" 를 물으려면 [takeHapticPeak] 나
     * [takePeakSinceLastCheck] 처럼 구간을 모으는 값을 써야 한다.
     */
    external fun currentLevel(): Float

    /**
     * 마지막 호출 이후의 최대 피크 (0..1). 읽으면 0 으로 되돌린다. 진동 알림([com.example.soundvisualizer.feedback.HapticNotifier]) 전용이다.
     *
     * 진동은 0.1초마다 판단하는데 [currentLevel] 로 보면 그 구간의 12% 남짓만 들여다보게 된다. 총소리 한 발처럼
     * 30~60ms 만 큰 소리는 확인과 확인 사이에 들어왔다 사라져, 화면에는 그려지는데 진동만 빠진다(#174).
     * 다른 누적값과 따로 두는 이유는 [takePeakSinceLastCheck] 의 설명과 같다. 먼저 읽는 쪽이 0 으로 되돌리기 때문이다.
     * 호출당 할당이 없다.
     */
    external fun takeHapticPeak(): Float

    /**
     * [out] (크기 2 이상) 에 `[마지막 호출 이후의 최대 피크, 그사이 도착한 버퍼 수]` 를 채우고 0 으로 되돌린다.
     *
     * [currentLevel] 은 가장 최근 버퍼(11.6ms) 하나뿐이라 드문드문 나는 소리를 놓친다. 이쪽은 구간 전체를
     * 훑으므로 "그동안 아무 소리도 받지 못했다" 를 말할 수 있다. 버퍼 수는 우리 캡처가 멈춘 것과 앱이
     * 조용한 것을 가르는 데 쓴다. [readPeaks] 와 다른 누적값이라 오버레이와 서로 값을 빼앗지 않는다.
     * 호출당 할당이 없다. ([BlockedCaptureNotice])
     */
    external fun takePeakSinceLastCheck(out: FloatArray)

    /** 누적값을 0 으로 돌린다. 캡처 시작·종료 시점에 각각 호출한다. */
    external fun reset()
}

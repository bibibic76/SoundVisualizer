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

    /** 누적값을 0 으로 돌린다. 캡처 시작·종료 시점에 각각 호출한다. */
    external fun reset()
}

package com.example.soundvisualizer

import kotlin.math.abs

/**
 * 외부 사운드 모드의 마이크 감도(#226). 안드로이드에 의존하지 않아 JVM 에서 테스트한다.
 *
 * 마이크로 받는 주변 소리는 폰에서 재생되는 소리보다 훨씬 작다. 몇 미터 떨어진 말소리는 오버레이가 깨어나는
 * 기준(0.01)에 겨우 닿고, 사무실 잡음도 그 근처다. 그래서 기본은 지금 그대로(100%) 두고, 사용자가 자리에 맞게
 * 올리거나(작은 소리도 그리기) 내리게(시끄러운 곳의 잡음을 덜 그리기) 한다.
 *
 * 감도는 네이티브가 잰 **소리 크기에만** 곱한다([AudioEngine.pushAudioBuffer] 의 levelGain). 오버레이·진동·
 * "마이크가 막혔다" 안내가 같은 크기를 보고, AI 가 받는 소리(PCM)는 그대로다.
 */
object MicSensitivity {

    /** 슬라이더의 칸(%). 한 칸마다 약 3dB(√2 배). */
    val STEPS = intArrayOf(50, 70, 100, 140, 200, 280, 400, 560, 800)

    /** 기본값. 감도를 조절하기 전과 똑같다. */
    const val DEFAULT = 100

    /** [percent] 를 크기에 곱할 값으로. 100% 가 1. */
    fun gain(percent: Int): Float = percent / 100f

    /** [percent] 에 가장 가까운 칸의 번호. 저장된 값이 칸에 없어도(손으로 고친 값 등) 슬라이더에 있는 값이 된다. */
    fun indexOf(percent: Int): Int = STEPS.indices.minByOrNull { abs(STEPS[it] - percent) } ?: STEPS.indexOf(DEFAULT)

    /** [percent] 를 가장 가까운 칸의 값으로 맞춘다. */
    fun clamp(percent: Int): Int = STEPS[indexOf(percent)]
}

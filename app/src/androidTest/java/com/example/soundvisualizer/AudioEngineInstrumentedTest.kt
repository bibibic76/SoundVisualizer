package com.example.soundvisualizer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 네이티브 피크 측정의 계약. 소비자마다 누적값이 따로라, 먼저 읽는 쪽이 다른 쪽의 구간을 가져가면 안 된다.
 *
 * 진동 알림은 0.1초마다 한 번만 보므로 구간 전체의 최대값을 받아야 한다. 가장 최근 버퍼만 보면
 * 총소리 한 발처럼 짧은 소리를 확인과 확인 사이에 놓친다(#174).
 */
@RunWith(AndroidJUnit4::class)
class AudioEngineInstrumentedTest {

    @Before
    fun clear() {
        AudioEngine.reset()
    }

    /** 좌 = 0.5p, 우 = -p 인 버퍼 하나를 캡처 스레드처럼 넘긴다. [gain] 은 마이크 감도(크기에만 곱함). */
    private fun push(peak: Float, frames: Int = 8, gain: Float = 1f) {
        val buffer = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()
        repeat(frames) {
            floats.put(peak * 0.5f)
            floats.put(-peak)   // 부호와 상관없이 크기로 본다
        }
        AudioEngine.pushAudioBuffer(buffer, frames * 2, gain)
    }

    @Test
    fun 진동용_피크는_읽는_사이_구간의_최대값이다() {
        push(0.10f)
        push(0.90f)
        push(0.20f)
        assertEquals(0.90f, AudioEngine.takeHapticPeak(), 0.0001f)
    }

    @Test
    fun 진동용_피크는_읽으면_0_으로_돌아간다() {
        push(0.70f)
        AudioEngine.takeHapticPeak()
        assertEquals(0f, AudioEngine.takeHapticPeak(), 0.0001f)
    }

    @Test
    fun 오버레이가_먼저_읽어도_진동용_피크는_남는다() {
        push(0.60f)
        val overlay = FloatArray(3)
        AudioEngine.readPeaks(overlay)
        assertEquals("오버레이가 읽은 우 피크", 0.60f, overlay[1], 0.0001f)
        assertEquals("진동이 그 구간을 통째로 놓쳤다", 0.60f, AudioEngine.takeHapticPeak(), 0.0001f)
    }

    @Test
    fun 진동이_먼저_읽어도_보호된_소리_안내의_누적값은_남는다() {
        push(0.40f)
        AudioEngine.takeHapticPeak()
        val check = FloatArray(2)
        AudioEngine.takePeakSinceLastCheck(check)
        assertEquals(0.40f, check[0], 0.0001f)
        assertEquals("버퍼 수", 1f, check[1], 0.0001f)
    }

    @Test
    fun 마이크_감도는_모든_소비자의_크기에_곱한다() {
        // 좌 0.1, 우 0.2 를 두 배로. 오버레이·진동·막힘 안내가 같은 크기를 본다(#226).
        push(0.20f, gain = 2f)
        val overlay = FloatArray(3)
        AudioEngine.readPeaks(overlay)
        assertEquals(0.20f, overlay[0], 0.0001f)
        assertEquals(0.40f, overlay[1], 0.0001f)
        assertEquals(0.40f, AudioEngine.takeHapticPeak(), 0.0001f)
        val check = FloatArray(2)
        AudioEngine.takePeakSinceLastCheck(check)
        assertEquals(0.40f, check[0], 0.0001f)
    }

    @Test
    fun 마이크_감도를_곱한_크기는_1_에서_자른다() {
        push(0.80f, gain = 2f)
        assertEquals(1f, AudioEngine.takeHapticPeak(), 0.0001f)
    }

    @Test
    fun 마이크_감도를_낮추면_크기도_줄어든다() {
        push(0.40f, gain = 0.5f)
        assertEquals(0.20f, AudioEngine.takeHapticPeak(), 0.0001f)
    }

    @Test
    fun reset_하면_진동용_피크도_지워진다() {
        push(0.80f)
        AudioEngine.reset()
        assertEquals(0f, AudioEngine.takeHapticPeak(), 0.0001f)
    }
}

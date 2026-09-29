package com.example.soundvisualizer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.soundvisualizer.feedback.HapticFeatureMirror
import com.example.soundvisualizer.feedback.HapticScenes
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 네이티브 피크 측정의 계약. 소비자마다 누적값이 따로라, 먼저 읽는 쪽이 다른 쪽의 구간을 가져가면 안 된다.
 *
 * 진동 알림은 틱마다 한 번만 보므로 구간 전체를 모은 값을 받아야 한다. 가장 최근 버퍼만 보면
 * 총소리 한 발처럼 짧은 소리를 확인과 확인 사이에 놓친다(#174).
 *
 * 진동용 값은 `[피크, 가장 큰 버퍼의 RMS, 그 버퍼의 영교차율, 버퍼 수]` 다. 흉내 소리가 실제와 같이 울리도록
 * JVM 쪽 계산([HapticFeatureMirror])이 네이티브와 같은 값을 내는지도 여기서 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class AudioEngineInstrumentedTest {

    private val frame = FloatArray(4)

    @Before
    fun clear() {
        AudioEngine.reset()
    }

    /** 좌 = 0.5p, 우 = -p 인 버퍼 하나를 캡처 스레드처럼 넘긴다. */
    private fun push(peak: Float, frames: Int = 8) {
        val buffer = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()
        repeat(frames) {
            floats.put(peak * 0.5f)
            floats.put(-peak)   // 부호와 상관없이 크기로 본다
        }
        AudioEngine.pushAudioBuffer(buffer, frames * 2)
    }

    private fun pushSamples(interleaved: FloatArray) {
        val buffer = ByteBuffer.allocateDirect(interleaved.size * 4).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(interleaved)
        AudioEngine.pushAudioBuffer(buffer, interleaved.size)
    }

    private fun sine(hz: Double, amp: Float, frames: Int = 512) = FloatArray(frames * 2) { i ->
        (amp * sin(2 * PI * hz * (i / 2) / 48_000.0)).toFloat()
    }

    private fun take(): FloatArray {
        AudioEngine.takeHapticFrame(frame)
        return frame
    }

    @Test
    fun 진동용_피크는_읽는_사이_구간의_최대값이다() {
        push(0.10f)
        push(0.90f)
        push(0.20f)
        assertEquals(0.90f, take()[0], 0.0001f)
    }

    @Test
    fun 진동용_프레임은_읽으면_0_으로_돌아간다() {
        push(0.70f)
        take()
        val f = take()
        for (i in 0 until 4) assertEquals(0f, f[i], 0f)
    }

    @Test
    fun 오버레이가_먼저_읽어도_진동용_피크는_남는다() {
        push(0.60f)
        val overlay = FloatArray(3)
        AudioEngine.readPeaks(overlay)
        assertEquals("오버레이가 읽은 우 피크", 0.60f, overlay[1], 0.0001f)
        assertEquals("진동이 그 구간을 통째로 놓쳤다", 0.60f, take()[0], 0.0001f)
    }

    @Test
    fun 진동이_먼저_읽어도_보호된_소리_안내의_누적값은_남는다() {
        push(0.40f)
        take()
        val check = FloatArray(2)
        AudioEngine.takePeakSinceLastCheck(check)
        assertEquals(0.40f, check[0], 0.0001f)
        assertEquals("버퍼 수", 1f, check[1], 0.0001f)
    }

    @Test
    fun reset_은_진동용_프레임_전체를_지운다() {
        push(0.80f)
        AudioEngine.reset()
        val f = take()
        for (i in 0 until 4) assertEquals(0f, f[i], 0f)
    }

    @Test
    fun 진동용_RMS_는_가장_큰_버퍼의_좌우_에너지다() {
        push(0.10f)
        push(0.90f)
        push(0.20f)
        val expected = sqrt((0.45f * 0.45f + 0.90f * 0.90f) / 2f)
        assertEquals(expected, take()[1], 1e-5f)
    }

    @Test
    fun 버퍼_수를_센다() {
        push(0.1f)
        push(0.2f)
        push(0.3f)
        assertEquals(3f, take()[3], 0f)
    }

    @Test
    fun 진동용_음높이는_사인파_주파수를_따른다() {
        pushSamples(sine(1000.0, 0.5f))
        val t1 = take()[2]
        assertEquals(2000f / 48_000f, t1, 2000f / 48_000f * 0.02f)
        pushSamples(sine(2000.0, 0.5f))
        val t2 = take()[2]
        assertEquals(2f, t2 / t1, 0.06f)
    }

    @Test
    fun 음높이는_가장_큰_버퍼의_것이다() {
        pushSamples(sine(600.0, 0.2f))
        pushSamples(sine(1400.0, 0.6f))
        pushSamples(sine(600.0, 0.3f))
        assertEquals(2800f / 48_000f, take()[2], 2800f / 48_000f * 0.03f)
    }

    @Test
    fun 아주_작은_잡음은_영교차로_세지_않는다() {
        pushSamples(FloatArray(1024) { if (it % 4 < 2) 1e-5f else -1e-5f })
        assertEquals(0f, take()[2], 0f)
    }

    @Test
    fun NaN_이_섞여도_RMS_가_굳지_않는다() {
        pushSamples(FloatArray(1024) { if (it == 10) Float.NaN else 0.1f })
        take()
        push(0.3f)
        val f = take()
        assertEquals(0.3f, f[0], 1e-4f)
        assertEquals(sqrt((0.15f * 0.15f + 0.3f * 0.3f) / 2f), f[1], 1e-5f)
    }

    @Test
    fun 네이티브와_JVM_거울이_같은_값을_낸다() {
        val mirror = FloatArray(3)
        for (scene in HapticScenes.all) {
            val src = scene.open()
            val buf = FloatArray(HapticFeatureMirror.FLOATS_PER_BUFFER)
            repeat(3) {
                src.next(buf)
                HapticFeatureMirror.analyze(buf, buf.size, mirror)
                pushSamples(buf)
                val f = take()
                assertEquals("${scene.id} 피크", mirror[0], f[0], 0f)
                assertEquals("${scene.id} RMS", mirror[1], f[1], mirror[1] * 1e-5f + 1e-9f)
                assertEquals("${scene.id} 영교차율", mirror[2], f[2], 0f)
            }
        }
    }
}

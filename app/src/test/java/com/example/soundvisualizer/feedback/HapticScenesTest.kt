package com.example.soundvisualizer.feedback

import com.example.soundvisualizer.AiClassification
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class HapticScenesTest {

    private fun buffers(scene: HapticScene, n: Int): List<FloatArray> {
        val src = scene.open()
        return List(n) { FloatArray(HapticFeatureMirror.FLOATS_PER_BUFFER).also { src.next(it) } }
    }

    @Test
    fun `같은 장면은 늘 같은 소리다`() {
        for (scene in HapticScenes.all) {
            val a = buffers(scene, 20)
            val b = buffers(scene, 20)
            for (i in a.indices) assertArrayEquals(scene.id, a[i], b[i], 0f)
        }
    }

    @Test
    fun `미리보기는 4초 반 안이다`() {
        for (label in listOf(AiClassification.DANGER, AiClassification.SPEECH, AiClassification.AMBIENT)) {
            assertTrue(HapticScenes.previewFor(label).durationMs <= 4500)
        }
        assertEquals("hilo", HapticScenes.previewFor(AiClassification.DANGER).id)
    }

    @Test
    fun `사이렌은 크기가 한결같다`() {
        val out = FloatArray(3)
        for (scene in listOf(HapticScenes.wail, HapticScenes.yelp, HapticScenes.hilo)) {
            val dbs = buffers(scene, 400).drop(5).map { HapticFeatureMirror.analyze(it, it.size, out); 20 * log10(out[1]) }
            assertTrue("${scene.id} RMS 폭 ${dbs.max() - dbs.min()}", dbs.max() - dbs.min() < 1.0f)
        }
    }

    @Test
    fun `영교차율은 순음의 2f 나누기 샘플레이트다`() {
        val buf = FloatArray(HapticFeatureMirror.FLOATS_PER_BUFFER)
        val out = FloatArray(3)
        for (hz in intArrayOf(600, 1000, 1400)) {
            for (i in 0 until HapticFeatureMirror.FRAMES_PER_BUFFER) {
                val v = (0.5 * sin(2 * PI * hz * i / HapticFeatureMirror.SAMPLE_RATE)).toFloat()
                buf[2 * i] = v
                buf[2 * i + 1] = v
            }
            HapticFeatureMirror.analyze(buf, buf.size, out)
            val expected = 2f * hz / HapticFeatureMirror.SAMPLE_RATE
            assertEquals("$hz Hz", expected, out[2], expected * 0.05f)
        }
    }

    @Test
    fun `아주 작은 떨림은 교차로 세지 않는다`() {
        val buf = FloatArray(HapticFeatureMirror.FLOATS_PER_BUFFER) { if (it % 4 < 2) 1e-5f else -1e-5f }
        val out = FloatArray(3)
        HapticFeatureMirror.analyze(buf, buf.size, out)
        assertEquals(0f, out[2], 0f)
    }

    @Test
    fun `모으기는 네이티브처럼 가장 큰 버퍼가 제 영교차율을 들고 간다`() {
        val acc = FloatArray(HapticInput.FRAME_SIZE)
        HapticFeatureMirror.fold(acc, floatArrayOf(0.2f, 0.1f, 0.03f))
        HapticFeatureMirror.fold(acc, floatArrayOf(0.9f, 0.5f, 0.04f))
        HapticFeatureMirror.fold(acc, floatArrayOf(0.3f, 0.2f, 0.05f))
        assertArrayEquals(floatArrayOf(0.9f, 0.5f, 0.04f, 3f), acc, 0f)
    }

    @Test
    fun `흉내 입력은 시계만큼 버퍼를 보낸다`() {
        var now = 0L
        val input = SyntheticHapticInput(HapticScenes.steady, { now })
        val f = FloatArray(HapticInput.FRAME_SIZE)
        input.takeFrame(f)
        now = 107
        input.takeFrame(f)
        assertEquals(10f, f[HapticInput.BUFFERS], 0f)
        now = 150
        val bursty = SyntheticHapticInput(HapticScenes.steady, { now }, Delivery.BURSTY)
        bursty.takeFrame(f)
        now = 150 + 43
        bursty.takeFrame(f)
        assertEquals(4f, f[HapticInput.BUFFERS], 0f)
    }
}

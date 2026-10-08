package com.example.soundvisualizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/** 흔한 폰 화면(1080×2400, 420dpi). 세로와 가로를 모두 본다. */
private const val SHORT_SIDE = 1080
private const val LONG_SIDE = 2400
private const val DENSITY = 2.625f
private const val FRAME_NS = 16_666_667L

/** 정해 둔 소리 크기를 돌려주는 입력. 소리는 내지 않는다. */
private class FixedInputs(var mode: VisualMode, var settings: ModeSettings) : VisualizerInputs {
    var left = 0f
    var right = 0f
    override fun readPeaks(out: FloatArray) {
        out[0] = left
        out[1] = right
        out[2] = 1f
    }
    override fun currentMode(): VisualMode = mode
    override fun settingsFor(mode: VisualMode): ModeSettings = settings
    override fun coarseLabel(): String = AiClassification.AMBIENT
    override fun colorFor(label: String): Int = 0x66CCFF
    override fun isShown(label: String): Boolean = true
}

/** 엔진이 건 자르기를 적어 두는 캔버스. 그리기는 그대로 한다. 첫 자르기(위 띠)의 아래 끝이 띠 두께다. */
private class BandCanvas(bitmap: Bitmap) : Canvas(bitmap) {
    val clips = mutableListOf<FloatArray>()
    val thickness: Int get() = if (clips.isEmpty()) -1 else clips[0][3].toInt()
    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        clips += floatArrayOf(left, top, right, bottom)
        return super.clipRect(left, top, right, bottom)
    }
}

/**
 * 엔진이 건 자르기를 무시하고 첫 띠의 그리기(발광, 본 도형)만 받는 캔버스.
 * 띠마다 같은 경로를 다시 그리므로, 첫 띠의 것만 받으면 엔진이 자르지 않고 그릴 때와 같은 그리기가 된다.
 */
private class UnclippedCanvas(bitmap: Bitmap) : Canvas(bitmap) {
    private var clips = 0
    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        clips++
        return true
    }
    override fun drawPath(path: Path, paint: Paint) {
        if (clips <= 1) super.drawPath(path, paint)
    }
}

/** 시험할 소리. 300프레임 동안 좌우에 넣고, [silentAfter] 프레임 동안 끊는다. */
private class Sound(val name: String, val left: Float, val right: Float, val silentAfter: Int = 0, val speed: Float = 20f)

/**
 * 가장자리 띠로 잘라 그린 그림이 자르지 않고 그린 그림과 같은지 픽셀로 본다(#172, #189, #350).
 *
 * 같은 엔진 상태를 소프트웨어 비트맵에 두 번 그린다. 한 번은 평소처럼 띠 네 개로 자르고, 한 번은 자르지 않는다.
 * - 띠 안쪽(가운데)은 한 픽셀도 달라서는 안 된다. 다르면 띠가 도형이나 발광을 잘라 먹은 것이다.
 *   띠를 파도 점으로만 재거나 발광이 번지는 폭을 빼면 여기서 걸린다.
 * - 띠 안은 안티앨리어싱 반올림만큼 다를 수 있다. 같은 경로를 다른 범위로 잘라 래스터화하기 때문이다.
 *   예전 8dp 띠도 자르지 않은 그림과 같은 정도로 달랐다. 띠가 겹쳐 두 번 칠하거나 빠뜨리면 크게 다른 픽셀이 많아진다.
 *
 * 소리는 내지 않는다. 엔진에 정해 둔 소리 크기를 넣는다. 힙이 작은 기기에서도 돌도록 픽셀은 한 줄씩 읽는다.
 */
@RunWith(AndroidJUnit4::class)
class EdgeBandInstrumentedTest {

    private val sounds = listOf(
        Sound("작게", 0.05f, 0.05f),
        Sound("보통", 0.15f, 0.15f),
        Sound("크게", 0.4f, 0.4f),
        // 곡선이 가장 깊은 파도 점보다 몇 px 더 부푸는 소리다. 띠를 점으로만 재면 이 소리에서 잘린다.
        Sound("왼쪽으로 치우침", 0.3f, 0.15f),
        Sound("왼쪽만", 0.3f, 0f),
        // 소리를 끊은 직후라 앞 채널은 0 이고 뒤 채널만 남는다.
        Sound("뒤쪽만", 0.4f, 0.4f, silentAfter = 3, speed = 100f)
    )

    private fun engineAfter(sound: Sound, mode: VisualMode, glow: Boolean, w: Int, h: Int): VisualizerEngine {
        val settings = ModeSettings(speed = sound.speed, isGlowMode = glow, glowIntensity = if (glow) 50f else 0f)
        val inputs = FixedInputs(mode, settings)
        val engine = VisualizerEngine(DENSITY, inputs).also { it.setSurfaceSize(w.toFloat(), h.toFloat()) }
        var t = FRAME_NS
        repeat(300 + sound.silentAfter) { frame ->
            val loud = frame < 300
            inputs.left = if (loud) sound.left else 0f
            inputs.right = if (loud) sound.right else 0f
            engine.tick(t)
            t += FRAME_NS
        }
        return engine
    }

    @Test
    fun 띠로_잘라_그려도_자르지_않은_그림과_같다() {
        val row = IntArray(LONG_SIDE)
        val refRow = IntArray(LONG_SIDE)
        for ((w, h) in listOf(SHORT_SIDE to LONG_SIDE, LONG_SIDE to SHORT_SIDE))
            for (mode in listOf(VisualMode.Wave, VisualMode.Outline, VisualMode.Pad))
                for (glow in listOf(false, true)) for (sound in sounds) {
                    val engine = engineAfter(sound, mode, glow, w, h)
                    val banded = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val bandCanvas = BandCanvas(banded)
                    engine.draw(bandCanvas, w.toFloat(), h.toFloat(), 0L)
                    val ref = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    engine.draw(UnclippedCanvas(ref), w.toFloat(), h.toFloat(), 0L)

                    val where = "${w}x$h $mode 발광 $glow ${sound.name}"
                    val t = bandCanvas.thickness
                    assertEquals("$where: 띠 네 개로 잘라 그리지 않았다", 4, bandCanvas.clips.size)

                    var drawn = 0
                    var differing = 0
                    var centre = 0
                    var large = 0
                    var maxDelta = 0
                    for (y in 0 until h) {
                        banded.getPixels(row, 0, w, 0, y, w, 1)
                        ref.getPixels(refRow, 0, w, 0, y, w, 1)
                        for (x in 0 until w) {
                            if (refRow[x] != 0) drawn++
                            if (row[x] == refRow[x]) continue
                            differing++
                            if (x >= t && x < w - t && y >= t && y < h - t) centre++
                            val delta = premultipliedDelta(row[x], refRow[x])
                            if (delta > 32) large++
                            maxDelta = max(maxDelta, delta)
                        }
                    }
                    banded.recycle()
                    ref.recycle()

                    val stats = "띠 ${t}px, 그린 픽셀 $drawn, 다른 픽셀 $differing(최대 차이 $maxDelta, 32 넘게 $large), 가운데 $centre"
                    assertTrue("$where: 그린 것이 없다", drawn > 0)
                    assertEquals("$where: 띠 안쪽이 다르다. 띠가 도형을 잘라 먹었다 ($stats)", 0, centre)
                    assertTrue("$where: 띠 안이 안티앨리어싱 반올림보다 크게 다르다 ($stats)", large * 1000 <= drawn)
                }
    }

    @Test
    fun 외곽선에서_파도로_바꿔도_파도_띠에_외곽선_두께가_남지_않는다() {
        // 예전에는 띠에 더할 선 두께를 페인트에서 읽어서, 외곽선을 그린 뒤 파도로 바꾸면 외곽선의 4dp 가 파도 띠에 남았다.
        fun thickness(engine: VisualizerEngine): Int {
            val bitmap = Bitmap.createBitmap(SHORT_SIDE, LONG_SIDE, Bitmap.Config.ARGB_8888)
            val canvas = BandCanvas(bitmap)
            engine.draw(canvas, SHORT_SIDE.toFloat(), LONG_SIDE.toFloat(), 0L)
            bitmap.recycle()
            return canvas.thickness
        }
        val sound = sounds[1]
        val waveOnly = thickness(engineAfter(sound, VisualMode.Wave, glow = false, SHORT_SIDE, LONG_SIDE))

        // 같은 소리로 외곽선을 그리다 마지막 프레임에 파도로 바꾼다. 두 모드의 설정이 같아 깊이는 위와 같다.
        val inputs = FixedInputs(VisualMode.Outline, ModeSettings())
        val engine = VisualizerEngine(DENSITY, inputs).also { it.setSurfaceSize(SHORT_SIDE.toFloat(), LONG_SIDE.toFloat()) }
        inputs.left = sound.left
        inputs.right = sound.right
        var t = FRAME_NS
        repeat(299) {
            engine.tick(t)
            t += FRAME_NS
        }
        val outline = thickness(engine)
        inputs.mode = VisualMode.Wave
        engine.tick(t)
        val switched = thickness(engine)

        assertTrue("외곽선 띠($outline)가 선 두께만큼 두껍지 않다 (파도 $waveOnly)", outline > waveOnly)
        assertEquals("외곽선을 그린 뒤의 파도 띠가 처음부터 파도인 띠와 다르다", waveOnly, switched)
    }

    /** 두 색(getPixels 의 알파를 곱하지 않은 ARGB)을 알파를 곱한 값으로 바꿔 채널 차이의 최댓값을 낸다. 화면에 섞이는 값이 이쪽이다. */
    private fun premultipliedDelta(a: Int, b: Int): Int {
        val alphaA = a ushr 24
        val alphaB = b ushr 24
        var delta = abs(alphaA - alphaB)
        for (shift in 0 until 24 step 8) {
            val ca = (((a ushr shift) and 0xFF) * alphaA + 127) / 255
            val cb = (((b ushr shift) and 0xFF) * alphaB + 127) / 255
            delta = max(delta, abs(ca - cb))
        }
        return delta
    }
}

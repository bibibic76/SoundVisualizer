package com.example.soundvisualizer.direction

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

private const val DEAD_ZONE = 6f

private fun side(lag: Float, topChannel: Int, rotation: Int) =
    ScreenSideMapper.side(lag, topChannel, rotation, DEAD_ZONE)

class ScreenSideMapperTest {

    @Test
    fun `세로로 들면 시간차와 상관없이 가운데다`() {
        for (rotation in listOf(Surface.ROTATION_0, Surface.ROTATION_180)) {
            for (lag in listOf(-20f, -8f, 0f, 8f, 20f)) {
                assertEquals("회전 $rotation, 시간차 $lag", ScreenSide.Center, side(lag, topChannel = 1, rotation = rotation))
            }
        }
    }

    @Test
    fun `반시계로 돌리면 위쪽 마이크에 가까운 소리가 왼쪽이다`() {
        // ROTATION_90: 폰의 위쪽 끝이 화면 왼쪽에 온다. 위쪽 마이크가 채널 1 이면 시간차가 음수일 때 위쪽에 먼저 닿았다.
        assertEquals(ScreenSide.Left, side(-12f, topChannel = 1, rotation = Surface.ROTATION_90))
        assertEquals(ScreenSide.Right, side(12f, topChannel = 1, rotation = Surface.ROTATION_90))
    }

    @Test
    fun `시계 방향으로 돌리면 좌우가 뒤집힌다`() {
        // ROTATION_270: 폰의 위쪽 끝이 화면 오른쪽에 온다.
        assertEquals(ScreenSide.Right, side(-12f, topChannel = 1, rotation = Surface.ROTATION_270))
        assertEquals(ScreenSide.Left, side(12f, topChannel = 1, rotation = Surface.ROTATION_270))
    }

    @Test
    fun `위쪽 마이크가 채널 0 이면 부호를 거꾸로 읽는다`() {
        assertEquals(ScreenSide.Left, side(12f, topChannel = 0, rotation = Surface.ROTATION_90))
        assertEquals(ScreenSide.Right, side(-12f, topChannel = 0, rotation = Surface.ROTATION_90))
        assertEquals(ScreenSide.Right, side(12f, topChannel = 0, rotation = Surface.ROTATION_270))
    }

    @Test
    fun `정면에 가까운 작은 시간차는 가운데다`() {
        for (lag in listOf(-5.9f, -2f, 0f, 3f, 5.9f)) {
            assertEquals("시간차 $lag", ScreenSide.Center, side(lag, topChannel = 1, rotation = Surface.ROTATION_90))
        }
        assertEquals(ScreenSide.Left, side(-6f, topChannel = 1, rotation = Surface.ROTATION_90))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `채널은 0 이나 1 이어야 한다`() {
        side(10f, topChannel = 2, rotation = Surface.ROTATION_90)
    }
}

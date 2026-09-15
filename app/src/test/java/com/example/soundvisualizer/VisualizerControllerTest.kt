package com.example.soundvisualizer

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 켜기 전에 물어볼 권한 목록. 앱의 실행 버튼과 빠른 설정 타일이 같은 목록을 쓴다.
 *
 * 알림 권한은 Android 13(API 33)에 생긴 권한이라 그 아래에서 요청하면 항상 거부로 돌아온다.
 */
class VisualizerControllerTest {

    private val none: (String) -> Boolean = { false }
    private val all: (String) -> Boolean = { true }

    @Test
    fun `Android 12 이하는 마이크 권한만 묻는다`() {
        assertArrayEquals(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            VisualizerController.requiredPermissions(32, none)
        )
    }

    @Test
    fun `Android 13 이상은 알림 권한도 묻는다`() {
        assertArrayEquals(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
            VisualizerController.requiredPermissions(33, none)
        )
    }

    @Test
    fun `이미 허용된 권한은 다시 묻지 않는다`() {
        assertArrayEquals(emptyArray<String>(), VisualizerController.requiredPermissions(34, all))
        assertArrayEquals(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            VisualizerController.requiredPermissions(34) { it == Manifest.permission.RECORD_AUDIO }
        )
    }

    @Test
    fun `마이크 권한을 물을 때만 이유를 먼저 설명한다`() {
        assertTrue(VisualizerController.needsMicRationale(VisualizerController.requiredPermissions(34, none)))
        assertTrue(VisualizerController.needsMicRationale(VisualizerController.requiredPermissions(29, none)))
        // 알림 권한만 남은 경우
        assertFalse(
            VisualizerController.needsMicRationale(
                VisualizerController.requiredPermissions(34) { it == Manifest.permission.RECORD_AUDIO }
            )
        )
        assertFalse(VisualizerController.needsMicRationale(VisualizerController.requiredPermissions(34, all)))
    }
}

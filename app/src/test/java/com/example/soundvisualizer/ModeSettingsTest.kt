package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 모드 설정의 기본값을 고정한다.
 *
 * 이 값들은 그냥 취향이 아니라 슬라이더의 의미와 맞물려 있다. 예를 들어 최대 진하기가
 * 0 이면 크기 고정 모드가 완전히 투명해져서 아무것도 보이지 않고, 민감도는 화면에
 * 보이는 값(내부 계수의 4배)이라 바꾸면 반응 속도가 통째로 달라진다.
 *
 * SharedPreferences 를 쓰는 [SettingsManager] 의 저장·복원은 계측 테스트 영역이라
 * 여기서는 다루지 않는다.
 */
class ModeSettingsTest {

    @Test
    fun `기본값이 고정되어 있다`() {
        val d = ModeSettings()
        assertEquals("크기", 50f, d.intensity, 0f)
        assertEquals("속도", 20f, d.speed, 0f)
        assertEquals("진하기", 50f, d.opacity, 0f)
        assertEquals("민감도", 15f, d.sensitivity, 0f)
        assertEquals("반지름", 40f, d.circleRadius, 0f)
        assertEquals("고정 크기", 30f, d.opacityFixedSize, 0f)
        assertEquals("최대 진하기", 100f, d.opacityFixedMaxOpacity, 0f)
        assertEquals("광원 강도", 0f, d.glowIntensity, 0f)
        assertTrue("공간 리플은 기본 켜짐", d.useRippleDelay)
        assertFalse("광원은 기본 꺼짐", d.isGlowMode)
        assertFalse("크기 고정은 기본 꺼짐", d.intensityAsOpacity)
    }

    @Test
    fun `최대 진하기 기본값이 0 이면 크기 고정 모드가 보이지 않는다`() {
        // 알파 = 최대진하기/100 * 볼륨비율. 0 이면 소리가 아무리 커도 0 이다.
        assertTrue(
            "최대 진하기 기본값이 0 이면 크기 고정 모드에서 화면이 비어 보인다",
            ModeSettings().opacityFixedMaxOpacity > 0f
        )
    }

    @Test
    fun `진하기 기본값은 절반쯤 보이는 값이다`() {
        val opacity = ModeSettings().opacity
        assertTrue("진하기 기본값($opacity)이 화면에 보이는 범위여야 한다", opacity in 1f..100f)
    }

    @Test
    fun `copy 는 지정한 값만 바꾼다`() {
        val base = ModeSettings()
        val changed = base.copy(intensity = 77f)
        assertEquals(77f, changed.intensity, 0f)
        assertEquals(base.speed, changed.speed, 0f)
        assertEquals(base.opacity, changed.opacity, 0f)
        assertEquals(base.sensitivity, changed.sensitivity, 0f)
    }

    @Test
    fun `copy 후 수정이 원본에 번지지 않는다`() {
        // SettingsManager 의 update 가 copy().apply { } 로 도는데,
        // ModeSettings 가 var 필드를 가진 data class 라 얕은 복사가 맞게 동작해야 한다.
        val base = ModeSettings()
        val originalIntensity = base.intensity
        base.copy().apply { intensity = 99f }
        assertEquals("원본이 함께 바뀌었다", originalIntensity, base.intensity, 0f)
    }
}

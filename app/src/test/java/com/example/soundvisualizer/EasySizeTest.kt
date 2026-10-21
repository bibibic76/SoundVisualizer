package com.example.soundvisualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 쉬운 설정의 크기 세 단계(#408)가 한 모드의 설정에 무엇을 쓰고, 그 모드의 지금 설정을 어느 단계로 보여 주는지.
 * 크기는 모드마다 따로다(사용자 결정 2026-10-22). 어느 모드에 쓰는지는 EasySettingsSettingTest 가 본다.
 */
class EasySizeTest {

    @Test
    fun `처음 설정은 보통으로 보인다`() {
        assertEquals(EasySize.Medium, EasySize.of(ModeSettings()))
        assertEquals("보통은 처음 값 그대로다", ModeSettings(), EasySize.Medium.applyTo(ModeSettings()))
    }

    @Test
    fun `단계는 작을수록 작고 옅다`() {
        val steps = EasySize.values()
        for (i in 1 until steps.size) {
            assertTrue("${steps[i]} 크기", steps[i].intensity > steps[i - 1].intensity)
            assertTrue("${steps[i]} 진하기", steps[i].opacity > steps[i - 1].opacity)
        }
        for (step in steps) {
            assertTrue("$step 크기가 슬라이더 범위 밖이다", step.intensity in 0f..100f)
            assertTrue("$step 진하기가 슬라이더 범위 밖이다", step.opacity in 0f..100f)
        }
    }

    @Test
    fun `단계는 크기와 진하기만 바꾸고 크기 고정을 끈다`() {
        val custom = ModeSettings(
            intensity = 12f, speed = 77f, opacity = 9f, circleRadius = 64f, useRippleDelay = false, sensitivity = 33f,
            isGlowMode = true, glowIntensity = 45f, intensityAsOpacity = true, opacityFixedSize = 55f, opacityFixedMaxOpacity = 66f
        )
        for (step in EasySize.values()) {
            val applied = step.applyTo(custom)
            assertEquals(
                step.name,
                custom.copy(intensity = step.intensity, opacity = step.opacity, intensityAsOpacity = false),
                applied
            )
        }
    }

    @Test
    fun `쓴 단계는 그 단계로 보인다`() {
        for (step in EasySize.values()) {
            assertEquals(step.name, step, EasySize.of(step.applyTo(ModeSettings())))
        }
    }

    @Test
    fun `자세한 설정에서 바꾼 크기나 크기 고정은 어느 단계도 아니다`() {
        assertNull(EasySize.of(ModeSettings(intensity = 63f)))
        assertNull(EasySize.of(ModeSettings(opacity = 80f)))
        assertNull("크기 고정을 켜면 크기가 움직이지 않는다", EasySize.of(ModeSettings(intensityAsOpacity = true)))
    }

    @Test
    fun `단계와 상관없는 값은 단계를 가리지 않는다`() {
        // 속도·광원처럼 쉬운 설정이 맡지 않는 값을 자세한 설정에서 바꿔도 크기 단계는 그대로 보인다.
        val tuned = EasySize.Large.applyTo(ModeSettings(speed = 90f, isGlowMode = true, glowIntensity = 30f, circleRadius = 70f))
        assertEquals(EasySize.Large, EasySize.of(tuned))
    }
}

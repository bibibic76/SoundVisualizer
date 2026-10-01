package com.example.soundvisualizer.tutorial

import com.example.soundvisualizer.R
import org.junit.Assert.assertEquals
import org.junit.Test

class TutorialPageTest {

    @Test
    fun `외부 사운드 모드면 소리 쪽은 마이크로 주변 소리를 그린다고 설명한다`() {
        // "폰에서 재생되는 소리만, 주변 소리는 듣지 않는다" 는 외부 사운드 모드에서 거짓이다(#226).
        assertEquals(R.string.tutorial_sound_body, TutorialPage.Sound.bodyFor(externalSoundMode = false))
        assertEquals(R.string.tutorial_sound_body_external, TutorialPage.Sound.bodyFor(externalSoundMode = true))
        for (page in TutorialPage.entries.filter { it != TutorialPage.Sound }) {
            assertEquals("${page.name} 쪽은 모드와 상관없다", page.body, page.bodyFor(externalSoundMode = true))
            assertEquals(page.body, page.bodyFor(externalSoundMode = false))
        }
    }
}

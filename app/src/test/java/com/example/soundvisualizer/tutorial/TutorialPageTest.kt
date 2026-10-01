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
    }

    @Test
    fun `외부 사운드 모드면 방향 쪽은 아직 가운데로만 그린다고 말한다`() {
        // 그 모드는 모든 소리를 가운데로 그린다. 좌우를 구분한다고 하면 왼쪽에서 부르는 소리를 앞에서 난 소리로 읽는다(#268).
        assertEquals(R.string.tutorial_direction_title, TutorialPage.Direction.titleFor(externalSoundMode = false))
        assertEquals(R.string.tutorial_direction_title_external, TutorialPage.Direction.titleFor(externalSoundMode = true))
        assertEquals(R.string.tutorial_direction_body, TutorialPage.Direction.bodyFor(externalSoundMode = false))
        assertEquals(R.string.tutorial_direction_body_external, TutorialPage.Direction.bodyFor(externalSoundMode = true))
    }

    @Test
    fun `나머지 쪽은 모드와 상관없다`() {
        val modeAware = setOf(TutorialPage.Sound, TutorialPage.Direction)
        for (page in TutorialPage.entries.filter { it !in modeAware }) {
            for (external in listOf(false, true)) {
                assertEquals("${page.name} 쪽 설명", page.body, page.bodyFor(external))
                assertEquals("${page.name} 쪽 제목", page.title, page.titleFor(external))
            }
        }
        assertEquals(TutorialPage.Sound.title, TutorialPage.Sound.titleFor(externalSoundMode = true))
    }
}

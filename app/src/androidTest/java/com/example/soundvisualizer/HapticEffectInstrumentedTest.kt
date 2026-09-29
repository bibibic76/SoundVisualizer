package com.example.soundvisualizer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.soundvisualizer.feedback.HapticPattern
import com.example.soundvisualizer.feedback.HapticPlayer
import com.example.soundvisualizer.feedback.HapticScenes
import com.example.soundvisualizer.feedback.HapticShapes
import com.example.soundvisualizer.feedback.HapticSimulator
import com.example.soundvisualizer.feedback.HapticStrength
import com.example.soundvisualizer.feedback.seamCues
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 진동으로 보내는 모든 모양이 이 기기의 VibrationEffect 로 만들어지는지. 진동은 울리지 않는다.
 * 안드로이드는 잘못된 파형(길이 불일치, 음수, 255 초과)을 만들 때 예외를 던지는데, 그러면 그 진동은 조용히 빠진다.
 */
@RunWith(AndroidJUnit4::class)
class HapticEffectInstrumentedTest {

    private val player = HapticPlayer(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun 모든_모양과_예시_계획이_진동효과로_만들어진다() {
        if (!player.hasVibrator) return
        for (amp in listOf(true, false)) for (p in HapticPattern.values()) for (s in HapticStrength.values()) {
            assertNotNull("$p $s amp=$amp", player.buildEffect(HapticShapes.oneShot(p, s, amp)))
        }
        assertNotNull(player.buildEffect(HapticShapes.STOPPED_ALERT))
        for (sdk in intArrayOf(29, 36)) for (scene in HapticScenes.all) for (amp in listOf(true, false)) {
            for (cue in HapticSimulator.run(scene, sdk = sdk, amplitudeControl = amp, aiLagMs = 300)) {
                assertNotNull("${scene.id} api$sdk amp=$amp ${cue.plan}", player.buildEffect(cue.plan))
            }
        }
        for (sdk in intArrayOf(29, 36)) for (cue in seamCues(sdk)) {
            assertNotNull("seam api$sdk ${cue.plan}", player.buildEffect(cue.plan))
        }
    }
}

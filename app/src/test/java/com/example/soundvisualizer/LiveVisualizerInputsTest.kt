package com.example.soundvisualizer

import android.content.SharedPreferences
import com.example.soundvisualizer.ai.AiClassificationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 엔진이 프레임마다 읽는 실제 배선([LiveVisualizerInputs]).
 *
 * [VisualizerEngineTest] 는 가짜 입력을 넣으므로 이 배선을 한 줄도 지나가지 않고,
 * [AiLabelContractTest] 는 ai/ 와 이쪽의 **라벨 문자열이 같은지**만 본다. 그래서 그 경계 안쪽,
 * 즉 "danger 를 받았을 때 정말 danger 색을 쓰는지" 는 여기서만 확인된다. 두 분기를 맞바꿔도
 * 컴파일되고 나머지 테스트는 전부 통과한다.
 *
 * 색 상수를 이 파일에 복사하지 않는다. 대응하는 흐름과 같은 값인지만 보므로, 기본색을 바꿔도
 * 이 테스트는 고칠 필요가 없다.
 */
class LiveVisualizerInputsTest {

    @After
    fun tearDown() {
        // SettingsManager 와 AiClassification 은 object 라 상태가 테스트 사이에 남는다.
        // 기본값으로 돌려놓지 않으면 다른 테스트가 실행 순서에 따라 깨진다 (ScreenOffPauseTest 참고).
        SettingsManager.load(MemoryPrefs())
        AiClassification.detach()
    }

    private fun loadPrefs(block: SharedPreferences.Editor.() -> Unit) {
        val prefs = MemoryPrefs()
        prefs.edit().apply(block).apply()
        SettingsManager.load(prefs)
    }

    private fun result(coarse: String) = AiClassificationResult(
        coarse = coarse,
        display = coarse,
        confidence = 1f,
        gunshotScore = 0f,
        boosterAvailable = true,
        preBoosterCoarse = coarse,
        boosterAccepted = false,
        meetsThreshold = true,
        useBoosterDangerPreview = false,
        timestampMs = 0L
    )

    @Test
    fun `종류마다 그 종류의 색을 쓴다`() {
        // 세 색을 서로 다른 값으로 세운다. 기본색이 우연히 달라서 통과하는 것이 아님을 분명히 한다.
        loadPrefs {
            putInt("color_ambient", 0xFF111111.toInt())
            putInt("color_speech", 0xFF222222.toInt())
            putInt("color_danger", 0xFF333333.toInt())
        }

        assertEquals(0xFF111111.toInt(), LiveVisualizerInputs.colorFor(AiClassification.AMBIENT))
        assertEquals(0xFF222222.toInt(), LiveVisualizerInputs.colorFor(AiClassification.SPEECH))
        assertEquals(0xFF333333.toInt(), LiveVisualizerInputs.colorFor(AiClassification.DANGER))
    }

    @Test
    fun `색은 설정 흐름에서 온다`() {
        // 저장값이 없을 때도 배선이 맞는지. 기본색 세 개가 서로 다르므로 맞바꾸면 어긋난다.
        loadPrefs { }

        assertEquals(SettingsManager.colorAmbient.value, LiveVisualizerInputs.colorFor(AiClassification.AMBIENT))
        assertEquals(SettingsManager.colorSpeech.value, LiveVisualizerInputs.colorFor(AiClassification.SPEECH))
        assertEquals(SettingsManager.colorDanger.value, LiveVisualizerInputs.colorFor(AiClassification.DANGER))
        assertNotEquals(
            "기본색이 서로 같으면 이 테스트가 배선을 확인하지 못한다",
            SettingsManager.colorSpeech.value,
            SettingsManager.colorDanger.value
        )
    }

    @Test
    fun `모르는 라벨은 환경음 색을 쓴다`() {
        // ai/ 가 라벨 이름을 바꿔도 화면이 죽지 않고 환경음으로 떨어지는 것이 약속이다.
        loadPrefs { putInt("color_ambient", 0xFF444444.toInt()) }

        assertEquals(0xFF444444.toInt(), LiveVisualizerInputs.colorFor("불꽃놀이"))
        assertEquals(0xFF444444.toInt(), LiveVisualizerInputs.colorFor(""))
    }

    @Test
    fun `종류마다 그 종류의 표시 여부를 쓴다`() {
        // 한 번에 한 종류만 켠다. 세 경우를 다 보면 어느 두 분기를 맞바꿔도 반드시 어긋난다.
        // (셋 다 기본값이 켜짐이라, 기본 상태로는 맞바꿈이 드러나지 않는다.)
        val labels = listOf(AiClassification.AMBIENT, AiClassification.SPEECH, AiClassification.DANGER)
        for (only in labels) {
            loadPrefs {
                putBoolean("show_ambient", only == AiClassification.AMBIENT)
                putBoolean("show_speech", only == AiClassification.SPEECH)
                putBoolean("show_danger", only == AiClassification.DANGER)
            }

            for (label in labels) {
                assertEquals(
                    "$only 만 켠 상태에서 $label",
                    label == only,
                    LiveVisualizerInputs.isShown(label)
                )
            }
        }
    }

    @Test
    fun `모르는 라벨은 환경음 표시 여부를 쓴다`() {
        loadPrefs {
            putBoolean("show_ambient", false)
            putBoolean("show_speech", true)
            putBoolean("show_danger", true)
        }

        assertEquals(false, LiveVisualizerInputs.isShown("불꽃놀이"))
    }

    @Test
    fun `모드마다 그 모드의 설정을 쓴다`() {
        // 네 모드의 값을 다르게 세운다. 기본값은 네 모드가 모두 같아서 맞바꿈이 드러나지 않는다.
        loadPrefs {
            putFloat("wave_intensity", 11f)
            putFloat("pad_intensity", 22f)
            putFloat("circle_intensity", 33f)
            putFloat("outline_intensity", 44f)
        }

        assertEquals(11f, LiveVisualizerInputs.settingsFor(VisualMode.Wave).intensity, 0f)
        assertEquals(22f, LiveVisualizerInputs.settingsFor(VisualMode.Pad).intensity, 0f)
        assertEquals(33f, LiveVisualizerInputs.settingsFor(VisualMode.CircleRipple).intensity, 0f)
        assertEquals(44f, LiveVisualizerInputs.settingsFor(VisualMode.Outline).intensity, 0f)
    }

    @Test
    fun `지금 모드는 설정에서 온다`() {
        for (mode in VisualMode.values()) {
            loadPrefs { putInt("visualMode", mode.ordinal) }
            assertEquals(mode, LiveVisualizerInputs.currentMode())
        }
    }

    @Test
    fun `라벨은 분류기에서 온다`() {
        // 고정값을 돌려주는 것이 아니라 AiClassification 을 실제로 거치는지 본다.
        AiClassification.attach { result(AiClassification.DANGER) }
        assertEquals(AiClassification.DANGER, LiveVisualizerInputs.coarseLabel())

        AiClassification.attach { result(AiClassification.SPEECH) }
        assertEquals(AiClassification.SPEECH, LiveVisualizerInputs.coarseLabel())

        AiClassification.detach()
        assertEquals(AiClassification.AMBIENT, LiveVisualizerInputs.coarseLabel())
    }
}

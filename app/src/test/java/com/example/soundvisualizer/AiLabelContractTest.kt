package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiPostProcessor
import com.example.soundvisualizer.ai.GunshotBoosterDecision
import com.example.soundvisualizer.ai.YamnetCoarseClassifier
import com.example.soundvisualizer.ai.YamnetThreeClassMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 화면 색·표시 여부·진동 설정이 알아듣는 라벨. 이 밖의 값은 모두 환경음으로 처리된다. */
private val UI_LABELS = setOf(AiClassification.AMBIENT, AiClassification.SPEECH, AiClassification.DANGER)

/**
 * AI 분류(ai/)가 내보내는 소리 종류 라벨이 화면·진동 쪽 상수([AiClassification])와 같은 문자열인지 확인한다.
 *
 * 두 쪽은 문자열로만 이어져 있다. 화면(LiveVisualizerInputs)과 진동(HapticSettings, SettingsManager)은
 * 모르는 라벨을 일부러 환경음으로 처리하므로, ai/ 쪽 라벨 이름만 바뀌면 ai/ 테스트는 모두 통과하는데
 * 기기에서는 총소리가 환경음 색으로 진동 없이 표시된다. 이 테스트는 그 어긋남을 기기 없이 잡는다.
 *
 * ai/ 코드는 고치지 않고 공개 함수만 부른다. 어떤 소리를 어느 종류로 볼지는 ai/ 테스트(파이썬 골든)가 맡고,
 * 여기서는 나오는 라벨이 세 상수 중 하나인지와 종류마다 대표 소리 하나씩만 본다.
 */
class AiLabelContractTest {

    companion object {
        private lateinit var classNames: List<String>
        private lateinit var classifier: YamnetCoarseClassifier

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            // 테스트 리소스의 클래스 목록은 앱 assets 의 것과 같은 파일이다. 리소스가 없으면 assets 원본을 읽는다.
            val csv = AiLabelContractTest::class.java.classLoader
                ?.getResourceAsStream("ai/yamnet_class_map.csv")
                ?: File("src/main/assets/ai/yamnet_class_map.csv").inputStream()
            classNames = YamnetCoarseClassifier.loadClassNames(csv)
            classifier = YamnetCoarseClassifier(classNames)
        }

        private fun assertUiLabel(where: String, label: String) {
            assertTrue(
                "$where: '$label' 은 화면·진동이 모르는 라벨이라 환경음으로 처리된다 (알아듣는 라벨: $UI_LABELS)",
                label in UI_LABELS
            )
        }

        private fun indexOfClass(name: String): Int {
            val index = classNames.indexOf(name)
            assertTrue("YAMNet 클래스 목록에 '$name' 이 없다", index >= 0)
            return index
        }

        /** [name] 클래스만 확률 1 이고 나머지는 0 인 YAMNet 출력. */
        private fun oneHot(name: String): FloatArray =
            FloatArray(YamnetCoarseClassifier.NUM_CLASSES).also { it[indexOfClass(name)] = 1f }

        /** 종류마다 대표 소리 하나. */
        private val REPRESENTATIVES = listOf(
            "Gunshot, gunfire" to AiClassification.DANGER,
            "Speech" to AiClassification.SPEECH,
            "Rain" to AiClassification.AMBIENT
        )
    }

    @Test
    fun `YAMNet 모든 클래스 이름은 세 라벨 중 하나로 묶인다`() {
        assertEquals(YamnetCoarseClassifier.NUM_CLASSES, classNames.size)
        classNames.forEach { name ->
            assertUiLabel(name, YamnetThreeClassMapper.mapDisplayNameToCoarse(name))
        }
        assertEquals("이름 없음", AiClassification.AMBIENT, YamnetThreeClassMapper.mapDisplayNameToCoarse(null))
        assertEquals("빈 이름", AiClassification.AMBIENT, YamnetThreeClassMapper.mapDisplayNameToCoarse(""))
    }

    @Test
    fun `대표 소리는 화면과 진동이 기대하는 라벨 상수로 묶인다`() {
        REPRESENTATIVES.forEach { (name, expected) ->
            val probs = oneHot(name)
            assertEquals("매핑: $name", expected, YamnetThreeClassMapper.mapDisplayNameToCoarse(name))
            assertEquals("투표: $name", expected, classifier.classify(probs).coarse)
        }
    }

    @Test
    fun `어떤 클래스가 1위여도 분류기 투표 결과는 세 라벨 중 하나다`() {
        classNames.forEachIndexed { index, name ->
            val probs = FloatArray(YamnetCoarseClassifier.NUM_CLASSES)
            probs[index] = 1f
            assertUiLabel("투표: $name", classifier.classify(probs).coarse)
        }
    }

    @Test
    fun `게임 음악에 묻힌 총소리는 Booster 와 후처리를 거쳐 DANGER 로 나온다`() {
        // 실제 파이프라인(RealtimeAiPipeline)과 같은 순서로 잇는다: 투표 → Booster 판정 → 후처리.
        // 음악이 1위라 투표는 환경음이 되고, Booster 가 총소리 점수를 보고 위협음으로 올리는 경로다.
        val probs = FloatArray(YamnetCoarseClassifier.NUM_CLASSES)
        probs[indexOfClass("Music")] = 0.5f
        probs[indexOfClass("Gunshot, gunfire")] = 0.45f
        val pre = classifier.classify(probs)
        assertUiLabel("투표", pre.coarse)

        for (gunshotScore in listOf(0f, 1f)) {
            val decision = GunshotBoosterDecision.decide(probs, classNames, pre, gunshotScore)
            assertUiLabel("Booster 전 (점수 $gunshotScore)", decision.preBoosterCoarse)
            assertUiLabel("Booster 후 (점수 $gunshotScore)", decision.postBoosterCoarse)

            val topKSummary = pre.top5.joinToString(separator = " | ") { it.name }
            val post = AiPostProcessor().process(
                AiPostProcessor.FrameInput(
                    coarse = decision.postBoosterCoarse,
                    display = decision.postBoosterDisplay,
                    confidence = decision.postBoosterConfidence,
                    adoptedDangerFromBooster = decision.accepted,
                    hasStrongDangerCue = decision.hasStrongDangerCue,
                    hasCriticalDangerCue = AiPostProcessor.isCriticalDangerEvent(
                        decision.postBoosterDisplay,
                        topKSummary
                    ),
                    topKSummary = topKSummary
                )
            )
            assertUiLabel("후처리 (점수 $gunshotScore)", post.uiCoarse)

            if (gunshotScore == 1f) {
                assertTrue("총소리 단서와 최고 점수는 Booster 가 받아들여야 한다: ${decision.reason}", decision.accepted)
                assertEquals("Booster 후", AiClassification.DANGER, decision.postBoosterCoarse)
                assertEquals("후처리", AiClassification.DANGER, post.uiCoarse)
            }
        }
    }

    @Test
    fun `후처리가 화면에 넘기는 라벨은 항상 세 상수 중 하나다`() {
        // 후처리 입력 라벨도 상수가 아니라 ai/ 매핑에서 받는다. 상수를 넣으면 이름이 어긋났을 때
        // 후처리가 받은 문자열을 그대로 돌려줘서 어긋남이 가려진다.
        fun frame(name: String, confidence: Float) = AiPostProcessor.FrameInput(
            coarse = YamnetThreeClassMapper.mapDisplayNameToCoarse(name),
            display = name,
            confidence = confidence,
            hasStrongDangerCue = GunshotBoosterDecision.isStrongDangerKeyword(name),
            hasCriticalDangerCue = AiPostProcessor.isCriticalDangerKeyword(name)
        )

        val pp = AiPostProcessor()

        // 아직 기준을 넘은 결과가 없으면 후처리의 초기 라벨이 그대로 화면에 간다.
        assertEquals("초기 라벨", AiClassification.AMBIENT, pp.process(frame("Speech", 0f)).uiCoarse)

        // 위협음 → 대화음 → 환경음 → 위협음 순서로, 종류마다 몇 프레임씩 흘려 히스테리시스가 넘어간 뒤의 라벨을 본다.
        for ((name, expected) in REPRESENTATIVES + REPRESENTATIVES.first()) {
            val results = List(5) { pp.process(frame(name, 0.9f)) }
            results.forEachIndexed { i, r ->
                assertUiLabel("$name ${i + 1}번째 화면 라벨", r.uiCoarse)
                assertUiLabel("$name ${i + 1}번째 확정 라벨", r.confirmedCoarse)
            }
            assertEquals(name, expected, results.last().uiCoarse)
        }

        // 캡처를 다시 시작하면(reset) 초기 라벨로 돌아간다.
        pp.reset()
        assertEquals("재시작 후 초기 라벨", AiClassification.AMBIENT, pp.process(frame("Gunshot, gunfire", 0f)).uiCoarse)
    }
}

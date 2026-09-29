package com.example.soundvisualizer

import com.example.soundvisualizer.ai.AiPostProcessor
import com.example.soundvisualizer.ai.GunshotBoosterDecision
import com.example.soundvisualizer.ai.YamnetCoarseClassifier
import com.example.soundvisualizer.ai.YamnetThreeClassMapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 분류 탭에서 바꾼 소리 종류가 분류기에 어떻게 들어가는지 확인한다.
 *
 * - 아무것도 바꾸지 않았으면 분류는 전과 같아야 한다. 매핑이 키워드 규칙과 같고, Booster·강한 단서에 쓰는
 *   키워드 소리는 모두 기본이 위협음이라 새 조건("지금 위협음인 소리만 단서로 센다")이 아무것도 거르지 않는다.
 * - 바꾸면 투표, Booster, 강한 단서가 모두 사용자가 고른 종류를 따른다. 위협음에서 뺀 소리가 다른 길로
 *   다시 위협음이 되면 사용자에게는 "바꿨는데 그대로" 로 보인다.
 *
 * ai/ 코드는 고치지 않고 공개 함수만 부른다(AiLabelContractTest 와 같은 방식). 사용자 선택은 전역 값이라
 * 테스트마다 비워 다른 테스트에 새지 않게 한다.
 */
class SoundTypeOverrideTest {

    companion object {
        private lateinit var classNames: List<String>
        private lateinit var classifier: YamnetCoarseClassifier

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val csv = SoundTypeOverrideTest::class.java.classLoader
                ?.getResourceAsStream("ai/yamnet_class_map.csv")
                ?: File("src/main/assets/ai/yamnet_class_map.csv").inputStream()
            classNames = YamnetCoarseClassifier.loadClassNames(csv)
            classifier = YamnetCoarseClassifier(classNames)
        }

        private fun probs(vararg pairs: Pair<String, Float>): FloatArray =
            FloatArray(YamnetCoarseClassifier.NUM_CLASSES).also { p ->
                pairs.forEach { (name, value) ->
                    val index = classNames.indexOf(name)
                    assertTrue("YAMNet 클래스 목록에 '$name' 이 없다", index >= 0)
                    p[index] = value
                }
            }

        /**
         * 실제 파이프라인(RealtimeAiPipeline)과 같은 순서: 투표 → Booster 판정 → 후처리.
         *
         * 같은 소리를 세 프레임 흘려 히스테리시스(대화음·환경음은 두 프레임)를 넘긴 뒤의 결과를 돌려준다.
         * 후처리는 환경음에서 시작하므로, 한 프레임만 보면 "환경음이 나왔다" 는 확인이 아무것도 증명하지 못한다.
         * 그래서 환경음을 기대하는 검사는 프레임 하나의 판정([GunshotBoosterDecision.Result.postBoosterCoarse])도 함께 본다.
         * @param gunshotScore null 이면 Booster 를 불러오지 못한 경로.
         */
        private fun run(probabilities: FloatArray, gunshotScore: Float?): Pair<GunshotBoosterDecision.Result, AiPostProcessor.FrameResult> {
            val postProcessor = AiPostProcessor()
            var last: Pair<GunshotBoosterDecision.Result, AiPostProcessor.FrameResult>? = null
            repeat(3) {
                val pre = classifier.classify(probabilities)
                val decision = if (gunshotScore == null) {
                    GunshotBoosterDecision.unavailable(probabilities, classNames, pre)
                } else {
                    GunshotBoosterDecision.decide(probabilities, classNames, pre, gunshotScore)
                }
                val topKSummary = pre.top5.joinToString(separator = " | ") { it.name }
                val post = postProcessor.process(
                    AiPostProcessor.FrameInput(
                        coarse = decision.postBoosterCoarse,
                        display = decision.postBoosterDisplay,
                        confidence = decision.postBoosterConfidence,
                        adoptedDangerFromBooster = decision.accepted,
                        dangerCuePromoted = decision.dangerCuePromoted,
                        hasStrongDangerCue = decision.hasStrongDangerCue,
                        hasCriticalDangerCue = AiPostProcessor.isCriticalDangerEvent(decision.postBoosterDisplay, topKSummary),
                        topKSummary = topKSummary
                    )
                )
                last = decision to post
            }
            return last!!
        }

        private fun <T> withOverrides(overrides: Map<String, String>, block: () -> T): T {
            YamnetThreeClassMapper.userOverrides = overrides
            return try {
                block()
            } finally {
                YamnetThreeClassMapper.userOverrides = emptyMap()
            }
        }
    }

    @After
    fun clearOverrides() {
        YamnetThreeClassMapper.userOverrides = emptyMap()
    }

    @Test
    fun `아무것도 바꾸지 않으면 모든 소리의 매핑이 키워드 규칙과 같다`() {
        assertTrue(YamnetThreeClassMapper.userOverrides.isEmpty())
        classNames.forEach { name ->
            assertEquals(name, YamnetThreeClassMapper.defaultCoarse(name), YamnetThreeClassMapper.mapDisplayNameToCoarse(name))
        }
    }

    @Test
    fun `Booster 와 강한 단서의 키워드 소리는 모두 기본이 위협음이다`() {
        // 이것이 깨지면 "지금 위협음인 소리만 단서로 센다" 는 조건이 아무것도 바꾸지 않은 사용자에게서도
        // 단서를 거르게 되어, 분류 탭을 만들기 전과 판정이 달라진다.
        val keywordLabels = classNames.filter {
            GunshotBoosterDecision.isGunshotKeyword(it) || GunshotBoosterDecision.isStrongDangerKeyword(it)
        }
        assertTrue("키워드에 걸리는 소리가 있어야 이 검사가 의미가 있다", keywordLabels.size >= 10)
        keywordLabels.forEach { name ->
            assertEquals("$name 의 기본 종류", AiClassification.DANGER, YamnetThreeClassMapper.defaultCoarse(name))
        }
    }

    @Test
    fun `바꾼 종류가 매핑과 투표에 쓰이고 기본 종류는 그대로다`() {
        withOverrides(mapOf("Doorbell" to AiClassification.DANGER, "Walk, footsteps" to AiClassification.AMBIENT)) {
            assertEquals(AiClassification.DANGER, YamnetThreeClassMapper.mapDisplayNameToCoarse("Doorbell"))
            assertEquals(AiClassification.AMBIENT, YamnetThreeClassMapper.defaultCoarse("Doorbell"))
            assertEquals(AiClassification.DANGER, classifier.classify(probs("Doorbell" to 1f)).coarse)

            assertEquals(AiClassification.AMBIENT, YamnetThreeClassMapper.mapDisplayNameToCoarse("Walk, footsteps"))
            assertEquals(AiClassification.DANGER, YamnetThreeClassMapper.defaultCoarse("Walk, footsteps"))
            assertEquals(AiClassification.AMBIENT, classifier.classify(probs("Walk, footsteps" to 1f)).coarse)

            // 바꾸지 않은 소리는 규칙 그대로
            assertEquals(AiClassification.DANGER, classifier.classify(probs("Siren" to 1f)).coarse)
        }
    }

    @Test
    fun `위협음으로 올린 소리는 화면 라벨까지 위협음으로 나온다`() {
        withOverrides(mapOf("Doorbell" to AiClassification.DANGER)) {
            for (score in listOf(null, 0f, 1f)) {
                val (_, post) = run(probs("Doorbell" to 0.9f), score)
                assertEquals("Booster 점수 $score", AiClassification.DANGER, post.uiCoarse)
            }
        }
    }

    @Test
    fun `대화음을 환경음으로 바꾸면 말소리가 환경음으로 나온다`() {
        val speech = probs("Speech" to 0.9f)
        assertEquals("바꾸기 전", AiClassification.SPEECH, run(speech, 0f).second.confirmedCoarse)
        withOverrides(mapOf("Speech" to AiClassification.AMBIENT)) {
            val (decision, post) = run(speech, 1f)
            assertFalse("말소리에서는 Booster 가 막혀 있어야 한다: ${decision.reason}", decision.accepted)
            assertEquals(AiClassification.AMBIENT, decision.postBoosterCoarse)
            assertEquals(AiClassification.AMBIENT, post.uiCoarse)
        }
    }

    @Test
    fun `다른 소리를 대화음으로 옮겨도 그 밑의 총소리와 사이렌은 위협음으로 나온다`() {
        // 말소리 프레임에서 Booster 를 막는 규칙은 소리의 성질이라 키워드 규칙으로 판단한다. 사용자가 음악·TV 를
        // 대화음 색으로 보려고 옮겼다고 그 밑의 위협음까지 사라지면 안 된다.
        val cases = listOf(
            Triple(mapOf("Music" to AiClassification.SPEECH), probs("Music" to 0.5f, "Gunshot, gunfire" to 0.45f), 1f),
            Triple(mapOf("Music" to AiClassification.SPEECH), probs("Music" to 0.6f, "Siren" to 0.1f), 0f),
            Triple(
                mapOf("Television" to AiClassification.SPEECH),
                probs("Television" to 0.5f, "Smoke detector, smoke alarm" to 0.1f),
                null
            )
        )
        cases.forEach { (overrides, frame, score) ->
            withOverrides(overrides) {
                val (decision, post) = run(frame, score)
                assertEquals("$overrides 점수 $score: ${decision.reason}", AiClassification.DANGER, post.uiCoarse)
            }
        }
    }

    @Test
    fun `위협음으로 올린 소리가 묻힌 사이렌을 가리지 않는다`() {
        // 초인종을 위협음으로 올려도, 음악에 묻힌 사이렌이 강한 단서로 위협음이 되는 길은 그대로여야 한다.
        // 올린 소리가 묻힌 위협음 찾기에 끼면 제 작은 확률(0.10)로 표시를 차지해 낮은 확신도 막음(0.12)에 걸린다.
        val frame = probs("Music" to 0.3f, "Doorbell" to 0.10f, "Siren" to 0.06f)
        assertEquals("바꾸기 전", AiClassification.DANGER, run(frame, 0f).second.uiCoarse)
        withOverrides(mapOf("Doorbell" to AiClassification.DANGER)) {
            for (score in listOf(null, 0f)) {
                val (decision, post) = run(frame, score)
                assertEquals("Booster 점수 $score: ${decision.reason}", AiClassification.DANGER, post.uiCoarse)
            }
        }
    }

    @Test
    fun `위협음에서 뺀 총소리로는 Booster 가 위협음을 올리지 않는다`() {
        // 음악이 1위라 투표는 환경음이고, Booster 가 총소리 단서와 점수를 보고 위협음으로 올리는 경로다.
        val mix = probs("Music" to 0.5f, "Gunshot, gunfire" to 0.45f)
        val (before, beforePost) = run(mix, 1f)
        assertTrue("바꾸기 전에는 Booster 가 받아들인다: ${before.reason}", before.accepted)
        assertEquals(AiClassification.DANGER, beforePost.uiCoarse)

        withOverrides(mapOf("Gunshot, gunfire" to AiClassification.AMBIENT)) {
            for (score in listOf(null, 0f, 1f)) {
                val (decision, post) = run(mix, score)
                assertFalse("Booster 점수 $score: ${decision.reason}", decision.accepted)
                assertFalse("Booster 점수 $score: 강한 단서로도 올리지 않는다", decision.dangerCuePromoted)
                assertEquals("Booster 점수 $score", AiClassification.AMBIENT, decision.postBoosterCoarse)
                assertEquals("Booster 점수 $score", AiClassification.AMBIENT, post.uiCoarse)
            }
        }
    }

    @Test
    fun `총소리 하나만 빼면 남은 총소리 단서는 그대로 센다`() {
        val mix = probs("Music" to 0.5f, "Gunshot, gunfire" to 0.3f, "Machine gun" to 0.15f)
        withOverrides(mapOf("Gunshot, gunfire" to AiClassification.AMBIENT)) {
            val (decision, post) = run(mix, 1f)
            assertTrue("기관총은 여전히 위협음이라 단서다: ${decision.reason}", decision.accepted)
            assertEquals("Booster 가 고르는 이름도 뺀 소리가 아니다", "Machine gun", decision.postBoosterDisplay)
            assertEquals(AiClassification.DANGER, post.uiCoarse)
        }
    }

    @Test
    fun `위협음에서 뺀 사이렌은 강한 단서로도 올라가지 않는다`() {
        // 음악에 묻힌 사이렌. 투표는 환경음이지만 강한 단서(사이렌 5% 이상)가 위협음으로 올리는 경로다.
        val mix = probs("Music" to 0.6f, "Siren" to 0.1f)
        val (before, beforePost) = run(mix, 0f)
        assertTrue("바꾸기 전에는 강한 단서로 올린다: ${before.reason}", before.dangerCuePromoted)
        assertEquals(AiClassification.DANGER, beforePost.uiCoarse)

        withOverrides(mapOf("Siren" to AiClassification.AMBIENT)) {
            for (score in listOf(null, 0f)) {
                val (decision, post) = run(mix, score)
                assertFalse("Booster 점수 $score", decision.dangerCuePromoted)
                assertEquals("Booster 점수 $score", AiClassification.AMBIENT, decision.postBoosterCoarse)
                assertEquals("Booster 점수 $score", AiClassification.AMBIENT, post.uiCoarse)
            }
        }
    }
}

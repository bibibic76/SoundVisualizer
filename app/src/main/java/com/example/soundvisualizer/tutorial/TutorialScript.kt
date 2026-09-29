package com.example.soundvisualizer.tutorial

import com.example.soundvisualizer.AiClassification
import com.example.soundvisualizer.ModeSettings
import com.example.soundvisualizer.VisualMode
import com.example.soundvisualizer.VisualizerInputs
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 튜토리얼 그림 한 쪽의 소리 대본. [cycleSec] 초마다 처음부터 되풀이한다.
 *
 * @param stillAtSec 그림을 멈춰 둘 때(시스템에서 애니메이션을 껐거나 멈춤을 눌렀을 때, 옆 쪽을 미리 그려 둘 때)
 *   보여 줄 장면의 시각. 그 쪽이 말하려는 것이 가장 잘 보이는 순간을 고른다.
 */
enum class TutorialScene(val cycleSec: Float, val stillAtSec: Float) {
    /** 무엇을 하는 앱인지. 1초마다 한 번씩 소리가 쿵 울리고, 왼쪽·오른쪽으로 조금씩 번갈아 기운다. */
    Sound(cycleSec = 4f, stillAtSec = 2.4f),

    /** 방향. 왼쪽에서 나다가 잠깐 쉬고 오른쪽에서 난다. */
    Direction(cycleSec = 4.4f, stillAtSec = 1.3f),

    /** 종류. 환경음 → 대화음 → 위협음. */
    Types(cycleSec = 6.6f, stillAtSec = 4.75f),

    /** 진동. 조용한 환경음 뒤에 위협음이 터지고, 그때 폰이 떤다. */
    Vibration(cycleSec = 4.2f, stillAtSec = 1.75f)
}

/**
 * 쪽마다 엔진에 흘려 줄 소리. 캡처 서비스가 오버레이 엔진에 주는 것과 같은 모양(좌우 피크 0~1, AI 라벨)이라
 * 튜토리얼의 그림은 앱이 실제로 그리는 모양 그대로다.
 *
 * - 피크는 파형이 아니라 **그사이 가장 큰 크기**다. 그래서 오르내림은 50ms 보다 짧게 만들지 않는다.
 * - 번쩍임은 1초에 세 번보다 적게 둔다(WCAG 2.3.1). 위협음은 빨간색이라 특히 그렇다.
 *
 * 모두 시각만 받는 순수 함수다. 같은 시각에는 늘 같은 값을 내므로 기기 없이 검사할 수 있다(TutorialScriptTest).
 */
object TutorialScript {

    /** 한 대본 안의 시각. 음수나 한 바퀴를 넘는 값도 받는다. */
    fun loopTime(scene: TutorialScene, t: Float): Float {
        val c = scene.cycleSec
        return ((t % c) + c) % c
    }

    /** [out] 의 앞 두 칸에 `[좌 피크, 우 피크]` 를 채운다. */
    fun peaks(scene: TutorialScene, t: Float, out: FloatArray) {
        val s = loopTime(scene, t)
        var l: Float
        var r: Float
        when (scene) {
            TutorialScene.Sound -> {
                // 1초에 한 번 0.45초 동안 이어지는 쿵. 짧게 치고 마는 소리는 엔진이 부드럽게 따라가는 사이에 묻힌다.
                // 한 번은 왼쪽, 한 번은 오른쪽으로 조금 기울여 테두리 전체가 숨 쉬듯 움직이게 한다.
                val index = floor(s).toInt()
                val level = 0.06f + 0.72f * thump(s - index, hold = 0.45f, release = 0.12f)
                if (index % 2 == 0) {
                    l = level; r = level * 0.72f
                } else {
                    l = level * 0.72f; r = level
                }
            }
            TutorialScene.Direction -> {
                val side = directionSide(s)
                val level = directionLevel(s)
                // 반대쪽에도 조금 들리게 둔다. 한쪽만 있는 소리는 실제로 드물다.
                l = if (side < 0) level else level * 0.15f
                r = if (side > 0) level else level * 0.15f
            }
            TutorialScene.Types -> {
                when {
                    s < TYPE_SEGMENT_SEC -> {
                        // 환경음: 잔잔하게 이어지는 소리
                        val level = 0.45f + 0.1f * sin(2f * PI.toFloat() * 0.8f * s)
                        l = level; r = level
                    }
                    s < 2 * TYPE_SEGMENT_SEC -> {
                        // 대화음: 말소리처럼 오르내린다(초당 두어 번)
                        val u = s - TYPE_SEGMENT_SEC
                        val syllable = max(0f, sin(2f * PI.toFloat() * 2.2f * u))
                        val level = 0.3f + 0.35f * syllable * syllable
                        l = level; r = level * 0.95f
                    }
                    else -> {
                        // 위협음: 총소리 세 발. 오른쪽에서 난다.
                        val level = 0.2f + 0.7f * hits(s - 2 * TYPE_SEGMENT_SEC, TYPE_DANGER_HITS)
                        l = level * 0.65f; r = level
                    }
                }
            }
            TutorialScene.Vibration -> {
                if (s >= VIBRATION_DANGER_START && s < VIBRATION_DANGER_END) {
                    val level = 0.15f + 0.75f * hits(s - VIBRATION_DANGER_START, VIBRATION_DANGER_HITS)
                    l = level; r = level * 0.85f
                } else {
                    // 조용한 환경음
                    val level = 0.12f + 0.04f * sin(2f * PI.toFloat() * 0.8f * s)
                    l = level; r = level
                }
            }
        }
        out[0] = l.coerceIn(0f, 1f)
        out[1] = r.coerceIn(0f, 1f)
    }

    /** 그 시각에 AI 가 붙였을 라벨([AiClassification] 의 라벨). */
    fun label(scene: TutorialScene, t: Float): String {
        val s = loopTime(scene, t)
        return when (scene) {
            TutorialScene.Sound, TutorialScene.Direction -> AiClassification.AMBIENT
            TutorialScene.Types -> when {
                s < TYPE_SEGMENT_SEC -> AiClassification.AMBIENT
                s < 2 * TYPE_SEGMENT_SEC -> AiClassification.SPEECH
                else -> AiClassification.DANGER
            }
            TutorialScene.Vibration ->
                if (s >= VIBRATION_DANGER_START && s < VIBRATION_DANGER_END) AiClassification.DANGER
                else AiClassification.AMBIENT
        }
    }

    /**
     * 방향 쪽에서 소리가 나는 곳. 왼쪽이면 -1, 오른쪽이면 1, 쉬는 중이면 0.
     * 그림이 장면 안의 그 자리에 소리 표시를 띄운다.
     */
    fun sourceSide(scene: TutorialScene, t: Float): Int =
        if (scene == TutorialScene.Direction) directionSide(loopTime(scene, t)) else 0

    /** 방향 쪽 소리의 세기(0~1). 소리 표시의 진하기로 쓴다. */
    fun sourceLevel(scene: TutorialScene, t: Float): Float =
        if (scene == TutorialScene.Direction) directionLevel(loopTime(scene, t)) else 0f

    /** 방향 쪽 소리 표시에서 박자마다 새로 퍼지는 파문이 퍼진 정도(0~1). 쉬는 중이면 -1. */
    fun sourceRipple(scene: TutorialScene, t: Float): Float {
        if (scene != TutorialScene.Direction) return -1f
        val s = loopTime(scene, t)
        val start = directionStart(s) ?: return -1f
        return ((s - start) % DIRECTION_BEAT_SEC) / DIRECTION_BEAT_SEC
    }

    /**
     * 그 시각에 폰이 떨고 있는지. 기본 위협음 진동(강하게 두 번)처럼 짧게 두 번 떤다. 같은 종류는 2초 안에
     * 다시 울리지 않으므로(HapticPolicy) 위협음이 이어져도 한 번만 떤다.
     * 튜토리얼은 진동을 실제로 울리지 않고 그림으로만 보여 준다.
     */
    fun vibrating(scene: TutorialScene, t: Float): Boolean {
        if (scene != TutorialScene.Vibration) return false
        val u = loopTime(scene, t) - VIBRATION_DANGER_START
        return (u >= 0f && u < BUZZ_SEC) || (u >= BUZZ_SEC + BUZZ_GAP_SEC && u < 2 * BUZZ_SEC + BUZZ_GAP_SEC)
    }

    // ---------------- 대본의 조각 ----------------

    private const val TYPE_SEGMENT_SEC = 2.2f
    private val TYPE_DANGER_HITS = floatArrayOf(0.1f, 0.8f, 1.5f)

    private const val DIRECTION_LEFT_END = 1.6f
    private const val DIRECTION_RIGHT_START = 2.2f
    private const val DIRECTION_RIGHT_END = 3.8f
    private const val DIRECTION_BEAT_SEC = 0.5f

    private const val VIBRATION_DANGER_START = 1.5f
    private const val VIBRATION_DANGER_END = 2.9f
    private val VIBRATION_DANGER_HITS = floatArrayOf(0f, 0.7f)
    private const val BUZZ_SEC = 0.14f
    private const val BUZZ_GAP_SEC = 0.1f

    private fun directionSide(s: Float): Int = when {
        s < DIRECTION_LEFT_END -> -1
        s >= DIRECTION_RIGHT_START && s < DIRECTION_RIGHT_END -> 1
        else -> 0
    }

    /** 방향 쪽에서 지금 나는 소리가 시작한 시각. 쉬는 중이면 null. */
    private fun directionStart(s: Float): Float? = when (directionSide(s)) {
        -1 -> 0f
        1 -> DIRECTION_RIGHT_START
        else -> null
    }

    private fun directionLevel(s: Float): Float {
        val start = directionStart(s) ?: return 0f
        val u = s - start
        // 소리가 시작할 때 한 프레임에 튀어 오르지 않게 짧게 키운다.
        val attack = min(1f, u / 0.06f)
        return attack * (0.45f + 0.3f * exp(-(u % DIRECTION_BEAT_SEC) / 0.2f))
    }

    /** 쿵 하나: 짧게 올라 [hold] 초 이어지다 [release] 초 간격으로 줄어든다. [u] 는 친 뒤 흐른 시간. */
    private fun thump(u: Float, hold: Float, release: Float): Float = when {
        u < 0f -> 0f
        u < hold -> min(1f, u / 0.04f)
        else -> exp(-(u - hold) / release)
    }

    /** [starts] 시각마다 한 번씩 크게 치고 0.25초 이어지다 사그라드는 소리. [u] 가 첫 번째보다 앞이면 0. */
    private fun hits(u: Float, starts: FloatArray): Float {
        var last = -1f
        for (start in starts) if (u >= start) last = start
        if (last < 0f) return 0f
        return thump(u - last, hold = 0.25f, release = 0.15f)
    }
}

/**
 * [TutorialScript] 를 오버레이 엔진이 읽는 입력으로 잇는다. 엔진은 앱이 쓰는 것과 같은 [com.example.soundvisualizer.VisualizerEngine] 이다.
 *
 * 모드는 기본값인 파도로 고정하고, 사용자가 꺼 둔 종류도 모두 그린다. 튜토리얼은 앱이 무엇을 하는지 보여 주는
 * 곳이라 지금 설정이 아니라 처음 설정의 모양을 보여 준다. 색만은 사용자가 고른 색을 쓴다. 설정 탭에서 보는 색과
 * 범례가 달라지면 안 되기 때문이다.
 *
 * 실제 소리([com.example.soundvisualizer.LiveVisualizerInputs])와 오버레이를 깨우는 신호(OverlayWake)에는 닿지 않는다.
 * 튜토리얼을 켜 둔 채 시각화가 돌아도 둘이 서로를 건드리지 않는다.
 *
 * @param colors 라벨 → ARGB 색.
 */
class TutorialDemoInputs(
    private val scene: TutorialScene,
    private val colors: (String) -> Int
) : VisualizerInputs {

    /** 대본의 지금 시각(초). 그림이 프레임마다 옮긴다. */
    var timeSec = 0f

    override fun readPeaks(out: FloatArray) {
        TutorialScript.peaks(scene, timeSec, out)
        // 버퍼가 새로 도착한 것으로 알린다. 그래야 엔진이 피크를 감쇠 없이 그대로 받는다.
        out[2] = 1f
    }

    override fun currentMode(): VisualMode = VisualMode.Wave

    override fun settingsFor(mode: VisualMode): ModeSettings = settings

    override fun coarseLabel(): String = TutorialScript.label(scene, timeSec)

    override fun colorFor(label: String): Int = colors(label)

    override fun isShown(label: String): Boolean = true

    /** 엔진이 틱마다 읽는다. 사용자의 모드 설정과 섞이지 않게 따로 만들어 둔다. */
    private val settings = demoSettings()

    companion object {
        /**
         * 그림의 모드 설정. 기본값([ModeSettings])에서 진하기와 반응만 올렸다. 폰 그림이 실제 화면보다 훨씬 작아서,
         * 기본값 그대로면 어두운 장면 위의 빨간 위협음이 흐리고 박자가 잘 읽히지 않는다.
         */
        fun demoSettings(): ModeSettings = ModeSettings(intensity = 55f, opacity = 80f, speed = 20f, sensitivity = 40f)
    }
}

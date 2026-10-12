package com.example.soundvisualizer.tutorial

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.example.soundvisualizer.R
import com.example.soundvisualizer.SettingsManager
import kotlin.math.floor
import kotlin.math.max

/**
 * 한 쪽 그림의 소리(#327). 그림이 프레임마다 대본 시각을 [onFrame] 으로 넘기면, 그사이 지난 소리([TutorialScript.cues])를
 * 낸다. 그래서 소리가 그림의 박자와 같은 프레임에 시작하고, 그림이 멈추면(쪽을 넘김·멈춤 버튼·앱을 내림) 다음 소리도 나지 않는다.
 *
 * 늦은 소리는 내지 않는다. 앱을 내렸다 돌아오면 그림의 시각이 그동안 흐른 만큼 건너뛰는데, 그사이의 소리를 한꺼번에 내면
 * 소리가 겹쳐 쏟아진다. 소리를 도중에 켰을 때도 지난 소리를 몰아 내지 않는다.
 *
 * 메인 스레드에서만 쓴다.
 *
 * @param canPlay 지금 소리를 내도 되는지. 아니면 그 소리는 건너뛴다.
 * @param play 소리 하나를 낸다.
 * @param cancel 나던 소리를 모두 멈춘다.
 */
internal class TutorialSounds(
    private val cues: List<TutorialCue>,
    private val cycleSec: Float,
    private val canPlay: () -> Boolean,
    private val play: (TutorialCue) -> Unit,
    private val cancel: () -> Unit
) : TutorialFrameFollower {
    private var lastT = BEFORE_START

    override fun onFrame(t: Float) {
        val from = lastT
        lastT = t
        if (t <= from) return
        for (cycle in floor(max(from, 0f) / cycleSec).toInt()..floor(t / cycleSec).toInt()) {
            for (cue in cues) {
                val at = cycle * cycleSec + cue.atSec
                if (at > from && at <= t && t - at <= MAX_LATE_SEC && canPlay()) play(cue)
            }
        }
    }

    /** 나던 소리를 멈추고, 다시 움직이면 처음부터 센다. */
    override fun stop() {
        lastT = BEFORE_START
        cancel()
    }

    companion object {
        /** 처음 그리는 프레임(0초)의 소리도 내도록 그보다 조금 앞에서 센다. */
        private const val BEFORE_START = -0.001f

        /** 이보다 늦게 알게 된 소리는 내지 않는다. 60fps 의 몇 프레임이 밀려도 낼 만큼. */
        const val MAX_LATE_SEC = 0.15f
    }
}

/**
 * 튜토리얼 소리를 내는 [SoundPool]. 소리 켜기를 누르면 만들고, 끄거나 튜토리얼을 닫으면 [close] 한다.
 *
 * - 미디어 볼륨을 따른다.
 * - 우리 캡처(내부 소리)가 듣지 않게 캡처를 막는다. 튜토리얼 그림은 실제 오버레이와 섞이지 않는다.
 * - 조각은 만들 때 모두 읽어 둔다(합쳐 약 60KB). 다 읽기 전의 소리는 건너뛴다.
 *
 * 메인 스레드에서만 쓴다. 읽기가 끝났다는 알림도 만든 스레드(메인)로 온다.
 */
internal class TutorialSoundPlayer(context: Context) {

    private val pool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                .build()
        )
        .build()
    private val loaded = HashSet<Int>()
    private val streams = ArrayDeque<Int>()
    private var closed = false
    private val ids: Map<TutorialClip, Int>

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
        ids = TutorialClip.entries.associateWith { pool.load(context, rawRes(it), 1) }
    }

    fun play(cue: TutorialCue) {
        if (closed) return
        val id = ids[cue.clip] ?: return
        if (id !in loaded) return
        val stream = pool.play(id, cue.left, cue.right, 1, 0, 1f)
        if (stream == 0) return
        streams.addLast(stream)
        while (streams.size > MAX_STREAMS) streams.removeFirst()
    }

    fun stopAll() {
        if (closed) return
        for (stream in streams) pool.stop(stream)
        streams.clear()
    }

    fun close() {
        if (closed) return
        stopAll()
        closed = true
        pool.release()
    }

    private companion object {
        /** 한꺼번에 나는 소리 수. 경적 세 번이 겹쳐도 넉넉하다. */
        const val MAX_STREAMS = 4

        fun rawRes(clip: TutorialClip): Int = when (clip) {
            TutorialClip.Chirp -> R.raw.tutorial_chirp
            TutorialClip.Birds -> R.raw.tutorial_birds
            TutorialClip.Talk -> R.raw.tutorial_talk
            TutorialClip.Honk -> R.raw.tutorial_honk
        }
    }
}

/**
 * 그 쪽의 [TutorialSounds]. 소리를 꺼 두었으면([player] 가 null) null 이다.
 *
 * 시각화가 실행 중이면 소리를 내지 않는다. 외부 사운드 모드에서는 마이크가 튜토리얼 소리를 듣고 실제 오버레이와
 * 진동이 반응하기 때문이다(튜토리얼 진동과 같은 규칙).
 */
@Composable
internal fun rememberTutorialSounds(scene: TutorialScene, player: TutorialSoundPlayer?): TutorialSounds? =
    remember(scene, player) {
        player ?: return@remember null
        TutorialSounds(
            TutorialScript.cues(scene),
            scene.cycleSec,
            canPlay = { !SettingsManager.isServiceRunning.value },
            play = player::play,
            cancel = player::stopAll
        )
    }

package com.example.soundvisualizer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/**
 * 이 기기의 오디오 서버가 마이크 녹음에 캡처 시각 표시를 주는지 잰다(#352 폰 확인 1단계). 진동도 소리도 내지 않고, 들은 소리는
 * 저장하지 않고 버린다.
 *
 * 외부 사운드 모드의 AI 입력은 우리 진동이 든 구간을 캡처 시각으로 뺀다([CaptureClock]). 시각 표시
 * (`AudioRecord.getTimestamp(…, TIMEBASE_BOOTTIME)`)를 받지 못하거나 틀린 기기는 어림으로 돌고 구간 끝을 60ms 늘린다. 앱과 같은
 * 형식(VOICE_RECOGNITION, 48kHz, 스테레오, float)으로 3초 동안 512 프레임씩 읽으며, 앱의 [CaptureClock] 에 그대로 흘려 물은 수와
 * 받은 수, 받아들인 기준, 입력 지연(어림 − 시각 표시)을 `SvClockProbe` 로그로 남긴다. 기대: 90% 넘게 받고 지연은 5~100ms.
 *
 * 기본으로는 건너뛴다. 마이크를 열기 때문이다. 이렇게 돌리고 `SvClockProbe` 로그를 본다.
 * ```
 * adb shell am instrument -w -e clockProbe true \
 *   -e class com.example.soundvisualizer.CaptureClockProbeInstrumentedTest \
 *   com.example.soundvisualizer.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class CaptureClockProbeInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    // 권한은 아래에서 준다.
    @SuppressLint("MissingPermission")
    @Test
    fun logCaptureTimestamps() {
        assumeTrue(
            "마이크를 여는 확인이라 기본으로는 건너뛴다. -e clockProbe true 로 돌린다",
            InstrumentationRegistry.getArguments().getString("clockProbe") == "true"
        )
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)

        val mask = AudioFormat.CHANNEL_IN_STEREO
        val min = AudioRecord.getMinBufferSize(RATE, mask, AudioFormat.ENCODING_PCM_FLOAT)
        assumeTrue("getMinBufferSize=$min", min > 0)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(RATE)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(max(min * 2, FLOATS * 4 * 4))
            .build()
        try {
            assumeTrue("AudioRecord 를 열지 못했다", record.state == AudioRecord.STATE_INITIALIZED)
            record.startRecording()
            val clock = CaptureClock(record.sampleRate)
            val stamp = AudioTimestamp()
            var asked = 0
            var stamped = 0
            var lastOk = false
            val poll: (LongArray) -> Boolean = { out ->
                asked++
                val ok = record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS
                if (ok) {
                    stamped++
                    out[0] = stamp.framePosition
                    out[1] = stamp.nanoTime
                }
                lastOk = ok
                ok
            }
            val buffer = FloatArray(FLOATS)
            val end = SystemClock.elapsedRealtime() + PROBE_MS
            var frames = 0L
            while (SystemClock.elapsedRealtime() < end) {
                val n = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                val readNs = SystemClock.elapsedRealtimeNanos()
                if (n <= 0) break
                val before = clock.polls
                clock.onRead(n / 2, readNs, poll)
                frames += n / 2
                if (clock.polls != before) {
                    Log.i(
                        TAG,
                        "poll ${clock.polls}: stamped=$lastOk mode=${clock.mode} " +
                            "lat=${"%.1f".format(clock.latencyMs)}ms anchors=${clock.anchors} rejects=${clock.rejects}"
                    )
                }
            }
            record.stop()
            Log.i(
                TAG,
                "sr=${record.sampleRate} ch=${record.channelCount} frames=$frames asked=$asked stamped=$stamped " +
                    "anchors=${clock.anchors} rejects=${clock.rejects} fails=${clock.failures} mode=${clock.mode} " +
                    "lat=${"%.1f".format(clock.latencyMs)}ms"
            )
        } finally {
            record.release()
        }
    }

    private companion object {
        const val TAG = "SvClockProbe"
        const val RATE = 48000
        const val FLOATS = 1024
        const val PROBE_MS = 3000L
    }
}

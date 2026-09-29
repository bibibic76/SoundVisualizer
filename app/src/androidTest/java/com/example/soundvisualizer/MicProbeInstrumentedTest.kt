package com.example.soundvisualizer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneInfo
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 이 기기의 마이크가 무엇을 주는지 로그로 남긴다(#226). 소리를 내지 않고, 들은 소리는 크기만 계산하고 버린다.
 *
 * 외부 사운드 모드의 두 결정이 이 결과에 달려 있다.
 * - 방향: 서로 다른 두 마이크가 따로 들어오는지(채널 수, 좌우 상관, 마이크 위치). 많은 기기가 모노를 복제해 준다.
 * - 입력 이득: 조용한 곳의 잡음 크기와 말소리 크기.
 *
 * 기본으로는 건너뛴다. 소스마다 3초씩 마이크를 열기 때문이다. 이렇게 돌리고 `SvMicProbe` 로그를 본다.
 * ```
 * adb shell am instrument -w -e micProbe true \
 *   -e class com.example.soundvisualizer.MicProbeInstrumentedTest \
 *   com.example.soundvisualizer.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class MicProbeInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun logMicrophones() {
        assumeTrue(
            "마이크를 여는 확인이라 기본으로는 건너뛴다. -e micProbe true 로 돌린다",
            InstrumentationRegistry.getArguments().getString("micProbe") == "true"
        )
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)

        val manager = context.getSystemService(AudioManager::class.java)
        for (mic in manager.microphones) {
            Log.i(
                TAG,
                "mic id=${mic.id} type=${mic.type} addr=${mic.address} location=${mic.location} " +
                    "pos=${xyz(mic.position)} orient=${xyz(mic.orientation)} desc=${mic.description} " +
                    "channels=${mic.channelMapping}"
            )
        }
        Log.i(TAG, "unprocessed supported=${manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)}")

        for ((name, source) in SOURCES) {
            for ((maskName, mask) in MASKS) probe(name, source, maskName, mask)
        }
    }

    // 권한은 위에서 준다.
    @SuppressLint("MissingPermission")
    private fun probe(name: String, source: Int, maskName: String, mask: Int) {
        val min = AudioRecord.getMinBufferSize(RATE, mask, AudioFormat.ENCODING_PCM_FLOAT)
        if (min <= 0) {
            Log.i(TAG, "$name/$maskName: getMinBufferSize=$min")
            return
        }
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(RATE)
                        .setChannelMask(mask)
                        .build()
                )
                .setBufferSizeInBytes(max(min * 2, 8192))
                .build()
        } catch (e: Exception) {
            Log.i(TAG, "$name/$maskName: build failed ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.i(TAG, "$name/$maskName: not initialized")
            record.release()
            return
        }
        try {
            record.startRecording()
            val channels = record.channelCount
            val buffer = FloatArray(1024 * channels)
            val sumSq = DoubleArray(channels)
            val peak = FloatArray(channels)
            var cross = 0.0
            var frames = 0L
            var zeros = 0L
            val end = SystemClock.uptimeMillis() + PROBE_MS
            // 처음 0.3초는 버린다. 마이크가 켜지며 들어오는 꺼짐 소리와 0 이 섞인다.
            val warmUpEnd = SystemClock.uptimeMillis() + 300
            while (SystemClock.uptimeMillis() < end) {
                val n = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) break
                if (SystemClock.uptimeMillis() < warmUpEnd) continue
                var i = 0
                while (i + channels <= n) {
                    for (c in 0 until channels) {
                        val v = buffer[i + c]
                        sumSq[c] += (v * v).toDouble()
                        peak[c] = max(peak[c], abs(v))
                        if (v == 0f) zeros++
                    }
                    if (channels == 2) cross += (buffer[i] * buffer[i + 1]).toDouble()
                    frames++
                    i += channels
                }
            }
            val silenced = record.activeRecordingConfiguration?.isClientSilenced
            val active = try {
                record.activeMicrophones.joinToString { "${it.id}@${xyz(it.position)}" }
            } catch (e: Exception) {
                "?"
            }
            record.stop()
            val rms = sumSq.map { sqrt(it / max(frames, 1)) }
            val corr = if (channels == 2 && sumSq[0] > 0 && sumSq[1] > 0) cross / sqrt(sumSq[0] * sumSq[1]) else Double.NaN
            Log.i(
                TAG,
                "$name/$maskName: sr=${record.sampleRate} ch=$channels frames=$frames " +
                    "rmsDb=${rms.joinToString { "%.1f".format(db(it)) }} " +
                    "peakDb=${peak.joinToString { "%.1f".format(db(it.toDouble())) }} " +
                    "corrLR=${"%.3f".format(corr)} zeros=$zeros silenced=$silenced activeMics=[$active]"
            )
        } finally {
            record.release()
        }
    }

    private fun xyz(c: MicrophoneInfo.Coordinate3F): String = "(%.3f, %.3f, %.3f)".format(c.x, c.y, c.z)

    private fun db(v: Double): Double = if (v <= 0.0) -200.0 else 20 * log10(v)

    private companion object {
        const val TAG = "SvMicProbe"
        const val RATE = 48000
        const val PROBE_MS = 3000L

        val SOURCES = listOf(
            "MIC" to MediaRecorder.AudioSource.MIC,
            "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
            "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED
        )
        val MASKS = listOf(
            "stereo" to AudioFormat.CHANNEL_IN_STEREO,
            "mono" to AudioFormat.CHANNEL_IN_MONO
        )
    }
}

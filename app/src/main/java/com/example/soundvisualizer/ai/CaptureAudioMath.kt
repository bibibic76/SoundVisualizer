package com.example.soundvisualizer.ai

import kotlin.math.ceil

/**
 * Capture→16k helpers (downmix + ResampleMonoFloatTo16kCustom).
 */
object CaptureAudioMath {

    const val TARGET_SAMPLE_RATE = 16000
    const val REQUIRED_MONO_16K_SAMPLES = AudioPreprocessor.REQUIRED_MONO_16K_SAMPLES // 15600

    fun captureSamplesForOneYamnetWindow(captureSampleRate: Int): Int {
        return ceil(
            REQUIRED_MONO_16K_SAMPLES * captureSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        ).toInt()
    }

    /**
     * Interleaved stereo/float PCM → mono frames.
     * channels==2 → (L+R)/2. Odd trailing sample dropped.
     */
    fun downmixInterleavedToMono(
        interleaved: FloatArray,
        floatCount: Int,
        channels: Int,
        dest: FloatArray,
        destOffset: Int = 0
    ): Int {
        if (floatCount <= 0 || channels <= 0) return 0
        val frames = floatCount / channels
        if (frames <= 0) return 0
        require(destOffset + frames <= dest.size)
        if (channels == 1) {
            System.arraycopy(interleaved, 0, dest, destOffset, frames)
            return frames
        }
        var si = 0
        var di = destOffset
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += interleaved[si++]
            }
            dest[di++] = sum / channels
        }
        return frames
    }

    /**
     * ResampleMonoFloatTo16kCustom — fixed dest length, linear interpolation.
     * Left-pad semantics are applied by feeding a right-padded source of the correct length.
     */
    fun resampleMonoFloatTo16kCustom(
        source: FloatArray,
        sourceLength: Int,
        sourceSampleRate: Int,
        destination: FloatArray,
        destLength: Int = REQUIRED_MONO_16K_SAMPLES
    ) {
        require(destination.size >= destLength)
        for (i in 0 until destLength) destination[i] = 0f
        if (sourceLength <= 0) return

        if (sourceSampleRate == TARGET_SAMPLE_RATE) {
            val copyLen = minOf(sourceLength, destLength)
            System.arraycopy(source, 0, destination, 0, copyLen)
            return
        }

        val factor = sourceSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        for (i in 0 until destLength) {
            val srcPos = i * factor
            val index1 = srcPos.toInt()
            val index2 = index1 + 1
            val alpha = (srcPos - index1).toFloat()
            if (index1 >= sourceLength) {
                destination[i] = 0f
            } else {
                val val1 = source[index1]
                val val2 = if (index2 < sourceLength) source[index2] else val1
                destination[i] = (1f - alpha) * val1 + alpha * val2
            }
        }
    }
}

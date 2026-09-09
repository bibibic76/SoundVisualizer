package com.example.soundvisualizer

import java.nio.ByteBuffer

object AudioEngine {
    init {
        System.loadLibrary("soundvisualizer")
    }

    external fun init()

    /** Interleaved stereo float PCM from a Kotlin array (compat path). */
    external fun pushAudioData(data: FloatArray, length: Int)

    /**
     * Interleaved stereo float PCM from a direct [ByteBuffer] (zero-copy path used by
     * [AudioCaptureService]). [floatCount] is the number of floats starting at index 0.
     */
    external fun pushAudioBuffer(buffer: ByteBuffer, floatCount: Int)

    /**
     * Fills [out] (size >= 3) with `[leftPeak, rightPeak, buffersPushedSinceLastCall]`
     * and resets the native accumulators. Peaks are max|sample| in 0..1.
     * No allocation per call.
     */
    external fun readPeaks(out: FloatArray)

    /** 1024-point FFT magnitude of the oldest ring-buffer data (reserved for the AI branch). */
    external fun getSpectrogram(): FloatArray?

    /** Frees C++ RingBuffer. Call only after the capture thread has stopped. */
    external fun destroy()
}

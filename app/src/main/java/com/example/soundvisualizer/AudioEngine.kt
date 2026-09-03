package com.example.soundvisualizer

object AudioEngine {
    init {
        System.loadLibrary("soundvisualizer")
    }

    external fun init()
    // Extracts real-time Left/Right RMS levels
    external fun getAudioLevels(): FloatArray?

    external fun pushAudioData(data: FloatArray, length: Int)
    external fun getSpectrogram(): FloatArray?
    // Frees C++ RingBuffer and KissFFT resources
    external fun destroy()
}

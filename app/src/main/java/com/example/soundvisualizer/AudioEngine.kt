package com.example.soundvisualizer

object AudioEngine {
    init {
        System.loadLibrary("soundvisualizer")
    }

    external fun init()
    external fun pushAudioData(data: FloatArray, length: Int)
    external fun getSpectrogram(): FloatArray?
    external fun destroy()
}

#include <jni.h>
#include <string>
#include <android/log.h>
#include <cmath>
#include "RingBuffer.h"
#include "kiss_fft.h"

#define LOG_TAG "SoundVisualizer_NDK"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global instance of the RingBuffer for audio data
// Capacity of 1 second for 44100Hz stereo (44100 * 2 floats)
static RingBuffer<float>* audioBuffer = nullptr;

extern "C"
JNIEXPORT void JNICALL
Java_com_example_soundvisualizer_AudioEngine_init(JNIEnv *env, jobject thiz) {
    if (audioBuffer == nullptr) {
        audioBuffer = new RingBuffer<float>(44100 * 2);
        LOGD("Audio RingBuffer initialized.");
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_soundvisualizer_AudioEngine_pushAudioData(JNIEnv *env, jobject thiz,
                                                           jfloatArray data, jint length) {
    if (audioBuffer == nullptr) return;

    jfloat* c_data = env->GetFloatArrayElements(data, nullptr);
    if (c_data != nullptr) {
        bool success = audioBuffer->push(c_data, length);
        if (!success) {
            // Buffer overflow, consider handling or logging
            // LOGE("Audio buffer overflow!");
        }
        env->ReleaseFloatArrayElements(data, c_data, JNI_ABORT);
    }
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_example_soundvisualizer_AudioEngine_getSpectrogram(JNIEnv *env, jobject thiz) {
    if (audioBuffer == nullptr) return nullptr;

    const int FFT_SIZE = 1024;
    float pcm_data[FFT_SIZE];
    
    // Attempt to read 1024 samples
    size_t readCount = audioBuffer->pop(pcm_data, FFT_SIZE);
    
    if (readCount < FFT_SIZE) {
        // Not enough data
        return nullptr;
    }

    // Allocate KissFFT
    kiss_fft_cfg cfg = kiss_fft_alloc(FFT_SIZE, 0, nullptr, nullptr);
    kiss_fft_cpx in[FFT_SIZE];
    kiss_fft_cpx out[FFT_SIZE];

    // Apply Hanning window & copy to complex input
    for (int i = 0; i < FFT_SIZE; i++) {
        float multiplier = 0.5f * (1.0f - cos(2.0f * M_PI * i / (FFT_SIZE - 1)));
        in[i].r = pcm_data[i] * multiplier;
        in[i].i = 0;
    }

    // Execute FFT
    kiss_fft(cfg, in, out);
    free(cfg);

    // Calculate magnitude (Spectrogram)
    int outSize = FFT_SIZE / 2;
    jfloatArray result = env->NewFloatArray(outSize);
    jfloat magnitude[outSize];
    
    for (int i = 0; i < outSize; i++) {
        magnitude[i] = sqrt(out[i].r * out[i].r + out[i].i * out[i].i);
    }
    
    env->SetFloatArrayRegion(result, 0, outSize, magnitude);
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_soundvisualizer_AudioEngine_destroy(JNIEnv *env, jobject thiz) {
    if (audioBuffer != nullptr) {
        delete audioBuffer;
        audioBuffer = nullptr;
        LOGD("Audio RingBuffer destroyed.");
    }
}

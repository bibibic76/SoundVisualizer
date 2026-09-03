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
std::atomic<float> currentLeftRms(0.0f);
std::atomic<float> currentRightRms(0.0f);

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

        // Calculate RMS for Left and Right channels
        float leftSumSq = 0;
        float rightSumSq = 0;
        int frames = length / 2;
        if (frames > 0) {
            for (int i = 0; i < length; i += 2) {
                leftSumSq += c_data[i] * c_data[i];
                if (i + 1 < length) {
                    rightSumSq += c_data[i+1] * c_data[i+1];
                }
            }
            float lRms = sqrt(leftSumSq / frames);
            float rRms = sqrt(rightSumSq / frames);
            // Smooth with previous value (Exponential Moving Average)
            currentLeftRms.store(lRms * 0.2f + currentLeftRms.load() * 0.8f, std::memory_order_relaxed);
            currentRightRms.store(rRms * 0.2f + currentRightRms.load() * 0.8f, std::memory_order_relaxed);
        }
        env->ReleaseFloatArrayElements(data, c_data, JNI_ABORT);
    }
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_example_soundvisualizer_AudioEngine_getAudioLevels(JNIEnv *env, jobject thiz) {
    jfloatArray result = env->NewFloatArray(2);
    jfloat levels[2];
    levels[0] = currentLeftRms.load(std::memory_order_relaxed);
    levels[1] = currentRightRms.load(std::memory_order_relaxed);
    env->SetFloatArrayRegion(result, 0, 2, levels);
    return result;
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

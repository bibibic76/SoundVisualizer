#include "RingBuffer.h"
#include "kiss_fft.h"
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <jni.h>

#define LOG_TAG "SoundVisualizer_NDK"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global instance of the RingBuffer for audio data
// Capacity of 1 second for 44100Hz stereo (44100 * 2 floats)
// NOTE: 링버퍼/스펙트로그램은 시각화가 사용하지 않는다 (AI 분류 브랜치용으로 유지).
static RingBuffer<float> *audioBuffer = nullptr;

// ---------------------------------------------------------------------------
// 좌/우 채널 피크. 버퍼 구간에서 채널별 max|sample| 을 취한다.
//
// 캡처 스레드(producer)가 버퍼마다 피크를 "마지막 readPeaks() 이후 최대값"으로 누적하고,
// 렌더 스레드(consumer)가 readPeaks() 로 읽으면서 0 으로 되돌린다.
// 프레임(16ms)마다 오디오 버퍼(11ms)가 1~2개 도착하므로 그 사이의 순간 피크를 놓치지 않는다.
//
// 순서 보장: producer 는 피크 → pushCount(release) 순으로 쓰고,
// consumer 는 pushCount(acquire) → 피크 순으로 읽는다.
// 따라서 pushCount > 0 이면 그 푸시의 피크는 반드시 함께 읽힌다 (한 프레임짜리 0 으로 꺼지는 현상 방지).
// ---------------------------------------------------------------------------
static std::atomic<float> peakLeft{0.0f};
static std::atomic<float> peakRight{0.0f};
static std::atomic<int> pushCount{0};

static inline void atomicMax(std::atomic<float> &target, float value) {
  float cur = target.load(std::memory_order_relaxed);
  while (value > cur &&
         !target.compare_exchange_weak(cur, value, std::memory_order_release,
                                       std::memory_order_relaxed)) {
  }
}

// 인터리브 스테레오 float 샘플 처리 (캡처 스레드에서만 호출)
static void processSamples(const float *samples, jint length) {
  if (length <= 0)
    return;

  if (audioBuffer != nullptr) {
    // 가득 차면 조용히 버린다 (소비자는 AI 브랜치의 getSpectrogram).
    audioBuffer->push(samples, static_cast<size_t>(length));
  }

  float l = 0.0f;
  float r = 0.0f;
  jint i = 0;
  for (; i + 1 < length; i += 2) {
    const float a = fabsf(samples[i]);
    const float b = fabsf(samples[i + 1]);
    if (a > l)
      l = a;
    if (b > r)
      r = b;
  }
  atomicMax(peakLeft, l);
  atomicMax(peakRight, r);
  pushCount.fetch_add(1, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_soundvisualizer_AudioEngine_init(JNIEnv *env, jobject thiz) {
  if (audioBuffer == nullptr) {
    audioBuffer = new RingBuffer<float>(44100 * 2);
    LOGD("Audio RingBuffer initialized.");
  }
  peakLeft.store(0.0f, std::memory_order_relaxed);
  peakRight.store(0.0f, std::memory_order_relaxed);
  pushCount.store(0, std::memory_order_relaxed);
}

// FloatArray 경로 (호환용). Critical 접근으로 배열 복사를 피한다.
extern "C" JNIEXPORT void JNICALL
Java_com_example_soundvisualizer_AudioEngine_pushAudioData(JNIEnv *env,
                                                           jobject thiz,
                                                           jfloatArray data,
                                                           jint length) {
  if (data == nullptr)
    return;
  const jsize arrayLen = env->GetArrayLength(data);
  if (length > arrayLen)
    length = arrayLen;

  auto *c_data = static_cast<jfloat *>(env->GetPrimitiveArrayCritical(data, nullptr));
  if (c_data == nullptr)
    return;
  processSamples(c_data, length);
  env->ReleasePrimitiveArrayCritical(data, c_data, JNI_ABORT);
}

// Direct ByteBuffer 경로 (AudioRecord.read(ByteBuffer) → 복사 0회).
extern "C" JNIEXPORT void JNICALL
Java_com_example_soundvisualizer_AudioEngine_pushAudioBuffer(JNIEnv *env,
                                                             jobject thiz,
                                                             jobject buffer,
                                                             jint floatCount) {
  if (buffer == nullptr)
    return;
  auto *samples = static_cast<const float *>(env->GetDirectBufferAddress(buffer));
  if (samples == nullptr)
    return;
  const jlong capacityFloats = env->GetDirectBufferCapacity(buffer) / static_cast<jlong>(sizeof(float));
  if (floatCount > capacityFloats)
    floatCount = static_cast<jint>(capacityFloats);
  processSamples(samples, floatCount);
}

// out[0] = 좌 피크, out[1] = 우 피크, out[2] = 마지막 호출 이후 푸시된 버퍼 수. 읽은 뒤 0 으로 리셋.
extern "C" JNIEXPORT void JNICALL
Java_com_example_soundvisualizer_AudioEngine_readPeaks(JNIEnv *env, jobject thiz,
                                                       jfloatArray out) {
  if (out == nullptr || env->GetArrayLength(out) < 3)
    return;
  jfloat values[3];
  // pushCount 를 먼저 읽는다. 생산자가 피크 -> pushCount(release) 순으로 쓰므로,
  // 여기서 0 이 아닌 값을 읽으면 그 이전의 피크 쓰기가 반드시 보인다.
  // 순서를 뒤집으면 "count > 0 인데 피크는 0" 이 되어 한 프레임 꺼진다.
  values[2] = static_cast<jfloat>(pushCount.exchange(0, std::memory_order_acquire));
  values[0] = peakLeft.exchange(0.0f, std::memory_order_acq_rel);
  values[1] = peakRight.exchange(0.0f, std::memory_order_acq_rel);
  env->SetFloatArrayRegion(out, 0, 3, values);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_soundvisualizer_AudioEngine_getSpectrogram(JNIEnv *env,
                                                            jobject thiz) {
  if (audioBuffer == nullptr)
    return nullptr;

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
  const int outSize = FFT_SIZE / 2;
  jfloatArray result = env->NewFloatArray(outSize);
  jfloat magnitude[outSize];

  for (int i = 0; i < outSize; i++) {
    magnitude[i] = sqrt(out[i].r * out[i].r + out[i].i * out[i].i);
  }

  env->SetFloatArrayRegion(result, 0, outSize, magnitude);
  return result;
}

// 캡처 중지 후 상태만 초기화한다. 링버퍼는 일부러 해제하지 않는다.
//
// 해제하면 캡처 스레드가 join 을 제시간에 마치지 못했을 때 pushAudioBuffer 가
// 해제된 버퍼를 건드려 use-after-free 가 난다. nullptr 검사도 delete 와의 경합을 막지 못한다.
// 352KB 를 프로세스 수명 동안 유지하고 init() 이 재사용하는 편이 안전하다.
extern "C" JNIEXPORT void JNICALL
Java_com_example_soundvisualizer_AudioEngine_reset(JNIEnv *env, jobject thiz) {
  peakLeft.store(0.0f, std::memory_order_relaxed);
  peakRight.store(0.0f, std::memory_order_relaxed);
  pushCount.store(0, std::memory_order_relaxed);
  LOGD("Audio engine reset.");
}

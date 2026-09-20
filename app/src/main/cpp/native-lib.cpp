#include <android/log.h>
#include <atomic>
#include <cmath>
#include <jni.h>

#define LOG_TAG "SoundVisualizer_NDK"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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
//
// 상태가 정적 atomic 뿐이라 해제할 자원이 없다. 캡처 스레드가 서비스보다 늦게 끝나도
// 건드릴 대상이 프로세스 수명과 같은 정적 atomic 이므로 use-after-free 가 성립하지 않는다.
// ---------------------------------------------------------------------------
static std::atomic<float> peakLeft{0.0f};
static std::atomic<float> peakRight{0.0f};
static std::atomic<int> pushCount{0};

// 가장 최근 버퍼의 좌우 중 큰 피크. readPeaks() 와 달리 읽어도 초기화하지 않는다.
// 개발자 모드 HUD 가 "지금 소리 크기" 로 보여 주는 값이다. 여러 곳에서 읽어도 서로 빼앗지 않는다.
// 구간을 훑어야 하는 쪽(진동 알림, 보호된 소리 안내)은 이 값이 아니라 아래 누적값을 쓴다.
static std::atomic<float> lastLevel{0.0f};

// 보호된 소리 안내(BlockedCaptureNotice)가 쓰는 세 번째 누적값. 피크와 버퍼 수를 함께 센다.
//
// 오버레이의 peakLeft/peakRight 를 나눠 쓸 수 없다. 먼저 읽는 쪽이 0 으로 되돌리므로 다른 쪽은 그 구간을
// 통째로 놓친다. lastLevel 로도 안 된다. 가장 최근 버퍼(11.6ms) 하나만 담고 있어서 0.5초마다 보는 쪽이
// 실제로 들여다보는 구간은 전체의 2% 남짓이고, 드문드문 나는 효과음은 확인과 확인 사이에 들어왔다 사라진다.
// "소리가 났는데도 못 봤다" 는 곧 멀쩡한 앱을 탓하는 안내가 되므로, 구간 전체의 최대값을 따로 모은다.
//
// checkCount 는 "우리 캡처가 멈춘 것" 과 "앱이 조용한 것" 을 가른다. 버퍼가 한 개도 오지 않았다면 무음의
// 원인은 우리 쪽이고, 그때 앱을 탓하면 사용자는 엉뚱한 곳을 고치러 간다.
// 값은 한 소비자(메인 스레드의 확인 틱)만 읽는다. 버퍼당 늘어나는 비용은 원자 연산 두 번뿐이다.
static std::atomic<float> checkPeak{0.0f};
static std::atomic<int> checkCount{0};

// 진동 알림(HapticNotifier)이 쓰는 네 번째 누적값. 0.1초마다 읽고 0 으로 되돌린다.
//
// 여기도 lastLevel 로는 안 된다. 0.1초에 한 번 가장 최근 버퍼(11.6ms)만 보면 시간의 12% 남짓만 보는 셈이라,
// 총소리 한 발처럼 30~60ms 만 큰 소리는 확인과 확인 사이에 들어왔다 사라진다. 그러면 화면에는 그려지는데
// (오버레이는 구간 최대값을 읽는다) 진동은 오지 않는다. 폰이 주머니에 있으면 진동이 유일한 알림이다.
static std::atomic<float> hapticPeak{0.0f};

static inline void atomicMax(std::atomic<float> &target, float value) {
  float cur = target.load(std::memory_order_relaxed);
  while (value > cur &&
         !target.compare_exchange_weak(cur, value, std::memory_order_release,
                                       std::memory_order_relaxed)) {
  }
}

// Direct ByteBuffer 경로 (AudioRecord.read(ByteBuffer) → 복사 0회).
// 인터리브 스테레오 float 을 가정한다. 캡처 스레드에서만 호출된다.
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
  const jlong capacityFloats =
      env->GetDirectBufferCapacity(buffer) / static_cast<jlong>(sizeof(float));
  if (floatCount > capacityFloats)
    floatCount = static_cast<jint>(capacityFloats);
  if (floatCount <= 0)
    return;

  float l = 0.0f;
  float r = 0.0f;
  for (jint i = 0; i + 1 < floatCount; i += 2) {
    const float a = fabsf(samples[i]);
    const float b = fabsf(samples[i + 1]);
    if (a > l)
      l = a;
    if (b > r)
      r = b;
  }
  const float peak = l > r ? l : r;
  atomicMax(peakLeft, l);
  atomicMax(peakRight, r);
  atomicMax(checkPeak, peak);
  atomicMax(hapticPeak, peak);
  // 두 카운터 모두 피크 뒤에 release 로 올린다 (읽는 쪽의 acquire 와 짝을 이룬다).
  pushCount.fetch_add(1, std::memory_order_release);
  checkCount.fetch_add(1, std::memory_order_release);
  lastLevel.store(peak, std::memory_order_relaxed);
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

// 가장 최근 버퍼의 좌우 중 큰 피크 (0..1). 읽어도 초기화하지 않는다.
extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_soundvisualizer_AudioEngine_currentLevel(JNIEnv *env, jobject thiz) {
  return lastLevel.load(std::memory_order_relaxed);
}

// out[0] = 마지막 호출 이후의 최대 피크, out[1] = 그사이 도착한 버퍼 수. 읽은 뒤 0 으로 리셋.
// 보호된 소리 안내 전용이라 오버레이의 readPeaks() 와 서로 값을 빼앗지 않는다.
extern "C" JNIEXPORT void JNICALL
Java_com_example_soundvisualizer_AudioEngine_takePeakSinceLastCheck(JNIEnv *env,
                                                                    jobject thiz,
                                                                    jfloatArray out) {
  if (out == nullptr || env->GetArrayLength(out) < 2)
    return;
  jfloat values[2];
  // readPeaks() 와 같은 이유로 개수를 먼저 읽는다. 생산자가 피크 -> 개수(release) 순으로 쓰므로,
  // 개수가 0 이 아니면 그 이전의 피크 쓰기가 반드시 보인다.
  // 순서를 뒤집으면 "버퍼는 왔는데 피크는 0" 이 되어, 소리가 났는데도 못 받은 것으로 센다.
  values[1] = static_cast<jfloat>(checkCount.exchange(0, std::memory_order_acquire));
  values[0] = checkPeak.exchange(0.0f, std::memory_order_acq_rel);
  env->SetFloatArrayRegion(out, 0, 2, values);
}

// 마지막 호출 이후의 최대 피크 (0..1). 읽은 뒤 0 으로 되돌린다. 진동 알림 전용이라 다른 소비자와 값을 빼앗지 않는다.
extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_soundvisualizer_AudioEngine_takeHapticPeak(JNIEnv *env, jobject thiz) {
  return hapticPeak.exchange(0.0f, std::memory_order_acq_rel);
}

// 누적값을 0 으로 돌린다. 캡처 시작 시점과 종료 시점에 각각 호출한다.
// (시작 때 호출하면 네이티브 라이브러리 적재 실패도 캡처 스레드가 아니라 여기서 드러난다.)
extern "C" JNIEXPORT void JNICALL
Java_com_example_soundvisualizer_AudioEngine_reset(JNIEnv *env, jobject thiz) {
  peakLeft.store(0.0f, std::memory_order_relaxed);
  peakRight.store(0.0f, std::memory_order_relaxed);
  pushCount.store(0, std::memory_order_relaxed);
  lastLevel.store(0.0f, std::memory_order_relaxed);
  checkPeak.store(0.0f, std::memory_order_relaxed);
  checkCount.store(0, std::memory_order_relaxed);
  hapticPeak.store(0.0f, std::memory_order_relaxed);
  LOGD("Audio engine reset.");
}

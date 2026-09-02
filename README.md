# 🎧 SoundVisualizer

안드로이드 스마트폰 내부의 소리를 실시간으로 분석하고, AI 사운드 분류를 통해 화면에 시각화해주는 오버레이(Overlay) 애플리케이션입니다. 청각 장애인을 위한 환경음 알림 및 모바일 게임(FPS 등)의 사운드 플레이 보조를 목적으로 설계되었습니다.

---

## ✨ 주요 기능 (Core Features)

### 1. 🎙️ 실시간 시스템 오디오 캡처
안드로이드 **MediaProjection API**를 사용하여 기기 내부에서 재생되는 소리(게임, 미디어 등)를 다이렉트로 캡처합니다. (Android 10 이상 지원)

### 2. ⚡ Zero-Latency C++ 오디오 분석 엔진
코틀린(Kotlin) 환경에서의 가비지 컬렉터(GC)로 인한 프레임 드랍을 막기 위해 핵심 오디오 데이터 처리를 **C++ NDK** 기반으로 구현했습니다.
- **Lock-Free RingBuffer**: 실시간 오디오 스트림을 손실 없이 C++ 메모리로 전달합니다.
- **KissFFT 연산**: 캡처된 PCM 데이터를 실시간 주파수 스펙트로그램으로 고속 변환합니다.

### 3. 🧠 AI 사운드 분류 (준비 완료)
- **TensorFlow Lite (TFLite)** 의존성 구성 완료.
- 주파수 데이터를 기반으로 향후 YAMNet 등의 모델을 연동하여, 주변 소리(Ambient), 사람 목소리(Speech), 위험/총소리(Danger) 등을 실시간으로 분류합니다.

### 4. 📱 터치 통과형 투명 오버레이 UI (Jetpack Compose)
- **SYSTEM_ALERT_WINDOW** 권한을 사용해 모든 앱 위에 최상단으로 렌더링됩니다.
- `FLAG_NOT_TOUCHABLE` 속성을 적용하여, 시각화 그래픽 아래에 있는 게임이나 앱을 완벽하게 터치 및 조작할 수 있습니다.

---

## 🎨 시각화 모드 (Visual Modes)

본 프로젝트는 AI 추론 결과와 FFT 데이터에 따라 다음과 같은 3가지 모드의 시각화를 지원하도록 설계되었습니다.

*   🌊 **Wave 모드 (진동파)**
    - 화면 하단 또는 양쪽 가장자리에 오디오 주파수 스펙트럼(이퀄라이저 바/파동)을 실시간으로 렌더링합니다.
*   🎯 **Pad 모드 (방향 패드)**
    - 소리의 크기나 분류된 소리의 방향성을 둥근 빛 번짐 형태로 화면 가장자리에 직관적으로 표현합니다.
*   🚨 **Outline 모드 (외곽선)**
    - 총소리(Gunshot)나 비명 같은 특정 '위험(Danger)' 소리 감지 시, 화면 전체 테두리가 붉은색 등으로 강렬하게 점멸하여 사용자의 즉각적인 반응을 유도합니다. 평소에는 은은한 색(Ambient)을 유지합니다.

---

## 🛠️ 기술 스택 (Tech Stack)

- **언어**: Kotlin, C++17
- **UI 프레임워크**: Jetpack Compose
- **오디오 / DSP**: AudioRecord, MediaProjection API, KissFFT, JNI (Java Native Interface)
- **AI 추론**: TensorFlow Lite (예정)
- **빌드 시스템**: Gradle (KTS), CMake (NDK)

---

## 🚀 시작하기 (Getting Started)

1. **Android Studio**에서 본 프로젝트를 엽니다.
2. 프로젝트 동기화(Gradle Sync)를 완료하여 `gradlew` 래퍼 및 필수 라이브러리를 다운로드합니다.
3. 기기 또는 에뮬레이터에 빌드 및 실행(Run)합니다.
4. 앱 실행 시 **화면 녹화(MediaProjection)** 및 **다른 앱 위에 표시(Overlay)** 권한을 허용합니다.
5. 바탕화면이나 다른 앱(게임 등)으로 이동하면 하단에 실시간 오디오 스펙트로그램이 그려지는 것을 확인할 수 있습니다!

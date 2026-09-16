# R8 유지(keep) 규칙.
#
# 여기 적힌 것은 전부 "이름이 살아 있어야 하는" 대상이다.
# R8 은 자바·코틀린 코드가 참조하는 것은 알아서 따라가지만, 네이티브(C/C++) 가 문자열로 찾는 이름은 알지 못한다.
# 그래서 이름이 바뀌거나 지워지면 빌드는 멀쩡히 끝나고 폰에서만 깨진다.
# 규칙을 고치거나 지웠으면, 릴리스 APK 를 실제로 설치해 AI 분류가 도는지 확인한 뒤에 머지한다.
#
# AGP 기본 파일(proguard-android-optimize.txt)이 이미 넣어 주는 것은 여기에 다시 적지 않는다.
#   - enum 의 values() / valueOf()
#   - Signature, InnerClasses, EnclosingMethod, 런타임 애너테이션 속성
#   - 매니페스트에 적힌 Activity·Service 는 AGP 가 따로 규칙을 만들어 준다.

# ── 난독화된 크래시를 읽기 위한 최소한의 정보 ────────────────────────────────
# R8 을 켜면 스택 트레이스의 클래스·메서드 이름이 a, b 로 바뀐다. 줄 번호까지 없으면
# 팀에 나눠준 APK 에서 올라온 크래시를 되돌릴 수 없다. 원래 파일 이름은 숨기고 줄 번호만 남긴다.
# 되돌릴 때 쓰는 매핑 파일은 app/build/outputs/mapping/release/mapping.txt 에 생긴다.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── 우리 JNI (libsoundvisualizer.so) ──────────────────────────────────────
# 네이티브 함수 이름은 Java_com_example_soundvisualizer_AudioEngine_readPeaks 처럼
# "패키지 + 클래스 + 메서드" 로 만들어진다. 셋 중 하나만 바뀌어도 첫 호출에서 UnsatisfiedLinkError 가 난다.
# 기본 파일에 모든 native 메서드를 지키는 같은 규칙이 있지만, 기본 파일을 바꾸더라도 깨지지 않게 여기에도 남긴다.
# (keepclasseswithmembernames 는 이름만 지킨다. 아무도 쓰지 않으면 지워지는 것은 그대로다.)
-keepclasseswithmembernames,includedescriptorclasses class com.example.soundvisualizer.AudioEngine {
    native <methods>;
}

# ── ONNX Runtime: Java → 네이티브 ────────────────────────────────────────
# libonnxruntime4j_jni.so 도 같은 방식으로 심볼 이름을 만든다. 세션 생성, 텐서 만들기, run(), close()
# 까지 ORT 의 거의 모든 동작이 이 경로를 지나므로 클래스 이름과 native 메서드 이름을 그대로 둬야 한다.
-keepclasseswithmembernames,includedescriptorclasses class ai.onnxruntime.** {
    native <methods>;
}

# ── ONNX Runtime: 네이티브 → Java ────────────────────────────────────────
# ORT 의 C 코드는 결과를 자바 객체로 만들어 돌려준다. 그때 FindClass 와 GetMethodID 에
# "ai/onnxruntime/OnnxTensor" 같은 이름과 "(JJLai/onnxruntime/TensorInfo;)V" 같은 시그니처 문자열을 쓴다.
# 아래 목록은 libonnxruntime4j_jni.so 안에 실제로 들어 있는 ai/onnxruntime 문자열 그대로다.
# 이 생성자들은 패키지 전용이라 자바 쪽에서 부르는 곳이 없다. 적어 두지 않으면 R8 이 "쓰지 않는 코드" 로 지운다.
# includedescriptorclasses 는 시그니처에 나오는 타입(TensorInfo, MapInfo, ValueInfo …) 의 이름까지 함께 지킨다.

# 추론 결과 텐서와 그 메타데이터. 이게 없으면 YAMNet 분류가 통째로 실패한다.
-keep,includedescriptorclasses class ai.onnxruntime.OnnxTensor { <init>(...); }
-keep,includedescriptorclasses class ai.onnxruntime.TensorInfo { <init>(...); }

# 세션을 열 때 입력·출력 정보를 만들어 돌려주는 객체들. 모델 로딩 첫 단계에서 바로 쓴다.
-keep,includedescriptorclasses class ai.onnxruntime.NodeInfo { <init>(...); }
-keep,includedescriptorclasses class ai.onnxruntime.MapInfo { <init>(...); }
-keep,includedescriptorclasses class ai.onnxruntime.SequenceInfo { <init>(...); }
-keep class ai.onnxruntime.ValueInfo

# 네이티브 오류를 자바 예외로 올리는 통로. 지워지면 오류 메시지 대신 알 수 없는 크래시가 된다.
-keep,includedescriptorclasses class ai.onnxruntime.OrtException { <init>(...); }

# 지금 쓰는 두 모델은 텐서만 내놓아서 아래는 실제로 불리지 않는다. 다만 같은 반환 경로에 있어서
# 모델이 map/sequence 를 내놓는 순간 조용히 깨진다. 합쳐서 몇 KB 라, 확신이 없는 쪽은 남겨 둔다.
-keep class ai.onnxruntime.OnnxValue
-keep,includedescriptorclasses class ai.onnxruntime.OnnxMap { <init>(...); }
-keep,includedescriptorclasses class ai.onnxruntime.OnnxSequence { <init>(...); }
-keep,includedescriptorclasses class ai.onnxruntime.OnnxSparseTensor { <init>(...); }
-keep,includedescriptorclasses class ai.onnxruntime.OnnxModelMetadata { <init>(...); }

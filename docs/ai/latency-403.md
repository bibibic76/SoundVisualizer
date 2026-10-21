# #403 지연 개선 1차 검증 (2026-10-21)

## 판단

목표는 정확도와 실행 비용을 악화시키지 않으며 반응 지연을 줄이는 것이다. 200ms 고정 주기는 이번 비교에서 회귀가 있어 적용하지 않는다. 우선 **모델 입력을 비트 단위로 보존하는 FFT 계수 재사용**을 후보로 구현했다. 호스트에서는 전처리가 조금 빨라졌지만, 실기기에서 체감 지연·발열이 개선됐다고 주장할 근거는 아직 없다. #403은 완료하지 않는다.

기준은 `develop`의 `e6fb5fe`(v1.7.2 이후 문서 변경 포함)다. #379의 시작 기준 250ms 및 최소 휴식 100ms는 이미 들어 있다. #400과 #402는 이 기준에 포함되지 않는다. 이후 실기기 A/B에서는 두 작업 포함 여부를 동일하게 맞춘다.

## 200ms 후보: 적용 보류

기존 manifest 812 WAV를 읽기만 했다. 주 평가 645 = Ambient 248 / Speech 15 / Danger 382이며 legacy 107과 라벨 미확정 60개는 주 지표에서 제외한다. 같은 현재 Python 기준 구현, Qualcomm frontend, Booster 없음, 기본 매핑, threshold/hysteresis로 비교했다. 이 manifest는 반복 사용한 진단 자료이고 독립 holdout이 아니다.

| 지표 | 250ms | 200ms |
|---|---:|---:|
| 주 평가 파일별 UI 다수결 accuracy | 78.7597% | 77.0543% |
| 주 평가 macro F1 | 0.771417 | 0.759852 |
| Danger 다수결 / 382 | 260 | 250 |
| Danger에서 한 번 이상 Danger / 382 | 341 | 344 |
| Ambient에서 한 번 이상 Danger / 248 | 42 | 46 |
| 대표 81개 다수결 accuracy | 90.1235% | 87.6543% |
| 전체 추론 창 수 | 11,753 | 14,466 |
| 주 평가 Ambient의 Danger 표시 유지 시간 합계 | 44.536초 | 50.450초 |

두 arm 모두 812/812 처리했고 오류는 없었다. 새 재현 도구는 기존 ring-buffer 평가 도구와 두 arm 1,624/1,624 파일에서 UI 횟수·다수결이 일치했다. 이번 수치는 현재 기본 매핑을 사용하므로 과거 #378의 당시 기본 매핑 지표와 직접 이어 붙이지 않는다.

비교는 원본 시작 위상 0ms 한 개다. 한 창보다 짧은 파일만 메모리상 1초 무음을 덧붙이고 파일별 후처리 상태를 비운다. EOF의 마지막 부분 입력도 당시 평가와 같은 방식으로 판정한다. 이 단일 위상에서 회귀 사례가 충분히 나와 채택하지 않았으며, 다른 모든 위상에서도 반드시 나쁘다는 결론은 아니다. 고정 주기만 바꿔도 입력 창 위치와 히스테리시스의 시간 길이가 달라진다. 정상 실행 중 계산 횟수도 초당 4 → 5회가 된다.

파일에 사건 시작/끝 시간 정답이 없으므로 any-Danger를 구간 오경보율로 부르지 않는다. Android 캡처·추론 소요시간·무음 게이트·화면·진동은 이 replay로 재현하지 않는다. 기록 도구의 `danger_held_ms`도 마지막 샘플 시각까지만 계산하며 이후 라벨 해제는 포함하지 않는다. `first_expected_result_from_replay_start_ms`는 파일 시작 기준의 관측값이며 실제 사건 지연이 아니다.

## FFT 후보: 수치 보존

현재 FFT는 각 butterfly마다 회전 계수의 다음 값을 곱셈으로 계산한다. 같은 512-point FFT가 96개 STFT 프레임마다 반복된다. `PlannedRadix2Fft`는 원래와 같은 recurrence로 단계별 계수를 한 번 만들고 재사용한다. `cos(j * angle)`로 새로 계산하지 않으며 butterfly 순서, double 연산, reflect padding, 멜 필터, FIR, 모델·후처리는 보존한다.

- 추가 계수 저장: frontend 인스턴스당 `2 × 511 × 8 = 8,176` bytes의 배열 데이터. 배열 헤더·객체 오버헤드는 별도다. 기존 512개 bit-reversal 인덱스는 plan 안으로 이동한다.
- 단위 검사: 원래 FFT와 2/4/16/512 크기, 무음·임펄스·고정 seed 복소수 입력 100회씩 raw double bits 일치.
- 기존 Python golden 전처리·리샘플 경로 검사와 신규 FFT 검사: 로컬 JVM에서 13개 통과. 픽스처를 바꾸지 않았다.
- 실제 812 WAV / 11,753개 창을 동일한 FIR로 16kHz 변환해 이전·후보 Kotlin frontend에 넣었다. **모든 6,144개 log-mel float의 raw bits가 모든 창에서 일치**했다.
- 전체 후보 log-mel 연결 SHA-256: `ba6016a16d9c5320041579aeed7b11ac20b4530337e7d9c83e11b6fa5c3c95e7`.
- Android ART에서도 원래 FFT와 raw bits를 비교하는 계측 테스트를 추가했다. 로컬에는 SDK/ADB가 없어 실행하지 못했다.

동일한 창의 모델 입력은 같으므로 이 최적화가 모델의 수치 입력을 바꾸지 않는다는 근거다. 실제 scheduler는 계산이 끝나는 시각에 영향을 받으므로, 모든 실시간 상황의 관측 창·UI 순서가 완전히 같다는 보장은 아니다.

## 호스트 비용 측정

Linux aarch64 / OpenJDK 17.0.20.1, Kotlin JVM compiler 2.0.20로 만든 같은 harness의 이전·후보 jar를 번갈아 실행했다. runtime stdlib는 두 arm 모두 2.4.0이다. 각 arm 5개 새 JVM, 항목별 200회 warm-up 뒤 1,000회 측정했다. 아래는 **각 run 통계의 중앙값**이다. 다른 오프라인 replay와 겹치지 않는 구간에서 실행했다.

| 항목 | 이전 median / p95 (ms) | 후보 median / p95 (ms) | 이전 → 후보 thread CPU / tick (ms) |
|---|---:|---:|---:|
| 48kHz FIR만 (대조) | 0.582150 / 0.601898 | 0.582025 / 0.603106 | 0.584125 → 0.585109 |
| log-mel | 0.412208 / 0.433373 | 0.366672 / 0.388378 | 0.414345 → 0.369151 |
| 48kHz FIR + log-mel | 0.995442 / 1.029438 | 0.946322 / 0.970570 | 0.999979 → 0.949215 |

전처리 median 약 4.9%, log-mel 약 11.0% 감소다. **전처리 전체 절대 차이는 약 0.049ms**이고 YAMNet 시간은 포함하지 않았다. 일부 run에 JIT/스케줄링 튐이 있었다. 아래에 각 run의 전처리 median/p95를 모두 남긴다. 호스트 microbenchmark를 Android ART 성능·배터리·체감 반응 개선으로 환산하지 않는다. 초기 계수 준비 비용은 위 steady-state 수치에 포함되지 않는다.

| run | 이전 전처리 median / p95 (ms) | 후보 전처리 median / p95 (ms) |
|---|---:|---:|
| 1 | 0.993567 / 1.016690 | 0.941406 / 0.962363 |
| 2 | 0.995442 / 1.029438 | 0.949322 / 0.979902 |
| 3 | 1.059935 / 2.325142 | 0.948238 / 0.970570 |
| 4 | 0.994067 / 1.017232 | 0.941906 / 0.963154 |
| 5 | 1.002150 / 1.270707 | 0.946322 / 1.001275 |

Python reference 테스트 25개도 통과했다. 로컬 Gradle 전체 검사는 `SDK location not found`로 중단되어 전체 Android 빌드/테스트와 구분한다.

## 재현

Kotlin CLI와 JDK, Python reference requirements가 필요하다. 원본 WAV는 manifest 경로에 준비하고 출력은 repo 밖에 둔다. 아래 `$BASE`는 `e6fb5fe`의 별도 checkout, `$STDLIB`는 두 arm에서 공유할 Kotlin stdlib jar다.

```bash
kotlinc "$BASE/app/src/main/java/com/example/soundvisualizer/ai/AudioPreprocessor.kt" \
  "$BASE/app/src/main/java/com/example/soundvisualizer/ai/QualcommSourceAudioPreprocessor.kt" \
  "$BASE/app/src/main/java/com/example/soundvisualizer/ai/CaptureAudioMath.kt" \
  tools/ai_reference/FrontendBenchmark.kt -d /tmp/frontend-baseline.jar
kotlinc app/src/main/java/com/example/soundvisualizer/ai/{AudioPreprocessor,QualcommSourceAudioPreprocessor,CaptureAudioMath,PlannedRadix2Fft}.kt \
  tools/ai_reference/FrontendBenchmark.kt -d /tmp/frontend-candidate.jar
kotlinc tools/ai_reference/FrontendParity.kt -d /tmp/frontend-parity.jar

python tools/ai_reference/check_frontend_bit_parity.py \
  --manifest /tmp/sovis-351-manifest.json \
  --classpath "/tmp/frontend-parity.jar:$STDLIB" \
  --baseline-jar /tmp/frontend-baseline.jar --candidate-jar /tmp/frontend-candidate.jar
python tools/ai_reference/compare_frontend_benchmarks.py \
  --baseline-classpath "/tmp/frontend-baseline.jar:$STDLIB" \
  --candidate-classpath "/tmp/frontend-candidate.jar:$STDLIB" \
  --rounds 5 --iterations 1000 --output /tmp/frontend-benchmark.json

OPENBLAS_NUM_THREADS=1 python tools/ai_reference/evaluate_cadence.py \
  --manifest /tmp/sovis-351-manifest.json --model-dir app/src/main/assets/ai \
  --step-ms 250 --output /tmp/cadence250.json
OPENBLAS_NUM_THREADS=1 python tools/ai_reference/evaluate_cadence.py \
  --manifest /tmp/sovis-351-manifest.json --model-dir app/src/main/assets/ai \
  --step-ms 200 --output /tmp/cadence200.json
```

원본 manifest SHA-256: `68b138da5083a75199514cf37c50761588155a6d9e885e0adfd2d00a6e207f82`. 생성한 수치 JSON과 음원은 커밋하지 않는다.

## 후속 작업 / 아직 확인하지 못한 것

- 같은 release APK 설정으로 실기기 PCM onset → candidate → confirmed → overlay/haptic command를 단조 시계로 측정하고, 첫 결과와 실행 중 반응을 분리한다. 모델 로딩·약 0.975초 입력 축적도 별도로 기록한다.
- PCM 이전 시스템 지연, 화면 실제 표시, 모터의 물리 반응을 측정하지 못했다면 명령 시각과 혼동하지 않는다.
- 내부 재생음·진동 OFF baseline, 마이크 진동 OFF, 진동 ON을 분리한다. #371 입력 치환 및 #400/#402 영향을 섞지 않는다.
- FFT 후보의 ART 전처리 시간·CPU·온도·전력을 측정한다. 수치 일치는 절전 실증이 아니다. 느려지는 기기에서는 채택하지 않는다.
- 무음 복귀 시 분석을 앞당기는 후보는 아직 평가하지 않았다. 너무 이른 입력으로 인한 판정 실패, 오경보, 재예약·중복, 연속음에서 효과가 없는 경우를 평가한 뒤 다룬다.
- threshold·hysteresis·모델 창을 바꿔 빨라 보이게 만드는 조정은 하지 않는다.

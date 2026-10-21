# #407 정규 우선 보완과 다음 지연 개선 아이디어

후속 분석: [화면 경로별 원인·구간별 실험·개선 설계](latency-root-cause-407.md).

## 이번 변경의 범위

정규 스케줄이 추가 추론을 기다리거나, 성공한 조기 추론이 원래 슬롯을 소비하던 경로를 제거했다. **실험 기본 OFF·draft 유지**이며, “성능 저하 없이 실기기 딜레이 개선 완료”라는 의미는 아니다.

- 정규: 기존 단일 coroutine·ORT session·frontend·scratch·postprocessor·무음 게이트 완료 횟수를 유지한다. 정규 deadline은 `val`이며 조기 결과에 따라 이동하지 않는다.
- 조기: 별도 단일 작업자에서 별도 ORT session·frontend·scratch를 소유한다. 초기화도 그 작업자에서 하며 준비 전에는 시도하지 않는다. 기본 OFF에는 두 번째 세션/스레드가 없다.
- 동시에 예약/실행하는 추가 작업은 최대 하나. 바쁠 때 새 요청을 쌓지 않고 생략한다. 정규 경로에는 future.get/await/join, 조기 작업의 inferLock이 없다.
- 일찍 끝난 Speech는 regular parent 객체·mapping·연속 입력 활성·기존 Speech threshold·동일 세부 라벨·confidence 비감소 조건을 만족할 때만 임시 published view로 제공한다. **정규 postprocessor/candidate에는 주입하지 않는다.**
- 임시 view는 원래 정규 deadline에 만료한다. 정규 tick 시작/stop/reset/close에서 세대를 무효화하고, 이전 parent나 mapping의 결과는 읽을 수 없다. 늦은 Speech가 새 Danger를 덮지 않는다.
- 무음 gate가 닫히거나 최근 지속 입력이 끊기면 임시 view를 노출하지 않는다. 정규 inference와 silence-drain 횟수는 추가 관측 때문에 바뀌지 않는다.
- close는 조기 native 추론을 기다리지 않는다. 세션 해제는 같은 작업자의 활성 native 호출이 돌아온 뒤 실행한다. native 실행 중 강제 close/interrupt로 메모리를 해제하지 않는다.

`earlyConfirmation`은 이제 **정규 확정 상태가 아니라 임시 공개 view**를 뜻한다. 전역 `AiClassification.latest()`를 읽는 화면뿐 아니라 진동 소비자도 이를 볼 수 있으므로 순간 오표시/진동 변화·깜빡임을 검증해야 한다. 기한 만료가 정규 결과 완료보다 먼저 오면 잠깐 기존 Ambient로 돌아갔다 Speech가 될 수도 있다. CSV 뒤의 실험 열은 유지한다. `executed/completed`는 정규 호출 수, 추가 호출은 `confirmationAttempts`, 기한 내 채택 가능한 결과는 `confirmationAccepted`로 따로 센다. 카운터는 화면에 실제 표시된 횟수가 아니다.

## 보장한 것과 보장하지 못한 것

이번 보완은 **코드 수준의 직렬 대기와 정규 슬롯 소비를 없앤 것**이다. 동시에 실행하는 두 세션이 CPU·캐시·RAM·메모리 대역폭·발열을 공유하는 것은 없애지 못한다. 짧은 PCM ring snapshot lock도 공유한다. 별도 세션의 실제 추가 RSS나 전력은 측정하지 않았다. 작업자 우선순위를 background로 낮췄어도 정규 추론의 물리적 실행시간/스케줄링 deadline을 보장하지 않는다.

ONNX Runtime의 [RunOptions.setTerminate](https://github.com/microsoft/onnxruntime/blob/v1.22.0/java/src/main/java/ai/onnxruntime/OrtSession.java)는 가능한 빨리 종료하도록 요청하는 API다. 취소 요청 시각에 native 연산이 즉시 끝난다는 보장이 없으므로 같은 세션에 timeout만 걸어 무지연을 주장하지 않았다. Android의 [background thread priority](https://developer.android.com/reference/android/os/Process#THREAD_PRIORITY_BACKGROUND)도 실시간 격리와는 다르다.

앞선 직렬 방식처럼 성공 슬롯을 소비하지 않으므로 추가 추론 비용은 오히려 커질 수 있다. 원래 100ms 정규 휴식은 유지하지만 서로 다른 lane 사이의 총 CPU 휴식을 보장하는 구조는 아니다. 한 번의 추론을 공짜로 추가한 것이 아니며 실기기 p95·발열·메모리 검증 전 일반 활성화하지 않는다.

## 검증

- 순수 JVM에서 실제 별도 executor를 사용해 늦은 작업의 결과 폐기, 단일 flight, deadline 만료, 새 세대 무효화, 오류 복구, close의 비대기와 실행 후 resource 해제를 검사한다.
- 실제 Android ONNX 계측 테스트: 조기 lane을 latch로 막아 둔 상태에서도 정규 모델이 Danger를 확정하고, 해제 뒤 늦은 Speech가 이를 덮지 않는지 검사한다. 같은 출력의 일찍 끝난 preview가 정규 확인 후보를 소비하지 않는지, mapping 변경과 거절 결과도 검사한다.
- 새 음원 124개 ×3위상에 fixed25/variable/early-spike 가정을 적용한다. 두 번째 모델이 이미 준비됐고 CPU/RAM 경쟁이 없다는 **이상적인 분리 모델**이다. 새 구조의 실제 Android 지연 측정이 아니다. 자료 구성과 원본 해시는 이전 [검증 근거](responsive-validation-407.json)를 따른다.
- 47개 Python 테스트 통과. 기존 직렬 회귀 테스트는 제거하지 않고, 새 구조의 정규 상태/추론 수/첫 검출/노출 유지 비교를 추가했다.

최종 replay 수치 및 순간 Speech 노출 집계는 [새 구조 근거 JSON](isolated-preview-407.json)에 보관한다. 기존 직렬 구조의 125ms 결과를 새 구조의 실기기 측정으로 부르지 않는다.

| 124파일 ×3위상, 계산시간 가정 | 새 구조의 관찰 |
|---|---|
| fixed25 | Speech189조건 중90개 개선/99개 동일/악화·새 손실0. 각 arm median/p95 489/611→422/547ms. paired 단축량 중앙값0ms |
| variable | Speech90개 개선/99개 동일/악화·새 손실0. 앞선 직렬 방식의 새 다수결 회귀가 이 replay에서는 없음 |
| early-spike(추가 호출만200ms) | 늦은 결과131개 폐기. Speech189조건 전부 기존과 동일, 추가 지연·새 손실0. 속도 개선도 없음 |
| 세 profile 모두 | 정규 추론 수·정규 첫 검출·정규 다수결·정규 Danger/Speech 노출이 baseline과 372/372조건 일치. 각 profile의 새 다수결 회귀0, 새 Ambient Speech 노출 조건0 |

다만 **오표시 시간이 늘어나는 비용은 남았다.** fixed25의 Ambient Speech 노출 합계는9.750→9.998초, variable은11.875→11.995초다(tail포함). 새 노출 조건이나 다수결 회귀가 없다고 오표시 비용이0인 것은 아니다. fixed25 총 추론 수는9,068→9,199(**+1.445%**)로 직렬 방식보다 더 늘었고 이는CPU/전력 증가율이 아니다. 새 Danger 원본은6개뿐이며 기존 미검출도 그대로다. 이 자료는 직전 실험에 이미 사용했으므로 이번 비교를 새로운 독립 holdout으로 부르지 않는다.

```bash
OPENBLAS_NUM_THREADS=1 python tools/ai_reference/evaluate_isolated_preview.py \
  --manifest /tmp/sovis-407-validation-v2/manifest.json \
  --model-dir app/src/main/assets/ai --output /tmp/isolated-full.json
python tools/ai_reference/evaluate_isolated_preview.py \
  --compact-report-only /tmp/isolated-full.json --output /tmp/isolated-summary.json
python -m unittest discover -s tools/ai_reference -p 'test_*.py'
```

## 다른 아이디어 — 권장 순서와 판정 기준

**사용자 확인: 느리다는 피드백의 대상은 화면 색상·분류 표시다.** 따라서 아래 진동 후보는 이번 문제의 주된 해결책이 아니다. 화면 기준 후속 순서는 **단계별 trace → 첫 실행 입력 축적 분리 → 새로운 분석 위상 → 짧은 문맥 모델 검증**이다. 아래 번호는 후보 구분이며 화면 문제의 실행 순서와 다르다.

### 1. AI 확정 직후 진동 판단 깨우기: 모델을 바꾸지 않는 작은 개선

현재 `feedback/HapticTuning.kt`의 idle 판단 간격은 100ms이고 `HapticNotifier`는 handler의 다음 판단 때 `labelSource`를 읽는다. AI의 **정규 확정 라벨이 바뀌었을 때만** handler를 깨우면 다음 poll까지의 대기를 줄일 여지가 있다. 절감 상한은 해당 polling 대기분(조건에 따라 0~약100ms)이지 분류 지연 전체가 아니다. 현재도 driver가 먼저 깨우는 경우가 있어 평균 50ms 절감을 보장하지 않는다.

실험 설계: handler에 알림을 최대 하나로 합치고 새 판정만 읽는다. 진동 cooldown·박자·driver deadline·통화/사용자 설정·자기 진동 gate는 그대로 적용한다. 이벤트에서 곧바로 모터를 울리지 않는다. 기준/후보에 **같은 확정 라벨 시퀀스**를 넣어 command timing·중복·진동 횟수를 비교하고, 마이크 ON/OFF를 분리한다. 화면 색상 지연은 이 방식으로 줄지 않는다. 이번에는 구현하지 않았다.

### 2. 구간별 monotonic trace로 남은 실제 지연 찾기: 우선 병목 확인

PCM capture timestamp → AI snapshot/start/end → regular confirmed → overlay read / haptic command를 같은 시간축으로 연결한다. 이미 capture clock·개발 로그·CSV가 있으나 결과 `timestampMs`는 wall clock이어서 그대로 뺄 수 없다. 첫 실행과 연속 전환, 내부음과 마이크/진동 ON을 구분한다. 디버그 통계는 고정 크기로 집계해 캡처 스레드 allocation이나 매 프레임 로깅을 늘리지 않는다.

특히 마이크 자기 진동 치환은 입력을 과거 깨끗한 소리로 바꾸므로 시간상 오래된 입력을 최신 입력처럼 재면 안 된다. 이 trace는 속도 개선 자체가 아니라, 다음 실험이 실제 병목을 줄였는지 판정하는 장치다.

화면 쪽에는 regular result ID/시각과 해당 coarse를 처음 읽은 draw frame 시각을 함께 기록한다. 확정은 빨랐는데 그리기가 늦었는지, 확정 자체가 늦었는지부터 구분한다. 현재 오버레이는 캡처 기반 wake와 frame 읽기를 이미 사용하므로 이를 새 개선처럼 재구현하지 않는다. 화면 sleep/가림/잠금 해제도 분리하고, UI polling 주기를 무조건 높여 해결하지 않는다.

첫 실행에 한정한 별도 후보도 있다. 현재 서비스는 모델 초기화 완료 후 pipeline을 attach하므로 준비 전 PCM은 AI ring에 남지 않는다. 작은 bounded startup ring에 최근 입력만 보존해 초기화 후 넘기면 준비 완료 뒤 새로 창을 채우는 대기를 줄일 여지가 있다. 이는 실행 중 색상 전환에는 효과가 없고, 과거 Danger를 새 사건처럼 뒤늦게 알리거나 이전 실행/mapping의 입력을 섞지 않는 수명·시각 검증이 필요하다. 아직 구현하지 않았다.

### 3. 시작/큰 변화 뒤 분석 위상 재배치: 연산 수를 유지하는 실험

추가 session 대신 기존 tick을 당기고 뒤에서 간격을 보상하는 방식이다. CPU 증가를 제한할 여지는 있지만 앞선 후보에서 Speech 지연이 악화됐으므로 그대로 재사용하지 않는다. 실패 후 긴 빈 구간을 만들지 않는 제한, 연속음 중 전환, 짧은 Danger를 포함한 새 설계가 필요하다. 동일 inference 수라도 입력 snapshot이 달라져 정확도가 바뀔 수 있다. 검증 전 적용하지 않는다.

### 4. 짧은 문맥/streaming 모델: 큰 개선 잠재력, 별도 정확도 프로젝트

현재 앱의 0.975초 창은 매 사건마다 고정으로 그만큼 기다린다는 뜻은 아니지만 이전 소리의 문맥이 남는다. [YAMNet](https://www.tensorflow.org/hub/tutorials/yamnet)의 현재 고정 입력을 임의로 잘라 padding하는 것은 검증된 단축 모델이 아니다. 짧은 문맥용 학습/증류 또는 상태를 재사용하는 streaming 모델을 별도로 비교해야 한다.

동일 사건 annotation으로 첫 올바른 판정·p95·짧은 Danger recall·환경음 오경보·전환 안정성·전력을 평가한다. 모델 학습/교체는 이번 PR의 범위 밖이며 자동 착수하지 않는다. 큰 체감 개선을 노린다면 scheduler 미세 조정보다 이 방향이 근본적이지만, 현재 정확도 유지가 보장되는 것은 아니다.

### 5. 모델 실행 최적화: 보조 수단

현행 모델의 provider/graph 최적화나 [INT8 양자화](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html)를 별도 A/B로 비교할 수 있다. 양자화는 수치와 판정을 바꿀 수 있다. 기존 기기의 정규 추론 약24ms를 줄이는 것만으로 수백 ms 문맥·확인 대기가 사라지지는 않는다. 호스트 FFT의 약0.05ms 개선을 큰 체감 효과로 환산하지 않는다.

캡처 read는 1,024 float = stereo512frame로 48kHz에서 약10.7ms, 44.1kHz에서 약11.6ms다. [AudioRecord READ_BLOCKING](https://developer.android.com/reference/android/media/AudioRecord#READ_BLOCKING)과 OS/mixer backlog는 구분해야 하지만, read 단위를 반으로 줄이는 것만으로 수백 ms를 줄일 수 있다는 근거는 없다. 오버레이도 이미 프레임에서 색을 즉시 읽으므로 색 보간 제거를 새 개선처럼 제안하지 않는다.

## 권장 결론

이번 분리 보완은 역효과를 줄이기 위한 실험 기반이지 배포 승인이 아니다. **이번 사용자 문제는 화면이므로 정규 확정→draw 구간을 포함한 실측이 우선**이다. 첫 실행만 느린 경우에는 초기 입력 보존을, 실행 중 색상 전환 자체가 느리면 새로운 위상 설계 또는 짧은 문맥 모델의 정확도 검증을 별도 작업으로 잡는다. 진동 wake-up은 후순위의 별도 후보다. threshold/2회 확인을 근거 없이 낮추거나 회귀 파일만 예외 처리하지 않는다.

# 화면 색상·분류 표시 지연: 원인 분해와 개선 설계

## 확인 범위

2026-10-21, develop `3ec9725`, PR #409의 앱 구현 `6c3f171` 기준. 마지막 문서 커밋 `c5d34a9`도 원격 반영 및 CI 6개 성공을 확인했다. 현재 앱 동작은 추가 변경하지 않았다. 실험 OFF·draft 유지. 호스트 FFT 0.05ms 절감은 이 문제의 주 해결책이 아니다.

## 서로 다른 지연 경로

| 구간 | 코드에서 확인한 사실 | 해석 |
|---|---|---|
| 첫 실행 | `AudioCaptureService.startAiPipelineAsync` 완료 후 attach, `RealtimeAiPipeline.start`에서 입력 ring reset | 모델 준비와 입력 창 채우기가 직렬이다. 입력 보존으로 일부 겹칠 가능성이 있으나 실제 초기화 시간은 미측정 |
| 실행 중 입력 | `AiAudioBuffer.hasEnoughForYamnetWindow`, 15,600 samples /16kHz | 첫 창은 약975ms 필요. 이후 매 사건마다975ms를 새로 기다리는 것은 아님 |
| 분석 | `RealtimeAiPipeline`의250ms start-to-start, 최소 휴식100ms | 분석 위상 대기가 생김. 느린 기기는250ms보다 길어질 수 있음 |
| 판정 | `AiPostProcessor` 일반 coarse 전환2회, Danger 진입1회 | 말소리의 확인 대기와 위협음의 검출 지연은 원인이 다름 |
| 낮은 확신도 | `applyCoarseHysteresis`에서 threshold 미달이면 즉시 return | 이전 확정 라벨과 후보를 유지함. 단순 계산 지연과 별개로 오래된 색이 남을 수 있음. 임의 timeout은 판정 정책 변경 |
| 화면 색 | `VisualizerEngine.tick`에서 coarse 읽고 색을 즉시 대입 | 색 보간 없음. 프레임 제한과 OS 렌더 지연은 있음. idle에서 보관 중인 소리는33ms poll, 나머지는 capture wake |
| 분류 글자 | `AiDebugOverlay.DEBUG_POLL_MS=100` | 개발자 HUD는 색과 다른 경로. 확정 뒤 다음 poll까지0~100ms 명목 대기 + formatting/dispatch/render 시간이 추가될 수 있음 |
| 표시 여부·모양 | 숨김 라벨, 크기/불투명도 smoothing, 짧은 소리 보관 | 맞는 분류를 얻어도 사용자가 인지할 그림이 없을 수 있음. draw callback 발생과 실제 보이는 색을 구분해야 함 |

화면 잠금·가림 구간은 별도로 분류한다. 현재 코드에 capture wake와 잠금 복귀 수정이 이미 있으므로 새 해결책처럼 중복 구현하지 않는다. 개발자 HUD의100ms를 일반 테두리 색 지연으로 합산하지 않는다.

## 모델을 바꾸지 않은 구간별 재생 진단

124파일 ×3위상. 앞서 사용한 corpus이고 독립 holdout이 아니다. 무음 뒤 파일을 재생하며 원본 sampling rate를 유지했다. 계산은25ms 고정 가정이며 OS·화면·동시 추론 경쟁은 재현하지 않는다. baseline의 raw 유효 후보/확정 시점만 관찰하며 기존 결과를 변경하지 않는 테스트를 추가했다. 요약은 [latency-stages-407.json](latency-stages-407.json).

| 대상 | 첫 유효 후보 snapshot까지 중앙값/p95 | 첫 계산 가정 | 첫 후보 완료→확정 중앙값/p95 | 총 중앙값/p95 |
|---|---:|---:|---:|---:|
| Speech189조건,189검출 |214/336ms|25ms|250/250ms|489/611ms|
| Danger18조건,15검출·3미검출 |898/1551ms|25ms|0/0ms|923/1576ms|

각 조건의 세 구간 합은 해당 검출 지연과 같다. 일반적으로 서로 다른 구간의 percentile을 더하면 안 된다. 첫 구간은 모델이 단서를 얻는 시간과 정규 분석 위상이 합쳐진 것이므로 순수 모델 지연으로 해석하지 않는다. Danger 원본은 chainsaw/thunderstorm6개뿐이다. 총성/사이렌 일반 성능을 뜻하지 않는다. Speech도63원본 중60개가 영어 숫자다. 위협음 미검출3조건을 지연 통계에서 숨기지 않는다.

이 자료에서는 Speech189조건 모두 첫 유효 후보 뒤250ms를 기다렸다. 즉 말소리의 가장 큰 수정 가능한 대기 중 하나가 확인 간격이다. 하지만 기존2회 확인을1회로 줄이면 오경보 보호를 없애므로 해결책으로 채택하지 않는다. Danger는 이미1회 확인이라 같은 변경으로 이득을 기대할 수 없다.

```bash
OPENBLAS_NUM_THREADS=1 python tools/ai_reference/evaluate_responsive_inference.py \
  --manifest /tmp/sovis-407-validation-v2/manifest.json \
  --model-dir app/src/main/assets/ai --output /tmp/latency-stages.json \
  --arms --diagnostic-events
python tools/ai_reference/summarize_latency_stages.py /tmp/latency-stages.json /tmp/stages-summary.json
python -m unittest discover -s tools/ai_reference -p 'test_*.py'
```

## 개선안과 채택 순서

1. **확정 결과 전달을 먼저 측정하고 HUD poll을 이벤트로 대체.** 동일한 정규 결과를 conflated notification으로 전달해 최신 값만 읽게 하면 판정 자체를 바꾸지 않고 HUD의poll대기를 줄일 수 있다. developerMode 수명에만 구독하고 pause/detach/mapping 변경시 초기화한다. level·age 표시를 위한 저빈도 timer는 분류 이벤트와 분리하고 결과마다 overlay 전체를 recomposition하지 않는다. 이는 분류 글자용 개선이고 색의 수백ms 병목 해결은 아니다. 아직 구현하지 않았다.

2. **말소리 확인 전용의 작은 streaming verifier를 검증.** 기존 YAMNet·정규 판정은 유지하고 Ambient→Speech 첫 유효 후보가 있을 때만 최근100~200ms의 새 입력을 검증하는 후보를 비교한다. 일반 VAD만으로 Speech를 확정하면 음악/군중/잡음과 세부 라벨을 구분하지 못할 수 있다. 최근 말소리 판별용 학습·calibration과 독립 음성/음악/TV/게임/혼합음 검증이 필요하다. 별도 YAMNet 전체를 한 번 더 돌리는 현재 실험보다 낮은 비용으로 확인 대기를 줄일 가능성이 있으나, 아직 모델도 정확도 보장도 없다. verifier가 늦거나 불확실하면 정규 결과를 따른다. 후보 표시를 확정으로 오해시키지 않고, 임시 결과 만료 때 깜빡이는 문제까지 표시 정책으로 검증해야 한다. 목표는 첫 후보 이후 대기를100ms 이상 줄이는 것이며 달성 수치가 아니다.

3. **위협음·연속 전환에는 짧은 문맥 또는 streaming 주 모델 평가.** 첫 후보가 늦는 경우 확인만 당겨도 해결되지 않는다. 사건 시작/종료 annotation이 있는 한국어 대화, 배경음→대화, 대화→위협음, 짧은 충격음으로 onset→raw 유효 후보를 비교한다. 최근 입력에 민감한 모델이 오경보를 늘리지 않는지 평가하고, 긴 문맥의 안정성을 유지하는 teacher/student 후보도 비교한다. 현재 고정 입력을 단순히 잘라 padding하는 방식은 채택하지 않는다. 학습/모델교체 구현은 이 PR 밖이다.

4. **첫 실행에만 bounded 입력 보존.** 모델 준비 전부터 최근975ms만 수집해 준비 완료 직후 최신 창을 전달한다. 중복/역순 PCM, 이전 capture session·mapping 입력, 잠금 복귀의 오래된 Danger를 막아야 한다. 추론 정답을 그대로 보장하는 공짜 최적화는 아니지만 모델 로딩+창 축적의 직렬 대기를 겹치는 구체적 후보다. 실행 중 전환 지연에는 효과 없음.

현재 별도 YAMNet preview는 정규 경로의 앱 수준 대기를 제거했지만 RAM·CPU 경쟁과 오표시 노출 증가가 남고,189조건 중99개는 빨라지지 않았다. 따라서 이 실험을 일반 활성화하는 것을 근본 해결로 선택하지 않는다. 고정200ms 분석이나 threshold 하향도 앞선 정확도 회귀 때문에 바로 적용하지 않는다.

## 다음 검증에서 성공을 판정하는 기준

- capture frame index/session ID와 monotonic 시각으로 PCM→snapshot→raw→confirmed→첫 실제 draw/HUD 적용을 연결한다. wall clock 기반 `timestampMs`를 monotonic과 빼지 않는다. AudioRecord 수신 시각은 마이크에 실제 소리가 도착한 시각과 같다고 가정하지 않는다.
- 정규/임시 결과를 구분하고 draw가 실제 표시 가능했는지 기록한다. draw 시각은 디스플레이 광출력 시각이 아니므로 최종 체감 평가는 영상/기기 trace로 확인한다. 입력 오디오 업로드 없이 고정 크기 진단만 유지한다.
- 첫 실행/정상 실행/잠금 복귀, 색/HUD, 내부음/마이크를 분리한다. 마이크 자기진동 치환으로 입력이 과거 소리로 바뀌는 경우도 따로 기록한다.
- 동일 사건 paired 지연 median/p95와 미검출, 잘못된 색 노출시간, 깜빡임, Danger recall, CPU/RSS/발열을 함께 비교한다. 가설 목표는 steady Speech100ms 이상 단축, Danger 악화 없음이며 현재 달성 선언이 아니다. 표본 수와 신뢰구간까지 보고 작은 표본의 회귀0을 무회귀 증명으로 쓰지 않는다.

실기기가 없는 현재 완료한 것은 구조 보완, 재현 가능한 병목 분해, 개선 설계다. 사용자 체감 문제 해결 완료를 선언할 근거는 아직 없다.

계측 근거: Android [AudioTimestamp](https://developer.android.com/reference/android/media/AudioTimestamp)는 framePosition의 capture 시각을 추정하며 timebase를 명시한다. 시스템이 모르는 지연까지 보정하지는 않는다. [YAMNet 공식 설명](https://blog.tensorflow.org/2021/03/transfer-learning-for-audio-data-with-yamnet.html)의 feature patch는0.96초이며 앱의975ms waveform 요구량과 구분한다. 짧은 verifier는 이 문서의 미검증 제안이며 공식 자료가 성능을 보장하는 것은 아니다.

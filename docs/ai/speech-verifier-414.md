# #414 짧은 말소리 확인기: 현재 정책은 채택하지 않음

일반 VAD를 보조 확인기로 쓰면 일부 말소리를 앞당길 수 있지만, 이번 두 정책은 **정확도를 유지하며 유의미하게 지연을 줄인다는 채택 기준을 통과하지 못했다.** 앱 모델·임계값·2회 확인·화면 동작은 변경하지 않았다. #411 계측, #413 HUD 알림과 독립된 호스트 실험이다.

## 무엇을 비교했나

[실험 전 계획](speech-verifier-plan-414.md)을 커밋 `82c4bdf`로 먼저 고정했다. Silero VAD를 녹음 시작부터 실행하는 `continuous`, 첫 유효 Speech 후보가 생길 때160ms 입력으로 준비하는 `triggered160`을 비교했다. 이후 새32ms block3개가 각각0.9 이상이어야 조기 coarse Speech를 표시한다. 다음 정규 판정은 항상 우선하며 보조 결과는 정규 상태를 바꾸지 않는다.

공식 모델은 [Silero v6.2.3의 고정 ONNX](https://github.com/snakers4/silero-vad/blob/5cd7945676eb32225748052e2e6a0580e4686a08/src/silero_vad/data/silero_vad.onnx)다. [공식 wrapper](https://github.com/snakers4/silero-vad/blob/5cd7945676eb32225748052e2e6a0580e4686a08/src/silero_vad/utils_vad.py)의16kHz/512sample +64sample context/recurrent state 계약을 따랐다. 라이선스는[MIT](https://github.com/snakers4/silero-vad/blob/5cd7945676eb32225748052e2e6a0580e4686a08/LICENSE)이며 모델은 앱/저장소에 추가하지 않았다.

## 결과

기존124원본 ×3위상 =372조건을 **재사용**했다. Speech189조건(63원본), Ambient165조건(55원본), Danger18조건(6원본)이다. 고정 정규250ms/계산25ms, 보조 호출당2ms 가정에서:

| 지표 | 상시 상태 유지 | 후보 후160ms 준비 |
|---|---:|---:|
| 말소리 개선 / 동일 / 악화 / 새 미검출 |97 /92 /0 /0|85 /104 /0 /0|
| paired 첫 표시 단축 중앙값 |95ms|0ms|
| 원본 단위 bootstrap95% 구간 |0~95ms|0~95ms|
| 각 arm 지연 중앙값 / p95 (기준489 /611ms) |425 /585.4ms|443.2 /547ms|
| Ambient의 추가 Speech 표시 시간 |126.7ms|126.7ms|
| 추가 노출 조건 / 처음 오표시가 생긴 조건 |1 /0|1 /0|
| 조기 표시 후 정규 비Speech로 철회 |0|1회 /101ms|
| 추가 VAD 호출 수 (전체 재생 합계) |94,506|2,747|

단축량은 동일 조건의 차이를 계산한 **paired 중앙값**으로 본다. 두 arm의 중앙값을 빼서 전체 사용자의 개선량으로 부르지 않는다. Danger 정규 판정이 유지되고 기본 첫 검출이 늦어지지 않는 것은 이 replay가 정규 결과를 그대로 보존하도록 설계한 결과다. 실제 두 작업자의 CPU·RAM·발열 경쟁을 검증한 것이 아니다.

사전 지정 민감도 분석(확률0.75/0.5, 호출8/32ms)도 [요약 JSON](speech-verifier-414/summary.json)에 모두 남겼다. 임계값을 낮춘 결과를 사후에 최종 정책으로 선택하지 않았다. 호출32ms에서 triggered 방식은 준비 비용 때문에189조건 모두 개선0이며 기본 결과로 복귀했다. 기한 후 실제 계산이 끝난 횟수와 단순히 확률 기준을 못 채운 경우를 구분한다.

### 실패 원인1: 서로 다른 모델도 같은 오분류를 확인할 수 있다

`3-144692-A-21.wav`는 ESC-50의 **재채기(sneezing)** 자료다. phase100ms에서 기존 YAMNet이 Speech 후보를 내고 VAD도 새3block을0.9 이상으로 받아들였다. 마지막 확률은 상시0.969, triggered0.964였다. 그 결과 기존의 잘못된 Speech 표시를 약126.7ms 더 일찍 시작했다.

새 오분류 파일이0개여도 **기존 오표시 시간을 늘리는 회귀**다. 일반 VAD의 음성 활동과 제품의 Speech 범주는 일치하지 않으며, 연속3block은 recurrent state와 문맥을 공유하므로 독립된 세 표가 아니다. 기존2회 확인을 이 세 값으로 치환해 같은 정확도라고 가정할 수 없다. 재채기 한 파일을 예외 처리하거나 결과를 보고 임계값만 높이는 해결은 채택하지 않는다.

파일 단위 class에 따른 노출시간은 순간별 annotation을 대신하는 proxy다. 이 재채기 사례는 명확한 경고지만 음악/TV/혼합음 전체의 실제 오경보율을 추정하는 자료는 아니다.

### 실패 원인2: 짧은 준비 방식은 성공 범위가 작고 잠깐 나타나는 결과가 있다

triggered 방식은 비용이 작지만104/189조건에서 첫 표시를 앞당기지 못했다. 영어 숫자 자료180조건 중78개, 긴 낭독9조건 중7개가 개선됐다. 긴 낭독은3원본뿐이므로 일반화하지 않는다.

`5_theo_0.wav`, phase100ms의 첫 표시만 보면861→260ms로601ms 빨라진 것처럼 보인다. 그러나101ms 표시 뒤 정규 Ambient로 되돌아간다. 이601ms는 안정적으로 유지된 판정의 단축이 아니다. 집계에는 철회 횟수/시간과 `saved_without_reverted_preview_median_p95_ms`를 별도로 남겼다. 일반 적용은 첫 순간뿐 아니라 지속 표시·철회·깜빡임을 함께 판정해야 한다.

### 시간·비용 해석의 한계

- FSDD60원본은8kHz이고 이번 baseline은512 native-frame 수신을 모델링한다. 따라서 이 자료의 수신 단위는64ms다. 실제 Android44.1/48kHz의 약11ms와 같지 않다. 관측한95ms를 실기기 개선 상한이나 예상값으로 쓰지 않는다. 다음 검증에서는 실제 캡처 rate로 입력을 재생하고 baseline부터 다시 계산해야 한다.
- FIR 우측48 native sample과 capture chunk가 수신될 때까지 기다린다. 미래 입력으로 과거에 판정하는 낙관적 계산을 피했다. VAD 준비5호출도 비용에 포함한다.
- 상시는 약31.25회/초, triggered는 후보당 제한된 호출이다. 단일 ONNX 호출 호스트 실행시간은 요약 JSON에 따로 기록했다. 수신·리샘플링·스레드 경합·Android 세션 RSS·전력·발열·실제 그리기는 포함하지 않는다. 작은 모델의 단일 호출이 빠르다고 상시 사용의 비용이0인 것은 아니다.
- fixed-slot trace는 정규 추론 overrun/wake jitter를 재현하지 않는다. 보조 모델 준비 완료를 가정하며 초기 로딩 지연도 실제 앱 측정이 아니다. OS 우선순위만으로 자원 격리를 보장할 수 없다.
- 자료 대부분이 짧은 영어 숫자이며 독립 holdout이 아니다. Danger는 chainsaw/thunderstorm6개뿐이다. 기본 미검출3조건도 남는다. 한국어 대화·짧은 위협음·게임/TV·연속 전환 정확도가 입증된 것은 아니다.
- 조기 coarse 결과를 기존 세부 라벨·확정 결과·진동에 전역 적용하는 Android 구현은 하지 않았다.

## 다음 설계: 제품 범주를 확인하는 작은 모델이 필요하다

이번 결과로 **generic VAD만 연결하는 구현은 보류**한다. 체감 개선 잠재력은 있으므로 후속 설계는 다음으로 좁힌다.

1. **사건 시작/종료 annotation과 제품 라벨을 먼저 고정한다.** 한국어 자연 대화, 재채기/기침/웃음/호흡, 음악/노래/TV/게임, 환경음→말소리, 말소리→짧은 위협음을 포함한다. 원본/화자/녹음 세션 단위로 train·calibration·holdout을 분리하고, 이번124원본은 개발 자료로만 둔다. 실제44.1/48kHz 수신 경로로 평가한다.
2. **최근128~192ms와 첫 YAMNet 후보의 증거를 입력으로 하는 전용 Speech verifier를 학습·비교한다.** 단순 voiced/unvoiced가 아니라 제품의 Speech/비Speech를 구분해야 한다. 재채기 등 두 모델이 함께 틀리는 hard negative를 학습하되 별도 원본으로 검증한다. YAMNet의 오답을 그대로 증류하는 것만으로는 이 문제를 해결하지 못한다. 구체 모델/학습 성능은 아직 없다.
3. **Android 적용 후보는 단일 선택적 worker + 제한된 준비 비용으로 설계한다.** 정규 스케줄은 독립 유지, 늦거나 불확실하면 기본값, 세션·mapping·parent 검증, 새 Danger 최우선. 파일별 첫 순간 외에 지속 표시 지연, 잘못된 색 노출시간, 철회 횟수를 평가한다. 별도 작업자도 실제 CPU 경합 검증은 필요하다.
4. **진행 기준은 독립 자료에서 정확도·오표시 비열등성과 paired 지속 표시 단축100ms 이상이다.** acceptable accuracy margin은 임의로 정하지 않으며 현재 사용자 요구를 따라 추가 오표시를 허용하는 결론을 내리지 않는다. 합격한 경우에만 #411 계측과 #413 알림을 통합한 Android 시제품으로 실기기 비교한다.

학습·원본 수집은 이 PR의 완료 항목이 아니다. 이번 완료 항목은 후보 정책의 실험, 기각 근거, 재현 도구와 다음 설계다. 즉 현재까지의 앱 딜레이 문제가 해결됐다는 결론은 아니다.

## 재현과 검증

15개 호스트 테스트로 새 입력3개 확인, 수신 시각 반영, 지연 폐기, 준비 비용, Danger 우선, 상태 보존, 표시 철회, activity marker가 없어도 노출 집계,5가지 rate의 resample 일치/미래 샘플 비사용, 실제 ONNX 상태 초기화를 확인한다. CI도 같은 테스트를 실행하며 앱에는 변경이 없다.

`speech-verifier-414/baseline.json.gz`는 #407 원본 trace를 정확히 압축한 파일이다. `full-report.json.gz`에18개 profile의 모든 조건과 preview 시각을 보관했다. `summary.json`에 두 원본 JSON의SHA256, 버전, 집계와 주요 실패 조건을 둔다. 원본 오디오/모델은 포함하지 않는다.

```bash
# 기존 tools/ai_reference/requirements.txt 설치 환경 사용
curl --fail --location \
  https://raw.githubusercontent.com/snakers4/silero-vad/5cd7945676eb32225748052e2e6a0580e4686a08/src/silero_vad/data/silero_vad.onnx \
  --output /tmp/speech-verifier.onnx
OPENBLAS_NUM_THREADS=1 SOVIS_VERIFIER_MODEL=/tmp/speech-verifier.onnx \
  python -m unittest discover -s tools/ai_reference -p 'test_speech_verifier*.py'
OPENBLAS_NUM_THREADS=1 python tools/ai_reference/evaluate_speech_verifier.py \
  --baseline docs/ai/speech-verifier-414/baseline.json.gz \
  --vad-model /tmp/speech-verifier.onnx \
  --audio-root /tmp/sovis-407-validation-v2 \
  --output /tmp/verifier-new.json
```

오디오는 #409의 `36a3964`에 있는 `prepare_responsive_validation.py`와 `docs/ai/responsive-validation-407.json`의 고정 원본을 사용한다. 각 source/decoded hash를 검사하므로 동일 이름의 다른 파일은 거부한다. OGG는 원본 다운로드 hash와 decoded WAV hash를 구분한다. 모든 오디오가 준비되어야 전체 replay를 돌릴 수 있다. 호스트·ORT 버전 차이로 확률/실행시간이 달라질 수 있으므로 보고서의 환경 정보를 함께 비교한다.

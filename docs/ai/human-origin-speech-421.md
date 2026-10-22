# #421 승인된 사람 소리 정책과 Silero 재평가

사용자는 해커톤 실증에서 “사람이 소리를 냈다”는 정보를 전달하기 위해 Speech를 human-origin vocal sound로 확정했다. 대화·노래·웃음·울음·기존 승인 발성에 **Sneeze / Cough / Breathing**을 더한다. 특정 파일을 통과시키기 위한 임시 정답 변경이 아니다. Danger 매핑, safety allowlist, threshold, 정규2회 확인 의미는 유지한다.

production mapper와 Python reference에 같은 exact label을 추가한다. `Breathing machine`이나 동물 소리까지 keyword로 넓히지 않는다. 한국어 UI의 ‘대화음’은 좁은 뜻이므로 핵심 종류 명칭/설명은 ‘사람 소리’로 맞춘다.

## 역사 자료와 현 정책

- #414/#415는 당시 재채기/기침/호흡=Ambient 정책의 실험이다. 기존 JSON·해시·사전 계획은 역사 증거로 보존한다. 그 정책에서 탈락했던 재채기→Speech는 **새 정책에서는 정답**이므로 같은 이유로 탈락시키지 않는다.
- #416/#419의83개 원본·선택·annotation pending은 보존한다. 신규 평가에서는 모든 sneezing/coughing/breathing 범주를 Speech로 해석하고 웃음/울음/노래와 일관되게 처리한다. 사람이 검토한 timestamp가 생긴 것처럼 바꾸지 않는다.
- 과거 trace의 expected만 바꿔서 새 결과라고 부르지 않는다. 새 mapper로 YAMNet coarse voting/threshold/정규 확인을 다시 계산한 baseline과 같은 입력의 Silero 후보를 비교한다. 동일한 과거189 Speech 조건도 별도로 보고한다.

## 진단 평가 사전 설정

Silero v6.2.3 ONNX SHA256 `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`,16kHz512sample+64context, 별도 상태. #415의 상시/후보 후160ms 준비 정책을 유지한다. 새 후보 완료 후 시작하는32ms block3개 각각0.9 이상, 호출2ms 가정, 다음 정규 예정 시작 전에 채택하고 다음 정규 완료까지 임시 표시한다. 8/32ms 비용 가정도 비교한다. 정규 상태에 보조 결과를 주입하지 않는다.

기존124개/신규83개를 각각 구분한다. 원래 sample rate 재현은 과거95ms와 비교하기 위한 것이며, 실제 앱44.1/48kHz 수신 조건은 별도로 baseline부터 재생한다. onset은 PCM activity proxy이고 첫 표시/지속 표시/정규 확정은 구분한다. 기악과 비인간 Ambient의 노출시간/새 조건, 철회, miss, Danger 첫 검출과 정규 타임라인, VAD 호출·실행시간을 보고한다. 노래가 포함될 수 있는 과거 음악 파일은 파일 단위 Ambient 진단 proxy와 라벨 불확실성을 따로 표시한다.

완전한 독립 holdout/일반화 성능을 주장하지 않는다. #419의 pending annotation은 진단 실행을 막지 않지만 정확도 증명으로 승격하지 않는다. paired100ms를 절대 문턱으로 쓰지 않고 수치적 개선·주요 Speech 이득·명확한 Ambient 회귀 크기·Danger 보존·앱 안정성을 종합한다. 유망하면 **별도 feature PR**에서 기본 OFF 비교 후보/계측/APK/Galaxy 절차를 준비한다. 머지는 하지 않는다.

## 결과 — 같은 입력의 baseline 대 상시 Silero

단위 ms. 정규 YAMNet25ms, Silero1호출2ms를 가정한 진단 replay다. median/p95는 양쪽 검출된 같은 조건에 한정한다. 조건은 파일×시작 위상0/100/200ms이며 독립 표본3배를 뜻하지 않는다. paired는 **각 조건의 차이**를 구한 분포로, 두 arm median의 단순 차이가 아니다.

| 범위 | Speech/paired | baseline 첫 표시 median/p95 | 후보 첫 표시 median/p95 | paired 단축 median/p95 | 개선/동일/악화 | baseline/후보 미검출 |
|---|---:|---:|---:|---:|---:|---:|
| #415 당시 Speech189, native |189/189|489 / 611|425 / 585.4|**95 / 101**|97 / 92 / 0|0 / 0|
| 기존124 전체의 새 Speech, native |207/204|489 / 707.7|447.6 / 672.3|**0 / 101**|98 / 106 / 0|3 / 3|
| 신규83의 Speech, native·미검토 |240/229|765 / 1568.3|738 / 1543.4|123 / 149|131 / 98 / 0|11 / 11|
| 합계207파일, native |447/433|547 / 1329|516 / 1256.3|**95 / 149**|229 / 204 / 0|14 / 14|
| 합계207파일,48k 재생 변환 |447/434|561 / 1314.4|521 / 1212.6|**112.3 / 138.3**|233 / 201 / 0|13 / 13|

baseline의 첫 표시는 정규 확정과 같고, 후보는 그 앞에 **임시 화면 표시**만 더한다. **정규 확정 지연은 양쪽 동일**(native547/1329,48k561/1314.4)이다. 모델 자체 정확도 개선·확정이95ms 빨라졌다는 뜻이 아니다. 기존124의 새 Speech 전체 native median은0이므로 모든 cohort/사례에서 개선된다고 주장하지 않는다. 기존 재채기 오표시126.7ms는 새 정책에서 사람이 낸 소리의 올바른 조기 표시로 해석되며 그 이유만으로 탈락하지 않는다.

48k 범주별 개선/동일: 한국어 읽기124/26(미검출0,paired median126.3), 영어 읽기9/0(0,118.3), 짧은 숫자94/86(0,118.3), 기침5/20(2,0), 재채기1/26(0,0), 호흡0/8(10,0), 웃음0/17(1,0), 아기 울음0/18(0,0). **호흡·웃음·울음의 개선은 확보하지 못했다.** Silero는 사람 소리 의미 전체를 푸는 모델이 아니라 첫 Speech 후보를 보조하는 VAD이므로, 이 범주를 별도 기종에서 꼭 확인한다. 한국어50개는10화자의 읽기이지 자연 대화50개가 아니다.

### 오표시 / 철회 / Danger

- Ambient→Speech 추가 시간 **native0ms,48k0ms**. 기악3개/9조건도 증가0. 기존 Ambient 오표시 자체는 native500ms,48k2250ms로 양쪽 동일하게 남는다(48k기차1750+물방울500). native→48k의 차이를 Silero 회귀로 비교하지 않는다. 과거 음악 파일의 파일 단위 label은 불확실성이 있어 final accuracy가 아니다.
- 상시 native 철회0;48k **1건124.3ms**(`legacy:2_lucas_0.wav`,phase0,1900.7→2025ms). 정규 확정 전 Speech가 잠깐 Ambient로 돌아가는 위험이다. 그 사례의 첫 flash 단축624.3ms를 안정적 이득으로 팔지 않는다(철회 제외 이득0). 전체 안정적 paired median도112.3ms이다.
- 후보 후 실행(triggered160,cost2): native193개 개선/240동일,48k182개 개선/252동일, **paired median 둘 다0**. 각각 철회1건(native101ms,48k124.3ms). 따라서 호출이 적어도 이번 목적의 주 후보로 고르지 않는다.
- 기존 Danger18조건: baseline 미검출 native3/48k2가 남고, 상시 후보 추가 손실0·추가 지연0. 정규 타임라인은 독립 계산을 가정한 replay에서 그대로 유지하는 구성이다. **Android CPU 격리의 실증이 아니다.**
- 추가 대표 Danger25개×3위상=75조건(사이렌/화재·연기 경보/차량 경적 등)을48k로 재계산했다. 옛 mapper 대 새 mapper의 첫 Danger 결과 **75/75 동일**, baseline 미검출7은 그대로다. Silero 추가 손실/지연0,preview채택0. Danger 의미·allowlist·threshold는 변경하지 않았다. 앱에서는 별도 worker가 정규 `inferLock`/모델 세션/후처리 상태를 공유하지 않고, 화면만 임시로 보이며 진동·잠금 알림은 정규 결과를 읽는다. native 호출 중 강제 해제하지 않고 owner가 자원을 닫는다.

### 비용과 한계

native 총 replay5841.591초(무음/세 위상 포함): 정규16964호출 그대로, 상시VAD182253호출≈31.2회/초, triggered5742호출.48k 정규16945/상시182243/triggered5514호출. 가정2ms이면 보조 실행62.5ms/초 규모이고, 실측 호스트 단일VAD호출 median/p95는 native0.078/0.102ms(ARM64 host ORT1.31)이다. **이것을 Galaxy CPU/발열/절감 지연 수치로 사용하지 않는다**. 앱은 resampling/FIR/session/state/추가 메모리 비용도 든다. cost8/32ms 민감도에서도48k상시 paired median106.3/82.3ms지만 이것 역시 CPU경합 없는 replay다. 모델 약2.3MB, 별도1thread CPU session이 추가된다.

신규83개는 annotation human-review/timestamp 미완료, 자연대화 부족, 재사용 corpus와 선택 상관관계, 라이선스/출처 한계가 있는 **진단 자료**다. 독립 최종 정확도나 일반화 성능을 주장하지 않는다.48k변환은 명시한 linear playback변환 뒤 reference FIR이지 Android 오디오 mixer의 완전 재현이 아니다. 실제 Galaxy44.1/48k 비용/발열/regular p95/실제 draw/철회를 확인해야 한다.

## 판단과 후속

**production 비교 후보로 채택**, 기본 OFF의 별도 #422 / draft PR #423에서 baseline/candidate APK를 제공한다. 기존 탈락 원인이 새 정책에서 해소되고 첫 표시 이득을 재현했으나, 실기기 채택/production ON은 아직 승인하지 않는다. #411 계측·#413 HUD 갱신을 양쪽에 같게 넣어 Silero 순증을 비교하고, #419 corpus draft는 후속 엄격한 verifier 평가용으로 보존한다. Galaxy 절차는 #423의 `docs/ai/silero-galaxy-422.md`. 머지하지 않는다.

## 재현/근거

`artifacts/human-origin-421/summary.json`에 cohort/범주/비용별 결과, raw/gzip/model/manifest SHA256, 철회 사례를 저장했다. `native.json.gz`, `48k.json.gz`, `danger48k.json.gz`는 원본 rows의 lossless gzip이다. 원본 WAV는 연구용 라이선스/개인정보 범위를 지키기 위해 APK에 포함하지 않는다. 기존124은 #407/#415 manifest, 신규83은 #419 manifest와 원음 준비 절차를 사용한다. frozen #415/#419 expected는 당시 증거로 보존하고 **새 실행에서 전체 범주의 expected를 `human_origin_policy.expected_for()`로 일관되게 변환**한다. 옛 trace 전용 `evaluate_speech_verifier.py`는 명시적인 `--historical-414` 없이는 실행을 거부한다. responsive runner의 새 실행도 같은 글로벌 정책을 적용한다.

```sh
OPENBLAS_NUM_THREADS=1 python tools/ai_reference/reevaluate_human_speech.py \
  --legacy-manifest LEGACY124/manifest.json --new-manifest NEW83/manifest.json \
  --new-audio-dir NEW83/audio --model-dir app/src/main/assets/ai \
  --vad-model silero_vad.onnx --rate native --output native.json
# 같은 명령에 --rate 48000 --output 48k.json
# 대표 Danger: --extra-danger-manifest REPRESENTATIVE/manifest.json --only-extra-danger
# --rate 48000 --output danger48k.json
python tools/ai_reference/publish_human_speech_report.py --native native.json \
  --rate48 48k.json --danger48 danger48k.json --output-dir REPORT
```

호스트 의존성 Python3.12 / numpy2.5.4 / onnxruntime1.31.0 / soundfile0.13.1. 모델/manifest 해시가 달라지면 같은 실험이라고 부르지 않는다. 최초 결과 뒤 runner에 추가된 대표 Danger 비교 기능은 동일한 replay/mapper/VAD 계산을 사용한다.

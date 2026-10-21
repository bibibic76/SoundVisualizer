# #407 사용자 피드백에 따른 반응 지연 실험

사용자 피드백은 “소리 분류 반응이 조금 느린 것 같다”이다. 이번 목표는 FFT 계산 비용(#403/#406)보다 큰, 첫 올바른 분류의 반응 시간을 줄이는 것이다. 기준은 `develop` `3ec9725`이며 잠금 해제 복구 #402를 포함하고 FFT 초안 #406은 포함하지 않는다.

## 현재 결론

**기본 배포 동작에는 적용하지 않는다.** 말소리의 두 번째 확인을 앞당기면 약 100~125ms의 개선 여지가 있었다. 최근 입력 활성 조건을 추가해 환경음 → 말소리 회귀를 3개에서 1개 조건으로 줄였지만 완전히 없애지는 못했고, 추가 추론도 있었다. 따라서 사용자 목표인 “성능 저하 없이”를 달성했다고 선언할 수 없다. 기본 빌드의 `aiSpeechConfirmationExperiment`는 false이며, true 비교 APK와 재현 도구만 제공한다. 실기기 체감·발열·전력 검증도 미완료다.

## 비교 방법

- 이전의 812 WAV manifest를 읽기만 한다. 원본 파일을 복사·편집·학습하지 않는다.
- 각 파일 앞에 1.5초 무음, 뒤에 1.5초 무음을 메모리상 붙인다. 시작 위상은 0/100/200ms 세 개다. 총 2,436개 조건, 주 평가 645파일 × 3 = 1,935개 조건이다.
- 원본 채널 512-frame 캡처 덩어리의 peak/power, 현재 무음 게이트, 단일 추론 소비자, 100ms 최소 휴식, 기존 모델·매핑·threshold·2회 확인을 유지해 replay한다.
- 추론 소요시간은 **가정값 25ms**다. 실제 Android에서 잰 시간이 아니다. 늦게 깨어나거나 실행 시간이 튀는 비용, 시스템 mixer·화면·진동·열은 재현하지 않는다. 원본 96kHz 파일도 native rate에서 replay하므로 실제 앱의 44.1/48kHz 캡처 분포와 같지 않다.
- onset은 원본 채널 peak > 0.01인 첫 캡처 덩어리의 시각이다. 사람이 붙인 의미상 사건 시작 정답이 아니다. 이 시각 뒤 새 유효 예측으로 목표 coarse가 확정된 시각까지를 계산한다.
- Ambient의 기본 UI가 원래 Ambient라는 이유로 지연 0을 부여하지 않는다. 지연은 Speech/Danger에서만 비교한다. 미검출 전체 및 양쪽 모두 검출된 paired 조건을 함께 보고한다.
- 파일별 다수결은 소스 재생 구간의 **UI 유지 시간**으로 계산한다. 기존 #403의 프레임 횟수 다수결·무음 없는 replay와 지표를 혼합하지 않는다. Danger 노출 시간에는 파일 뒤 1.5초 tail을 포함한다.
- 반복 사용한 corpus다. 위상 3개는 독립된 새 음원이 아니며, Speech는 15개 원본에 불과하다. 아래 수치는 독립 holdout 성능이나 실제 폰의 사건 검출 지연이 아니다.

## 후보와 관찰

파형의 무음 복귀/에너지 증가를 감지한 뒤 50/100ms에 기존 두 분석 슬롯을 당기고 원래 위상으로 복귀하는 후보부터 비교했다. 81파일 × 3위상에서 50ms 후보는 Danger paired median 516 → 366ms였지만 Speech 545 → 661ms로 악화됐다. 이른 입력이 아직 불충분하거나 긴 복귀 간격이 생기는 문제가 있어 앱에 넣지 않았다. 위상 자체를 재설정하는 후보도 Speech 지연 회귀와 추가 추론이 있었다.

다음으로 모델이 이미 Speech 후보를 냈을 때 필요한 **두 번째 관측**만 앞당겼다. 단순 이동은 실패한 이른 관측이 기존 후보를 지워 지연을 늘리는 사례가 있었다. 실패 결과를 버리고 기존 후보·정규 슬롯을 유지하면 그 회귀를 막을 수 있었지만, Danger 해제나 순간 Speech 확정의 의미가 바뀔 수 있었다.

앱의 실험 경로는 다음처럼 제한했다.

1. 현재 확정 상태 Ambient, 유효한 Speech 후보 1회일 때만 시도한다. Danger 진입·해제, 다른 coarse 후보는 당기지 않는다.
2. 직전 tick이 25ms 이하이고, 종료 후 100ms 휴식 및 정규 tick으로 돌아갈 예상 여유가 있을 때만 시도한다. 깨어난 뒤에도 여유를 재확인한다. 예측보다 실제 추론이 길어지면 정규 tick이 늦어질 수 있으므로 실기기 검증이 필요하다.
3. 새 관측의 정확한 세부 라벨이 같고 confidence가 떨어지지 않았으며 기존 threshold를 통과해야 기존 2회 확인으로 확정한다. 확인 횟수나 threshold를 낮추지 않는다.
   - 추론 전과 확정 직전에 모두 최근 100ms의 캡처 덩어리가 계속 활성(기존 peak > 0.01 기준)이고 마지막 입력도 100ms 이내여야 한다. 0.975초 창에 남은 짧은 단서만으로 조기 확정하지 않기 위한 조건이다. 현재 입력이 조용하면 추론 전부터 생략한다.
4. 실패·다른 종류·다른 라벨·낮아진 confidence는 UI와 기존 후보를 갱신하지 않고 원래 정규 tick을 유지한다. 성공했을 때만 해당 정규 슬롯을 소비한다.
5. 매핑 변경 시 이전 요청을 버린다. 같은 코루틴·기존 inference lock으로 실행하며 캡처 스레드에서는 추론하지 않는다. stop/cancel 시 대기 중 요청도 종료된다.

이는 corpus에 맞춰 confidence 숫자를 탐색한 결과가 아니다. 파형 기반 후보, 단순 조기 확인, 실패 복귀, 동일 라벨/비감소 confidence 조건이라는 단계별 가설을 모두 기록한다. 같은 corpus로 반복 선택했기 때문에 독립 검증으로 일반화할 수 없다.

## 보수적 후보 결과: 812파일 × 3위상

| 지표 | 기준 | 실험 |
|---|---:|---:|
| Speech 지연 median / p95 (45 paired 조건) | 545.1 / 818.3ms | 443.3 / 689.2ms |
| Speech 빨라짐 / 같음 / 늦어짐 | — | 31 / 14 / 0 |
| Speech paired 차이 중앙값 | — | 125ms 단축 |
| Speech 미검출 / 45 | 0 | 0 |
| Danger 미검출 / 1,146 | 83 | 83 |
| Danger paired 지연 | 기준 | 모든 paired 조건에서 동일 |
| Ambient에서 한 번 이상 Danger / 744 | 177 | 177 (새 노출 조건 0) |
| Ambient Danger 노출 합계 (tail 포함) | 206.000초 | 206.000초 |
| 주 평가 시간 다수결 정답 / 1,935 | 1,375 | 1,376 |
| 주 평가 macro F1 | 0.738332 | 0.743297 |
| Ambient → Speech 다수결 / 744 | 12 | 13 |
| 총 추론 횟수 / 전체 조건 | 47,299 | 47,449 (+0.317%) |

현재 입력 활성 조건을 넣기 전에는 아래 세 Ambient 파일의 위상 100ms에서 기준은 맞고 조기 확인은 Speech가 되는 회귀가 있었다. 조건 추가 뒤 남은 것은 ESC-50 한 조건이다.

- `ambient_딸꾹질_youtube_02.wav`: 회귀 해소
- ESC-50 `2-25293-A-22.wav`: **회귀 남음**
- DESRA `89_HmnCoughChld.wav`: 회귀 해소

라벨을 바꾸거나 이 소리들만 예외 처리해 결과를 개선하지 않는다. 전체 평균이 좋아졌다는 이유로 남은 회귀를 숨기지 않는다. 추가 추론 0.317%도 CPU·배터리 증가율이 아니다. 불안정한 입력에서는 실패한 시도가 더 잦을 수 있다. 기본 활성화하지 않는 이유다.

별도 0dB whole-file RMS 혼합 진단도 수행했다. 배경을 `.08` RMS로 맞추고 목표 소리를 같은 RMS로 메모리상 섞었다. 음악 1개 + Speech 15개는 45조건 중 기준 5개만 검출할 만큼 masking이 컸다. 입력 활성 조건을 넣기 전 동일 라벨 arm에서는 기존 5개를 유지하고 6개를 추가로 검출했다. Speech 배경 + 44.1kHz Danger 20개/60조건에서는 기준/후보 모두 36개를 검출했고 paired 지연이 같았다. native rate가 다른 5개 Danger 파일은 명시적으로 제외했다. 혼합 신호에 독립 시간 정답이 없고 마지막 입력 활성 조건도 별도로 추가됐으므로, 이 진단을 최종 후보의 일반 정확도 실증으로 합산하지 않는다.

## 앱과 A/B APK

```bash
./gradlew assembleRelease -PaiSpeechConfirmationExperiment=false
./gradlew assembleRelease -PaiSpeechConfirmationExperiment=true
```

기본값 false. 실험 true일 때도 첫 유효 후보가 없거나 느린 tick이면 기존 250ms 경로를 따른다. 일반 설정 UI나 저장된 사용자 매핑을 바꾸지 않는다.

`AI confirmation experiment APKs` workflow는 같은 commit의 false/true 릴리스 APK를 같은 공유 디버그 키로 서명해 별도 artifact로 만든다. `experiment.txt`의 commit과 mode, arm64 APK, R8 mapping을 함께 보관한다. 실기기에는 gradle connectedAndroidTest를 실행하지 않는다(기존 규칙대로 종료 시 앱 삭제 위험이 있다). 기존 설치와 서명이 다르면 데이터 삭제로 해결하지 말고 별도 테스트 설치 환경을 준비한다.

CSV 끝에 `responsive_confirmation`, `early_confirmation`, `confirmation_attempts`, `confirmation_accepted`를 추가한다. 기존 열 순서는 유지한다. rejected early 결과는 마지막 UI 판정을 대체하지 않으며 다음 정상 행의 누적 counter로 비용을 확인한다. raw model 결과가 단지 일찍 나왔다는 것과 실제 UI 확정이 빨라진 것을 구분한다.

## 재현

```bash
OPENBLAS_NUM_THREADS=1 python tools/ai_reference/evaluate_responsive_inference.py \
  --manifest /tmp/sovis-351-manifest.json --model-dir app/src/main/assets/ai \
  --arms confirm_active --output /tmp/responsive-active.json
OPENBLAS_NUM_THREADS=1 python -m unittest discover -s tools/ai_reference -p 'test_*.py'
```

원본 manifest SHA-256: `68b138da5083a75199514cf37c50761588155a6d9e885e0adfd2d00a6e207f82`. 모델은 저장소의 동일한 `yamnet.onnx`/`yamnet.data`를 사용했다. 보고서의 모델 해시와 실행 commit을 함께 확인한다. `--arms advance50 advance100 rephase50 confirm100 confirm_safe100 confirm_consistent confirm_active onset_confirm50`으로 진단 arm들을 재실행할 수 있다. `--inference-ms 50` 등은 가정한 계산 시간의 민감도 검사이며 실제 폰 측정이 아니다.

실기기가 현재 없다는 사용자 확인에 따라 이번에는 오프라인 검증·실험 경로·CI·A/B APK까지 진행한다. **일반 사용자 적용, 실제 지연·발열·전력 검증, “성능 저하 없이”라는 목표 달성은 보류**한다.

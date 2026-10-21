# #414 말소리 조기 확인: 실험 전 계획

앱은 변경하지 않는다. 목표는 Ambient→Speech 첫 유효 후보 뒤 정규 확인의 250ms 대기를 100ms 이상 줄일 가능성을 검증하는 것이다. 기존 2회 확인과 YAMNet/mapper/threshold는 유지한다. 결과를 보기 전에 아래 정책을 고정한다. 이 실험에서 통과해도 독립 검증과 Android 실측 없이는 적용하지 않는다.

## 후보

- 공식 Silero VAD v6.2.3, commit `5cd7945676eb32225748052e2e6a0580e4686a08`, MIT. ONNX SHA256 `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3` (2.21MiB). 모델은 /tmp에서만 평가하고 앱에 포함하지 않는다.
- 공식 입력 계약: 16kHz 512sample(32ms) + 이전64sample context, recurrent state `[2,1,128]`. 새 녹음/후보 cold start마다 state를 초기화한다. 32ms 입력이라고 총 문맥도32ms인 것은 아니다.
- `continuous`: 녹음 시작부터 VAD 상태를 유지한다(초당31.25호출 비용). `triggered160`: 후보 발생 때 최근 완전히 수신한5개 block(160ms)으로 상태를 준비한 뒤 새 block만 처리한다.
- 두 방식 모두 정규 raw=Speech, threshold 통과, confirmed=Ambient, candidate streak=1일 때만 시도한다. 첫 후보 완료 **이후에 시작하는** 새3개 연속 block(96ms)의 확률이 각각0.9 이상이어야 Speech 임시 표시를 허용한다. 0.5/0.75는 민감도 분석용이며 사후 최적값을 선택하지 않는다.
- 일반 VAD는 Speech 세부 종류나 제품의 음악/군중/TV 정책을 검증하지 못한다. 따라서 실험의 조기 표시는 coarse Speech만 가정하며 기존 세부 라벨 확정이라고 주장하지 않는다.
- 결과가 다음 정규 예정 시작(snapshot+250ms) 전에 준비돼야 채택한다. 표시 만료는 다음 정규 완료와 snapshot+275ms 중 이른 때. 다음 정규 결과가 항상 우선한다. 정규 상태에 보조 결과를 주입하지 않는다. preview가 철회되는 횟수와 노출시간도 보고한다.
- VAD 계산시간은 호출당2ms를 기본으로,8ms/32ms를 부담 가정으로 비교한다. 준비 호출도 직렬 비용에 포함한다. 별도 worker가 정규 계산에 미치는 CPU 경쟁은 이 replay가 보장하지 못한다.

## 데이터·시간·집계

- #407의124개 원본 ×3위상=372조건과 baseline raw/confirmed trace를 재사용한다. **개발 자료이지 새 holdout이 아니다.** trace SHA256 `0ad4cc4ab51d3ddea8753b58d334290d10ad0034d60f76f13b6dea8dd9d6f5b5`.
- 이 trace는 fixed250ms slot / 계산25ms 모델이다. 실제 정규 scheduler overrun, wake jitter, Android input backlog를 재현하지 않는다. 늦은 보조 결과 폐기와 기본값 유지의 논리만 별도 검증한다.
- 원본 hash를 확인한다. 512 native-frame capture chunk로 수신을 모델링한다. VAD용 FIR는 기존 reference 계수를 사용하며 미래48 native sample이 필요한 경우 그 데이터가 들어오는 capture chunk까지 기다린 것으로 계산한다. 원본 전체를 미리 읽어도 미래 입력을 과거에 사용하지 않는다.
- onset은 PCM activity marker이며 인간의 Speech 시작 annotation이 아니다. 파일 전체 class는 coarse label이고 혼합음의 순간별 정답은 없다.
- 주 지표: 모든 Speech 조건의 paired 첫 표시 단축 median/p95와 개선/동일/악화/손실; Ambient의 Speech 노출시간 증가 및 새 노출 조건; Danger 첫 검출/표시 유지; 조기 표시 후 정규 비Speech로 철회된 횟수/시간; VAD 호출 수와 호스트 실행시간.
- waveform별로3위상을 묶은 bootstrap을 함께 기록해 위상을 독립 원본인 것처럼 세지 않는다. 이 CI도 같은 화자/데이터셋 종속과 자료 재사용 편향은 제거하지 못한다.

## 판단 기준

정책0.9/3block, 계산2ms 기준 paired 단축 median≥100ms, 새 Speech 손실/지연 악화0, Ambient 오표시 시간 증가0, 새 잘못된 노출 조건0, Danger 정규 결과 유지가 모두 필요하다. 하나라도 실패하면 이 정책을 앱 적용 후보로 채택하지 않는다. 전부 만족해도 holdout 통과가 아니며 추가 검증만 진행 가능하다. 부족한 한국어 자연 발화, 배경→대화, 음악/노래/TV/게임, 짧은 Danger를 별도 원본 단위 holdout과 사건 annotation으로 채워야 한다.

## 출처

- [공식 입력 wrapper](https://github.com/snakers4/silero-vad/blob/5cd7945676eb32225748052e2e6a0580e4686a08/src/silero_vad/utils_vad.py)
- [공식 MIT 라이선스](https://github.com/snakers4/silero-vad/blob/5cd7945676eb32225748052e2e6a0580e4686a08/LICENSE)
- [공식 모델 파일](https://github.com/snakers4/silero-vad/blob/5cd7945676eb32225748052e2e6a0580e4686a08/src/silero_vad/data/silero_vad.onnx)

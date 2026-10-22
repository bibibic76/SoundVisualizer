# #416 말소리 확인기: 신규 검증 후보와 학습·평가 계약

**신규 후보83개, 약680초를 준비했다. 모델 추론·학습은 하지 않았다.** 알려진 과거 원본/그룹 및 파일·decoded PCM 해시와의 중복은0이다. 그러나 정답 구간 검토0건, 자연 대화·전환·위협음 부족, 일부 신원/원 배포 대조 미완료이므로 **평가 준비 완료가 아니다** (`ready_for_evaluation=false`). 앱 변경·정확도/지연 개선 주장도 없다.

## 준비된 것과 남은 것

| 후보 | 파일 | 알려진 독립 단위 | 상태 |
|---|---:|---|---|
| 한국어 낭독 |50|공개 test의10화자 ×5발화|CC BY 4.0, 원 배포↔mirror byte 대조 미완료|
| 재채기·기침·호흡 |각6, 합18|선택 전체에서 다른 녹음 원본/기여자|ESC-50 NC 연구용, 실제 발성자 신원 미확인|
| 웃음·아기 울음 |각6, 합12|위와 같은 원본/기여자 분리|제품 Speech 양성 후보, NC 연구용|
| 기악 |3|다른 곡/연주자|Public Domain 표기1, CC BY3.0/4.0 각1|

한국어는16kHz, ESC는44.1kHz, 기악은22.05kHz 원본 그대로다. 재인코딩·정규화·리샘플링·자르기는 하지 않았다. 과거936개 로컬 WAV를 PCM 감사했고, manifest의 원본/decoded hash까지 합쳐943개 파일 hash,936개 PCM hash,614개 알려진 그룹을 제외 목록으로 고정했다. PCM을 읽지 못한 파일은0개다. 이 숫자는 독립 사람936명이라는 뜻이 아니다.

신규83개 모두 `holdout_reserved`다. 현재 train/calibration 자료는 없다. 한국어 원래 test의10화자는 이후 train/calibration에 쓰지 않는다. **다음 작업은 정답 구간 검토 및 부족한 데이터 보충이며, 바로 학습·성능 판정을 시작할 상태가 아니다.**

## 제품 정답을 먼저 맞춘다

현재 mapper는 Speech를 사람의 언어만으로 한정하지 않는다. 기본 매핑 기준은:

| 실제 사건 | 기본 정답 |
|---|---|
| 한국어 발화·낭독, 노래/합창/랩, 웃음, 울음, 승인된 외침/군중 발성 |Speech|
| 재채기, 기침, 호흡, 순수 기악 |Ambient|
| 기존 승인된 위협음 |Danger|
| 겹친 소리의 주 사건이 불명확하거나 정책 적용이 불명확함 |uncertain, 학습 negative로 사용 금지|

“음악 파일이니 Ambient”, “말뜻이 없으니 Ambient”로 자동 지정하지 않는다. 노래·TV·게임 등의 구간에는 사람 목소리가 포함될 수 있다. 후보 목록의 `expected_hint`는 공개 범주에서 가져온 검토용 힌트이며 정답이 아니다. 검토 큐에는 이 힌트를 빼서 그대로 승인하는 편향을 줄인다. 실제 구간 라벨은 기존 분류기의 예측과도 독립적으로 만든다.

정책은 develop `3ec9725`의 mapper SHA256로 고정하며 CI가 변경을 감지한다. 사용자 mapping override가 켜진 경우는 별도 조건이다. 과거 #407/#414의 파일 단위 음악 라벨은 혼합음 최종 정답으로 재사용하지 않는다. #414의 재채기→Speech 노출 증가 사례는 현재 기본 정책에서도 Ambient 정답과 충돌하므로 기각 근거는 유지된다.

## 원본 분리와 “독립”의 범위

[사전 계획](verifier-corpus-plan-416.md)은 `26e3495`에서 고정했다. ID의 고정 해시로 선택하며 음성/분류 결과로 후보를 고르지 않았다. `selection.json`은 신규 오디오 decode/download 전에 저장했다(한국어는 먼저 고정 parquet를 받음). 최종 manifest는 `633b89d9a23f936e7e631d531467f19f96127292636dfe36aa96e037472e950a`다.

- 같은 화자/원본 녹음/곡·연주자/기여자 그룹이 train·calibration·holdout을 넘으면 오류다. 입력 파일 hash가 달라도 decoded PCM hash가 같으면 중복으로 거부한다.
- 믹스·잡음 추가·속도 변경·리샘플링 파생물은 모든 부모의 그룹과 split을 물려받는다. 서로 다른 split 원본을 섞는 것, 부모 없는 참조와 순환 참조는 거부한다. 파생물 수로 독립 표본 수를 늘리지 않는다.
- 공개 test를 train/calibration으로 전환하거나, 고정한 평가 후보를 삭제/재배정/그룹 변경하면 검사에 실패한다. hash가 잘못된 manifest도 실패한다. Git에 고정된 목록과 코드 리뷰를 함께 사용하며 악의적인 모든 파일 동시 수정까지 암호학적으로 막는 장치는 아니다.
- 과거 로컬 파일에는 화자/연주자 신원이 빠진 자료가 있다. source-disjoint/정확한 PCM 불일치는 확인했지만 다른 rate·녹음·부분 구간의 근접 중복 부재는 증명하지 못했다. 공개 선행 모델의 training overlap도 알 수 없다.
- ESC 기여자는 녹음자이지 실제 발성자와 동일하다는 보장이 없다. 한국어 ID도 공개 metadata의 화자 ID 기준이다. 이를 실명 식별이나 인물별 완전 독립성 증명으로 부르지 않는다.

## 정답 구간 검토 절차

`review-queue.json`의83개 작업을 원본 PCM 기준으로 검토한다. 모델 예측을 보지 않고 서로 다른 두 검토자가 사건과 경계를 표시하고 차이를 조정하는 절차를 채택한다. 이 두 명 요구는 이번 평가 설계의 품질 기준이며 외부 승인 규칙이 아니다. 현재 검토자·구간을 만들어 넣지 않았다.

- 시간은 파일 시작을0ms로 한다. 첫 실제 발성/사건 시작부터 끝까지 표시하며 앞뒤 silence와 중간 Ambient도 포함해 `[0,duration]` 전체를 겹침/빈틈 없이 나눈다.
- 발화 내부 짧은 쉼과 새로운 사건의 구분, 혼합음의 주 사건, onset/offset 불확실 범위는 검토 지침에 기록한다. 경계가 애매하면 해당 구간을 uncertain으로 두고 조정 전 지연 정답으로 쓰지 않는다. 합성음의 삽입 시각은 발성 시작 정답을 대신하지 않는다.
- `review.status=adjudicated`, 서로 다른 `reviewer_ids`2개 이상, 유한한 시간 값, 구간 순서/범위, 허용 coarse 값이 필요하다. reviewer ID가 있다는 것만으로 사람이 들었다는 사실을 증명하지 않으므로 검토 이력과 원본 hash를 PR에 함께 남긴다.
- `events`는 예를 들어 `{start_ms:0,end_ms:120,coarse:"ambient"}`, `{start_ms:120,end_ms:900,coarse:"speech"}`처럼 이어진다. 이 예시는 파일의 실제 정답이 아니며 완성된 자료에 복사하면 안 된다.

기존 VAD·에너지 threshold·ASR은 참고 도구로만 쓸 수 있고, 그 출력만으로 `adjudicated`를 부여하지 않는다. 동일 평가 자료의 결과를 보고 정책/threshold를 바꾸면 그 자료는 개발 자료로 전환하고 최종 판단에 새 holdout을 쓴다.

## 학습과 평가 계약

**학습 대상:** 최근128~192ms의 인과적 PCM 문맥과 첫 YAMNet 후보의 증거로 제품 Speech를 확인하는 작은 모델을 비교한다. YAMNet 또는 VAD 예측을 그대로 정답으로 복사하지 않는다. 재채기·기침·호흡·기악은 말소리와 혼동하기 쉬운 음향적 유사 사례, 노래·웃음·울음은 놓치면 안 되는 양성 사례다. 짧은 window가 사건 경계를 걸치면 불확실성을 별도 처리하고 단순히 파일 전체 라벨을 모든 window에 붙이지 않는다. 모델 구조/학습량/성능은 아직 확정하거나 구현하지 않았다.

**분리:** train에서만 파라미터 학습, calibration에서만 threshold/연속 확인 수/준비 문맥/표시 만료를 정한다. 최종 모델·설정·후처리·manifest·baseline commit hash를 고정한 뒤 holdout을 실행한다. 필요한 자연 대화, 노래, TV/게임 혼합, 환경음→Speech, Speech→짧은 Danger는 현재0개이며 별도 원본·세션으로 보충해야 한다. 공개 낭독50개만으로 이 범위를 대체하지 않는다.

**공정한 시간축:** 동일 원본을44.1/48kHz 캡처 조건으로 변환해 두 arm의 baseline부터 다시 계산한다.16kHz 파일을 그대로512frame씩 처리한32ms 수신을 Android 조건이라고 부르지 않는다. 변환본은 같은 부모의 파생물이다. FIR 지연·실제 read 반환·스냅숏·정규 완료·화면 read/draw를 구분한다. 첫 실행과 steady 전환, 내부음과 마이크, pause/재시작, 자기 진동 입력 처리를 분리한다. #411의 read 반환은 실제 소리 발생 시각이 아니고 draw callback도 광출력 시각은 아니다.

**주 지표:** 단순 첫 flash 대신, 조기 표시가 다음 정규 올바른 표시까지 끊기지 않고 이어지는 시작 시각을 평가한다. 정규 결과 이전에 철회되면 안정적 단축으로 인정하지 않는다. 이미 같은 색을 유지하던 사건은 새 반응과 따로 세며, 사건 종료 뒤 처음 나타난 결과는 늦은 검출로 분리한다. 짧은 사건·미검출·경계 uncertain은 별도로 집계해, 검출된 쉬운 사건만 남긴 median으로 합격시키지 않는다.

| 판정 항목 | 목표/조건 |
|---|---|
| paired 지속 표시 지연 |중앙값100ms 이상 단축, p95 악화 없음; 개선/동일/악화/손실 모두 보고|
| 잘못된 색상/분류 표시 |추가 오표시 조건·총 노출시간·정규 결과로 철회 증가0|
| Speech/Danger 검출 |새 손실0, Danger 첫 판정·지속 노출·정규 스케줄 악화 없음|
| 계산 비용 |선택적 worker 호출·준비 비용·late drop 기록, 기기별 CPU/RSS/발열·정규 p95 비교|
| 통계·coverage |원본/화자·세션 단위 paired 신뢰구간, 클래스별 원본 수·배경 노출시간·미검증 범위 공개|

추가 오표시를 허용하는 비열등성 margin을 임의로 정하지 않았다. 유한 표본의 회귀0은 실제 오경보 증가가 정확히0이라는 증명이 아니다. 예를 들어 독립 Bernoulli 사건n개에서 회귀0이어도 단측95% 상한은 `1 - 0.05**(1/n)`으로0보다 크다. 여러 위상/증강/같은 화자를 독립n으로 넣으면 안 된다. 배경 false alarm/hour에는 사건 수가 아니라 실제 주석된 노출시간과 군집 의존성을 고려한다. 현재 자료는 이런 통계를 확정할 크기/범위가 아니다.

완성된 corpus 구조 검사를 통과하더라도 모델 합격·배포 승인은 별도다. 실기기 없이 CPU/RSS/발열·화면 개선까지 통과했다고 판정하지 않는다. #415의 일반 VAD 정책은 여전히 미채택이고, #411/#413은 별도 draft다.

## 재현·검사

오디오 약11.3분은 `/tmp/sovis-416-audio`에만 있다. 저장소에는 선택/출처/해시/제외 목록/미완성 검토 큐/감사 결과만 포함한다. 공개 test의 전체 mirror parquet는 약60MB이며 신뢰할 수 없는 원격 dataset Python 코드는 실행하지 않고 pyarrow로 읽었다.

```bash
python -m unittest discover -s tools/ai_reference -p 'test_verifier_corpus.py'
python tools/ai_reference/verifier_corpus.py \
  --manifest docs/ai/verifier-corpus-416/manifest.json \
  --exclusions docs/ai/verifier-corpus-416/exclusions.json \
  --selection docs/ai/verifier-corpus-416/selection.json
# 동일 명령에 --require-ready를 붙이면 현재 exit 2가 정상이다.
```

13개 테스트는 제품 라벨·고정 mapper, 선택 순서 독립성, 원본/화자/PCM 누수, test 재배정, 원본 삭제, 혼합음의 두 부모·순환 lineage, NC 학습 차단, 미검토·uncertain·시각 오류를 확인한다. CI의 녹색은 **무결성 검사 통과**이며 미완성 자료가 평가 가능하다는 뜻이 아니다.

음원 재구성은 `prepare_verifier_corpus.py --help`의 인자를 사용한다. 고정된 한국어 parquet, #351/#407 manifest, 기존 평가 WAV 목록이 필요하다. `--audio-dir`와 `--output-dir`는 새 경로여야 한다. 과거 목록이 달라지면 제외 결과도 달라지므로 기존 frozen corpus를 대체하지 말고 새 버전으로 검토한다. Python/decoder 버전은 manifest에 기록했다. 다른 decoder의 PCM hash가 다르면 이를 자동으로 무시하지 않는다.

## 출처와 이용 조건

- [Zeroth 원 배포 / OpenSLR40](https://www.openslr.org/40/): CC BY4.0, 한국어 낭독·화자 test 구성. [고정 mirror](https://huggingface.co/datasets/kresnik/zeroth_korean/tree/1fe937899f828af822293d05e086200946088bdf)는 전송 편의를 위해 사용하며 원 배포와의 byte 대조는 미완료다.
- [ESC-50 고정 README](https://github.com/karolpiczak/ESC-50/blob/33c8ce9eb2cf0b1c2f8bcf322eb349b6be34dbb6/README.md), [개별 출처 LICENSE](https://github.com/karolpiczak/ESC-50/blob/33c8ce9eb2cf0b1c2f8bcf322eb349b6be34dbb6/LICENSE): source ID/기여자와 dataset NC 조건을 함께 기록했다. 원음이CC0인 항목도 ESC-50 배포물을 자동으로 상용 학습 승인하지 않는다.
- [librosa 원음별 출처](https://github.com/librosa/data/tree/38f4b06556fa0ff1acda5e677d8ba05d1bc0fff0/audio): Hungarian Dance / US Army Strings, Mihai Sorohan trumpet, Stefan Balke의 Drese 재녹음. attribution 전문은 각 manifest row에 보관했다. 이용 가능 표기는 해당 배포처의 고지 기록이며 권리 검토가 필요한 적용 범위를 자동 승인하지 않는다.

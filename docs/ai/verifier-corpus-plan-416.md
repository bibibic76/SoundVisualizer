# #416 검증 자료 준비 계획 — 추론 전 고정

이번 단계는 데이터 준비와 평가 기준 정의다. 모델 추론/학습/앱 변경은 하지 않는다. 수집 파일은 **holdout 후보 예약**이며 사람의 사건 시각 검토가 끝난 정답 자료로 부르지 않는다.

## 제품 라벨

develop `3ec9725`의 `YamnetThreeClassMapper.kt` SHA256 `2202c0769605a0075017405e04ac8cb5c4ed9550695b645c2fdf6e6746acd3ab`를 기준으로 한다.

- 한국어 대화/낭독, 노래/합창/랩, 웃음/울음/승인된 외침은 Speech다.
- 재채기/기침/호흡, 순수 기악은 Ambient다. 일반적인 비언어 발성을 모두 Ambient로 두지 않는다.
- 혼합 음악·노래·TV·게임은 파일 종류만으로 정답을 만들지 않는다. 구간별 실제 사건과 제품 정책을 검토하고 불명확한 구간은 uncertain으로 둔다.
- 위협음은 기존 정책을 따른다. 기본 매핑 기준 검증이며 사용자 remapping 상태의 모델 적용을 보장하지 않는다.

## 결과를 보지 않고 고를 후보

1. 한국어: OpenSLR40 Zeroth-Korean의 공개 test를 HF `kresnik/zeroth_korean` revision `1fe937899f828af822293d05e086200946088bdf`로 가져온다. test parquet SHA256 `2f6382b902622ede1bf35e5a167a9b06b0f1b8c896e1947324b8b5180fca7502`. 화자당 최대5개, ID의 고정 해시 순서로 선택한다. 읽은 문장이므로 자연 대화로 세지 않는다. 원 배포와 mirror의 byte 동일성은 아직 확인하지 않았다. 공식 test의10화자는 앞으로 train/calibration에서 제외한다.
2. 사람 소리: ESC-50 pinned commit `33c8ce9eb2cf0b1c2f8bcf322eb349b6be34dbb6`의 sneezing/coughing/breathing/laughing/crying_baby를 각 최대6개 원본에서 선택한다. 과거 source ID와 기여자가 같은 후보를 제외한다. 목록/라이선스에 기여자가 없는 경우 제외한다. 녹음자와 실제 화자가 같다는 보장은 없다. NC 자료는 연구용으로 표시하고 상용 학습 가능으로 자동 승인하지 않는다.
3. 기악: librosa/data `38f4b06556fa0ff1acda5e677d8ba05d1bc0fff0`의 Hungarian Dance(US Army Strings), sorohanro solo-trumpet-06, drese-tempo(Stefan Balke). 이전7곡과 다른 원본/연주자이며 같은 곡의hq/저해상도 버전을 별도 원본으로 세지 않는다. 개별 attribution을 저장한다.

이전 #351 manifest, #407 manifest 및 로컬 기존 평가 WAV 전체를 감사 대상 목록으로 기록한다. 파일 SHA256와 같은 rate/channel의 decoded float32 PCM SHA256, ESC 원본 ID/기여자, 알려진 화자/연주자 ID로 중복을 검사한다. 재인코딩·변속·다른 rate의 근접 중복 및 신원 불명 기록은 이 검사로 부정할 수 없다. 선행 모델의 학습 자료와의 중복도 미확인이다.

## 준비 상태와 평가 원칙

원본 다운로드 전에 선택 목록을 저장하고, 다운로드 후에도 모델 결과로 파일을 교체하지 않는다. 그룹/해시 충돌은 실패로 처리한다. 모든 파일은 annotation pending, event 구간은 빈 목록으로 생성한다. VAD/에너지 threshold/ASR로 사람이 검토한 정답 시각을 대신하지 않는다.

학습/calibration/holdout은 화자·녹음·곡/연주자·증강 원본 연결 그룹 단위로 분리한다. 두 원본을 섞은 합성음은 양쪽 그룹을 모두 물려받아야 한다. test 예약 원본을 학습으로 바꾸거나 unknown을 음성 negative로 학습시키지 않는다.

합격 목표: paired **지속 표시** 단축 중앙값100ms 이상, p95 악화 없음, 새 Speech/Danger 손실0, 추가 잘못된 색 노출/철회 증가0. 미검출·애매한 라벨·수신 시각 오차를 별도 보고한다. 표본에서 회귀0은 모집단 무회귀 증명이 아니므로 원본/화자 단위 신뢰구간과 미검증 범위를 같이 보고한다. 실기기 CPU/RSS/발열·정규 판정 p95 검증도 별도 필수다.

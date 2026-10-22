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

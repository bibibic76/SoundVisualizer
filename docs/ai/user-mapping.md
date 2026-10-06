# 사용자 3분류 매핑 (#291)

## 계약

- `SettingsManager.soundTypes`의 불변 map을 추론 시작마다 한 번 읽는다. 키는 YAMNet CSV의 정확한 영어 `display_name`, 값은 `ambient` / `speech` / `danger`다.
- 사용자 지정은 기존 top-3 확률 합산 투표에 반영한다. 모델 출력, top-5 순위, frontend, confidence와 수치 threshold는 변경하지 않는다.
- Danger에서 뺀 라벨은 game-mix 표시명 선택, safety cue, critical keyword, 그 단서에 의한 threshold 완화에서 제외한다. 함께 나온 다른 유효 Danger 단서는 계속 동작한다.
- 새로 Danger로 지정한 일반 라벨은 정상 투표에만 참여한다. 낮은 확률의 safety cue나 새 Music masking 정책으로 확대하지 않는다.
- 설정이 바뀌면 이전 결과를 즉시 숨기고, 다음 tick에서 이전 hysteresis 상태를 초기화한다. 무음 게이트 때문에 추론을 생략하는 tick에도 초기화한다. 새 판정은 기존 confidence/hysteresis 조건을 그대로 따른다.
- 사용자 설정이 없으면 기존 기본 정책 그대로다. Plop/Gargling 기본값 수정은 별도 #319 / #320이며 이 PR에 섞지 않는다.

## 평가 기록

CSV 끝에 `mapping_override_count`, `mapping_signature`를 추가한다. signature는 추론 당시 적용한 매핑을 영어 라벨순으로 정렬한 `이름=종류;이름=종류` 문자열이며, 기본값은 `default`다. 해시가 아니라 실제 매핑 내용이므로 재현할 수 있다. top-5는 필터링하지 않은 모델 원출력 후보다.

#117 기본 평가에는 `mapping_override_count=0`, `mapping_signature=default`인 행만 사용한다. 사용자 매핑 결과는 별도로 평가하고 APK/commit과 함께 보관한다. Python offline reference는 여전히 기본 매핑 평가용이며 사용자 설정 replay를 제공하지 않는다.

## 확인

- JVM: 521개 실제 CSV 라벨의 기본 mapping 동일성, demotion, 독립된 다른 cue, 새 Danger 라벨, 저장 설정 연결, CSV.
- CI emulator: 모델을 실제로 실행한 뒤 실행 중 mapping 전환/초기화와 이전 결과 차단.
- 사람의 release APK 확인(머지 전): 개발자 분류 탭에서 Siren → Ambient, 별도 Explosion 유지, 원래대로 복원. 색/진동 및 CSV 내용을 확인한다. 유사 사이렌은 별도 라벨이므로 top-5도 같이 확인한다.
- 분류 탭 개발자 모드 제한은 유지한다. 전체 사용자 공개는 앱 담당자의 후속 작업이다.

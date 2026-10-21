# 분류 HUD 결과 알림 (#412)

새 판정을 공개한 뒤 HUD가 다음100ms poll을 기다리던 대기를 제거한다. 물리적 표시까지의 지연은 main dispatch·Compose·display에도 영향을 받으므로 실기기 절감량이나 평균50ms 개선을 보장하지 않는다. 테두리 색과 모델 자체의 분류 지연을 개선한 변경도 아니다.

`RealtimeAiPipeline.publishResult`는 기존 AtomicReference를 먼저 바꾸고 비동기 wake-up callback을 호출한다. 서비스는 이를 `AiClassification.notifyResultChanged`에 연결한다. 초기화·매핑 무효화·reset·close에서 null을 공개할 때도 같은 알림을 보낸다. 서비스 attach/detach도 알린다. 스케줄·모델·threshold·히스테리시스는 변경하지 않는다.

알림은 replay1·DROP_OLDEST SharedFlow의 Unit 신호다. 결과 payload를 보관하지 않으며 HUD가 깨어날 때 `latest()`의 현재 provider/매핑 검사를 거친다. producer는 tryEmit만 하고 UI 완료를 기다리지 않는다. HUD가 없으면 collector도 없고, 신호 한 개만 남는다. 일반 사용자의 테두리 렌더링에는 새 구독을 만들지 않는다.

`aiHudRefreshes`는 결과 알림과 기존100ms 보조 heartbeat를 합친다. 보조 heartbeat는 음량·결과 나이·설정을 계속 갱신한다. 알림을 conflate해 UI가 밀릴 때 무제한 일을 쌓지 않는다. 느린 UI에서는 중간 결과가 생략될 수 있으므로 모든 판정을 기록한다는 의미가 아니다. 이벤트를 추가했으므로 개발자 HUD가 켜진 동안 formatting 횟수/CPU가 소폭 늘 수 있다. 실제 부담과 배터리는 실기기 미측정이다.

pause는 내부 collector/timer를 취소하며, 이미 대기한 신호도 현재 pause 상태를 확인하고 버린다. resume는 즉시 현재 source를 읽는다. 개발자 모드 OFF/overlay 종료는 produceState 취소로 정리한다. 분류 글자 갱신은 이 작은 HUD subtree 안에서만 일어난다.

## 검증

- 60초 heartbeat로 둔 테스트에서 결과 알림이2초 timeout 안에 도착하는지 확인한다. 테스트 시간은 실제 기기의 화면 절감 수치가 아니다.
- pause 중 구독0/소비 없음, resume 최신 값, collector 취소 뒤 구독0을 확인한다.
- 느린 소비자 앞에서1000개 알림을 합치고 최신 값만 읽는지 확인한다. 결과가 없어도 heartbeat는 동작한다.
- attach/replay/detach 뒤 이전 payload가 아닌 현재 source를 읽는지 확인한다.
- 실제 ONNX 공개 callback이 이미 저장된 결과를 보고, reset/close null을 관찰하며 확률/coarse가 그대로인지 Android에서 확인한다.

## PR #411 계측과 함께 적용할 때

이 변경은 develop 기반으로 독립된다. 아직 초안인 #411의 `HudFrame`/draw 계측은 포함하지 않았다. 두 변경은 `AiDebugOverlay`의 producer 주변과 pipeline 공개 지점에서 겹칠 수 있다. 함께 적용할 때 다음을 유지한다.

1. 이 PR의 `aiHudRefreshes(...).collect` 안에 #411의 HUD read 계측·`HudFrame` 생성을 둔다. `HUD_POLL`은 호환되는 기존 열 이름으로 남겨도 의미는 “HUD가 결과를 처음 읽은 시각”이다. 알림과 heartbeat 어느 경로든 같은 시점에서 찍는다.
2. 텍스트와 latency stamp는 동일 composition snapshot을 사용하고 기존 HUD draw 계측을 유지한다. 100ms delay를 다시 넣지 않는다.
3. pipeline은 공개 직전 BOOTTIME marker → AtomicReference store → 알림 순서를 지킨다. #411의 result event도 같은 stamp로 남긴다. callback 전후 파일 I/O/await를 추가하지 않는다.
4. 양쪽 유닛·계측을 함께 재검증하고 `publish_to_hud_poll/draw`, 누락 draw, 기록 ON/OFF 부담을 비교한다. 실제 Android 실기기는 현재 연결 불가이며 아직 수치가 없다.

실험 #407과 결합할 때는 정규 결과 알림만으로 임시 preview 생성/만료를 통지하지 못한다. 기존 보조 heartbeat가 이를 읽을 수 있지만 preview 자체의 이벤트화는 별도 검증 대상이다. 이 PR은 기본 정규 경로만 개선하며 #409의 실험을 활성화하지 않는다.

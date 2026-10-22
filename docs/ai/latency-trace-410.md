# PCM 수신부터 화면까지 지연 계측 (#410)

이 작업은 계측만 추가한다. 분석250ms·HUD100ms 주기, 모델·threshold·히스테리시스·색/모양 동작은 유지한다. #407의 별도 추론 실험과 독립된 develop 기반이다. 실기기 개선 수치는 아직 없다.

## 사용

1. 개발자 모드를 켜고 **결과 기록**을 켠다. 기존 `ai-*.csv`와 별도 `latency-*.csv`가 같은 `ai-log` 폴더에 생긴다. 지연 파일은 첫 계측 이벤트가 올 때 만든다. 기록을 끄면 큐를 비운 뒤 닫으며 메인 스레드에서 기다리지 않는다.
2. 먼저 정상 실행 중 연속 소리 전환을 기록한다. 첫 실행/잠금 복귀는 별도 파일로 시험한다. 시작 전부터 기록이 켜지지 않아 초기 이벤트가 없으면 초기화 시간을 추정하지 않는다.
3. 기록을 끄고 가져온다. 실행 중 가져오면 footer가 없을 수 있다.

```bash
adb pull /sdcard/Android/data/com.example.soundvisualizer/files/ai-log/
python3 tools/summarize_ai_latency.py ai-log/latency-*.csv
```

요약 도구는 파일마다 결과를 따로 출력한다. 앱 프로세스 재시작 시 ID가 다시 시작할 수 있으므로 서로 다른 파일을 ID만으로 합치지 않는다. 상세 분석은 표준 CSV parser로 한다. 기존 결과 CSV의 스키마는 변경하지 않았다.

## 연결되는 시점

모든 `*_boot_ns`는 `SystemClock.elapsedRealtimeNanos()`의 BOOTTIME이다. wall clock `timestampMs`, `System.nanoTime()`, Compose frame time을 이 값에서 빼지 않는다.

| 필드/이벤트 | 뜻 |
|---|---|
| capture_start / model_init_start,end / pipeline_start,stop | 기록 중 관찰한 lifecycle. 일부 이벤트만 있을 수 있음 |
| pipeline_session_id / result_id | AI 실행 회차와 결과의 식별자. recording 회차가 다른 결과는 소비자 이벤트에서 거절 |
| capture_session_id / first_frame / end_frame_exclusive | 실제로 복사한 PCM 창의 캡처 회차와 반열린 frame 범위. 같은 ring lock에서 PCM과 함께 snapshot |
| read_return_boot_ns | 그 snapshot의 마지막 입력 버퍼를 AudioRecord.read가 돌려준 시각. 음향 사건 onset이나 하드웨어 capture 시각이 아님 |
| snapshot_begin/end | ring 복사 전후. 이 구간에는 lock 획득 대기도 포함됨 |
| preprocessed / inferred / confirmed | 전처리 완료, 모델 실행 완료, mapper·후처리 완료 시각 |
| result.at_boot_ns | AtomicReference 결과 공개 직전 marker. store 자체는 이 시각 직후이며 물리적으로 정확한 원자 store 타임스탬프는 아님 |
| raw_coarse, raw_confidence, effective_threshold, meets_threshold, candidate_coarse/streak, confirmed_coarse | 새 후보가 늦는지, threshold 미달인지, 추가 확인을 기다리는지 구분 |
| color_read | 프레임 tick이 해당 결과를 처음 읽은 시각. detail은 eligible 또는 hidden_or_disabled |
| color_draw | 해당 결과로 색을 고른 엔진이 visible인 상태에서 draw callback의 그리기 함수를 마친 첫 시각 |
| hud_poll / hud_draw | HUD100ms 루프가 읽은 첫 시각 / 그 결과에 맞춰 구성된 텍스트의 첫 draw callback. 텍스트와 stamp는 같은 composition snapshot을 사용 |
| recording_end.detail | queue overflow·파일 상한의 dropped 수와 상한 도달 여부. footer가 없으면 종료/손실 상태를 확정하지 않음 |

색 읽기와 색 그리기는 별개다. 숨김 설정·낮은 크기/알파·idle·가림·최신 결과 대체로 어떤 결과는 draw되지 않는다. 프레임에서는 결과당 이벤트를 한 번만 만든다. HUD는 별도 소비자이므로 테두리 색보다 늦을 수 있다. draw callback은 실제 디스플레이 광출력이나 SurfaceFlinger present 시각이 아니다.

프레임 범위는 기록 회차와 캡처 회차가 같은 연속 입력이 요청 창 길이를 채웠을 때만 유효하다. 기록 중간 시작·캡처 재시작·미표기 테스트 입력에서는 capture ID/범위가0일 수 있다. `read_return_boot_ns=0`이면 입력 수신 시각을 모르는 것이며 AI·화면 시각만 분석한다. window frame 번호는 음향 onset 정답이 아니므로 `창 길이 + 수신→draw`를 사건 지연으로 계산하면 안 된다.

`input_may_be_filtered=true`는 마이크의 자기 진동 필터가 과거 깨끗한 입력으로 바꿨을 수 있다는 뜻이다. 그 PCM의 음향 내용은 마지막 버퍼 수신 시각보다 오래될 수 있다. true가 실제 치환을 증명하지는 않으며, 이 자료만으로 마이크 사건의 내용 나이를 복원하지 않는다. 캡처 source 정보는 capture_start에 있고, 기록을 실행 중 켜 그 이벤트가 없으면 시험 기록에 source를 별도로 적는다.

## 읽는 법과 한계

요약은 수신→snapshot, snapshot복사, 전처리, 모델, 판정, 공개, 공개→색/HUD read/draw의 median/p95를 출력한다. draw가 있는 결과에 조건부인 통계다. `missing_color_draw`, `missing_hud_draw`를 같이 보고 누락을0ms로 채우지 않는다. 결과가 writer queue에 들어오는 순서는 producer 스케줄링에 따라 달라질 수 있으므로 ID로 join한다. 음향 onset→첫 올바른 색의 latency, 미검출, 정확도는 별도 사건 annotation이 있어야 측정된다.

첫 raw Speech와 confirmed Speech의 시각을 동일 pipeline/mapping 내에서 따라가면 확인 대기를 볼 수 있다. threshold 미달 시 과거 candidate가 유지되는 기존 동작도 그대로 기록한다. `confirmed`는 후처리 실행 완료라는 뜻이며 raw가 올바른 새 사건이라고 보장하는 표시는 아니다.

기록 OFF에서는 파일/추가 writer thread/결과별 stamp·이벤트가 없다. ON에서는 최대512 event queue, 약20MiB 파일 상한, 결과당 최대5 consumer/result 이벤트로 제한한다. capture read마다 이벤트나 오디오 배열을 만들지 않는다. 큐 접근에 짧은 동기화는 있으며 무비용 계측이 아니다. ON/OFF의 처리시간·발열 차이는 기기에서 별도 확인해야 한다. 오디오 내용은 저장하지 않는다.

## 검증

- JVM: 첫 관찰 dedupe, 동시 관찰, 기록 재시작/종료 격리, bounded queue drop, PCM tail·metadata 일치와 reset, CSV quoting.
- Android 계측: 같은 실제 ONNX 입력에서 계측 OFF/ON 확률과 coarse/confidence가 동일하고 BOOTTIME 구간이 순서대로인지 검사. developer 기록 toggle로 별도 파일이 생기고 stop 후 footer까지 쓰이는지 검사.
- Python: producer 순서가 달라도 ID join, 누락 draw의0채움 방지, 시각 역전·미종료 기록 표시.

기기 SDK/ADB가 없는 호스트에서는 순수 Kotlin/Python을 검사하고 정식 Android 빌드·계측은 CI를 사용한다. 실기기 read backlog·draw 지연·온도·전력은 이 테스트의 보장 범위 밖이다.

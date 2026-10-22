# #422 Silero 화면 조기 표시 비교 — Galaxy 검증

머지/배포 승인 전 실기기 확인용 **draft PR #423**이다. 정규 YAMNet 확정/진동/잠금 화면 Danger 경로는 유지하고, 기본 매핑의 첫 Speech 후보가 통과했으나 아직 Ambient로 확정된 경우에만 별도 Silero worker의 **새로운 32ms 입력 3개가 각각 p≥0.9**이면 화면/HUD를 앞당긴다. 다음 정규 추론 시작 이후 새 채택 금지, 다음 정규 결과 또는 parent snapshot+275ms에 임시 표시 종료. 이미 확정된 Danger 위에는 표시하지 않는다. 느린 worker는 backlog를 버리고 재시작하며 정규 추론이 기다리지 않는다. CPU/메모리 경합까지 없다는 보장은 아니다.

## 비교 APK

- Actions `Silero Galaxy comparison APKs`의 **같은 run/commit**에서 `SoundVisualizer-silero-baseline`, `SoundVisualizer-silero-candidate`를 각각 받는다. 둘 다 arm64 R8 release이며 같은 서명을 사용한다. `comparison.json`의 variant/flag/commit/APK SHA256과 `signature.txt`의 인증서 SHA256을 대조한다.
- baseline: `-PaiSileroPreview=false`(기본값), candidate: `true`. **둘 다 새 사람 소리 정책 + #411 구간 계측 + #413 HUD 이벤트 갱신**이 들어 있다. 따라서 이 비교는 Silero의 순증 효과를 분리하며 현 develop 대비 HUD 개선까지 합친 수치가 아니다.
- ONNX는 고정 v6.2.3, SHA256 `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`, MIT. 두 APK에 모두 약2.3MB asset이 들어 있지만 baseline은 worker/session을 로드하지 않는다.
- 실기기에 Gradle connected 테스트를 돌리지 않는다(테스트 후 앱/설정 삭제 위험). APK만 `adb install -r /다운로드한/variant/app-arm64-v8a-release.apk`로 교체한다. uninstall/pm clear 하지 않는다. 설치된 정식 APK와 인증서가 다르면 **삭제하지 말고** 별도 테스트 폰을 쓰거나 담당자에게 확인한다.
- 변경 매핑이 있으면 기록해 두고 테스트용 기본 매핑으로 맞춘다. **사용자 정의 매핑이 하나라도 있으면 preview를 비활성화**한다. 실험 후 원래 설정을 복구한다. 캡처 경로/표시 토글/색/진동/음량/볼륨·폰 거리·방향을 양쪽에서 동일하게 유지한다.

## 최소 기능/안전 매트릭스

| 입력 | 확인할 내용 |
|---|---|
| 대화, 짧은 말소리 | 첫 색/사람 소리 표시, 확정, 누락, 깜빡임 |
| 웃음, 기침, 재채기, 호흡 | 모두 사람 소리가 정답. **모두 빨라지거나 모두 검출된다고 가정하지 않는다** |
| 노래, 울음/환호 | 승인된 사람 발성 의미 유지 |
| 기악, 일반 Ambient, 기차/물방울 | 사람 소리의 새 오표시 시간/조건 수, 철회 |
| Siren/Alarm/Danger | 검출 손실·첫 빨간 표시/진동/잠금 알림 지연 증가 없음 |
| Danger→말소리, 말소리→Danger, 겹친 입력 | Danger가 preview 때문에 막히거나 지워지지 않는지 |
| 중지/재시작, 소스 변경, 매핑 변경 | 이전 세션 preview가 돌아오지 않는지, 오류/멈춤 |

마이크/내부 캡처를 분리한다. 마이크는 진동 OFF로 먼저 측정한 뒤 ON에서 피드백을 확인한다. 앱/모델 warm-up 후 최소2초 무음, 각 범주 반복20회 이상을 권장한다. A→B/B→A 순서를 번갈아 실행하고 같은 파일/발화·음량을 사용한다. 성공한 빠른 사례만 선택하지 말고 미검출/철회까지 모두 남긴다. 실내 안전 음량의 녹음된 경보를 쓰며 실제 비상상황을 만들지 않는다.

## 숫자로 남기는 경로

1. 개발자 모드에서 AI 기록을 켜고 화면/HUD가 보이게 유지한다. 후보 HUD에는 `~PREVIEW`가 붙고 정규 확정과 구분된다. 각 run마다 기기/OS/입력 경로/rate/variant/commit/동일 설정과 재생 목록을 적는다.
2. 기록을 정상 종료하고 `adb pull /sdcard/Android/data/com.example.soundvisualizer/files/ai-log/ ./galaxy-baseline-또는-candidate/`로 CSV를 보관한다. 개발자 기록에는 소리 라벨이 있으므로 공유 전에 개인정보를 확인한다.
3. `python3 tools/summarize_silero_latency.py galaxy-candidate/latency-해당파일.csv`를 실행한다. preview→다음 정규 Speech **draw callback 선행량** median/p95, 미관측 draw(제외), 다음 정규가 Speech 아닌 경우, expiry read, 정규 publication 간격, VAD 호출/worker 경과 시간을 출력한다. 파일별 분석이며 session/result ID가 겹친 파일을 합치지 않는다. `recording_closed=false`/dropped>0이면 손실을 명시한다.
4. 이 값은 **후보 내 preview 선행량이지 baseline APK 대비 음향 onset→실제 화면 픽셀 개선량이 아니다**. 실제 A/B는 외부 재생음 시작과 폰 화면을 함께 찍는 고속 카메라/동기화 오디오로 재생 onset→첫 색/라벨/확정 시간을 같은 기준으로 채점한다. 60fps이면 프레임 분해능16.7ms와 오차를 보고한다. CSV의 read-return은 소리 발생 시각이 아니고 draw callback은 실제 디스플레이 표시 시각이 아니다.
5. A/B 각각 onset→첫 색, onset→라벨, onset→정규 확정의 median/p95와 paired 차이, 개선/동일/악화/미검출 수를 보고한다. 파일 label은 의미적 onset annotation이 아니므로 근거 없이 정확도/일반화 향상을 주장하지 않는다.

## 비용/중단 기준

같은 음량/경로로 무음+발화 혼합10분씩, 순서를 바꿔 측정한다. `adb shell dumpsys cpuinfo`, `adb shell dumpsys meminfo com.example.soundvisualizer`, `adb shell dumpsys thermalservice`, `adb shell dumpsys battery`를 같은 간격에 저장한다. VAD는 통상31.25회/초이고 CPU1thread의 recurrent session/리샘플링 비용이 추가된다. CSV worker wall time은 CPU 사용률이 아니다. 정규 추론 p95/화면 간격도 A/B 비교한다.

Danger 검출 손실/지연 악화, 반복적 비인간 오표시·깜빡임, 지속적인 VAD 오류/강제중단/발열·regular p95 악화가 있으면 OFF를 유지한다. Galaxy에서 실제 표시 이득과 비용/안정성을 확인한 뒤 **사용자가 production 적용을 최종 판단**한다. 현재 진단 이득은 단순화 replay(정규25ms/보조2ms 가정)이며 실기기 수치가 아니다.

# #351 safety guard 진단 (2026-10-08)

## 결론과 범위

Production은 바꾸지 않았다. 기준은 `dec5cc3` / Qualcomm frontend / Booster OFF / 기본 매핑이다. #283 B 변경은 사용자 override만 바꾸므로 이 기본 설정 비교에 영향을 주지 않는다.

다음 검토 후보는 **총기 veto 제거 + 승격하는 비총기 cue 자체가 기존 0.05 기준 이상**인 좁은 정책이다. 실제 적용은 사용자 승인 후 별도 PR이다. 총기 자체의 5% 승격은 추가 오경보가 확인되어 권장하지 않는다. Speech/무음 guard 일괄 제거도 권장하지 않는다. #345 신규 매핑과 Music masking/Singing bowl 변경은 섞지 않았다.

## 입력 / 재현 조건

- 원본 수정·복사·학습 없음. 대표 81 + legacy Booster 107 + ESC-50 380 + DESRA 52 + C3GD 87 + SESA 105 = 812 WAV.
- 기존 812 평가의 라벨/평가 정책 그대로: 주 평가 645 = Ambient 248 / Speech 15 / Danger 382. legacy 107은 F1에서 제외하며, 나머지 미확정 라벨 60개도 따로 본다.
- C3GD의 file_id 87개는 촬영장소·세션 독립성을 뜻하지 않는다. 반복 진단한 corpus이며 독립 holdout 성능으로 일반화하지 않는다. Speech 15개는 특히 부족하다.
- 전체 길이를 250ms 고정 간격으로 ingest, 최근 약 0.975초 모델창 사용. 한 창보다 짧은 파일만 메모리상 1초 무음 추가. 파일마다 hysteresis 초기화. 실제 Android silence gate, inference 지연/스케줄러, capture mixer, overlay/haptic hold는 재현하지 않는다.
- 모델 추론은 한 번: 11,753개 프레임의 521 softmax 값을 저장하고 같은 입력으로 10개 정책 비교. 출력값·threshold를 corpus에 맞춰 최적화하지 않았다.
- Kotlin production classifier/safety/postprocessor를 직접 컴파일. 진단 baseline은 모든 프레임에서 production safety 결과와 일치(assert), Python 기준 UI와도 11,753/11,753 일치. 기존 #344 결과의 confusion matrix 재현.
- manifest SHA256: `68b138da5083a75199514cf37c50761588155a6d9e885e0adfd2d00a6e207f82`
- 확률 cache SHA256: `8421fc89c76b3c47688593bf3c51d3f71b18a6bd95913e7440804e47c0cdada2`
- 로컬 자료: `/tmp/sovis-351-manifest.json`, `/tmp/sovis-351-frames.tsv`, `/tmp/sovis-351-results.tsv`, `/tmp/sovis-351-report.json`. `/tmp`은 영구 보관소가 아니며 아래 코드/조건과 이 보고서가 장기 기록이다. 음원·원출력 cache는 commit하지 않는다.

## 결과

F1은 **파일별 UI 다수결**이다. any-Danger는 파일 중 한 프레임이라도 Danger인 파일 수이며 사건 recall이나 구간 precision이 아니다. 시간 정답이 없으므로 아래 Ambient Danger를 엄밀한 frame false-positive rate라고 부르지 않는다.

| 후보(나머지 조건 유지) | macro F1 / 645 | Danger 다수결 / 382 | Danger any / 382 | Ambient any-Danger / 248 | UI 변경 프레임 / 전체 11753 |
|---|---:|---:|---:|---:|---:|
| baseline | 0.7566 | 239 | 319 | 42 | 0 |
| 총기 veto만 제거 | 0.7798 | 261 | 342 | 42 | 65 |
| veto 제거 + 비총기 cue 자체 ≥0.05 | 0.7787 | 260 | 341 | 42 | 60 |
| Speech 투표 guard만 제거 | 0.7566 | 239 | 319 | 42 | 0 |
| Speech 이름 guard만 제거 | 0.7566 | 239 | 319 | 42 | 0 |
| Speech guard 둘 다 제거 | 0.7566 | 239 | 319 | 42 | 4 |
| 무음 이름 guard만 제거 | 0.7577 | 240 | 319 | 42 | 3 |
| confidence <0.12 guard만 제거 | 0.7566 | 239 | 319 | 42 | 0 |
| blockPromotion 4조건 제거(총기 veto 유지) | 0.7577 | 240 | 319 | 42 | 7 |
| 총기 포함 cue 자체 ≥0.05 승격(veto 제거) | 0.7798 | 261 | 342 | 45 | 76 |

모든 후보에서 대표 81개 다수결은 73/81, macro F1 0.90635로 동일했다. Speech recall은 14/15, Speech any-Danger 0/15로 동일하지만 혼합 대화까지 안전하다는 뜻은 아니다. legacy firearm any는 baseline 35/58, veto 제거 및 총기 승격 후보 37/58이며 최종 정확도에 합산하지 않는다.

Confusion matrix (행 정답/열 예측: Ambient, Speech, Danger):

| 정책 | Ambient 행 | Speech 행 | Danger 행 |
|---|---|---|---|
| baseline | 235, 5, 8 | 1, 14, 0 | 140, 3, 239 |
| veto 제거 + cue floor | 235, 5, 8 | 1, 14, 0 | 119, 3, 260 |
| veto만 제거 / 총기 승격 | 235, 5, 8 | 1, 14, 0 | 118, 3, 261 |

## 중요한 반례와 한계

1. **veto만 지우면 안 되는 이유:** 현재 strong flag는 총기도 포함한다. 총기만 5%를 넘었는데 다른 비총기 cue를 그보다 낮은 확률로 승격할 수 있다. 예: `4-174797-A-15.wav`(water_drops), 1.0초, Inside small room .396 / Gunshot .060 / Cap gun .056 / Inside large room .051 / Explosion .043. veto만 제거하면 Explosion .043으로 Danger가 되고, 해당 파일 Danger 프레임은 3→6. cue 자체 floor 후보는 이 증가를 막는다. floor는 새로운 최적값 탐색이 아니라 기존 strong 기준을 채택 cue에도 적용하는 별도 후보다.
2. **veto+floor도 무위험하지 않음:** 이미 오경보가 있던 can_opening `5-221878-A-34.wav` Danger 7→8프레임, clapping `3-130330-A-22.wav` 3→4. any 파일 수만 보면 숨겨진다. 이 후보의 주 평가 다수결 개선 21개는 C3GD 20 + SESA 1에 집중되며, alarm/siren 전반의 개선 근거로 일반화하지 않는다.
3. **총기 직접 승격의 추가 오경보:** airplane `2-106849-A-47.wav` 0→6프레임, can_opening `2-144031-A-34.wav` 0→1, footsteps `3-103598-A-25.wav` 0→1. veto만 제거 대비 주 평가 다수결/any-Danger 이득은 없다. 따라서 재도입 권장 근거가 부족하다.
4. **Speech guard:** 둘을 함께 제거하면 미확정 ESC crying_baby `1-187207-A-20.wav`의 Speech .223 + Burst .064가 새 Danger가 된다. 다른 crying_baby 및 SESA casual도 변화한다. 이 파일들은 고정 3-class 정답이 없어 개선/회귀를 확정하지 않지만, scored Speech 15개의 무변화만으로 guard 제거가 안전하다고 할 수 없다.
5. **무음/낮은 confidence:** 무음 이름 차단 제거로 fireworks 1개 다수결 회복에 그친다. 낮은 confidence 차단만 제거하면 승격 판정은 104프레임 달라지나 후단 .12 threshold 때문에 UI 변화가 없다. UI 같음은 내부 동작 같음이 아니다.
6. 팀원의 대화+사이렌 결과는 원본/혼합 레시피/하네스를 #351 댓글로 요청했다. 아직 이 812와 동일 조건으로 재현한 결과가 아니며 별도 평가가 필요하다.

## 재실행

manifest는 기존 812 평가 report의 `rows` 또는 `path,dataset,expected,policy`가 있는 JSON 배열이다. 음원의 절대 경로는 로컬에서 준비한다. 출력은 repo 밖에 둔다.

```bash
python tools/ai_reference/export_safety_guard_frames.py \
  --manifest /tmp/sovis-351-manifest.json \
  --model-dir app/src/main/assets/ai \
  --output /tmp/sovis-351-frames-new.tsv

# kotlinc 또는 동등한 Kotlin 2.0.20 JVM compiler 사용; Android SDK 불필요
kotlinc app/src/main/java/com/example/soundvisualizer/ai/{YamnetCoarseClassifier,YamnetThreeClassMapper,YamnetMappingPolicy,YamnetSafetyCueDecision,AiPostProcessor}.kt \
  tools/ai_reference/SafetyGuardDiagnostics.kt -include-runtime -d /tmp/sovis-351.jar
java -jar /tmp/sovis-351.jar app/src/main/assets/ai/yamnet_class_map.csv \
  /tmp/sovis-351-frames-new.tsv > /tmp/sovis-351-results-new.tsv
python tools/ai_reference/summarize_safety_guards.py \
  --manifest /tmp/sovis-351-manifest.json \
  --frames /tmp/sovis-351-results-new.tsv --output /tmp/sovis-351-report-new.json
```

확률 cache는 float32를 보존하는 9자리 십진수다. exporter는 기존 출력 덮어쓰기를 거부하며 누락/손상 파일을 조용히 제외하지 않는다. Kotlin 하네스는 합성 guard 분리 점검과 각 프레임 baseline 동일성 assert를 실행한다. Kotlin/JVM 2.0.20, 기존 Python reference 가상환경(numpy/onnxruntime)의 실제 실행으로 확인했다. 이 도구는 user override나 다른 baseline contract를 위한 일반 평가 프레임워크가 아니다.

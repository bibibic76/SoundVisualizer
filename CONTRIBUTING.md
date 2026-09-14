# 기여 가이드 (Git 브랜치 운영 규칙)

이 저장소는 **Git Flow** 전략으로 브랜치를 운영합니다. 모든 기여자는 아래 규칙을 따라 작업합니다.

## 1. 브랜치 구성

| 브랜치 | 용도 | 분기 기준 | 머지 대상 | 이슈 연결 |
|---|---|---|---|---|
| `main` | 배포된 안정 버전만 관리. 모든 커밋은 버전(태그)이 됨 | - | - | 없음 (상시 브랜치) |
| `develop` | 다음 배포를 위한 개발 통합 브랜치 | `main` | `main` 또는 `release` | 없음 (상시 브랜치) |
| `feature` | 기능 단위 개발. 여러 개 동시 진행 가능 | `develop` | `develop` | **필수** (`feature` 라벨 이슈) |
| `hotfix` | 배포된 버전의 긴급 버그 수정 | `main` | `main` + `develop` | **필수** (`bug` 라벨 이슈) |
| `release` | 배포 전 QA·버그 수정 (필요할 때만) | `develop` | `main` + `develop` | 없음 (버전 단위, 필요 시 Milestone) |

작업 브랜치(`feature`, `hotfix`)는 GitHub 이슈 단위로 생성하고, 상시 브랜치(`main`, `develop`)와 배포용 브랜치(`release`)는 이슈와 연결하지 않습니다.

## 2. 작업 흐름

1. **기능 개발** — 이슈를 먼저 만들고, `develop`에서 해당 이슈 번호로 `feature` 브랜치를 생성합니다. 완료되면 `develop`으로 PR을 올립니다.
2. **배포 준비** — `develop`에서 `release` 브랜치를 생성하고, 이 브랜치에서는 버그 수정만 합니다.
3. **배포** — QA가 끝나면 `release`를 `main`에 머지하고 버전 태그를 답니다. 수정사항은 `develop`에도 머지합니다.
4. **긴급 수정** — `bug` 이슈를 먼저 만들고, `main`에서 해당 이슈 번호로 `hotfix` 브랜치를 생성합니다. 수정 후 `main`(새 태그)과 `develop`에 모두 머지합니다.

> 💡 **release 브랜치는 필요할 때만 사용합니다.** 배포 전에 따로 테스트할 기간을 두지 않는다면 2~3번을 건너뛰고 `develop` → `main`으로 PR을 올려 머지한 뒤, `main`에 버전 태그를 답니다.

## 3. 필수 규칙

- `feature`, `hotfix` 브랜치는 **이슈 없이 생성하지 않습니다.** 이슈 하나 = 브랜치 하나 = PR 하나입니다.
- `main`, `develop`에 **직접 커밋·푸시하지 않습니다.** 반드시 PR로 머지합니다.
- `release`/`hotfix`에서 수정한 내용은 **반드시 `develop`에도 반영**합니다.
- 배포 시마다 `main`에 버전 태그를 답니다.
- PR은 리뷰어 승인 후 머지하고, 머지된 작업 브랜치는 삭제합니다.

## 4. 브랜치 네이밍 규칙

| 브랜치 | 형식 | 예시 |
|---|---|---|
| main | `main` | `main` |
| develop | `develop` | `develop` |
| feature | `feature/{이슈번호}-{기능명}` | `feature/12-map-search` |
| hotfix | `hotfix/{이슈번호}-{내용}` | `hotfix/34-search-bug` |
| release | `release/v{major}.{minor}.{patch}` | `release/v1.1.0` |

- `feature`, `hotfix`는 **이슈 번호를 반드시 브랜치명 앞에** 붙입니다.
- 브랜치명은 **소문자와 하이픈(`-`)** 만 사용합니다. (공백·언더스코어·한글 금지)
- 버전은 `MAJOR.MINOR.PATCH` 형식을 사용하고, 태그는 `v1.1.0`처럼 작성합니다.

## 5. feature / hotfix 작업 절차

> **이슈 생성 → 브랜치 생성 → 커밋 → PR → 정리** 순서로 진행합니다.

1. **이슈 생성** — `Issues` 탭 → `New issue` → 템플릿 선택
   - feature 작업: **✨ 기능 개발 (feature)** 템플릿 → `feature` 라벨 자동 지정
   - hotfix 작업: **🐛 버그 수정 (hotfix)** 템플릿 → `bug` 라벨 자동 지정
   - 제목은 작업 내용을 한 줄로 작성하고, 템플릿의 항목을 모두 채운 뒤 담당자(Assignees)를 지정합니다.
2. **브랜치 생성** — 생성된 이슈 번호로 브랜치를 만듭니다.
   ```bash
   # feature: develop에서 분기
   git switch develop
   git pull origin develop
   git switch -c feature/12-map-search

   # hotfix: main에서 분기
   git switch main
   git pull origin main
   git switch -c hotfix/34-search-bug
   ```
3. **작업 & 커밋** — 커밋 메시지에 이슈 번호를 포함합니다.
   ```bash
   git commit -m "feat: 지도 검색 API 연동 (#12)"
   git push -u origin feature/12-map-search
   ```
4. **PR 생성** — base는 feature면 `develop`, hotfix면 `main` (머지 후 `develop`에도 반영)
   - PR을 열면 PR 템플릿이 자동으로 채워집니다.
   - `Closes #` 뒤에 이슈 번호를 적습니다. (예: `Closes #12` → 머지 시 이슈 자동 종료)
   - `확인 사항` 체크리스트를 모두 확인하고, 리뷰어 승인 후 머지합니다.
5. **정리** — 머지 후 작업 브랜치를 삭제합니다.
   ```bash
   git switch develop
   git pull origin develop
   git branch -d feature/12-map-search
   ```

# 기여 가이드 (Git 브랜치 운영 규칙)

이 저장소는 **Git Flow** 전략으로 브랜치를 운영합니다. 모든 기여자는 아래 규칙을 따라 작업합니다.

## 1. 브랜치 구성

| 브랜치 | 용도 | 분기 기준 | 머지 대상 | 이슈 연결 |
|---|---|---|---|---|
| `main` | 배포된 안정 버전만 관리. 모든 커밋은 버전(태그)이 됨 | - | - | 없음 (상시 브랜치) |
| `develop` | 다음 배포를 위한 개발 통합 브랜치 | `main` | `main` 또는 `release` | 없음 (상시 브랜치) |
| `feature` | 기능 단위 개발. 여러 개 동시 진행 가능 | `develop` | `develop` | **필수** (`feature` 라벨 이슈) |
| `bugfix` | 개발 중(`develop`)에 발견한, 아직 배포되지 않은 버그 수정 | `develop` | `develop` | **필수** (`bug` 라벨 이슈) |
| `hotfix` | 배포된 버전(`main`)의 긴급 버그 수정 | `main` | `main` + `develop` | **필수** (`bug` 라벨 이슈) |
| `release` | 배포 전 QA·버그 수정 (필요할 때만) | `develop` | `main` + `develop` | 없음 (버전 단위, 필요 시 Milestone) |

작업 브랜치(`feature`, `bugfix`, `hotfix`)는 GitHub 이슈 단위로 생성하고, 상시 브랜치(`main`, `develop`)와 배포용 브랜치(`release`)는 이슈와 연결하지 않습니다.

기능이 아닌 작업(문서, CI·빌드 설정, 리팩터링 등)도 **`feature` 이슈와 `feature` 브랜치로** 진행합니다. 이슈에 성격을 나타내는 라벨(예: `documentation`)을 함께 붙여 구분합니다.

### 버그는 어디서 고치나요?

버그는 **발견된 위치**에 따라 브랜치가 달라집니다. 모두 `bug` 라벨 이슈를 먼저 만듭니다.

| 발견 위치 | 브랜치 | 예시 |
|---|---|---|
| 개발 중인 코드(`develop`) — 아직 배포 전 | `bugfix` | 오늘 머지된 기능에서 발견한 오류 |
| 배포 준비 중(`release`) | `release` 브랜치에서 바로 수정 | QA 중 발견한 오류 |
| 배포된 버전(`main`) — 긴급 | `hotfix` | 팀에 나눠준 APK에서 발견한 오류 |

## 2. 작업 흐름

1. **기능 개발** — 이슈를 먼저 만들고, `develop`에서 해당 이슈 번호로 `feature` 브랜치를 생성합니다. 완료되면 `develop`으로 PR을 올립니다.
2. **버그 수정** — `bug` 이슈를 먼저 만들고, `develop`에서 해당 이슈 번호로 `bugfix` 브랜치를 생성합니다. 완료되면 `develop`으로 PR을 올립니다.
3. **배포 준비** — `develop`에서 `release` 브랜치를 생성하고, 이 브랜치에서는 버그 수정만 합니다.
4. **배포** — QA가 끝나면 `release`를 `main`에 머지하고 버전 태그를 답니다. 이어서 `main` → `develop` PR로 수정사항을 `develop`에도 반영합니다.
5. **긴급 수정** — `bug` 이슈를 먼저 만들고, `main`에서 해당 이슈 번호로 `hotfix` 브랜치를 생성합니다. 수정 후 `main`에 머지해 새 태그를 달고, `main` → `develop` PR로 `develop`에도 반영합니다.

> 💡 **release 브랜치는 필요할 때만 사용합니다.** 배포 전에 따로 테스트할 기간을 두지 않는다면 3~4번을 건너뛰고 `develop` → `main`으로 PR을 올려 머지한 뒤, `main`에 버전 태그를 답니다.

> 💡 머지된 작업 브랜치는 자동으로 삭제됩니다. 그래서 `release`·`hotfix`의 수정사항은 그 브랜치가 아니라 **`main`에서 `develop`으로** PR을 올려 반영합니다.

## 3. 필수 규칙

- `feature`, `bugfix`, `hotfix` 브랜치는 **이슈 없이 생성하지 않습니다.** 이슈 하나 = 브랜치 하나 = PR 하나입니다.
- `main`, `develop`에 **직접 커밋·푸시하지 않습니다.** 반드시 PR로 머지합니다. (저장소 설정으로 막혀 있습니다)
- `release`/`hotfix`에서 수정한 내용은 **반드시 `develop`에도 반영**합니다.
- 배포 시마다 `main`에 버전 태그를 답니다. 여기서 **배포**는 팀에 APK를 나눠주거나 시연용 버전을 확정하는 것을 말합니다. 태그를 push하면 **릴리스 APK** 워크플로가 그 버전의 APK를 빌드해 [Releases](https://github.com/bibibic76/SoundVisualizer/releases)에 붙입니다.
- PR은 **CI 통과와 팀장 승인** 후 머지합니다. 머지된 작업 브랜치는 자동으로 삭제됩니다.

### 리뷰와 머지

- **리뷰어는 팀장(@bibibic76)입니다.** `.github/CODEOWNERS`에 따라 PR을 열면 팀장에게 자동으로 리뷰가 요청되고, 팀장 승인이 있어야 머지할 수 있습니다.
- 팀장이 올린 PR은 GitHub이 본인 승인을 허용하지 않아, 팀장이 내용을 확인한 뒤 승인 없이 머지합니다. 이 예외는 **PR 머지에만** 적용되고, `main`·`develop`에 직접 push하는 것은 팀장도 막혀 있습니다.
- PR을 열면 CI(`빌드 · 유닛 테스트 · APK`)가 자동으로 돕니다. `develop`으로 가는 PR은 CI가 통과해야 머지됩니다. 자세한 내용은 [README의 자동 빌드 (CI)](README.md#자동-빌드-ci)를 참고하세요.

| PR | 머지 방식 | 이유 |
|---|---|---|
| `feature`·`bugfix` → `develop` | **Squash and merge** | 이슈 하나가 커밋 하나로 남아 `develop` 히스토리가 깔끔합니다. |
| `develop`·`release`·`hotfix` → `main` | **Create a merge commit** | 어떤 배포에 무엇이 들어갔는지 남습니다. |
| `main` → `develop` (배포·긴급 수정 반영) | **Create a merge commit** | 태그가 달린 `main`의 커밋이 `develop` 히스토리에 그대로 이어집니다. |

## 4. 브랜치 네이밍 규칙

| 브랜치 | 형식 | 예시 |
|---|---|---|
| main | `main` | `main` |
| develop | `develop` | `develop` |
| feature | `feature/{이슈번호}-{기능명}` | `feature/12-map-search` |
| bugfix | `bugfix/{이슈번호}-{내용}` | `bugfix/27-overlay-crash` |
| hotfix | `hotfix/{이슈번호}-{내용}` | `hotfix/34-search-bug` |
| release | `release/v{major}.{minor}.{patch}` | `release/v1.1.0` |

- `feature`, `bugfix`, `hotfix`는 **이슈 번호를 반드시 브랜치명 앞에** 붙입니다.
- 브랜치명은 **영어 소문자, 숫자, 하이픈(`-`)** 으로 작성합니다. 종류와 이름은 `/`로 나누고, 버전에는 `.`을 씁니다. (공백·언더스코어·한글 금지)
- 버전은 `MAJOR.MINOR.PATCH` 형식을 사용하고, 태그는 `v1.1.0`처럼 작성합니다.

## 5. feature / bugfix / hotfix 작업 절차

> **이슈 생성 → 브랜치 생성 → 커밋 → PR → 정리** 순서로 진행합니다.

1. **이슈 생성** — `Issues` 탭 → `New issue` → 템플릿 선택
   - feature 작업: **✨ 기능 개발 (feature)** 템플릿 → `feature` 라벨 자동 지정
   - bugfix·hotfix 작업: **🐛 버그 수정 (bugfix / hotfix)** 템플릿 → `bug` 라벨 자동 지정. 템플릿의 `발견 위치`로 둘 중 어느 브랜치인지 정합니다.
   - 제목은 작업 내용을 한 줄로 작성하고, 템플릿의 항목을 모두 채운 뒤 담당자(Assignees)를 지정합니다.
2. **브랜치 생성** — 생성된 이슈 번호로 브랜치를 만듭니다.
   ```bash
   # feature: develop에서 분기
   git switch develop
   git pull origin develop
   git switch -c feature/12-map-search

   # bugfix: develop에서 분기
   git switch develop
   git pull origin develop
   git switch -c bugfix/27-overlay-crash

   # hotfix: main에서 분기
   git switch main
   git pull origin main
   git switch -c hotfix/34-search-bug
   ```
3. **작업 & 커밋** — 커밋 메시지에 이슈 번호를 포함합니다.
   ```bash
   git commit -m "feat: 지도 검색 API 연동 (#12)"
   git commit -m "fix: 오버레이 종료 시 크래시 수정 (#27)"
   git push -u origin feature/12-map-search
   ```
4. **PR 생성** — base는 feature·bugfix면 `develop`, hotfix면 `main` (머지 후 `main` → `develop` PR로 반영)
   - PR을 열면 PR 템플릿이 자동으로 채워지고, 팀장에게 리뷰가 자동으로 요청됩니다.
   - PR 제목은 커밋 메시지 규칙과 같게 씁니다. (예: `feat: 지도 검색 기능 추가 (#12)`, `fix: 오버레이 종료 시 크래시 수정 (#27)`) Squash로 머지하면 제목이 그대로 커밋 메시지가 됩니다.
   - `Closes #` 뒤에 이슈 번호를 적습니다. (예: `Closes #12` → 머지 시 이슈 자동 종료)
   - CI가 통과하고 `확인 사항` 체크리스트를 모두 확인한 뒤, 팀장 승인을 받아 머지합니다.
5. **정리** — 머지하면 원격 작업 브랜치는 자동으로 삭제됩니다. 로컬 브랜치만 정리합니다.
   ```bash
   git switch develop
   git pull origin develop
   git fetch --prune
   git branch -D feature/12-map-search
   ```

   Squash로 머지한 브랜치는 git이 "머지되지 않은 브랜치"로 보기 때문에 `-d`로는 지워지지 않습니다. `-D`를 씁니다.

## 6. 문구 추가와 번역

앱의 기본 언어는 **영어**입니다. 폰 언어의 번역이 없거나 문구 하나가 빠져 있으면 영어로 보입니다. 화면에 보이는 문구는 코드에 직접 쓰지 않고 `strings.xml`에 둡니다.

| 폴더 | 언어 | 새 문구 |
|---|---|---|
| `app/src/main/res/values/` | 영어 (기본) | **필수** |
| `app/src/main/res/values-ko/` | 한국어 | **필수** |
| `app/src/main/res/values-ja/` 등 그 밖의 `values-xx/` | 다른 지원 언어 | 선택 (없으면 영어로 보임) |

- **새 문구는 `values/`(영어)와 `values-ko/`(한국어)에 같은 키로 함께 넣습니다.** 하나라도 빠지면 유닛 테스트 `StringResourcesTest`가 실패해 CI를 통과하지 못합니다. 두 파일은 키 순서와 섹션 주석을 맞춰 둡니다.
- 다른 언어는 나중에 번역해도 됩니다. 빠진 문구는 Lint의 `MissingTranslation` **경고**로만 표시됩니다.
- 브랜드 이름처럼 번역하지 않는 문구는 `values/`에만 `translatable="false"`를 붙여 두고, 번역 파일에는 넣지 않습니다.
- `'`와 `"`는 `\'`, `\"`로 쓰거나 `‘ ’`, `“ ”` 같은 인쇄용 따옴표를 씁니다. `%1$s` 같은 서식 지정자는 영어와 똑같이 둡니다. 테스트가 둘 다 검사합니다.
- 도움말처럼 다른 화면의 이름(예: **실행**, **언어**)을 인용하는 문구는, 그 언어에서 해당 버튼·탭에 쓴 번역과 똑같이 맞춥니다.
- 긴 번역도 화면이 깨지지 않도록 탭은 옆으로 밀리고, 버튼·선택지는 두 줄까지 줄을 바꿉니다. 그래도 짧은 이름표(탭, 버튼, 모드·진동 선택지, 타일 이름)는 짧게 번역합니다.

### 언어 추가하기

1. `app/src/main/res/values-xx/strings.xml`을 만듭니다. 폴더 이름은 안드로이드 리소스 규칙을 따릅니다. (예: 일본어 `values-ja`, 브라질 포르투갈어 `values-pt-rBR`, 인도네시아어 `values-in`)
2. `language/AppLanguages.kt`의 목록에 태그, 폴더 이름, 그 언어로 쓴 언어 이름을 한 줄 추가합니다. 설정의 언어 선택 창에 이 순서대로 보입니다.
3. `./gradlew testDebugUnitTest`로 확인합니다. 폴더와 목록이 어긋나면 테스트가 실패합니다.

Android 13 이상의 폰 설정 **앱 언어**에 뜨는 목록은 빌드할 때 `values-xx` 폴더에서 자동으로 만들어지므로 따로 고칠 곳이 없습니다. (기본 폴더의 언어는 `res/resources.properties`에 적혀 있습니다.)

## 7. 릴리스 서명 키

팀에 나눠주는 APK 는 **릴리스 빌드**입니다. R8 로 쓰지 않는 코드와 리소스를 지워 APK 가 크게 작아지고, 팀 공용 **릴리스 키**로 서명됩니다.

키와 비밀번호는 저장소에 넣지 않습니다. 빌드는 환경 변수로만 키를 받고, CI 는 저장소 Secret 으로 넘깁니다.

| 환경 변수 | 저장소 Secret | 내용 |
|---|---|---|
| `SV_RELEASE_KEYSTORE` | `RELEASE_KEYSTORE_BASE64` | 키 저장소 파일 (CI 는 base64 로 넣고, 빌드 때 파일로 풀어 그 경로를 넘깁니다) |
| `SV_RELEASE_KEYSTORE_PASSWORD` | `RELEASE_KEYSTORE_PASSWORD` | 키 저장소 비밀번호 |
| `SV_RELEASE_KEY_ALIAS` | `RELEASE_KEY_ALIAS` | 키 별칭 |
| `SV_RELEASE_KEY_PASSWORD` | `RELEASE_KEY_PASSWORD` | 키 비밀번호 |

> ⚠️ **키 파일과 비밀번호는 절대 커밋하지 않습니다.** 이름만 문서에 적고, 값은 Secret 과 각자의 환경 변수에만 둡니다. 키 파일은 저장소 폴더 밖에 두세요.

### 키 만들기 (저장소 관리자가 한 번만)

1. 키를 만듭니다. 비밀번호는 팀 비밀번호 관리 도구에 보관합니다. **이 키를 잃어버리면 기존에 설치된 앱 위에 새 버전을 덮어 설치할 수 없습니다.** 키 저장소 파일과 비밀번호를 함께 백업해 두세요.

   ```bash
   keytool -genkeypair -v \
     -keystore soundvisualizer-release.jks -storetype PKCS12 \
     -alias soundvisualizer -keyalg RSA -keysize 2048 -validity 10000
   ```

2. 키 저장소 파일을 base64 로 바꿉니다. (줄바꿈 없이 한 줄로)

   Windows (PowerShell):

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\soundvisualizer-release.jks")) | Set-Clipboard
   ```

   macOS · Linux:

   ```bash
   base64 < soundvisualizer-release.jks | tr -d '\n'
   ```

3. 저장소 **Settings → Secrets and variables → Actions → New repository secret** 에서 위 표의 Secret 4개를 모두 등록합니다.

4개가 다 있어야 **팀 릴리스 키**로 서명합니다. 하나라도 없으면 지금까지처럼 **공용 디버그 키**로 서명합니다. 어느 쪽으로 갔는지는 워크플로 실행 화면의 `서명` 알림에 남습니다.

**서명과 R8 은 따로 갑니다.** 릴리스 키가 없어도 `assembleRelease` 로 빌드해 R8 로 줄인 APK 를 붙입니다(56MB → 34MB). 서명은 공유 디버그 키 그대로라 기존 설치 위에 덮어 설치됩니다. 키를 등록하기 전에도 작은 APK 를 받을 수 있습니다.

| | 빌드 변형 | 서명 | 덮어 설치 |
|---|---|---|---|
| 릴리스 Secret 4개 | 릴리스 (R8) | 팀 릴리스 키 | 첫 배포만 안 됨 (#77) |
| Secret 없음 + 공유 디버그 키 | 릴리스 (R8) | 공유 디버그 키 | 됨 |
| 아무 키도 없음 | 릴리스 (R8) | 러너 임시 키 | 안 됨 |
| `SV_RELEASE_KEYSTORE` 이전 태그 | 디버그 | 공유 디버그 키 | 됨 |

마지막 줄이 예외인 이유는, 그 버전의 빌드 설정에는 릴리스 변형에 서명 설정이 없어 `assembleRelease` 가 **서명 없는 APK** 를 내기 때문입니다. 설치가 안 되므로 그런 태그는 디버그로 갑니다.

> ⚠️ **첫 릴리스 때 한 번은 팀 전원이 앱을 지우고 설치해야 합니다.**
> 지금까지의 태그 APK 는 공용 **디버그 키**로 서명됐습니다. 릴리스 키는 서명이 다르므로, 릴리스 키로 만든 첫 APK 는 **기존에 설치된 앱 위에 덮이지 않습니다**(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, 폰에는 "앱이 설치되지 않았습니다" 로 뜹니다).
> 지우고 설치하면 **저장된 설정이 모두 초기화됩니다**(표현 모드, 소리 종류별 색상·표시, 진동 세기와 패턴, 민감도, 언어). 한 번만 겪는 일이고, 그 다음 릴리스부터는 그대로 덮어 설치됩니다.
> **Secret 을 등록하기 전에 팀에 먼저 알려 주세요.**

### 로컬 빌드에서 달라지는 것

- **키가 없어도 빌드는 그대로 됩니다.** `./gradlew assembleRelease` 는 릴리스 키가 없으면 **디버그 키로 서명**합니다. 서명을 아예 빼면 APK 가 설치되지 않아서, R8 을 켠 빌드를 폰에서 확인할 수 없기 때문입니다. 각자의 기본 디버그 키로 서명되므로 다른 사람에게 건네지는 마세요. CI 는 같은 조합을 **공유** 디버그 키로 만들어 배포합니다.
- `SV_RELEASE_KEYSTORE` 를 설정했는데 파일이 없거나 나머지 셋 중 하나가 비어 있으면 **빌드가 바로 실패합니다.** 다른 키로 서명된 APK 를 폰에서야 발견하는 것보다 낫기 때문입니다.
- 릴리스 빌드는 이름이 바뀌므로(난독화) 크래시 로그를 그대로 읽을 수 없습니다. 되돌릴 때 쓰는 매핑 파일은 `app/build/outputs/mapping/release/mapping.txt` 에 생기고, 다시 빌드하면 덮어써집니다. **태그로 배포한 APK 의 매핑은 릴리스 워크플로가 `mapping-vX.Y.Z.txt` 로 릴리스에 함께 붙여 둡니다.** 로컬에서 누구에게 건넨 APK 가 있다면 그 매핑은 직접 챙겨 두세요.
- R8 이 지우면 안 되는 것(우리 JNI 진입점, ONNX 런타임이 네이티브에서 이름으로 찾는 클래스)은 `app/proguard-rules.pro` 에 이유와 함께 적혀 있습니다. 그 파일이나 AI 코드, ONNX 런타임 버전을 건드렸으면 **릴리스 APK 를 실제로 설치해 AI 분류가 도는지 확인한 뒤** 머지합니다.

  > `./gradlew connectedAndroidTest` 로는 확인되지 않습니다. 계측 테스트는 `debug` 변형에서 돌고, 그쪽은 R8 을 거치지 않습니다. keep 규칙이 빠져도 통과합니다.

  릴리스 APK 에서 확인하는 방법은 두 가지입니다.

  1. **눈으로**: 릴리스 APK 를 설치해 실행하고, 홈 화면에 "소리 종류를 구분하지 못함" 이 뜨지 않는지 봅니다(모델 로딩 성공). 그 다음 소리를 틀어 **위협음 색(기본 빨강)** 이 나오는지 봅니다. AI 를 못 불러왔다면 모든 소리가 환경음 색으로만 그려지므로, 위협음 색이 나왔다는 것은 추론까지 돌았다는 뜻입니다.
  2. **로그로**: AI 결과 로그는 `debuggable` 일 때만 남습니다. `app/build.gradle.kts` 의 `release` 블록에 `isDebuggable = true` 를 **임시로** 넣고(`isMinifyEnabled` 는 그대로 두세요) 빌드하면 `adb logcat` 에 `AI_RESULT` 가 찍힙니다. 확인 뒤 반드시 되돌립니다. 이때는 난독화가 꺼지므로 1번과 함께 보세요.
- 버전(`versionCode`, `versionName`)은 여기서 올리지 않습니다. 배포 시점에 팀장이 정합니다.

# 수행지시문 02: 안드로이드 홈 화면 위젯 (단계 4)

> **사용법**: 로컬 PC에서 Android Studio를 설치한 뒤, 이 저장소를 연 Claude Code 또는 Codex에게
> "`docs/instructions/02-android-widget.md` 수행해줘"라고 말하면 됩니다.
> 아래 내용은 AI 에이전트가 읽는 지시문입니다.

---

## 역할

너는 안드로이드 개발자다. 갤럭시 S25(One UI, Android 15 이상)의 **홈 화면 위젯** 앱을 이 저장소의 `android/` 폴더에 만든다.

## 먼저 읽을 것

1. `docs/data-format.md`: 데이터 형식과 읽기/쓰기 규칙. **반드시 이것을 따른다.**
   특히 할 일 "완료" 판단 규칙(`daily`는 `doneOn == 오늘`)과 sha 충돌 재시도 규칙.
2. `web/app.js`: 같은 규칙을 구현한 웹앱. 화면 구성과 동작 기준으로 삼는다.

## 기술 선택 (특별한 이유가 없으면 그대로 따른다)

| 항목 | 선택 |
|---|---|
| 언어 | Kotlin |
| 위젯 | **Jetpack Glance** (`androidx.glance:glance-appwidget`, `glance-material3`) |
| 설정 화면 | Jetpack Compose + Material 3 |
| 백그라운드 갱신 | WorkManager |
| 네트워크 | OkHttp, JSON은 kotlinx.serialization |
| 저장 | DataStore. 토큰은 **Android Keystore AES-GCM으로 암호화**해서 저장 (`EncryptedSharedPreferences`는 지원 중단이라 쓰지 않음) |
| minSdk / targetSdk | 26 / 최신 안정 버전 |
| 패키지명 | `com.morn24.dashboard` |

라이브러리 버전은 **작업 시점의 최신 안정 버전을 확인해서** 쓰고, Version Catalog(`gradle/libs.versions.toml`)로 관리한다.

## 기능 요구사항

### 1. 설정 화면 (앱 아이콘을 누르면 열림)

- 입력: owner (기본 `morn24`), repo (기본 `dashboard-data`), branch (기본 `main`), 토큰, 웹앱 주소 (기본 `https://morn24.github.io/Make_Dashboard/`)
- "연결 확인" 버튼: `data/todos.json`을 읽어 성공이나 실패를 한국어로 표시 (401/403/404 구분)
- "배터리 최적화 예외 설정" 안내 버튼: 삼성 기기에서 위젯 갱신이 늦어지는 것을 막기 위해 설정 화면으로 이동
- 위젯 추가 방법 안내 문구: "홈 화면 길게 누르기 → 위젯 → Dashboard"

### 2. 위젯 화면 (`SizeMode.Responsive`)

| 크기 | 보여줄 내용 |
|---|---|
| 작은 (2×2) | 오늘 할 일 `완료/전체`, 진행 막대, 진행 중 프로젝트 수 |
| 중간 (4×2) | 위 + 미완료 할 일 최대 3개 (체크 가능) + 1순위 프로젝트의 진행률과 다음 과제 1개 |
| 큰 (4×4) | 위 + 진행 중(`active`) 프로젝트 최대 4개: 이름, 진행률 막대, `next[0]` |

- 상단: 날짜, 마지막 갱신 시각, 새로고침 버튼
- 색상: `GlanceTheme`의 동적 색상(Material You)을 써서 One UI 배경과 어울리게 하고, 다크 모드도 지원
- 위젯 빈 곳이나 제목 탭 → 웹앱 주소 열기 (할 일 추가와 삭제는 웹앱에서 함)
- 설정 전 상태: "앱을 열어 연결을 설정하세요" + 탭하면 설정 화면

### 3. 할 일 체크 (위젯에서 바로)

- 체크 탭 → **화면 먼저 바꾸기** → 백그라운드에서 `읽기 → doneOn 설정 → sha와 함께 PUT`, 409/422면 최대 4번 재시도
- 실패하면 원래 상태로 되돌리고 위젯 상단에 짧은 오류 표시
- 체크 값은 "뒤집기"가 아니라 **탭한 시점의 목표 값**(완료 또는 미완료)으로 저장한다. 재시도해도 결과가 같아야 한다.

### 4. 갱신 시점

- WorkManager 주기 작업: 30분 (네트워크 연결 조건)
- **자정 직후** 1회 갱신 (매일 할 일이 미완료로 돌아가야 하므로). "오늘" 판단은 그릴 때마다 기기 현지 날짜로 다시 계산
- 위젯 새로고침 버튼, 설정 저장 직후, 위젯 처음 추가 시
- 마지막으로 받은 JSON을 DataStore에 캐시해서 오프라인에서도 표시

## 코드 구조 (권장)

```
android/
  app/src/main/java/com/morn24/dashboard/
    data/    Models.kt (Todo/Project/파일 모델), GitHubRepo.kt (read/write/mutate+retry), Store.kt (DataStore, 토큰 암호화)
    domain/  TodoRules.kt (isDone, isVisible, 정렬, 진행률), ProjectRules.kt (정렬)
    widget/  DashboardWidget.kt, DashboardWidgetReceiver.kt, ToggleTodoAction.kt
    work/    RefreshWorker.kt, Scheduler.kt
    ui/      SettingsActivity.kt (Compose)
```

`domain/`은 안드로이드에 의존하지 않는 순수 Kotlin으로 작성해서 단위 테스트한다.

## 테스트

- 단위 테스트 (JUnit): `TodoRules` (daily와 none의 완료 판단, 날짜 변경 시 미완료로 돌아가는지, 지난 완료 숨김, 정렬), 프로젝트 정렬, JSON 왕복(한글과 이모지 포함, 모르는 필드는 보존)
- `GitHubRepo` 테스트: OkHttp `MockWebServer`로 401, 404(파일 없음 → 빈 데이터), 409 후 재시도 성공, 4번 실패 시 예외
- **모르는 JSON 필드를 지우지 않도록** 한다. 웹앱이나 PC 스크립트가 나중에 필드를 추가해도 위젯이 저장하면서 지우면 안 된다. (`JsonObject`를 유지하거나 `ignoreUnknownKeys`와 원본 병합 방식 사용)

## 설치 안내 문서 `docs/android-setup.md`

사용자가 따라 할 수 있게 한국어로 적는다.

1. Android Studio 설치 → `android/` 폴더 열기
2. 폰: 설정 → 휴대전화 정보 → 소프트웨어 정보 → 빌드번호 7번 탭 → 개발자 옵션 → USB 디버깅 켜기
3. USB로 연결 → Run ▶ (또는 Build → Build APK 후 폰으로 옮겨 설치)
4. 앱에서 토큰 입력 (웹앱과 같은 Fine-grained 토큰 또는 새로 발급) → 연결 확인
5. 배터리 최적화 예외 → 홈 화면에 위젯 추가 → 크기 조절

## 제약

- 토큰을 로그(`Log.d` 등), 코드, 저장소에 남기지 않는다.
- `docs/data-format.md` 형식을 바꾸지 않는다. 바꿔야 하면 멈추고 사용자에게 묻는다.
- 서버나 Firebase 같은 외부 서비스를 추가하지 않는다. GitHub API만 쓴다.
- 빌드 산출물(`build/`, `.gradle/`, `local.properties`, `*.apk`, 키스토어)은 `.gitignore`에 넣는다.

## 진행 방식

1. 프로젝트 뼈대 생성 → 빌드 통과 → 커밋
2. `domain/` + 단위 테스트 → 커밋
3. `data/` + MockWebServer 테스트 → 커밋
4. 설정 화면 → 커밋
5. 위젯 (읽기 전용) → 실제 폰에서 확인 → 커밋
6. 위젯 체크 기능 → 커밋
7. WorkManager 갱신과 자정 갱신 → 커밋
8. `docs/android-setup.md` 작성, `docs/work-plan.md` 단계 4를 ✅로 변경

각 단계가 끝날 때 `./gradlew test assembleDebug`가 통과해야 다음 단계로 넘어간다.

## 완료 기준

- [ ] `./gradlew test assembleDebug` 통과
- [ ] 갤럭시 S25 홈 화면에 3가지 크기 위젯이 정상 표시 (사용자 확인)
- [ ] 위젯에서 체크 → 웹앱에 1분 안에 반영 / 웹앱에서 추가 → 위젯 새로고침 시 반영 (사용자 확인)
- [ ] 비행기 모드에서도 마지막 데이터 표시, 오류 문구 표시
- [ ] 자정이 지나면 매일 할 일이 미완료로 바뀜
- [ ] `docs/android-setup.md` 작성

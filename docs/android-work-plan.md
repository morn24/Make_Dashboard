# 안드로이드 위젯 업무계획서

작성일: 2026-10-31 (KST) · 대상: `docs/instructions/02-android-widget.md`, 전체 계획의 단계 4

## 1. 목표와 인계 범위

갤럭시 S25 홈 화면에서 오늘 할 일·일정·진행 중 프로젝트를 보고, 할 일을 체크하는 네이티브 앱을 만든다. 이 문서에서 설계를 확정하고, 실제 코드는 **별도 Codex 세션의 Luna (`gpt-6-luna`), 추론 강도 `max`**가 구현한다.

산출물은 `android/` 프로젝트, 자동 테스트, `docs/android-setup.md`, 검증 기록이다. 이 계획 작성 시점에는 앱 코드가 없으며, 빌드·실기기 동작을 완료로 표시하지 않는다. 원 지시문의 기능 요구와 `docs/data-format.md`의 저장 형식은 유지한다.

## 2. 확인한 시작 상태

- 기존 구현: `web/app.js`, PC 스크립트, Apps Script. 안드로이드 폴더 없음.
- 전체 `PLAN.md`: 계획 구체화 항목 추가 전 14/19 완료. 남은 5개는 앱·빌드, S25 표시, 상호 반영, 오프라인·자정, 설치 문서.
- Android Studio: `C:/Program Files/Android/Android Studio`.
- JBR: `C:/Program Files/Android/Android Studio/jbr/bin/java.exe`. `java`와 `gradle`은 PATH에서 발견되지 않았다. 버전은 구현 시작 시 확인한다.
- SDK: `C:/Users/morn/AppData/Local/Android/Sdk`. 플랫폼 33, 35, 36, 37.0 디렉터리가 존재한다. 설치된 숫자를 안정 버전의 근거로 삼지 않는다.
- `adb` 실행 파일은 있으나 데몬 시작에 실패했다. 기기 연결 여부와 USB 승인은 아직 확인하지 못했다.
- 대시보드 조회 시 사용자 확인 항목은 없었고, 다음 과제는 `안드로이드 위젯 앱 만들기 (수행지시문 02)`였다.

## 3. 기술 선택과 구조

| 영역 | 결정 |
|---|---|
| 앱 | Kotlin, 패키지 `com.morn24.dashboard`, minSdk 26 |
| 빌드 | Gradle Kotlin DSL + Wrapper, Version Catalog (`android/gradle/libs.versions.toml`) |
| 버전 | 구현 당일 공식 릴리스에서 최신 안정 SDK·AGP·Gradle·Kotlin 및 호환 JDK를 확인. Compose BOM, Glance, WorkManager, DataStore, OkHttp, serialization도 안정 버전과 근거 URL을 기록. preview/alpha/beta/RC는 제외 |
| 설정 | Compose + Material 3, owner/repo/branch/token/web URL, 한국어 상태 표시 |
| 위젯 | Jetpack Glance + Material 3, `SizeMode.Responsive`, 동적 색상·다크 모드 |
| 네트워크 | OkHttp, Contents API. 테스트에 MockWebServer. 연결·읽기·전체 호출에 제한 시간 설정 |
| 로컬 저장 | 단일 앱 범위 DataStore 인스턴스. 설정, 원본 JSON 캐시, 파일별 성공 시각·오류, 보류 중 체크 작업 저장 |
| 토큰 | Android Keystore AES-GCM. DataStore에는 암호문·IV만 저장. 암호문/토큰은 로그·작업 입력값·예외·저장소에 남기지 않음 |
| 테스트 | Android에 의존하지 않는 domain + JUnit, repository MockWebServer, 저장·작업·UI는 필요한 Android 테스트 |

예상 파일 구조:

```text
android/
  gradle/libs.versions.toml, gradle/wrapper/*, gradlew, gradlew.bat
  app/src/main/java/com/morn24/dashboard/
    data/Models.kt, GitHubRepo.kt, Store.kt, TokenCipher.kt
    domain/TodoRules.kt, ProjectRules.kt, CalendarRules.kt
    widget/DashboardWidget.kt, DashboardWidgetReceiver.kt, ToggleTodoAction.kt
    work/RefreshWorker.kt, ToggleTodoWorker.kt, MidnightWorker.kt, Scheduler.kt
    ui/SettingsActivity.kt, SettingsViewModel.kt
  app/src/test/... (규칙·API 테스트)
  app/src/androidTest/... (필요한 저장·위젯 통합 검증)
docs/android-setup.md
docs/android-verification.md (선택한 버전·명령 결과·실기기 미확인 항목)
```

## 4. 데이터 규칙 확정

### 할 일과 프로젝트

- 기기의 `ZoneId.systemDefault()`와 주입 가능한 `Clock`을 이용한다. 캐시에 저장된 '오늘'을 재사용하지 않고 렌더링·액션 시 다시 계산한다.
- daily 완료는 `doneOn == today`, none 완료는 `doneOn != null`. 오늘 이전에 완료한 none은 숨긴다. visible 분모와 완료 개수를 동일한 목록에서 계산하고, 0개는 `0/0`, 진행률 0으로 표시한다.
- 미완료 → 완료로 안정 분할하고 각 그룹의 원래 배열 순서를 유지한다. 프로젝트는 active → paused → done의 안정 분할이며 위젯에는 active만 표시한다.
- 첫 active가 1순위 프로젝트다. 다음 과제는 `next.firstOrNull { it !in checked.text }`. 모두 체크됐으면 `다음 과제 없음`으로 표시한다.
- GitHub에서 받은 원본 `JsonObject`를 유지한다. 쓰기는 대상 todo의 `doneOn`과 루트 `updatedAt`만 바꾸고, 다른 항목·필드·중첩 객체·배열 순서를 보존한다. `ignoreUnknownKeys`만으로 보존했다고 판단하지 않는다.
- 파일은 UTF-8, 2칸 들여쓰기, 끝 줄바꿈 1개. 프로젝트와 일정은 위젯에서 PUT하지 않는다.

### 일정과 크기별 표시

- 종일 일정은 날짜로 `start <= today < end`. 시간 일정은 기기 현지 날짜의 자정부터 다음 자정까지와 겹치는지 Instant로 비교한다. DST가 있는 지역도 24시간 고정 덧셈 대신 다음 날짜의 자정을 쓴다.
- 오늘 일정 정렬은 종일 먼저, 시간 일정은 실제 시작 Instant 오름차순, 동률은 원래 순서. `timeZone` 필드는 표시용 정보이며 '오늘'의 기기 시간대를 대체하지 않는다.
- **다음 일정은 오늘과 겹치는 시간 일정 중 `end > now`인 첫 항목**이다. 진행 중도 포함하고 종일·이미 종료한 일정은 제외한다. 다음 날 전용 일정은 선택하지 않는다.
- 작은 크기: 날짜·마지막 갱신·새로고침, 할 일 `완료/전체`와 진행 막대, 다음 일정 1개, active 프로젝트 수.
- 중간 크기: 작은 크기의 요약 + 남은 오늘 일정(종일은 오늘 유효하면 포함, 시간 일정은 `end > now`) 최대 2개, 미완료 할 일 최대 3개, 첫 active의 진행률·다음 과제 1개.
- 큰 크기: 공통 요약·미완료 할 일 + 오늘 일정 최대 5개(완료 일정 흐리게, 진행 중 강조), active 최대 4개(이름·진행률·미확인 다음 과제). 중간 크기의 일정 목록과 1순위 프로젝트를 중복 렌더링하지 않는다.
- calendar 404는 일정 구역 전체를 숨긴다. 파일은 있으나 남은 시간 일정이 없으면 `남은 일정 없음`. 파싱·인증·통신 실패는 404로 취급하지 않고 이전 캐시·오류를 표시한다.
- 시작 레이아웃 후보는 약 140×140 / 300×140 / 300×300 dp. 이는 런처 셀 크기 확정값이 아니다. 실제 가용 크기로 breakpoint를 조정하고 원 요구의 최대 항목 수를 충족하도록 Glance 목록·줄수 제한을 사용한다. 2×2·4×2·4×4 셀 검증은 S25에서 한다.
- 제목·빈 곳은 저장한 웹 URL을 연다. 새로고침·체크는 독립 액션으로 처리한다. 연결 전에는 설정 화면을 연다. 긴 한글·이모지·글꼴 확대·다크 모드를 확인한다.

## 5. 동기화와 실패 복구

### 읽기와 설정

1. 기본 설정은 원 지시문과 같다. 웹 URL은 정상적인 HTTPS 주소로 검증하고, 토큰 입력은 마스킹한다. 기존 토큰을 입력란·SavedState에 평문으로 복원하지 않는다.
2. 연결 확인은 입력 중인 설정으로 `todos.json`을 읽는다. 401(토큰), 403(권한/한도), 404(저장소·브랜치·파일 확인 필요)를 구분하며, 404를 성공으로 안내하지 않는다. 연결 확인 요청 자체는 파일을 생성하지 않는다.
3. 일반 갱신의 파일 404는 todos/projects 빈 데이터, calendar 미연결이다. GitHub는 접근 불가 저장소도 404로 응답할 수 있으므로 파일 부재만으로 단정하지 않는다.
4. 파일별 읽기 성공분은 저장하고 실패분은 기존 캐시를 유지한다. 마지막 성공 시각은 실제 성공 때만 변경한다. 깨진 JSON·미지원 version을 빈 목록으로 바꾸거나 PUT하지 않는다.
5. 설정 저장 시 구성 세대(config generation)를 올리고 저장소 식별자가 바뀌면 이전 캐시·보류 액션을 분리/정리한다. 구 설정으로 시작한 요청은 새 설정의 상태를 덮어쓸 수 없다.
6. Keystore 복호화 실패는 재연결 안내로 처리한다. Android 백업/기기 이전에 암호화 저장 파일을 포함하지 않도록 manifest·백업 규칙을 설정한다.

### 체크 액션

1. 액션에 todo id, **렌더링 시 의도한 목표 완료값**, 해당 날짜를 전달한다. 콜백에서 현지 날짜를 재확인해 오래된 날짜의 위젯이면 먼저 다시 그린다. 목표값을 최신 서버 상태의 반전으로 계산하지 않는다.
2. 작업 id, 설정 세대, todo id, 목표 `doneOn`(탭 시 날짜 또는 null)을 저장하고 낙관적 표시를 즉시 갱신한다. 같은 todo는 작업 중 재탭을 막아 액션 중복을 제한한다. 여러 위젯은 같은 저장 상태를 본다.
3. 실제 PUT은 지속 가능한 one-time Worker로 넘긴다. 체크 Worker에는 Connected 제약을 걸어 무한 대기시키지 않고, 오프라인이면 제한 시간 안에 실패·원복·오류를 보여준다. 실패 액션을 나중에 몰래 재적용하지 않는다.
4. 앱 범위 조정자로 refresh·toggle의 캐시 반영을 직렬화하고 보류 목표를 overlay한다. 실패 시 해당 작업 overlay만 제거하며 다른 todo의 변경·새로 받은 원본 전체를 과거 스냅샷으로 되돌리지 않는다.
5. 매 시도마다 GET → 대상 id 존재 확인 → 목표 `doneOn` 적용 → 받은 sha로 PUT. **총 최대 4회 PUT 시도(최초 1회 + 충돌 재시도 3회)**로 웹앱 및 '4번 실패' 테스트와 맞춘다. 409/422만 짧은 증가 backoff로 재시도한다. 401/403/404·파싱 실패는 즉시 실패한다.
6. 대상 id가 사라졌으면 재생성하지 않고 오류 처리한다. 응답이 유실된 경우에도 같은 목표값으로 재시도하므로 뒤집히지 않는다. 프로세스 재시작 시 저장된 작업과 Worker를 복구해 영구 '처리 중'을 남기지 않는다.
7. PUT 성공 후 반환 sha를 저장한다. 웹앱의 `STALE_GUARD_MS=90_000` 의도처럼, 직전 sha가 잠시 다시 GET되는 경우 90초 동안 성공 데이터를 과거로 덮어쓰지 않는다. 다른 sha의 새 변경은 받는다.

## 6. 갱신 일정과 Android 제약

- 네트워크 Connected 조건의 고유 PeriodicWork 30분. 중복 등록하지 않는다.
- 설정 저장·위젯 첫 추가·수동 새로고침은 즉시 작업과 전체 위젯 update를 요청한다. 마지막 위젯 삭제 시 위젯용 정기 작업을 정리하고 다시 추가하면 등록한다.
- 자정은 **네트워크 조건 없는 고유 OneTimeWork**로 다음 현지 자정 직후를 예약한다. 실행하면 캐시만으로 먼저 다시 그리고 다음 자정을 다시 예약한 뒤 네트워크 갱신을 요청한다. 자정에 `doneOn`을 삭제하거나 GitHub에 PUT하지 않는다.
- 부팅·날짜·시각·시간대 변경 시 수신 가능한 브로드캐스트와 앱/위젯 진입에서 날짜를 재계산하고 자정 예약을 복구한다. 실행 중 UI가 오래된 '오늘'을 계속 쓰지 않게 한다.
- WorkManager는 정확한 시각·30분 상한을 보장하지 않는다. 절전 중 자정 갱신은 지연될 수 있다. 별도 exact-alarm 권한을 요구하지 않고, 실행 가능한 첫 갱신에서 현지 날짜를 적용한다. 이 한계는 설치 문서에 적는다.
- 배터리 안내 버튼은 일반 배터리 최적화 설정 화면으로 이동한다. 삼성 '절전/초절전 앱' 제외 방법과 위젯 추가 방법을 한국어로 설명한다. 새 서비스·Firebase·서버는 도입하지 않는다.

## 7. 단계별 구현과 검증 게이트

각 단계는 이전 단계의 실제 빌드 통과 후 진행한다. Windows에서는 `android/gradlew.bat test assembleDebug`, 다른 환경은 `./gradlew test assembleDebug`를 사용한다. 완료 단계마다 관련 파일만 커밋한다. SDK/JDK·네트워크 문제로 실행하지 못하면 통과로 기록하지 않는다.

| 순서 | 구현 산출물 | 검증 |
|---|---|---|
| 1 | 공식 버전 확인 기록, Wrapper 포함 프로젝트 뼈대, manifest·아이콘·ignore | Gradle test/assembleDebug, 비밀·머신 경로 제외 확인 |
| 2 | domain 모델·규칙·테스트 | daily/none·날짜 변경·지난 완료·0개·안정 순서, checked 제외, 자정/DST/종일 end 미포함/다일·동시 일정 |
| 3 | 원본 JSON 보존 repository, Store·TokenCipher | 한글·이모지·미지 필드 왕복, 401/403/404, branch/ref·sha·base64·줄바꿈, 409/422 후 성공, 4회 실패·삭제된 id·깨진 JSON |
| 4 | 설정 화면·저장·재연결·배터리 안내 | 401/403/404별 한국어, 설정 변경 뒤 오래된 응답 배제, 암호화/복호화 실패 |
| 5 | 읽기 전용 3크기 위젯, 빈 상태·오류·캐시 | API 31+ 에뮬레이터/기기 렌더링·리사이즈·다크·긴 글. S25 미확인이어도 이후 구현은 계속 진행 |
| 6 | 낙관적 체크·Worker·충돌/실패 복구 | 목표값 멱등성, 같은 id 연타, 서로 다른 id, refresh 경합, 오프라인 원복, 재시작·stale sha |
| 7 | 30분·자정·설정 저장·추가·수동 갱신 | 작업 중복 방지, 네트워크 없는 자정 캐시 갱신, 시간대 변경·다음 자정 재예약 |
| 8 | 설치 안내·검증 기록·PLAN 반영 | 최종 test/assembleDebug, 실제 수행/미확인 구분, S25 체크리스트 전달 |

실기기를 사용할 수 없어도 자동 검증·구현·설치 안내는 끝까지 진행한다. 설치를 위해 개인 토큰이나 USB 승인이 필요한 경우에만 해당 사용자 동작을 요청한다. 토큰을 채팅으로 보내 달라고 하지 않는다.

## 8. 완료 판정과 수동 확인

`PLAN.md`의 기존 단계 4 항목을 결과에 맞게 체크한다. S25 사용자 항목 두 개는 사용자 확인 없이 체크하지 않는다. 오프라인·자정 항목은 검증 근거가 있을 때만 체크한다. 전체 단계 4는 남은 수동 확인까지 끝나야 `docs/work-plan.md`에서 ✅로 바꾼다.

사용자 확인 절차는 `docs/android-setup.md`에 다음을 적는다.

1. APK 설치·앱에서 토큰 입력·연결 확인·설정 저장.
2. S25에서 2×2, 4×2, 4×4 위젯을 추가하고 한글·다크 모드·리사이즈 확인.
3. 위젯 체크 후 열린 웹앱에서 1분 안에 반영 확인, 웹앱 추가 후 위젯 새로고침으로 확인.
4. 비행기 모드에서 캐시 유지와 새로고침/체크 오류·원복 확인.
5. daily 완료 후 날짜가 바뀌고 작업이 실행되면 미완료로 표시되는지 확인. 불필요한 데이터 파일 초기화가 없는지 확인.

## 9. 공식 확인 자료

계획 세션에서 공식 릴리스 표의 Glance 안정 버전은 **1.2.0 (2026-08-26)**임을 확인했다. 구현자는 이를 출발점으로 아래 문서의 당일 내용을 재확인하고 선택 버전·호환성·URL을 `docs/android-verification.md`에 기록한다. 나머지 의존성의 최종 버전은 뼈대 빌드 전에 확정한다.

- [Glance 릴리스](https://developer.android.com/jetpack/androidx/releases/glance)
- [Glance UI·크기 처리](https://developer.android.com/develop/ui/compose/glance/build-ui)
- [WorkManager 작업 정의·반복·초기 지연](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Android Gradle Plugin 릴리스](https://developer.android.com/build/releases/gradle-plugin)
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)

실행 순서와 세션 운영 규칙은 [Luna 구현 수행지시문](instructions/02-android-widget-luna.md)을 따른다.

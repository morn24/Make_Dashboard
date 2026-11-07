# 위젯 기능 맞추기 검증 (versionCode 7 / versionName 1.6)

작성일: 2026-11-07 · 계획: [업무계획서](android-widget-parity-work-plan.md) · 지시: [수행지시문 06](instructions/06-android-widget-parity-sonnet.md)

**실제 `morn24/dashboard-data`와 사용자 토큰·폰은 쓰지 않았다.** 쓰기 흐름은 단위 테스트(가짜 저장소, MockWebServer)와 에뮬레이터(Pixel_6a, API 36)에서 PC의 가짜 GitHub 서버(`scripts/fake_github_server.py`, 익명 데이터)로만 확인했다. 실기기는 설치·접속하지 않았다.

## 1. 데이터 불일치 B1–B5 대응표

| # | 웹앱 규칙 | 고친 곳 | 검증 |
|---|---|---|---|
| B1 | `weekly`는 `days`에 오늘 요일이 있을 때만 보임 | `TodoRules.scheduledOn/isVisible`, `DashboardDisplayData.todo`(전에는 `weekly`가 있으면 할 일 파일 전체가 형식 오류) | `RuleVectorsTest`(192개 규칙 벡터: 쉬는 요일 weekly·오늘 끝낸 weekly 포함), `DashboardDisplayDataTest`, 에뮬레이터에서 쉬는 요일 항목이 목록에서 빠짐 |
| B2 | 반복은 `doneOn == 오늘`이어야 완료 | `TodoRules.isDone` | 벡터, `DashboardDisplayDataTest` |
| B3 | 한 번 할 일의 미래 `date`는 그날까지 숨기고 '예정 n' | `TodoRules.isVisible/isUpcoming`, 진행률 분모 | 벡터, 에뮬레이터 `예정 2→3` 표시와 `할 일 1/7` 분모 |
| B4 | 반복 체크·취소 시 `history` 갱신, 취소하면 `doneOn`을 이전 완료일로 | `TodoRules.recordHistory/applyCompletion`, `TodoMutations.setCompletion` | 벡터(체크 384·왕복 192), `TodoMutationsTest`(90일 자르기, history 없는 옛 항목), 에뮬레이터에서 서버 파일의 `history`·`doneOn` 직접 확인 |
| B5 | 저장할 때 최상위 `updatedAt` 갱신 | `GitHubRepo.mutate` (초 단위 ISO 시각) | `GitHubRepoTest`, `ToggleTodoProcessorTest`, 에뮬레이터 서버 파일 |
| - | 모르는 필드(`gtask`·`gsync`·`time`·`days`·`date`·`history`·그 밖의 키·최상위 키) 보존 | `TodoMutations` (항목·문서를 복사해 필요한 키만 변경) | `TodoMutationsTest`, `ToggleTodoProcessorTest`, 에뮬레이터에서 `gtask`/`gsync`/`futureTopLevelKey` 그대로 |

체크 쓰기는 '목표 doneOn'이 아니라 '완료/취소 의도'를 저장 직전의 최신 파일에 적용한다(`ToggleTodoProcessor`). 실행 취소는 완료 직전의 `doneOn`·`history`(없었으면 키 없음)를 기억해 정확히 복원하고, 그 사이 다른 기기가 `doneOn`을 바꿨으면 충돌 메시지를 보인다. 보류 오버레이도 같은 계산(`TodoRules.applyCompletion`)을 쓴다(`overlayShowsTheSameStateAsTheRemoteWriteWillProduce` 테스트). 이전 버전(1.5)이 남긴 pending 기록은 `makeDone`이 없으면 옛 목표값으로 의도를 추정하고, 옛 실행 취소 기록은 공통 규칙으로 취소해 앱이 죽지 않는다(테스트 있음).

교차 검증 벡터 만들기: `node scripts/gen_rule_vectors.js` → `android/app/src/test/resources/rule-vectors.json` (`rules.js`를 수정하지 않고 vm으로 불러옴. 웹 규칙을 바꾸면 이 명령을 다시 실행하고 `RuleVectorsTest`가 깨지는 곳을 고친다). 입력 문장은 `scripts/test_rules.js`의 11개를 포함해 39개 문장 × 3개 기준 시각 = 117개.

## 2. 기능 A–H 상태

| ID | 기능 | 상태 | 확인 |
|---|---|---|---|
| A | 할 일 규칙 일치(B1–B5) | 완료 | 위 표 |
| C | 시간·🔥n·`n일 지남`·`예정 n` 표시 | 완료 (일부 미확인, 7절) | 4×4·4×2·2×2 에뮬레이터 캡처 |
| D | 할 일 추가(날짜·시간 읽기) | 완료 | `+` → 대화상자 → 저장 → 서버 파일에 새 항목, 위젯 즉시 갱신 (에뮬레이터), `TodoInputParser`는 벡터 117개 통과 |
| E | 수정·내일로 미루기·삭제(되돌리기) | 완료 | 제목 탭 → 수정 대화상자(반복 요일·시간), 삭제 → `삭제됨 · 되돌리기` → 원래 자리 복원 (에뮬레이터 서버 파일 순서 확인) |
| F | 일정 패널 머리 → 캘린더 앱 | 완료 | 에뮬레이터에서 Google 캘린더 앱(`AllInOneCalendarActivity`)이 열림 |
| G | 프로젝트 `다음 ·` 줄 → 다음 과제 체크 | 완료 | 동그라미 체크 → 서버 `projects.json`에 `checked: {text, at}`만 추가, `next`·`updatedAt`·다른 프로젝트·다른 키 그대로 |
| H | PMR-Wiki 칩 | 완료 | `PMR 실패 1` 칩 표시, 누르면 Chrome이 `…github.io/Make_Dashboard/#pmr`로 열림(로그캣 `act=VIEW dat=https://morn24.github.io/...`) |

화면·동작 결정(계획에 없던 선택):

- 행 구성: 체크 칸(44dp 너비, 48dp 높이)은 체크, 제목 영역은 수정. 시간은 제목 앞, 🔥/`n일 지남` 배지는 제목 아래 줄(2×2는 시간·제목만).
- 4×4에서는 할 일 패널이 왼쪽 열의 약 60%를 쓴다(`+` 머리 48dp 때문에 행이 너무 적게 보여서).
- `+`는 4×4·좁고 긴 크기·4×2에서는 할 일 패널 머리, 2×2에서는 목록 안 할 일 미리보기 머리에 둔다(스크롤 목록 안이라 공간 문제 없음).
- PMR 칩 우선순위: 실패 > 결정 > 끊김(하루 넘게 동기화 없음). 하나만 보인다.
- 요일 버튼은 현재 선택이 켜 둔 요일에서 시작해 토글한다(평일에서 수요일만 끄면 월·화·목·금). 웹앱은 비운 상태에서 시작한다.
- 새 할 일 `id`는 `t_` + 시간 36진수 + 랜덤 4자, 배열 맨 뒤, 한 번 할 일은 날짜가 없으면 오늘(웹앱 `addTodo`와 같음).

## 3. 명령 결과

| 명령 | 결과 |
|---|---|
| `android/`에서 `./gradlew.bat test assembleDebug` (기준 상태, 구현 전) | 통과 (42 tasks) |
| `./gradlew.bat test assembleDebug lintDebug` (구현 후) | 단위 테스트 125개 통과, APK 생성, lint 오류 0 (5절) |
| `node --test integrations/google-calendar/tests/Code.test.cjs` | 9개 통과 |
| `node scripts/test_rules.js` | 통과 (입력 문장 11개, 시작 10분 전 알림, 오후 5시 묶음) |
| `node scripts/test_apps_script.js` | 통과 (9개) |
| `node scripts/gen_rule_vectors.js` | 다시 만들어도 `rule-vectors.json`이 같음(결정적) |

단위 테스트 125개: `RuleVectorsTest` 6(벡터 192+384+192+54+117), `TodoMutationsTest` 13, `ToggleTodoProcessorTest` 11, `DashboardWriterTest` 8(추가·수정·삭제·프로젝트 체크·409 재시도·실패 시 캐시 유지), `GitHubRepoTest` 12(409/422 재시도, 4회 한도), `DashboardSyncEngineTest` 10(PMR 404·실패가 오류가 되지 않음), `DashboardDisplayDataTest` 4, `PmrRulesTest` 6, `RepeatChoiceTest` 3, 그 외 기존 테스트.

## 4. 에뮬레이터 확인 (익명 데이터, 가짜 서버)

준비(디버그 빌드 전용 장치): 앱의 `files/debug-api-base.txt`(adb `run-as`로만 쓸 수 있음)가 있을 때만 `DebugApiEndpointProvider`가 GitHub 주소를 `http://10.0.2.2:8765/`로 바꾼다. 이 파일은 adb로만 만들 수 있어서, 만들지 않은 평소 상태에서는 항상 `api.github.com`을 쓴다.

```
python scripts/fake_github_server.py --port 8765 --data-dir <빈 폴더>     # PC
adb -s emulator-5554 shell run-as com.morn24.dashboard sh -c "echo http://10.0.2.2:8765/ > files/debug-api-base.txt"
adb -s emulator-5554 shell cmd alarm set-timezone Asia/Seoul
```

그 뒤 위젯을 홈 화면에 올리고(위젯 선택기) 앱의 디버그 화면 "가짜 GitHub 서버에서 읽기"를 한 번 눌러 연결한다. 서버가 `data/todos.json` 등을 GitHub Contents API와 같은 규칙(sha, 409)으로 받고, 저장된 JSON을 그대로 돌려줘서 위젯이 쓴 결과를 직접 읽을 수 있다.

실제로 확인한 흐름(서버 로그·파일로 확인):

1. `+` → "11/9 15:30 hospital" 입력 → 미리보기(`11/9(월) 15:30 · 10분 전 알림`) → 추가: PUT 1번, 새 항목이 맨 뒤(`date: 2026-11-09`, `time: 15:30`, 한 번 할 일), `futureTopLevelKey`·다른 항목 그대로, 위젯 `예정 2→3`.
2. 쉬는 요일이 아닌 요일 반복(`weekly`) 체크: `doneOn=오늘`, `history=[오늘]`. `실행 취소` → `doneOn: null`, `history` 키가 다시 없어짐(원래 상태와 같음).
3. 같은 항목 삭제 → 위젯 `삭제됨 · … 되돌리기` → 되돌리기 → 원래 자리(`fx_03`이 `fx_02`와 `fx_04` 사이)에 `gtask`까지 그대로 복원.
4. 409 주입(`/__control/conflict`) 후 `daily` 항목 체크: PUT 409 → 다시 읽기 → PUT 200, `history`가 `[11-04,11-05,11-06,11-07]`로 이어짐.
5. 프로젝트 `다음 ·` 줄 → 다음 과제 대화상자에서 동그라미 체크 → `projects.json`에 `checked` 항목만 추가.
6. 일정 머리 → Google 캘린더 앱, PMR 칩 → 웹앱 `#pmr` 주소 인텐트.
7. 20개 목록(`TODO-01..20`, `PRJ-01..20`, 일정 20개를 가짜 서버가 서빙): 할 일·프로젝트 영역을 각각 스와이프해 `TODO-20`/`PRJ-20`까지 도달. 스크롤 동안 `할 일 0/20`이 그대로(체크 오작동 없음)이고 수정 대화상자가 열리지 않음(제목 탭과 스크롤 구분). 일정 영역은 패널이 너무 작아 이번 크기에서는 스크롤하지 못했다(영역 구조는 변경 없음).

덮어 설치: 같은 에뮬레이터에 versionCode 6 빌드 위로 `adb install -r`로 versionCode 7을 설치했고, 데이터를 지우지 않았는데도 연결·캐시·위젯이 유지됐다(applicationId·디버그 서명 그대로).

## 5. lint

`./gradlew.bat test assembleDebug lintDebug`는 처음에 이 작업 폴더의 `android/local.properties`(커밋하지 않는 PC별 파일) 작성 오류(PropertyEscape)로 lint만 실패했다. 파일을 고치고 lint 분석을 다시 돌린 `lintAnalyzeDebug lintReportDebug lintDebug --rerun`은 **BUILD SUCCESSFUL, 오류 0**이다. 새 경고는 `UseKtx`(문자열 `toUri`/`toColorInt`)와 `QueryPermissionsNeeded`(`OpenCalendarAction`의 캘린더 앱 확인) 정도다.

## 6. 캡처 (익명 데이터)

`docs/android-widget-parity-screenshots/`: `emulator-4x4-parity.png`, `emulator-4x2-parity.png`, `emulator-2x2-parity.png`, `emulator-scroll-last-items.png`.

캘린더 앱 화면은 에뮬레이터에 로그인된 계정의 실제 일정이 보여서 캡처를 저장하지 않았다(앱 실행 여부만 액티비티 이름으로 확인).

## 7. 확인하지 못한 것

- 실기기(S25)에서의 동작 전부. APK 설치·접속은 하지 않았다.
- 꾸미기 7종·큰 글씨(150%)·투명도에서 새 배지의 가독성. 에뮬레이터 런처가 위젯 갱신을 자주 놓쳐(런처를 다시 시작해야 갱신) 해당 조합 캡처를 만들지 못했다. 배지는 기존 `bodyStyle`/색상 규칙을 그대로 쓴다.
- 4×2에서 실행 취소 줄·PMR 칩이 함께 있을 때 머리 줄이 좁아진다(날짜가 줄어듦). 겹침을 줄이는 여백을 넣었지만 그 뒤 다시 캡처하지는 못했다.
- 위젯에서 추가한 할 일이 실제 웹앱·Google 할 일에 보이는지(Apps Script 동기화는 실제 서버가 필요).
- 한글 입력 문장은 에뮬레이터에서 직접 입력하지 못했다(adb는 영문만 입력). 한글 문장은 벡터 117개(웹앱 결과와 비교)로 검증했다.

## 8. APK

`android/app/build/outputs/apk/debug/Dashboard-parity-debug.apk` (applicationId `com.morn24.dashboard`, 디버그 서명 그대로, versionCode 7 / versionName 1.6). SHA-256은 PR 본문과 최종 보고에 적었다(커밋 뒤 다시 빌드하면 값이 달라질 수 있어 이 문서에는 고정하지 않는다).

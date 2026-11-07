# 수행지시문 06: 위젯 기능 맞추기 (웹앱 → 안드로이드 위젯)

작성일: 2026-11-07 · **Claude Sonnet / high** · 계획: [업무계획서](../android-widget-parity-work-plan.md)

## 실행 목적

사용자가 구현을 승인했다. 계획 세션(Claude Opus)은 기능 목록과 설계만 했다. **이 세션이 코드, 테스트, 에뮬레이터 검증, 업데이트 APK, 문서, PR까지 끝낸다.** 계획 승인을 다시 묻지 않는다. 막히면 가능한 데까지 끝내고, 막힌 부분은 사유와 함께 미완료로 보고한다.

## 작업 위치

- worktree: `C:/Users/morn/Desktop/Project/Make_Dashboard-widget`
- 브랜치: `widget-parity` (기준 `origin/main` 5c1008f)
- 원래 checkout(`Make_Dashboard`, main)과 `Make_Dashboard-pmr`(PR #7)는 건드리지 않는다.
- 배포 흐름: 브랜치 push → PR 생성 → **사용자가 병합**. main 직접 커밋, 강제 push, PR 병합은 하지 않는다.
- PR 도구: `"/c/Program Files/GitHub CLI/gh.exe"` (PATH에 없음).
- 커밋 작성자가 없다는 오류가 나면 `git -c user.name=morn24 -c user.email=morngi24@gmail.com commit ...`을 쓴다.

## 시작

1. 다음 문서를 끝까지 읽는다.
   - 업무계획서 `docs/android-widget-parity-work-plan.md` — 범위와 검수 기준은 이 문서가 정한다.
   - `docs/data-format.md`
   - `PLAN.md`
   - `docs/android-scroll-colors-verification.md` — 직전 위젯 상태
   - `web/rules.js`, `web/app.js`의 `toggleTodo`·`addTodo`·`updateTodo`·`deleteTodo`·`postponeTodo`·`toggleNext`·`gcalUrl`
2. PR #7의 `pmr-wiki.json` 필드를 확인한다: `git show origin/pmr-decisions:docs/data-format.md`.
3. 기준 상태를 먼저 확인한다. 아래 명령이 **지금** 통과하는지 돌려 보고, 이전 기록으로 대신하지 않는다.
   - `android/`에서 `./gradlew.bat test assembleDebug`
   - `node --test integrations/google-calendar/tests/Code.test.cjs`
   - `node scripts/test_rules.js`
4. 도구 위치
   - JBR: `C:/Program Files/Android/Android Studio/jbr`
   - SDK: `C:/Users/morn/AppData/Local/Android/Sdk`
   - 에뮬레이터: Pixel_6a AVD(API 36)
   - 디버그 seed: `android/app/src/debug/.../DebugWidgetSeedActivity.kt`
   - worktree에 `local.properties`가 없어 빌드가 실패하면 SDK 경로로 만든다. 이 파일은 커밋하지 않는다.
5. 대시보드 확인: `python "$HOME/.dashboard/dash_update.py" show`. python이 없으면 `py`를 쓴다.

## 구현 순서 (단계마다 테스트 후 커밋)

### 1. A·B: 할 일 규칙과 체크 쓰기 (가장 중요)

업무계획서 3-1의 B1–B5를 고친다.

- `TodoRules.kt`를 `web/rules.js`와 같은 함수 집합으로 고친다. 요일 계산은 기기 현지 날짜 기준이다(`0`=일요일).
- `scripts/gen_rule_vectors.js`를 만든다. `rules.js`를 수정하지 않고 불러와서(`vm`이나 `require` 래핑) 기대값 JSON을 `android/app/src/test/resources/rule-vectors.json`으로 쓴다. Kotlin 테스트가 이 파일을 읽어 비교한다. 생성 명령은 검증 문서에 적는다.
- 체크 쓰기를 '목표 doneOn'에서 '완료/취소 의도 + 원격 최신 항목에 웹앱과 같은 계산'으로 바꾼다.
  - 반복 할 일은 `history`를 90일로 자르고, 취소하면 `doneOn`을 이전 완료일로 되돌린다.
  - 최상위 `updatedAt`도 갱신한다.
  - 실행 취소에는 완료 직전의 `doneOn`·`history`를 저장한다. 원격이 그 뒤에 바뀌었으면 지금처럼 충돌 메시지를 보인다.
  - 보류 오버레이도 같은 계산을 쓴다. 기존 pending 저장 형식이 바뀌면, 설치된 이전 버전이 남긴 pending을 읽다 앱이 죽지 않게 처리한다(버리거나 변환).
- 모르는 필드(`gtask`, `gsync`, `time`, `days`, `date`, `history`, 그 밖의 키)가 쓰기 후에도 그대로 남는다는 것을 테스트로 고정한다.

### 2. C: 표시

- 시간, 🔥n(2 이상), `n일 지남`, 할 일 패널 머리의 `예정 n`을 넣는다.
- 진행률 분모는 웹앱과 같게 오늘 보이는 항목으로 한다.
- 2×2·4×2는 공간 우선순위(시간·제목 > 배지)를 지킨다.
- 기존 꾸미기 7종, 큰 글씨, 투명도에서 읽히는지 확인한다.

### 3. F·H: 읽기만 하는 기능

- F: 일정 패널 머리를 누르면 Google 캘린더 앱의 오늘이 열린다. 앱이 없으면 웹 주소로 연다.
- H: `DashboardFile.PMR`을 추가한다.
  - 404는 정상(없음)으로 처리한다.
  - 칩 규칙은 업무계획서 4절 H를 따르고, 누르면 `<웹앱 주소>#pmr`로 간다.
  - 이 파일을 읽다 실패해도 할 일·일정·프로젝트 표시가 실패로 바뀌지 않게 한다.

### 4. D·E: 할 일 추가·편집 대화상자

- 투명 대화상자 Activity 하나에 추가 모드와 편집 모드를 둔다.
- 입력 문장 읽기는 `parseTodoInput`을 Kotlin으로 옮긴다. 같은 벡터 파일로 검증한다.
- 할 일 패널 머리 `+`(48dp)는 추가 모드로, 할 일 제목 탭은 편집 모드로 연다. 체크 원 탭은 지금처럼 체크한다.
- 편집 모드에서 할 수 있는 것: 이름, 반복(한 번·매일·평일·주말·요일 선택), 날짜, 시간, 내일로 미루기, 삭제.
- 삭제는 위젯 실행 취소 줄의 '되돌리기'로 원래 자리에 복원한다.
- 저장 실패 시 입력을 유지하고 다시 시도할 수 있게 한다. 성공하면 캐시를 갱신하고 위젯을 즉시 다시 그린다.
- 모든 쓰기는 `DashboardSyncCoordinator.mutex`와 sha 재시도(최대 4회, 덮어쓰기 금지)를 쓴다.

### 5. G: 프로젝트 다음 과제 사용자 확인

- 4×4 프로젝트 행의 `다음 ·`을 누르면 그 프로젝트의 `next` 목록이 동그라미 체크와 함께 열린다.
- 체크하면 `projects.json` `checked`에 `{text, at}`를 넣고 빼는 일만 한다(웹앱 `toggleNext`와 같은 규칙, 빈 배열이면 키 삭제).
- 다른 필드와 다른 프로젝트는 건드리지 않는다. sha 충돌 테스트를 둔다.

### 6. 통합 검증·APK·문서·PR

업무계획서 5절 검수 기준 1–6을 모두 수행한다. 특히 다음을 지킨다.

- **실제 `morn24/dashboard-data`나 사용자 토큰으로 테스트 쓰기를 하지 않는다.** 쓰기 흐름은 MockWebServer·가짜 저장소·에뮬레이터의 익명 seed로만 확인한다. 실기기 S25에 설치하거나 사용자 캐시를 덮지 않는다.
- 에뮬레이터 캡처는 익명 데이터만 담아 `docs/android-widget-parity-screenshots/`에 둔다.
- 다음을 다시 확인한다.
  - 영역별 스크롤이 20번째 항목까지 가는지
  - 스크롤 중 체크 오작동이 없는지
  - 제목 탭과 스크롤이 구분되는지
- 버전: versionCode 7, versionName 1.6. 서명과 applicationId는 그대로 둔다. `adb install -r`로 덮어 설치되는지 확인한다(데이터 지우기 없이).
  - `android/app/build/outputs/apk/debug/Dashboard-parity-debug.apk`를 만들고 SHA-256을 남긴다.
- 문서
  - `docs/android-widget-parity-verification.md` 작성
  - 새 사용법이 있으면 `docs/android-setup.md` 보완
  - `docs/data-format.md` 갱신: 위젯이 이제 `projects.json` `checked`와 `pmr-wiki.json`을 쓰거나 읽는다는 내용
- `PLAN.md`의 '단계 8' 항목을 실제 결과대로 체크한다. 사용자 확인 항목은 체크하지 않는다.
- PR #7 상태 확인: `gh.exe pr view 7 --json state`.
  - 병합됐으면 `origin/main`에 rebase하고 충돌을 해결한다(양쪽 내용을 모두 살림).
  - 아니면 그대로 둔다.
  - 이후 `widget-parity`를 push하고 PR을 만든다.
  - PR 본문은 한국어로 쓴다: 바뀐 점, 사용자 확인 방법, 테스트 결과, 확인하지 못한 것.
  - 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`를 붙인다.
- 커밋 메시지 끝에는 `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`를 붙인다.

## 지킬 것

- 사용자 관점 동작을 유지한다.
  - 빠른 체크와 실행 취소
  - 오프라인 캐시·오류 표시
  - 2단 구성과 영역별 스크롤
  - 위젯별 꾸미기
  - `SizeMode.Exact`
- 의존성과 toolchain은 꼭 필요할 때만 더한다. MockWebServer가 이미 있으면 그것을 쓴다.
- 쉘 heredoc 안에 작은따옴표(`'`)가 있으면 실패할 수 있다. 긴 스크립트는 파일로 써서 실행한다.
- 파일을 지우지 않는다. 필요 없어진 코드는 지워도 되지만, 문서·캡처·기록은 지우지 않는다.

## 대시보드 기록 (최종 답변 전)

`PLAN.md` 완료/전체×100(반올림)으로 진행률을 계산한다. 그 다음 `show`를 다시 실행해 보고 `set`으로 합친다. `--id`는 생략하고, `--next`(목록 전체 교체)는 쓰지 않는다.

```
python "$HOME/.dashboard/dash_update.py" show
python "$HOME/.dashboard/dash_update.py" set --tool claude-code --progress <값> --current "<사용자 관점 한 문장>" --next-done "<show에 나온 문구 그대로>" --next-add "<남은 과제>" --summary "<한 문장>"
```

- 이번에 끝낸 과제: show의 `위젯에 웹앱 기능 맞추기(요일 반복·연속 기록 버그 수정, 추가·편집, PMR 칩)`가 끝났으면 `--next-done`으로 처리한다.
- 남은 사용자 몫은 `폰에 새 위젯 APK 설치하고 요일 반복·추가 확인` 하나로 `--next-add`한다.
- 기록이 실패해도 한 줄로 알리고 넘어간다.

## 최종 보고 (한국어)

1. 구현한 것: 업무계획서의 A–H별로 완료·부분·미완료를 적는다.
2. 테스트·에뮬레이터 결과: 명령과 결과, 캡처 경로.
3. PR 주소, APK 경로, SHA-256.
4. 확인하지 못한 것과 사용자가 할 일.

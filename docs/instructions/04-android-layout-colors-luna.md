# 수행지시문 04: 위젯 2단 구성·일정 원색·패널 배경·다크 조합

작성일: 2026-11-01 · 모델 **Luna (`gpt-6-luna`)** · 추론 **max**

## 실행 임무

사용자가 새 Luna·max 세션에서 구현하도록 명시 승인했다. `C:/Users/morn/Desktop/Project/Make_Dashboard`의 기존 앱을 개선한다. 앞선 세션은 계획과 기존 결과 검수만 했으므로 **이번 세션이 실제 Android/Apps Script 코드·테스트·렌더 검증·업데이트 APK를 완성**한다. 계획 승인 질문을 반복하지 않는다.

가장 먼저 `docs/android-layout-colors-work-plan.md`를 전부 읽는다. 네 요구 L1~L4와 6절 검수 항목이 이번 완료 기준이다.

1. 2×2는 요약, **4×4는 왼쪽 할 일/일정 + 오른쪽 프로젝트의 실제 2단 구성**. 같은 세로 목록에 행만 늘려 끝내지 않는다.
2. Google에서 지정한 **개별 일정 색상 우선**, 캘린더 기본색 fallback. 일정 앞에 원형 점. 일정 수집 코드와 Android 렌더를 모두 고친다.
3. 할 일·오늘 일정·프로젝트를 은은하게 다른 **패널 배경**으로 구분한다. 제목을 크게 강조해서 대신하지 않는다.
4. 어두운 배경을 유지한 채 **4가지 이상 강조색 조합**을 선택하고, 각 조합의 영역·액션·진행률이 달라진다.

기존 03의 색상/글씨/불투명도·위젯별 적용·Flow 즉시 반영·실행 취소·상태/경계 갱신은 유지한다. 새 앱이나 WebView 통합을 만들지 않는다. 기존 data-format은 유지하고 calendar의 기존 HEX `color`를 활용한다.

## 처음 확인할 것

- 적용 AGENTS, `git status --short`, `PLAN.md`, `docs/data-format.md`.
- 위 업무계획서, `docs/android-ux-verification.md`, 기존 screenshot 두 장 이상(2×2/4×4), `DashboardWidget.kt`, `DashboardSettings.kt`, `SettingsActivity.kt`, `DashboardDisplayData`, CalendarRules, Code.gs, appsscript.json.
- 미커밋 PLAN/README/02 지시문·기존 및 새 계획서/검토서/03·04 문서는 의도된 인계다. 다른 변경을 되돌리거나 무차별 stage하지 않는다.
- 시작할 때 `python "$HOME/.dashboard/dash_update.py" show`를 **처음부터 sandbox_permissions=require_escalated**로 실행한다. 사용자 확인·현재 다음 과제를 읽고 합친다. python이 없으면 py.
- JBR `C:/Program Files/Android/Android Studio/jbr`, SDK `C:/Users/morn/AppData/Local/Android/Sdk`, 기존 Pixel_6a AVD/debug harness를 활용한다. toolchain은 유지하고 baseline `./gradlew.bat test assembleDebug`를 확인한다. 이전 기록은 테스트 69개 성공이지만 현재 기준선을 직접 확인한다.

## 구현 지침

### A: 레이아웃

`WidgetLayoutPlanner`에 열/패널 계획을 명시한다. 큰 크기는 루트 Column의 공통 헤더·상태·요약 아래 Row를 두고 왼쪽 할 일/일정, 오른쪽 프로젝트를 배치한다. 패널 높이/목록을 조절해 첫 화면에 세 영역 일부가 보이도록 한다. 다중 collection의 플랫폼 동작은 검증하고, 필요하면 행 수/더보기로 줄이되 4×4를 단일 세로 목록으로 되돌리지 않는다.

4×2는 폭을 활용하고 2×2는 단일 요약이다. 큰 글꼴·undo·오류·리사이즈에도 핵심 영역과 48dp 액션을 보존한다. 제목은 작고 절제된 위계로 두고, 구분은 배경·간격으로 만든다. 순서·숨김 수·원문 접근은 유지한다.

### B: 캘린더 색상

- 기존 Code.gs는 cal.getColor만 저장한다. CalendarEvent.getColor는 HEX가 아니라 ID이므로 공식 Colors.get 팔레트로 해석한다.
- 공식 custom label/eventLabelId 경로도 다뤄 업무별 사용자색이 지원되게 한다. 세부 REST 읽기/캐시/회차 결합은 계획서 3절을 따른다. CalendarApp iCalUID와 REST event.id를 혼동하지 않는다.
- 캘린더/기간 단위 페이지 처리·팔레트 캐시로 요청을 제한한다. 라벨/팔레트 실패 때 일정 본문을 버리거나 빈 파일로 덮지 않는다. 미지 ID/없는 색/오류는 안전하게 기본색으로 처리한다.
- 기존 `color`에 최종 HEX를 쓰고 색상만 바뀌어도 sync가 변경을 인식하게 한다. Google에는 읽기만 하며 새 일정/라벨/색 변경 API는 쓰지 않는다.
- Android의 상세 일정과 다음 일정 모두 데이터 색의 **원형 마커**를 표시한다. 테마가 원래 색을 덮지 않게 하고 malformed 값은 fallback한다. 장식 점의 접근성 중복 낭독을 피한다.
- Apps Script의 helper 로직·pagination/반복·metadata 오류·색만 변경됨을 Node 등 가벼운 harness로 자동 검증한다. 개인 계정/원격 일정은 수정하지 않는다.

### C: 섹션 배경·팔레트

- 공통 역할 색상 해석기를 만들어 Glance/Compose preview/추가 preview에서 공유한다. 패널별 tint와 적절한 foreground를 사용하고 최소 4개 dark 조합을 제공한다.
- DARK 선택과 palette id는 독립적이어야 한다. 조합을 바꿀 때 어두운 배경을 유지한다. 자동·사용자 HEX·글씨·불투명도·위젯별 설정을 보존한다.
- 이전 저장값은 새 필드 기본값으로 읽고, unknown palette id도 안전하게 fallback한다. 토큰/연결 세대/캐시를 초기화하지 않는다.
- 0/50/100% 불투명도와 대비 보조를 확인한다. 배경 구분을 위해 글자/아이콘/일정 점까지 투명하게 하거나 불투명도 0%를 몰래 100%로 만들지 않는다.
- 꾸미기 화면에서 실제 3개 영역/진행률을 반영한 견본으로 선택하고 draft/취소/복원/개별·전체 적용을 유지한다.

### D: 실제 검증과 전달

- 단계별 `android/`에서 `./gradlew.bat test assembleDebug`, 최종 `lintDebug --console=plain`도 실행한다. 단계별 관련 파일만 지정해 커밋한다. push·강제 reset·개인 데이터/빌드물 stage는 하지 않는다.
- 실제 Glance 2×2/4×2/4×4를 동일 fixture로 렌더하고 이전 화면과 비교한다. 4×4에 오른쪽 프로젝트 열이 보이지 않으면 미완료다. 큰 글꼴·undo·오류도 확인한다.
- 같은 dark 바탕에서 네 palette의 실제 screenshot, 빨강/파랑/초록/기본색 일정 점, 부분 실패·캐시, 기존 빠른 체크/undo 퇴행 여부를 확인한다.
- `docs/android-layout-colors-verification.md`에 L1~L4별 구현 파일/자동 결과/실제 렌더/수동 미확인, 전후 비교·APK 정보·SHA-256·남은 사용자 조치를 남긴다. 예시 캡처만 저장하고 개인 제목·토큰은 노출하지 않는다.
- `docs/android-setup.md`와 `docs/google-calendar-setup.md`를 보완한다. 계정의 기존 Apps Script는 소스 파일 수정만으로 자동 반영되지 않는다. Code.gs 교체·저장·수동 실행·필요한 읽기 API 활성화/재승인을 사용자가 할 수 있게 구체화한다. 원격 설치가 필요한 경우에도 로컬 구현/검증/APK를 끝까지 완성한다.
- 기존 package·Keystore·debug 서명을 유지하고 versionCode를 올려 uninstall 없이 업데이트 가능한 `app-debug.apk` 및 `android/app/build/outputs/apk/debug/Dashboard-layout-colors-debug.apk`를 만든다.
- S25 실제 표시와 개인 Google Calendar 색상 일치는 사용자 확인 전 완료 처리하지 않는다. 환경 제약으로 못한 검증은 정확히 밝히고 가능한 작업을 계속한다.

## 계획과 대시보드 기록

PLAN의 이번 A~E를 실제 결과로 체크하고 과거 완료 기록을 지우지 않는다. 기존 수동 항목과 이번 E는 사용자 확인 증거 없이 체크하지 않는다. 이 세션 앞에서 새 범위를 포함해 재산정한 진행률을 기준으로 한다.

최종 답변 전 다시 `show`를 읽은 뒤 다음 과제를 합친다. 두 명령은 처음부터 `sandbox_permissions=require_escalated`로 실행한다. `$HOME`을 덮어쓰지 않는다. python이 없으면 py.

```powershell
python "$HOME/.dashboard/dash_update.py" show
python "$HOME/.dashboard/dash_update.py" set --tool codex --progress <PLAN완료/전체×100 반올림> --current "실제 단계" --next-done "실제로 끝낸 기존 문구 그대로" --next-add "남은 구체 과제" --summary "구현·검증·수동 미확인"
```

`--id` 생략, `--next` 전체 교체 금지. 새 범위로 낮추어야 할 때만 `--scope-grew`와 이유를 사용한다. 스크립트 실패는 한 줄 알리고 본 작업을 실패 처리하거나 반복 재시도하지 않는다.

최종 답변은 한국어로 구현 결과·실제 검증·APK/문서 링크와 **Apps Script 적용 및 S25 확인**을 분리해 전달한다. 계획/단위 테스트만으로 시각적 개선 완료를 주장하지 않는다.

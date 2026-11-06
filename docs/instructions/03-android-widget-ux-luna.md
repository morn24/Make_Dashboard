# 수행지시문 03: Android 위젯 반응 속도·꾸미기·UI/UX 개선

작성일: 2026-11-01 · **Luna (`gpt-6-luna`), 추론 강도 max**

## 임무와 승인

기존 `C:/Users/morn/Desktop/Project/Make_Dashboard/android/` 앱을 실제로 개선한다. 사용자는 색상·글씨 크기·배경 투명도·탭 반응과 검토서의 11개 제안 **전부**를 구현하도록 승인했다. 구체 설계는 앞선 세션이 완료했으며 이번 세션은 구현·검증·APK 제작을 끝까지 수행한다. 계획 승인 질문이나 '가능합니다' 답변에서 멈추지 않는다.

먼저 [개선 업무계획서](../android-ux-work-plan.md)를 전부 읽는다. **U1~U4와 R1~R11의 15개 대응표가 범위 기준**이다. 일부만 만들고 나머지를 새 제안으로 돌리지 않는다. 적용 지침·사용자 승인 범위 안에서 평범한 기술 선택은 직접 해결한다.

읽을 자료:

1. 적용되는 AGENTS 지침, `PLAN.md`, `docs/data-format.md`.
2. `docs/android-ux-work-plan.md`, `docs/android-ui-ux-review.md`.
3. 기존 `android/`의 data·widget·work·ui·domain·테스트와 `docs/android-verification.md`.
4. `docs/android-setup.md`, `web/app.js`의 공유 규칙.

이 03 문서가 이번 개선의 실행 지시다. 02 문서의 '앱 없음'·새 뼈대 생성은 이미 끝난 초기 작업이며 반복하지 않는다. 웹앱을 앱 안에 합치거나 공유 JSON 형식을 바꾸지 않는다.

## 시작 순서

1. `git status --short`로 인계 변경·다른 세션 작업을 확인한다. 기존 README/PLAN/02 지시문 변경과 Android 업무계획서/검토서/이번 문서는 의도된 미커밋 산출물일 수 있다. 삭제·복원하지 않는다.
2. `python "$HOME/.dashboard/dash_update.py" show`를 처음부터 `sandbox_permissions=require_escalated`로 실행해 기존 다음 과제와 사용자 확인을 읽는다. python이 없으면 py를 사용한다.
3. JBR `C:/Program Files/Android/Android Studio/jbr`, SDK `C:/Users/morn/AppData/Local/Android/Sdk`를 확인한다. 필요 환경 변수는 작업 프로세스 범위에 설정한다. 기존 호환 toolchain을 유지하고 습관적으로 최신 버전으로 올리지 않는다.
4. `android/`에서 기존 `./gradlew.bat test assembleDebug` 기준선을 확인한다. 현재 기록은 51개 테스트 성공이지만 실제 다시 확인한다.
5. 공식 [Glance 레이아웃 스킬](https://developer.android.com/agents/skills/system/glance-canonical-layouts/skill), [Glance 상태 갱신 스킬](https://developer.android.com/agents/skills/system/glance-appwidget-updates-and-memory/skill)과 검토서의 접근성/inset/위젯 품질 자료를 확인해 적용한다. UI 구현을 샘플 전체 교체로 해결하거나 Responsive를 무조건 Exact로 바꾸지 않는다. 기술적으로 조정하면 기능 유지와 근거를 기록한다.

## 구현 순서와 반드시 지킬 사항

업무계획서 8절의 A~H와 최상위 PLAN 항목을 대응시킨다.

### A. 실제 화면 반영과 실행 취소

- 한 DataStore 스냅샷 기반 표시 Flow를 만들고 `provideContent` **안에서** 구독한다. 초기 snapshot만 캡처해 `updateAll`에 의존하는 버그를 수정한다. 수집 루프에서 네트워크/쓰기/Worker 재등록을 하지 않는다.
- 목표 체크값을 로컬에 원자 저장한 직후 UI를 바꾼다. 네트워크·동기화 Mutex를 기다리기 전이다. 저장은 지속 가능한 Worker가 수행한다. 단순 coroutine fire-and-forget로 바꾸지 않는다.
- 보류 행을 유지하고 저장 중·성공·실패를 표시한다. 같은 todo 중복 방지와 여러 위젯 일관성을 유지한다.
- 최근 완료 1개에 실행 취소를 제공한다. 원래 doneOn을 복원하는 새 목표값 작업으로 구현하고 stale operation·날짜/설정 변경·다른 기기 변경·항목 삭제를 검사한다. 총 4회 sha PUT 재시도·unknown 필드 보존을 유지한다.
- 지연 서버보다 앞서 실제 Glance 화면이 바뀌는지 검증한다. Processor 단위 테스트만으로 즉시 표시를 증명했다고 하지 않는다.

### B. 사용자 요청 3가지 꾸미기

- 자동/프리셋/사용자 배경·강조색, 글씨 80~150%(10% 간격), 배경 불투명도 0~100%(5% 간격)를 구현한다. 배경 alpha만 변경하며 시스템 글꼴 배율을 존중한다.
- 위젯별 설정 + 신규 위젯 기본값을 별도 저장한다. 위젯 선택, 이 위젯/전체 적용, 저장 전 실시간 미리보기, 취소, 기본값 복원을 제공한다.
- 꾸미기는 네트워크 없이 동작한다. 저장 과정에서 기존 암호화 토큰·캐시·연결 세대·보류 작업을 지우지 않는다. 완전 투명·밝음/어두움 배경의 가독성을 확인하고 대비 보조 옵션을 제공한다.

### C. 레이아웃과 핵심 행동

- 크기/높이/글꼴에 맞춰 정보량을 조절하고 스크롤·숨겨진 개수·전체 보기로 원문 접근을 보장한다. '3개를 보여야 하니까 터치 영역을 축소'하는 방식은 금지한다.
- 다음 시간 일정은 종일과 별도 요약으로 모든 크기에서 우선 표시한다. 상세 정렬·배열 순서는 공유 약속 유지.
- 체크·새로고침·취소·더보기는 충분한 터치 크기와 의미 있는 접근성 이름/상태를 갖게 한다. 작은 헤더에 버튼을 과밀 배치하지 않는다.
- 데이터 없음·모두 완료·오류·오프라인을 구분하고 문자 막대를 실제 진행 표시로 바꾼다. 긴 제목·다음 과제의 읽기 공간을 확보한다.

### D/E. 신뢰할 수 있는 상태와 시간 변화

- 파일별 성공·부분 실패·404 상태를 저장/표시한다. 가장 최근 파일 하나의 성공을 전체 성공처럼 표시하지 않는다. 날짜 있는 캐시 시각과 복구 액션, 앱의 상세 상태를 제공한다.
- 다음 일정 시작/종료/자정에 네트워크 없는 로컬 재렌더를 예약·복구·정리한다. 같은 경계 즉시 루프·작업 중복을 피한다. 부팅·시간대 변경·위젯 삭제·일정 갱신을 반영한다.
- 원격 30분 작업과 로컬 시각 갱신을 분리한다. exact alarm·새 권한·외부 서비스 없이 Android 절전 제약을 정직하게 설명한다.

### F. 관리 앱 UX와 업데이트 호환

- 연결 후 관리 홈, 위젯 선택/꾸미기, 상태 상세, 웹앱 열기, 연결 변경, 도움말을 제공한다. 위젯 id를 검증해 해당 설정으로 진입한다.
- 지원 런처에 위젯 pin 요청, 미지원 시 수동 안내, 실제 내용을 반영한 picker preview를 제공한다.
- AndroidX ViewModel/viewModelScope·inset·IME·현재 테마의 시스템 바를 적용한다. 회전/테마 전환 시 비밀 아닌 상태를 보존하고 토큰 평문은 SavedState/로그/파일에 저장하지 않는다.
- package/applicationId/Keystore alias와 기존 데이터는 유지하고 versionCode를 올린다. 사용자 폰의 uninstall/clear-data는 하지 않는다. 기존 debug 서명으로 업데이트 APK를 만든다.

## G. 검증·산출물·완료 보고

- 단계마다 `android/`에서 `./gradlew.bat test assembleDebug`, 최종 `lintDebug`도 실제 수행한다. 관련 있는 파일만 지정해 단계별 커밋한다. push·강제 reset·관계없는 변경 stage는 하지 않는다.
- 업무계획서의 지연 서버·동시 체크·undo 충돌·설정 migration·부분 실패·일정 경계 테스트를 추가하고 기존 테스트를 유지한다.
- 사용 가능한 기존 Pixel_6a AVD 등 에뮬레이터에서 실제 UI를 검증한다. 가짜 repository/예시 데이터와 debug 전용 harness로 개인 토큰 없이 위젯을 렌더한다. 원격 개인 데이터를 바꾸지 않는다. 설치/기기 사용은 검증 범위 내에서 진행하고 실기기 권한·인증은 사용자에게 맡긴다.
- 실제 Glance 활성 세션의 지연 응답 전 UI 변화, 3크기·밝음/다크·큰 글꼴·긴 데이터·터치 bounds를 확인하고 screenshot/측정 근거를 저장한다. 자동 테스트만으로 TalkBack/잘림 확인을 대신하지 않는다.
- 렌더링/성능 근거와 U1~U4/R1~R11의 구현·검증·미확인 대응을 **`docs/android-ux-verification.md`**에 기록한다. 실제 명령 결과·APK 정보·SHA-256·수동 절차를 남긴다. 미구현/미검증을 숨기지 않는다.
- `docs/android-setup.md`와 `docs/work-plan.md`를 보완하고 실제 APK `android/app/build/outputs/apk/debug/app-debug.apk` 및 `Dashboard-ux-debug.apk`를 만든다. 빌드 산출물/개인 screenshot은 커밋하지 않는다. 익명 예시 검증 자료만 저장소에 포함할 수 있다.
- 환경이 막히면 가능한 구현과 자동 검증은 끝까지 하고 정확한 제한을 보고한다. 실행하지 않은 검증을 통과로 쓰지 않는다. 기능을 계획서만 남겨 구현 완료로 처리하지 않는다.
- 한국어 최종 답변에 구현 결과, 실제 빌드/렌더/측정, APK·설치/검증 문서 링크, 남은 S25 확인을 전달한다. S25 확인 없이 H와 기존 사용자 확인 항목은 체크하지 않는다.

## 프로젝트 대시보드 지침: 시작/최종 기록 전 필수

최상위 `PLAN.md` 체크 수로 진행률을 계산한다. 완료는 실제 증거에 따라 체크한다. '사용자 확인'을 처리할 때 실제 끝났으면 같은 문구를 `--next-done`, 부족하면 같은 문구를 `--next-add`로 복구하고 이유를 남긴다. 현재 수동 항목은 레이아웃 일부 확인만으로 완료 처리하지 않는다.

최종 답변 직전에 반드시 최신 값을 다시 읽고 다음 과제를 합친다. 아래 명령은 **둘 다 처음부터 `sandbox_permissions=require_escalated`**로 실행한다. python이 없으면 py. `$HOME` 변수는 덮어쓰지 않는다.

```powershell
python "$HOME/.dashboard/dash_update.py" show
python "$HOME/.dashboard/dash_update.py" set --tool codex --progress <PLAN완료/전체×100 반올림> --current "실제 현재 단계" --next-done "실제로 끝낸 기존 과제 문구 그대로" --next-add "구체적인 다음 과제" --summary "구현·검증·미확인 결과"
```

- `--id` 생략. `--next` 전체 교체 금지. 필요한 만큼 `--next-done`/`--next-add`를 반복하고 다른 세션 과제는 유지한다.
- 이 개선 범위 추가 시 계획 세션이 `--scope-grew`로 진행률을 재산정한다. 그 뒤 같은 범위에서는 진행률을 임의로 낮추지 않는다. 추가 범위로 낮춰야 할 때만 `--scope-grew`와 이유를 남긴다.
- 스크립트 실패는 한 줄 알리고 본 구현을 실패로 취급하지 않으며 반복 재시도하지 않는다.

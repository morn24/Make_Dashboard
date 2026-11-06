# 수행지시문 05: 위젯 높이 확장·독립 스크롤·색상 복구·따뜻한 팔레트

작성일: 2026-11-01 · **Luna (`gpt-6-luna`) / max**

## 실행 목적

사용자가 새 Luna·max 세션에서 구현을 명시 승인했다. 기존 프로젝트 `C:/Users/morn/Desktop/Project/Make_Dashboard`의 앱을 수정한다. 앞선 세션은 계획과 사전 검수만 수행했으므로 **이번 세션이 실제 코드·제스처 검증·업데이트 APK를 끝까지 완성**한다.

먼저 `docs/android-scroll-colors-work-plan.md`를 전부 읽는다. S1~S4와 7절 검수 기준이 이번 범위다. 이번 지시는 과거 02~04의 목록 상한/더보기 fallback보다 우선한다. 계획 승인을 다시 요청하거나 숫자 몇 개만 바꾼 뒤 완료하지 않는다.

## 시작

1. 적용 AGENTS, `git status --short`, PLAN, data-format, 이번 업무계획서, 직전 검증 기록 `docs/android-layout-colors-verification.md`, 이전 실제 4×4 캡처를 읽는다.
2. 기존 미커밋 PLAN/README/02 지시문/여러 계획·검토 문서와 **PMR-Wiki.code-workspace** 등 다른 작업물을 되돌리거나 stage하지 않는다. 이번 변경 파일만 다룬다.
3. 시작 때 `python "$HOME/.dashboard/dash_update.py" show`를 처음부터 `sandbox_permissions=require_escalated`로 실행해 기존 과제와 사용자 확인을 읽는다. python이 없으면 py.
4. 기존 JBR `C:/Program Files/Android/Android Studio/jbr`, SDK `C:/Users/morn/AppData/Local/Android/Sdk`, Pixel_6a AVD/debug harness와 연결 가능한 검증 장치를 활용한다. 직전에는 Galaxy S20을 사용했지만 현재 연결을 확인해야 한다.
5. 현재 baseline `android/`의 `./gradlew.bat test assembleDebug`와 Apps Script Node 테스트를 확인한다. 이전 기록의 성공을 현재 검증으로 대신하지 않는다. toolchain을 불필요하게 올리지 않는다.

## A/B: 전체 항목·실제 높이·영역별 스크롤

현재 큰 위젯도 todoRows/projectRows=1이고 take로 잘라 패널에 넘긴다. 패널은 Column/forEach이며 전체 클릭이 웹앱을 연다. 높이도 남는 공간을 다 쓰지 않는다. **이 네 가지를 함께 수정**한다.

- 모든 표시 대상 할 일·오늘 일정·active 프로젝트를 패널 collection에 넘긴다. 가시 행 수와 데이터 수를 분리하고 take(1)/take(4) 등으로 뒷항목을 없애지 않는다.
- 실제 높이에서 헤더/상태/undo를 제외한 본문, 좌우 컬럼, 각 패널에 유한 높이/높이 채우기를 부여한다. 78dp 고정 높이·undo 때문에 1개로 줄이는 분기를 제거한다. 세로로 늘면 처음부터 더 많이 보여야 한다.
- 4×4의 왼쪽 할 일/일정·오른쪽 프로젝트 2단과 은은한 패널 구분을 유지한다. 좁고 긴 크기도 높이를 활용한다. 현재 SizeMode.Exact는 유지한다.
- 각 영역에 bounded **native Glance LazyColumn + 모든 items + stable ids**를 넣는다. 바깥에 같은 방향의 스크롤을 중첩하지 않는다. rootModifier/sectionPanelModifier의 패널 전체/스크롤 바탕 웹앱 clickable을 제거하고 웹 이동은 별도 명시적 액션에 남긴다.
- 세로 스와이프는 목록 탐색이다. 길게 누른 위젯 이동·항목 재정렬을 구현하지 않는다. 일정/프로젝트/할 일은 서로 독립적으로 스크롤되어야 한다.
- 실제 런처에서 다중 collection이 막히면 공식 RemoteViews collection 연동을 패널 수준에서 검토할 수 있다. **웹앱 더보기로 후퇴하는 것은 해결책이 아니다.** 스크롤이 구현되지 않으면 미완료다.
- 기존 체크/undo/로컬 즉시 반영, 48dp 액션, 사용자 순서·checked 제외·캐시·일정 경계를 유지한다. 기존 planner의 '큰 크기도 1개'를 정답으로 한 테스트를 요구에 맞게 교체하고 실제 높이/목록 데이터/제스처를 검증한다.

## C: 캘린더 색상 문제를 실제 단계로 진단

이전 RGB fixture 성공은 개인 캘린더 정상 반영의 증거가 아니다. 직전 기록에는 개인 Apps Script에 새 Code.gs가 아직 적용되지 않았다고 되어 있다. 설치 여부/API 오류/팔레트·회차 결합/원격 JSON/앱 cache·mapper/원형 마커 중 첫 손실 지점을 구분한다.

- 승인된 실제 연결이 있으면 필요한 최소 읽기로 확인하고 제목·메일·토큰·원본 JSON을 출력하지 않는다. 연결된 스크립트는 목적지 프로젝트를 먼저 확인한다. 인증/새 권한 승인은 사용자 동작이다.
- labelVersion/fields 미지원, 401/403/429/5xx, 캐시 없는 첫 실행, legacy ID와 HEX·개별라벨·캘린더 HEX fallback, 반복/종일/이동/페이지 부분 실패를 코드/통합 fixture로 검증한다.
- 오류를 모두 기본값으로 내린 뒤 정상이라고 보고하지 않는다. 개인정보 없는 수집기 버전/단계별 HTTP 종류/색상 출처 개수를 내는 diagnoseCalendarColors 등 진단 경로를 제공한다.
- refreshCalendarColors가 메모리·영속 cache를 강제로 갱신하고 색만 바뀌어도 GitHub에 반영되게 한다. 앱은 이미 유효한 HEX를 palette/alpha로 바꾸지 않고 원형 점에 그대로 전달한다.
- 공유 JSON의 기존 color 형식은 유지하고 Android에 Google OAuth를 추가하지 않는다. Google Calendar에는 GET만 사용한다. 실제 일정을 만들거나 색/라벨을 수정하지 않는다.
- docs/google-calendar-setup.md에 실제 코드·manifest·API 활성화/권한·진단/강제갱신·원격 JSON 갱신·위젯 갱신 순서를 보완한다. 실제 스크립트에 접근하지 못하면 로컬 구현/검증/APK를 끝내고 필요한 사용자 적용을 정확히 남긴다. 개인 데이터 확인 없이 실서비스 복구 완료라고 하지 않는다.

## D: 붉은색·주황색·노란색 조합

- 기존 네 palette id/설정은 유지하고 레드 중심·오렌지 중심·옐로 중심 조합을 각각 새로 추가해 최소 7종을 제공한다. 같은 amber를 이름만 바꿔 추가했다고 하지 않는다.
- 계획서의 coral/sage/cream, apricot/blue/mint, butter-yellow/sky/plum을 출발점으로 실제 대비/조화를 검수한다. 주 액션/진행률이 기존 녹색 혼합에 묻히지 않게 한다.
- 공통 resolver·설정 견본·Glance·pin preview·위젯별 적용/전체 적용·취소·기본값·재시작 저장에 반영한다. 다크 바탕/글씨/투명도는 유지하고 Google 원형 색은 변경하지 않는다.

## E: 완료를 판정할 실제 검수

계획서 7절을 따른다. 특히 다음이 필수다.

1. 할 일/오늘 일정/active 프로젝트 **각 20개** fixture에서 실제 위젯 세로 스와이프로 20번째까지 확인. 각 영역 독립 스크롤·브라우저 미실행·체크 오작동 없음을 전후 캡처/보이는 id/bounds로 기록.
2. 같은 폭과 글꼴에서 실제 높이 3구간을 캡처하고 각 영역 첫 화면 가시 개수를 표로 기록. 긴 위젯에 여러 일정·여러 프로젝트가 처음부터 나와야 한다. 빈 공간만 커지고 1개 고정이면 실패.
3. 스크롤 후 새로고침/체크/undo/오류, 큰 글꼴·투명도, 기존 2단을 확인. 단위 테스트나 정적인 screenshot 한 장으로 스크롤 성공을 대신하지 않는다.
4. 실제 응답 형태의 색상 연계 fixture와 신규 따뜻한 3종 Glance 화면을 확인. 색상 소스/실제 Google 계정 미확인은 분리.

각 단계에서 `android/`의 `./gradlew.bat test assembleDebug`, 최종 `test assembleDebug lintDebug --console=plain`, `node --test integrations/google-calendar/tests/Code.test.cjs`를 실제 수행한다. 관련 파일만 단계별 커밋하고 push/강제 reset/무차별 stage는 하지 않는다.

`docs/android-scroll-colors-verification.md`에 S1~S4 대응표, 높이별 가시량, 스와이프 마지막 항목 근거, 색상 원인/확인 경로, 신규 팔레트, 명령 결과·APK version·SHA-256을 남긴다. 익명 fixture는 에뮬레이터/격리된 테스트 설치에만 넣고 사용자 실기기 계정·캐시·토큰을 강제로 덮지 않는다. 익명 screenshot만 저장하고 개인 데이터는 커밋하지 않는다.

applicationId/기존 debug 서명/Keystore/기존 설정·데이터를 유지하고 versionCode를 올린다. uninstall/clear-data 없이 업데이트 가능한 app-debug.apk와 `android/app/build/outputs/apk/debug/Dashboard-scroll-colors-debug.apk`를 만든다. 설치 안내와 전체 계획도 보완한다.

실제 제스처 검증이 불가능하면 그 부분을 미완료로 표시하고 가능한 코드는 끝까지 구현한다. S25/실제 Google 색 최종 확인 F는 사용자 증거 없이 체크하지 않는다.

## 대시보드: 최종 답변 전 필수

PLAN A~F를 실제 결과에 따라 체크한다. 사용자 확인 항목은 임의로 완료하지 않는다. 진행률은 최상위 PLAN 완료/전체×100 반올림이다.

최종 기록 직전에 아래 show를 다시 읽고 set으로 합친다. 두 명령 모두 처음부터 **sandbox_permissions=require_escalated**로 실행한다. python이 없으면 py. `$HOME`을 덮어쓰지 않는다.

```powershell
python "$HOME/.dashboard/dash_update.py" show
python "$HOME/.dashboard/dash_update.py" set --tool codex --progress <계산값> --current "실제 단계" --next-done "실제로 끝낸 기존 문구 그대로" --next-add "남은 구체 과제" --summary "구현·실제 검증·미확인"
```

`--id` 생략, `--next` 전체 교체 금지. 다른 세션 과제 보존. 이번 범위 증가는 계획 세션에서 반영하며, 추가 범위 없이 진행률을 낮추지 않는다. 실제 새 범위로 낮출 때만 --scope-grew와 이유를 쓴다. 기록 실패는 한 줄 알리고 본 작업을 실패로 간주하거나 반복 재시도하지 않는다.

최종 답변은 한국어로 실제 구현·스크롤/색상 검증·APK/문서 링크와 남은 사용자 동작을 전달한다. 계획 승인을 다시 묻지 않고 승인된 범위를 끝까지 수행한다.

# Android 레이아웃·색상 개선판 검증

## 결과 요약

레이아웃·일정 색상·영역 배경·네 가지 다크 팔레트 구현과 자동 검증을 마쳤습니다. Galaxy S20 (SM-G986N, Android 13, 1080×2400)에 debug APK를 설치해 2×2·4×2·4×4, 네 팔레트, 150% 글씨와 50% 배경 불투명도의 Glance 위젯을 확인했습니다. Galaxy S25와 개인 Google 캘린더 색상 일치는 사용자가 확인할 항목으로 남겨 두었습니다.

검증용 위젯은 익명 fixture 데이터로 만들었습니다. 테스트 중 표시된 `일부 갱신 실패 · 저장된 내용 표시`는 의도적으로 잘못된 fixture 토큰을 사용해 캐시·오류 표시를 확인한 결과이며, 실제 GitHub 저장소나 개인 일정에 쓰기를 수행하지 않았습니다.

## L1–L4 대응

| 항목 | 구현 및 자동 검증 | 실제 화면 근거 |
|---|---|---|
| L1 크기별 레이아웃 | `DashboardWidget.kt`에서 2×2 요약, 4×2 가로 배치, 4×4 좌우 2단을 분리했습니다. `WidgetLayoutPlannerTest.kt`가 실제 dp 크기에 따른 분류를 검사합니다. Glance는 `SizeMode.Exact`로 런처가 제공하는 현재 크기를 사용합니다 ([Android 공식 문서](https://developer.android.com/develop/ui/compose/glance/build-ui)). | [2×2](android-layout-colors-screenshots/widget-2x2-final.png) · [4×2](android-layout-colors-screenshots/widget-4x2-final.png) · [4×4](android-layout-colors-screenshots/widget-4x4-final.png) |
| L2 일정 색상 | `Code.gs`가 읽기 전용 Calendar API에서 캘린더 색상표와 event/label 색을 해석해 기존 `color: "#RRGGBB"` 출력에 넣습니다. Android `WidgetColorResolver.kt`는 각 일정을 원형 마커로 표시하고 잘못된 HEX는 중립 기본색으로 처리합니다. Node fixture 테스트 5개가 색상 선택·fallback 경로를 통과했습니다. | [4×4의 빨강·파랑·초록·기본색 일정](android-layout-colors-screenshots/widget-4x4-final.png) |
| L3 영역 배경 | 할 일·일정·프로젝트가 공통 역할 색을 쓰는 별도 패널로 표시됩니다. 불투명도는 표면에 적용되고 텍스트와 일정 마커 색은 유지됩니다. | [패널 배경](android-layout-colors-screenshots/widget-4x4-final.png) · [150% 글씨·50% 불투명도](android-layout-colors-screenshots/widget-4x4-large-font-50opacity.png) |
| L4 다크 팔레트 | `WidgetColorResolver.kt`의 네 팔레트와 `DashboardSettings.kt` 저장 호환, `SettingsActivity.kt` 공통 견본을 추가했습니다. 색상 resolver 단위 테스트 5개가 통과했습니다. | 같은 어두운 배경에서 [민트·블루·라벤더](android-layout-colors-screenshots/widget-4x4-final.png) · [스카이·앰버·퍼플](android-layout-colors-screenshots/widget-4x4-sky.png) · [라벤더·민트·피치](android-layout-colors-screenshots/widget-4x4-lavender.png) · [앰버·스카이·모브](android-layout-colors-screenshots/widget-4x4-amber.png) |

### 큰 글씨와 미리보기

[150% 글씨·50% 배경 불투명도 미리보기](android-layout-colors-screenshots/appearance-150-50-preview.png)에서 할 일·일정·프로젝트와 실행 취소 견본을 확인했습니다. 설정을 현재 위젯에 적용한 뒤 [실제 4×4 화면](android-layout-colors-screenshots/widget-4x4-large-font-50opacity.png)에서도 세 패널이 함께 보이고, 넘치는 항목은 `+더보기`로 접혔습니다. 검증 후 테스트 위젯은 다크 민트 기본 조합, 글씨 100%, 배경 불투명도 100%로 되돌렸습니다 ([복원 화면](android-layout-colors-screenshots/widget-4x4-restored-default.png)).

## 자동 검증과 APK

- Android: `android/`에서 `gradlew.bat test assembleDebug lintDebug --console=plain` 실행, `BUILD SUCCESSFUL`.
- Lint: 오류 0개, 경고 5개(기존 Android/Gradle/라이브러리 최신 버전 안내).
- Apps Script: `node --test integrations/google-calendar/tests/Code.test.cjs`, 테스트 5개 통과.
- 실기기: Galaxy S20에 `adb install -r`로 v1.4 (versionCode 5)를 설치했습니다. 업데이트 과정에서 uninstall 또는 앱 데이터 삭제를 하지 않았습니다.
- APK: [Dashboard-layout-colors-debug.apk](../android/app/build/outputs/apk/debug/Dashboard-layout-colors-debug.apk)
- 패키지: `com.morn24.dashboard`; 버전 `1.4` (`versionCode 5`).
- SHA-256: `84645454F908A4262EED7577C0CD4729A97C2528E931CFF57111BAC91D417696`
- Debug 서명 인증서 SHA-256: `eef4f7f3c45788f44ba68b0d0c7eb5b80fca39fdba75de04d4b02655a3fb41d4`

## 남은 사용자 확인

개인 Apps Script 프로젝트에는 수정 파일을 아직 적용하지 않았습니다. 기존 프로젝트의 `Code.gs`를 저장소의 `integrations/google-calendar/Code.gs`로 교체하고, 안내에 따라 Google Calendar API를 켠 뒤 `refreshCalendarColors()`를 수동 실행해야 개인 계정의 event/label 색상을 확인할 수 있습니다. 자세한 절차는 [Google 캘린더 설정 안내](google-calendar-setup.md)에 있습니다. 이 코드는 Calendar API를 GET으로만 호출합니다.

Galaxy S25에서 2×2·4×2·4×4 크기와 다크 조합을 확인하고, 위젯 체크 ↔ 웹앱 반영 및 개선판 업데이트 후 설정 유지 여부를 확인하는 일은 `PLAN.md`에 미완료로 남겼습니다. 본 검증은 연결된 Galaxy S20에서 수행했습니다.

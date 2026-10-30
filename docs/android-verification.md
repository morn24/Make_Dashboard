# Android 위젯 검증 기록

작성일: 2026-10-31 (KST)

## 도구 버전과 선택 근거

| 항목 | 선택 | 근거 |
|---|---|---|
| Android Gradle Plugin | 9.3.1 | Kotlin 2.4.21과의 공식 완전 호환 표에 포함. API 37 지원 |
| Gradle Wrapper | 9.7.1 | AGP 9.3의 최소 9.5.0 이상이며 Kotlin 2.4 계열의 9.7.0 지원 범위에 속하는 9.7 패치판 |
| Kotlin | 2.4.21 | 구현일 기준 최신 안정판. AGP 8.5.2–9.3.1, Gradle 7.6.3–9.7.0의 공식 지원 범위 |
| JDK | Android Studio JBR 25.0.2 | 설치 위치 `C:/Program Files/Android/Android Studio/jbr`; AGP 최소 JDK 17보다 높은 런타임 |
| compileSdk / targetSdk | 37 / 36 | 최신 안정 Compose BOM이 compileSdk 37을 요구함. Android 17은 현재 베타이므로 target은 안정판 Android 16(API 36)에 둠 |
| minSdk | 26 | 요구사항 |
| Compose BOM | 2026.09.00 | Android Developers에서 최신 안정 BOM으로 제시 |
| Glance | 1.2.0 | AndroidX 공식 안정 릴리스 표 |
| WorkManager | 2.12.0 | AndroidX 공식 안정 릴리스 표 |
| DataStore | 1.3.0 | AndroidX 공식 안정 릴리스 표 |
| Activity Compose | 1.13.0 | AndroidX 공식 안정 릴리스 표 |
| OkHttp | 5.5.1 | Square OkHttp 최신 안정 릴리스 |
| kotlinx.serialization | 1.11.0 | 최신 안정판. 1.12.0은 RC 상태 |

공식 자료:

- [AGP 9.3 릴리스/호환 정보](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
- [Android Gradle Plugin과 Kotlin/Gradle 호환표](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Gradle 9.7.1 릴리스 노트](https://docs.gradle.org/9.7.1/release-notes.html)
- [Compose BOM 및 compileSdk 요구사항](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)
- [Glance 릴리스](https://developer.android.com/jetpack/androidx/releases/glance)
- [WorkManager 릴리스](https://developer.android.com/jetpack/androidx/releases/work)
- [DataStore 릴리스](https://developer.android.com/jetpack/androidx/releases/datastore)
- [Activity 릴리스](https://developer.android.com/jetpack/androidx/releases/activity)
- [OkHttp 릴리스](https://github.com/square/okhttp/releases)
- [kotlinx.serialization 릴리스](https://github.com/Kotlin/kotlinx.serialization/releases)
- [Android 16](https://developer.android.com/about/versions/16) · [Android 17 상태](https://developer.android.com/about/versions/17)

설치된 SDK에는 Android 13/API 33, Android 15/API 35, Android 16/API 36, Android 17/API 37 플랫폼이 있다. 이 기기에는 Android Studio JBR가 있지만 ADB의 실제 기기 연결·USB 승인은 아직 확인하지 않았다.

## 빌드 및 자동 검증

### 1단계: Wrapper 포함 프로젝트 뼈대

- 실행일: 2026-10-31 (KST)
- 실행 위치: `android/`
- 명령(Windows, `android/`에서 실행): `gradlew.bat test assembleDebug`
- 환경: `JAVA_HOME=C:/Program Files/Android/Android Studio/jbr`, `ANDROID_HOME=C:/Users/morn/AppData/Local/Android/Sdk`
- 결과: **성공**, Gradle 9.7.1, `BUILD SUCCESSFUL in 4m 23s`
- 테스트: 아직 단위 테스트 소스가 없어 `testDebugUnitTest NO-SOURCE`; domain 테스트는 2단계부터 추가
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- wrapper: `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.jar`, `android/gradle/wrapper/gradle-wrapper.properties`
- JBR 확인: OpenJDK 25.0.2 (JetBrains)
- SDK 확인: Android 13/API 33, Android 15/API 35, Android 16/API 36, Android 17/API 37 설치
- ADB: `adb version`은 37.0.1; 기기 연결 상태와 USB 승인은 미확인

### 2단계: domain 규칙과 단위 테스트

- 실행일: 2026-10-31 (KST)
- 명령(Windows, `android/`에서 실행): `gradlew.bat test assembleDebug`
- 결과: **성공**, `BUILD SUCCESSFUL in 6s`
- 테스트: JUnit 15개 통과. daily/none 날짜 규칙·지난 완료 숨김·표시 분모·안정 순서·빈 목록, 프로젝트 상태/checked 다음 과제, 종일 end 미포함·다일·자정 겹침·일정 정렬·진행 중 다음 일정·지난 일정 제외·DST 날짜 경계를 확인
- 수정 이력: 첫 실행에서 테스트 기준 시각이 일정 시작 전인 fixture 오류 1건을 발견해 바로잡고 전체 명령을 다시 통과시킴
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`

### 3단계: GitHub 저장소·DataStore·토큰 암호화

- 실행일: 2026-10-31 (KST)
- 명령(Windows, `android/`에서 실행): `gradlew.bat test assembleDebug`
- 결과: **성공**, `BUILD SUCCESSFUL in 6s`
- 테스트: JUnit 30개 통과. MockWebServer에서 401/403/404, Contents API `ref`/branch/sha/base64/UTF-8 줄바꿈, unknown 필드·중첩 값 왕복, 409 및 422 재읽기 재시도 성공, 4회 충돌 실패, 삭제 todo, 깨진 JSON을 확인. Preferences DataStore 캐시·설정 세대·저장소 변경 시 캐시 무효화와 AES-GCM UTF-8 암·복호화/변조 거부를 확인
- 실제 Android Keystore: 로컬 JVM 단위 테스트는 주입된 테스트 AES 키를 사용. 기기 Keystore 자체의 생성/복원 동작은 S25 수동 확인 항목으로 남음
- 런타임 경고: DataStore 1.3.0의 내부 protobuf가 JBR 25에서 `sun.misc.Unsafe` 폐기 경고를 출력했으나 명령은 성공. 이후 Android 기기에서의 동작은 미확인
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`

### 4단계: 설정 화면과 연결 확인

- 실행일: 2026-10-31 (KST)
- 명령(Windows, `android/`에서 실행): `gradlew.bat test assembleDebug`
- 결과: **성공**, `BUILD SUCCESSFUL in 7s`
- 테스트: JUnit 35개 통과. 설정 기본값·토큰 암호화 저장/입력란 비우기, 401/403/404 한국어 분기, HTTPS 검증, Keystore 복호화 실패 재연결, 연결 도중 폼 변경 시 오래된 결과 폐기를 확인
- 수동 확인: Android Keystore UI·기기 복원 동작과 Galaxy S25에서의 배터리 설정 이동은 아직 기기에서 확인하지 않음
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`

### 5단계: 읽기 전용 3크기 위젯과 캐시 표시

- 실행일: 2026-10-31 (KST)
- 명령(Windows, `android/`에서 실행): `gradlew.bat test assembleDebug`
- 결과: **성공**, 최종 실행 `BUILD SUCCESSFUL in 5s`. 구현 과정에서 Glance API·컴포저블 참조 오류와 새 테스트의 import 누락 등 컴파일 오류 네 차례를 수정한 뒤 최종 명령이 통과
- 테스트: JUnit 41개 통과, 실패·오류 0개. 원본 JSON 보존 캐시, 한글·이모지 매핑, 파일별 성공 시각, 네트워크 실패 시 이전 유효 캐시 유지, 미지원 문서 버전 거부, 설정 세대가 바뀐 뒤 이전 응답의 캐시 쓰기 차단, 기본 설정·Keystore 재연결 안내를 확인
- 구현: `SizeMode.Responsive` 후보 크기 140×140 / 300×140 / 300×300dp, Glance 동적 색상 테마, 설정 전 안내, 제목/빈 곳의 웹앱 열기, 새로고침, 크기별 할 일·일정·프로젝트 표시. calendar 404에서는 일정 구역을 숨김
- 한계: ADB 에뮬레이터나 Galaxy S25의 실제 렌더링·리사이즈·다크 모드는 확인하지 않음. 후보 dp 크기와 줄임 동작은 S25 화면에서 확인 필요
- 런타임 경고: DataStore 1.3.0 내부 protobuf의 JBR 25 `sun.misc.Unsafe` 폐기 경고가 계속 출력됐으나 테스트와 APK 빌드는 성공
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`

### 6단계: 목표값 체크와 실패 복구

- 실행일: 2026-10-31 (KST)
- 명령(Windows, `android/`에서 실행): `gradlew.bat test assembleDebug`
- 결과: **성공**, 최종 실행 `BUILD SUCCESSFUL in 35s`
- 테스트: JUnit 47개 통과, 실패·오류 0개. DataStore의 체크 저장·중복 방지·저장소 변경 시 정리, 네트워크 없이 캐시에서 목표값을 먼저 표시, 성공 시 sha·원본 미지 필드 보존, 409 실패와 삭제 todo 복구, 설정 세대 변경 중단을 확인
- 구현: 체크 당시 현지 날짜와 완료 목표값을 지속 저장하고, 항목별 고유 OneTimeWork로 `GitHubRepo`의 GET→sha PUT 로직을 호출. 동일 항목 중복을 막고 체크 처리와 후속 갱신을 앱 프로세스 내 Mutex로 직렬화. 오류는 짧은 한국어로 캐시에 기록하고 해당 보류 표시만 제거
- 오프라인 처리: 체크 Worker에 네트워크 제약을 걸어 대기시키지 않고 OkHttp의 연결/읽기/전체 호출 제한 안에서 실패 후 원복하도록 구현
- 미수행: 실기기 네트워크 차단·프로세스 종료 후 복구·상호 반영은 폰에서 확인하지 않음. 단위 테스트는 Processor/Store 경로를 확인하며 WorkManager 실제 실행은 S25 확인 절차에 남김
- 런타임 경고: DataStore 1.3.0 내부 protobuf의 JBR 25 `sun.misc.Unsafe` 폐기 경고가 출력됐으나 테스트와 APK 빌드는 성공
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`

### 7단계: 주기·자정·수동 갱신

- 실행일: 2026-10-31 (KST)
- 명령(Windows, `android/`에서 실행): `gradlew.bat test assembleDebug`
- 결과: **성공**, `BUILD SUCCESSFUL in 1m 12s`
- 테스트: JUnit 51개 통과, 실패·오류 0개. 다음 현지 자정 직후 지연과 DST가 있는 23시간 날짜 경계, 완료된 todo의 이전 sha를 90초 동안 무시하고 캐시를 유지하는 동작을 확인
- 구현: 네트워크 연결 조건의 고유 30분 갱신, 설정 저장·위젯 추가·수동 새로고침의 고유 즉시 갱신. 수동 갱신은 오프라인에서도 바로 시도해 캐시와 오류를 표시. 자정 작업은 네트워크 조건 없이 캐시로 위젯을 먼저 다시 그린 뒤 다음 현지 자정을 예약하고 네트워크 갱신을 요청
- 날짜/시각/시간대/부팅 브로드캐스트는 자정 예약을 재설정하고 캐시를 다시 그리며 네트워크 갱신을 요청. 마지막 위젯 제거 때 위젯 갱신 예약을 취소
- 제약: WorkManager는 정확한 자정이나 30분 간격 상한을 보장하지 않음. 예약된 Worker의 실제 절전·부팅 복구 동작과 S25 표시 확인은 기기에서 아직 수행하지 않음
- 런타임 경고: DataStore 1.3.0 내부 protobuf의 JBR 25 `sun.misc.Unsafe` 폐기 경고가 출력됐으나 테스트와 APK 빌드는 성공
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`

### 8단계: 설치 안내와 최종 계획 반영

- 실행일: 2026-10-31 (KST)
- 최종 명령(Windows, `android/`에서 실행): `gradlew.bat test assembleDebug`
- 결과: **성공**, 최종 실행 `BUILD SUCCESSFUL in 26s`; 42개 작업 중 7개가 실행되고 나머지는 `UP-TO-DATE`. JUnit 51개 통과, 실패·오류 0개
- 문서: [`docs/android-setup.md`](android-setup.md)에 Android Studio/USB와 APK 설치, 토큰 권한·Keystore 재연결, 위젯 추가, 상호 반영, 오프라인·자정 확인 절차 작성
- 계획: 자동 검증으로 확인한 구현·오프라인 캐시/오류·자정 날짜 규칙·설치 문서만 `PLAN.md`에서 완료 처리. S25 표시와 두 방향 상호 반영은 체크하지 않음
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`

## 실제 기기 확인

이 작업에서는 Android 에뮬레이터나 실제 휴대전화를 사용하지 않았다. 따라서 Galaxy S25의 3크기 렌더링, 다크 모드·리사이즈, Android Keystore UI, WorkManager의 절전/재부팅 복구, 비행기 모드에서의 실제 위젯 표시, 위젯↔웹앱 상호 반영은 **미확인**이다. 이어서 할 사용자 확인 절차는 [`docs/android-setup.md`](android-setup.md)에 있다.

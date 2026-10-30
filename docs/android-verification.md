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

## 실제 기기 확인

실기기 확인 전. 에뮬레이터와 Galaxy S25의 렌더링 확인은 사용자가 이어서 할 수 있도록 설치 안내에 절차를 남길 예정이다.

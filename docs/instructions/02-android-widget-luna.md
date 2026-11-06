# 수행지시문 02-L: Luna 안드로이드 위젯 구현

작성일: 2026-10-31 · 모델: **Luna (`gpt-6-luna`)** · 추론 강도: **max**

## 임무

이 세션은 계획을 다시 쓰는 세션이 아니라, 확정된 계획에 따라 실제 구현·테스트·설치 문서를 완성하는 세션이다. 프로젝트 `C:/Users/morn/Desktop/Project/Make_Dashboard`에서 작업한다. Android 앱은 아직 없으며, 사용자는 별도 구현 세션의 시작까지 승인했다. 일상적인 가역적 구현·검증을 진행하기 위해 계획 승인을 다시 요청하지 않는다.

## 반드시 읽을 자료와 우선순위

1. `docs/data-format.md`: 변경 금지인 공유 데이터 약속. 변경이 꼭 필요하면 그 변경만 멈추고 사용자에게 묻는다.
2. `docs/android-work-plan.md`: 이번 세션에서 확정된 설계·단계·검증·완료 판정.
3. `docs/instructions/02-android-widget.md`: 원래의 전체 기능 요구. 구체적인 충돌 처리·자정 제약·수동 확인 구분은 업무계획서로 보완했다.
4. `web/app.js`: `isDone`, `isVisible`, `mutate`, `toggleTodo`, `eventsOn`, `STALE_GUARD_MS`, 프로젝트 checked 처리.
5. `PLAN.md`, `docs/work-plan.md`, `.gitignore`, 작업 위치에 적용되는 AGENTS 지침.

계획서와 실제 라이브러리 API가 다르면 기능 요구를 유지하는 작은 기술 조정을 하고 검증 문서에 이유를 남긴다. 공유 데이터 형식 변경, 새 서비스 도입은 하지 않는다.

## 첫 작업

1. `git status --short`로 다른 세션 변경을 확인한다. 사용자/다른 세션의 변경을 되돌리거나 무차별 stage하지 않는다. 인계받은 계획 문서는 미커밋 상태일 수 있다.
2. 프로젝트 지침대로 `python "$HOME/.dashboard/dash_update.py" show`를 **처음부터 sandbox_permissions=require_escalated**로 실행한다. 기존 다음 과제와 사용자 확인을 읽어 실제 결과와 합친다. `python`이 없으면 `py`.
3. Android Studio JBR·SDK·ADB를 확인한다. `java`는 PATH에 없을 수 있으므로 계획서의 JBR을 먼저 확인한다. `JAVA_HOME`/`ANDROID_HOME`이 필요하면 이 작업 프로세스 범위에서 설정하고 머신 전체 설정을 덮어쓰지 않는다. `$HOME`을 다른 변수로 재사용하지 않는다.
4. 공식 릴리스에서 최신 안정 버전·호환 조합을 확인하고 `docs/android-verification.md`에 근거와 날짜를 적는다. 설치된 preview SDK를 최신 안정으로 오해하지 않는다. 비공개 데이터나 토큰 없이 데모·MockWebServer로 먼저 구현한다.
5. `android/`를 만들고 Wrapper 포함 첫 빌드를 실행한다. 이후 업무계획서의 8단계를 차례로 수행한다.

## 필수 구현 체크포인트

- Kotlin + Compose 설정 + Jetpack Glance 3크기 + WorkManager + DataStore + Keystore AES-GCM + OkHttp/serialization.
- minSdk 26, 최신 안정 target/compile SDK, Version Catalog. Wrapper jar·properties·스크립트를 포함해 다른 PC에서도 빌드 가능하게 한다.
- 설정 기본값: morn24 / dashboard-data / main / `https://morn24.github.io/Make_Dashboard/`. 연결 확인에서 401/403/404 구분, 토큰 마스킹, 배터리 설정과 위젯 추가 안내.
- 캐시는 원본 JSON을 보존. unknown 필드를 유실하지 말고, invalid JSON을 빈 데이터로 덮어쓰지 않는다. 읽기 실패는 캐시 유지·오류 표시. calendar 404는 일정 칸 숨김.
- todo daily/none 규칙, 오늘 기준 visible 분모, 배열 안정 순서, active 프로젝트 순서, checked 제외한 다음 과제.
- 일정은 날짜 겹침·종일 end 미포함·자정 넘김·시간대 처리. '다음 일정'은 오늘 시간 일정 중 진행 중/시작 전의 첫 항목. 크기별 한도는 원 지시문과 업무계획서대로.
- 체크는 목표값과 탭 날짜를 고정, 낙관적 표시 후 지속 가능한 Worker에 위임. 같은 항목 처리 중 중복 탭 방지. refresh 경합·실패·프로세스 종료·오래된 sha 응답을 고려한다.
- GET→원본에 목표 변경→sha PUT, 409/422 시 재읽기, **총 4회 시도**. 실패 시 해당 체크만 원복·한국어 오류. 오프라인 체크를 무기한 대기 후 전송하지 않는다.
- 정기 30분 Connected, 네트워크 없는 자정 캐시 재렌더·다음 자정 재예약, 수동/설정 저장/처음 추가 갱신. exact alarm·30분 상한·정확한 자정 실행을 보장한다고 설명하지 않는다.
- `projects.json`/`calendar.json`에 PUT하지 않는다. 자정 daily 초기화를 위해 파일을 수정하지 않는다.
- 토큰·암호문·Authorization·개인 데이터는 코드·로그·스크린샷·테스트 fixture·커밋에 넣지 않는다. Keystore 분실·복원 시 재연결 처리와 백업 제외 규칙을 포함한다.

## 검증과 커밋

업무계획서 7절의 테스트 표를 기준으로 검증한다. 각 단계 후 `android/`에서 `./gradlew.bat test assembleDebug`를 실행하고 통과한 단계만 커밋한다. 폰 검증을 기다리느라 나머지 구현을 중단하지 않는다. 변경 파일을 개별 지정해 커밋하고, 강제 push·reset·관련 없는 변경 삭제는 하지 않는다. push는 필요 없다.

`docs/android-verification.md`에는 다음을 남긴다.

- 선택한 SDK/JDK/AGP/Gradle/Kotlin과 라이브러리 버전, 공식 근거 URL.
- 수행한 명령·성공/실패·테스트 결과·APK의 실제 경로.
- 에뮬레이터/기기 종류와 실제 확인한 시나리오. 자동 테스트와 수동 확인을 구분한다.
- 미수행 항목과 정확한 사유, 사용자가 이어서 할 짧은 절차.

빌드가 환경 때문에 막히면 오류를 해결할 수 있는 범위까지 진행하되, 실제 실패를 통과로 기록하지 않는다. 네트워크 승인이 필요하면 작업 목적을 명시해 정해진 escalation을 사용한다. 개인 토큰은 사용자가 폰에서 입력하게 한다.

## 계획·대시보드 기록 (최종 답변 전 필수)

기존 `PLAN.md`의 5개 구현/검증 항목을 근거에 맞게 체크한다. 계획 구체화 항목은 이미 완료이므로 그대로 둔다. S25 표시와 상호 반영은 사용자가 실제 확인하거나 대시보드 '사용자 확인'에 체크한 뒤에만 완료로 처리한다. 오프라인·자정도 검증 근거가 필요하다. 수동 확인이 남아 있으면 `docs/work-plan.md`의 단계 4는 '구현 완료·사용자 확인 대기' 등 사실에 맞게 적고 ✅ 전체 완료로 바꾸지 않는다.

대시보드는 사용자가 요청한 프로젝트 운영 절차다. 기록 직전에 현재 값을 반드시 다시 읽고, 다음 과제를 통째로 교체하지 않는다.

```powershell
python "$HOME/.dashboard/dash_update.py" show
python "$HOME/.dashboard/dash_update.py" set --tool codex --progress <완료수/전체수×100 반올림> --current "실제 단계" --next-done "실제로 끝낸 기존 과제 문구 그대로" --next-add "다음에 할 구체적인 과제" --summary "구현·검증·미확인 요약"
```

- 두 명령 모두 처음부터 sandbox 밖 실행 승인을 요청한다(`sandbox_permissions=require_escalated`). `--id`는 생략한다.
- `--next-done`/`--next-add`는 필요 개수만큼 반복한다. `show`에 있던 다른 세션 과제는 유지한다. `--next` 전체 교체는 사용하지 않는다.
- '사용자 확인'은 실제 결과를 확인해 완료면 같은 문구로 `--next-done`, 부족하면 같은 문구 `--next-add`로 다시 넣고 사유를 요약한다. PLAN에도 일치하게 반영한다.
- 진행률은 최상위 `PLAN.md` 기준이며 범위 확대 없이는 낮추지 않는다. 새 범위로 낮추어야 하면 `--scope-grew`와 이유를 기록한다.
- 스크립트 실패는 한 줄 알리고 본 구현 작업을 실패로 처리하지 않는다. 네트워크/토큰 오류로 반복 시도하지 않는다.

## 사용자에게 마무리할 내용

한국어로 구현 결과, 실제 빌드·테스트 결과, APK와 설치 안내·검증 기록 링크, 남은 S25 확인만 간결하게 전달한다. 앱에 토큰이 입력되거나 실제 폰 검증이 된 것처럼 추정하지 않는다. 구현 가능 범위는 계획 승인 질문 없이 끝까지 수행한다.

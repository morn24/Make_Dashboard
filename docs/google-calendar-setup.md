# Google 캘린더 · 할 일 · 알림 연결하기 (약 15분, 한 번만)

내 Google 계정에서 작은 프로그램(Google Apps Script)이 돌면서 세 가지를 합니다.

```
Google 캘린더 "일정" ──(10분마다, 읽기만)──▶ data/calendar.json ──▶ 대시보드 "오늘 일정"
Google 캘린더 "할 일" ◀──(3분마다, 양방향)──▶ data/todos.json   ──▶ 대시보드 "오늘 할 일"
알림 시각이 되면 ──(1분마다 확인)──▶ 폰 대시보드 앱에 푸시 알림
```

- **오늘 일정**에는 Google 캘린더의 **일정만** 나옵니다. Google **할 일**은 **오늘 할 일**에만 나옵니다 (중복 없음).
- **할 일 연동**: 한쪽에서 만들거나 지우면 다른 쪽도 따라갑니다. 완료 체크, 이름, 날짜도 맞춥니다.
  - **매일·요일 반복 할 일은 Google에 올리지 않습니다** (캘린더가 번잡해지지 않게).
  - Google 할 일은 시간을 저장하지 못해서, 시간이 있으면 제목 앞에 `15:00 병원`처럼 붙입니다. Google에서 이렇게 적어도 대시보드가 시간으로 읽습니다.
  - Google의 기본 목록(**내 할 일**)만 연동합니다. 처음 연결하면 Google의 **안 끝난 할 일**을 모두 가져옵니다.
  - 대시보드에서 "지난 완료 항목 정리"로 지운 **완료된** 할 일은 Google에서 지우지 않습니다 (기록 보존).
- **알림** (Google이 아니라 대시보드 앱이 보냅니다. 두 번 울리지 않게)
  - 시간이 있는 할 일: 시작 **10분 전**
  - 매일 **오후 5시**: "오늘 아직 못 한 일"과 "내일 할 일"을 **각각 묶어서** 하나씩
- 반영 시간: 할 일은 최대 3분, 일정은 최대 10분 (폰 웹앱은 1분마다 새로고침)

## 이미 캘린더 연결을 해 두었다면 (업데이트)

1. https://script.google.com → 기존 프로젝트 열기
2. `Code.gs` 전체를 이 저장소의 [`integrations/google-calendar/Code.gs`](../integrations/google-calendar/Code.gs)로 바꾸기 → 💾 저장
3. `appsscript.json` 전체를 [`integrations/google-calendar/appsscript.json`](../integrations/google-calendar/appsscript.json)으로 바꾸기 → 💾 저장
   (Google 할 일 서비스와 권한이 추가됩니다)
4. 함수 선택 칸에서 **`setup`** → ▶ 실행 → 새 권한(**Google 할 일 보기·수정**) 허용
5. 실행 로그에 `설치 완료: 1분마다 알림 확인 …` 이 보이면 성공
6. 아래 [4) 폰에서 알림 켜기](#4-폰에서-알림-켜기)

## 처음 설치한다면

### 1) 전용 토큰 만들기 (권장)

폰에 넣은 토큰을 그대로 써도 되지만, 따로 만들면 문제가 생겼을 때 이것만 끊을 수 있습니다.

1. GitHub → Settings → Developer settings → Personal access tokens → **Fine-grained tokens** → Generate new token
2. 이름: `calendar-sync`, 만료일: 1년
3. Repository access: **Only select repositories** → `dashboard-data`
4. Repository permissions → **Contents: Read and write**
5. 생성된 `github_pat_...` 복사

### 2) Apps Script 프로젝트 만들기

1. https://script.google.com 접속 (캘린더를 쓰는 Google 계정으로 로그인)
2. **새 프로젝트** → 왼쪽 위 "제목 없는 프로젝트"를 눌러 이름을 `Dashboard Sync`로 바꾸기
3. 기본으로 있는 `Code.gs` 내용을 **전부 지우고**, 이 저장소의
   [`integrations/google-calendar/Code.gs`](../integrations/google-calendar/Code.gs) 내용을 **전부 복사해서 붙여넣기** → 💾 저장
4. 왼쪽 ⚙ **프로젝트 설정**
   - "**appsscript.json 매니페스트 파일을 편집기에 표시**" 체크
   - 시간대: **(GMT+09:00) 서울**
5. ⚙ 프로젝트 설정 맨 아래 **스크립트 속성** → 속성 추가
   - 속성: `GITHUB_TOKEN`
   - 값: 1)에서 만든 토큰
   - **스크립트 속성 저장**
6. 왼쪽 `<>` 편집기로 돌아가서 `appsscript.json` 내용을 이 저장소의
   [`integrations/google-calendar/appsscript.json`](../integrations/google-calendar/appsscript.json) 으로 바꾸기 → 💾 저장
   (캘린더는 "읽기 전용", 할 일은 "보기·수정"으로 권한을 제한하고 Google 할 일 서비스를 켜는 설정입니다)

### 3) 설치 실행

1. 편집기 위쪽 함수 선택 칸에서 **`setup`** 선택 → **▶ 실행**
2. "승인 필요" 창 → **권한 검토** → 내 계정 선택
3. "Google에서 확인하지 않은 앱" 화면이 나오면 **고급 → Dashboard Sync(으)로 이동(안전하지 않음)**
   - 내가 직접 만든 스크립트라서 Google 검수를 받지 않았다는 뜻입니다. 이 코드는 캘린더를 **읽기만** 하고, 할 일은 **기본 목록만** 고치며, GitHub `dashboard-data`에만 씁니다.
4. 권한(캘린더 보기, 할 일 보기·수정, 외부 서비스 연결, 트리거 관리) **허용**
5. 아래 실행 로그에 `설치 완료: 1분마다 알림 확인 …` 이 보이면 성공

확인: GitHub `dashboard-data` 저장소에 `data/calendar.json`, `data/push.json`이 생기고, 폰 웹앱을 새로고침하면 "오늘 일정"과 Google 할 일이 나옵니다.

## 4) 폰에서 알림 켜기

1. 폰에서 **홈 화면에 설치한 대시보드 앱**을 엽니다 (크롬 탭이 아니라 앱으로)
2. ⚙ 설정 → 아래 **알림** → **🔔 알림 켜기** → 알림 허용
3. **테스트 알림**을 눌러 알림이 오는지 확인. 상단바에는 **흰 테두리 안의 체크** 아이콘이 보입니다
4. (선택) PC Apps Script에서 함수 `testPushNow` 실행 → 폰에 "새 알림이 없어요" 알림이 오면 서버 → 폰 연결까지 정상

갤럭시에서 알림이 늦거나 안 오면: 설정 → 애플리케이션 → **Chrome** → 배터리 → **제한 없음**.

## 설정 바꾸기

`Code.gs` 맨 위 `CONFIG`를 고치고 저장한 뒤 `setup`을 한 번 더 실행합니다.

| 항목 | 뜻 |
|---|---|
| `excludeCalendars` | 빼고 싶은 캘린더 이름. 예: `['대한민국의 휴일', '생일']` |
| `skipDeclined` | 거절한 초대 일정 빼기 (기본 `true`) |
| `daysAhead` | 저장할 기간 (기본 7일) |
| `calendarEveryMinutes` / `tasksEveryMinutes` | 일정 / 할 일 동기화 주기 (기본 10분 / 3분) |
| `taskList` | 연동할 Google 할 일 목록 id (기본 `@default` = 내 할 일) |
| `alertBeforeMinutes` | 시간 있는 할 일 알림 (기본 10분 전). 폰 쪽 `web/rules.js`의 `ALERT_BEFORE_MIN`도 같이 바꿉니다 |
| `summaryHour` | 묶음 알림 시각 (기본 17시). `web/rules.js`의 `SUMMARY_HOUR`도 같이 바꿉니다 |

Google 캘린더 앱에서 **숨긴 캘린더**는 자동으로 빠집니다.

## 문제 해결

| 증상 | 해결 |
|---|---|
| 실행 로그에 `GITHUB_TOKEN이 없습니다` | 2)의 5번 스크립트 속성을 다시 확인 |
| `GitHub 읽기 실패 401` | 토큰 오타이거나 만료. 새로 만들어 스크립트 속성 값만 교체 |
| `GitHub 읽기 실패 403/404` | 토큰의 저장소가 `dashboard-data`인지, Contents 권한이 Read and write인지 확인 |
| `Tasks is not defined` | `appsscript.json`을 바꾸지 않았거나 저장 안 함. 2)의 6번 다시 |
| 폰 앱 알림 설정에 "알림 키가 없어요" | Apps Script에서 `setup`을 한 번 실행 |
| 알림 서명 문제 의심 | 함수 `testPushCrypto` 실행 → 로그에 `서명 확인 OK` |
| 일정·할 일이 늦게 반영됨 | 할 일 최대 3분, 일정 최대 10분 + 웹앱은 1분마다 새로고침 |
| 동기화를 멈추고 싶음 | 함수 선택 칸에서 `uninstall` 실행 |
| 실행 기록 보기 | 왼쪽 ⏰ **트리거** 또는 ☰ **실행** 메뉴 |

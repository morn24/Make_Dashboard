# Google 캘린더 연결하기 (약 10분, 한 번만)

내 Google 계정에서 작은 프로그램(Google Apps Script)이 **10분마다** 캘린더를 읽어서
대시보드 데이터 저장소(`dashboard-data/data/calendar.json`)에 복사합니다.
웹앱과 위젯은 이 파일만 읽으면 되므로, 폰에서 Google 로그인을 따로 할 필요가 없습니다.

```
Google 캘린더 ──(10분마다, Apps Script)──▶ dashboard-data/data/calendar.json ──▶ 웹앱 · 위젯
```

- 일정이 **바뀌었을 때만** 저장합니다 (변경이 없으면 기록 안 함).
- 오늘부터 7일 치를 저장합니다. 화면에는 **오늘 일정**과 **내일 일정 요약**이 나옵니다.
- 읽기 전용입니다. 대시보드에서 캘린더 일정을 만들거나 고치지는 않습니다.
- PC에서 할 일이 아니라 **브라우저에서** 합니다. PC 브라우저를 권장합니다.

## 1) 캘린더 전용 토큰 만들기 (권장)

폰에 넣은 토큰을 그대로 써도 되지만, 따로 만들면 문제가 생겼을 때 이것만 끊을 수 있습니다.

1. GitHub → Settings → Developer settings → Personal access tokens → **Fine-grained tokens** → Generate new token
2. 이름: `calendar-sync`, 만료일: 1년
3. Repository access: **Only select repositories** → `dashboard-data`
4. Repository permissions → **Contents: Read and write**
5. 생성된 `github_pat_...` 복사

## 2) Apps Script 프로젝트 만들기

1. https://script.google.com 접속 (캘린더를 쓰는 Google 계정으로 로그인)
2. **새 프로젝트** → 왼쪽 위 "제목 없는 프로젝트"를 눌러 이름을 `Dashboard Calendar Sync`로 바꾸기
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
   (캘린더 권한을 "읽기 전용"으로 제한하는 설정입니다)
7. ⚙ 프로젝트 설정의 Google Cloud 프로젝트 링크를 열고 **Google Calendar API**를 사용 설정합니다. 이 스크립트는 Advanced Google Services 대신 `UrlFetchApp`으로 Calendar API의 읽기 전용 REST 경로를 호출합니다. 매니페스트에는 Calendar 읽기와 외부 요청 범위가 선언되어 있습니다.

## 3) 설치 실행

1. 편집기 위쪽 함수 선택 칸에서 **`setup`** 선택 → **▶ 실행**
2. "승인 필요" 창 → **권한 검토** → 내 계정 선택
3. "Google에서 확인하지 않은 앱" 화면이 나오면 **고급 → Dashboard Calendar Sync(으)로 이동(안전하지 않음)**
   - 내가 직접 만든 스크립트라서 Google 검수를 받지 않았다는 뜻입니다. 이 코드는 캘린더를 **읽기만** 하고 GitHub `dashboard-data`에만 씁니다.
4. 권한(캘린더 보기, 외부 서비스 연결, 트리거 관리) **허용**
5. 아래 실행 로그에 `설치 완료: 10분마다 동기화합니다.` 가 보이면 성공

확인: GitHub `dashboard-data` 저장소에 `data/calendar.json`이 생겼는지 보고, 폰 웹앱을 새로고침하면 "오늘 일정" 카드에 일정이 나옵니다.

## 설정 바꾸기

`Code.gs` 맨 위 `CONFIG`를 고치고 저장하면 됩니다.

| 항목 | 뜻 |
|---|---|
| `excludeCalendars` | 빼고 싶은 캘린더 이름. 예: `['대한민국의 휴일', '생일']` |
| `skipDeclined` | 거절한 초대 일정 빼기 (기본 `true`) |
| `daysAhead` | 저장할 기간 (기본 7일) |
| `intervalMinutes` | 확인 주기. 1, 5, 10, 15, 30 중 하나. 바꾼 뒤에는 `setup`을 한 번 더 실행 |

Google 캘린더 앱에서 **숨긴 캘린더**는 자동으로 빠집니다.

## 일정별 색상 동기화

레이아웃·색상 개선판의 [`Code.gs`](../integrations/google-calendar/Code.gs)는 일정의 기존 JSON `color` 필드에 Google에서 읽은 HEX 색상을 저장합니다. 색상 우선순위는 일정 라벨 색, 개별 일정의 색, 레거시 CalendarApp 색 ID의 Google 색상표 HEX, 캘린더 기본 색, 중립 회색 순입니다. 캘린더 API 호출은 `GET`만 사용하며 Google 캘린더를 수정하지 않습니다. GitHub의 `calendar.json`은 색을 포함한 일정 데이터가 바뀐 경우에만 갱신합니다.

기존 Apps Script 프로젝트를 업데이트하려면 이 저장소의 새 `Code.gs` 전체를 붙여 넣어 저장하고, 연결된 Google Cloud 프로젝트에서 Google Calendar API가 켜져 있는지 확인합니다. 새 권한 동의가 표시되면 캘린더 읽기와 외부 요청만 허용합니다. 색상 캐시를 무시해 즉시 다시 확인하려면 함수 선택 칸에서 **`refreshCalendarColors`**를 실행합니다. 이후 10분 트리거는 `syncCalendar`를 계속 실행하며, 색상표와 라벨은 캐시 만료 뒤 다시 읽힙니다.

| 증상 | 해결 |
|---|---|
| `Calendar API returned 403` 또는 `accessNotConfigured` | Apps Script 프로젝트 설정에서 연결된 Google Cloud 프로젝트를 열어 Google Calendar API를 사용 설정하고 `refreshCalendarColors`를 다시 실행 |
| 권한 오류 또는 401 | `appsscript.json`의 `calendar.readonly`, `script.external_request` 범위를 확인하고 함수 실행 시 권한을 다시 승인 |
| Google 일정 색을 바꿨지만 대시보드에 옛 색이 남음 | `refreshCalendarColors`를 직접 실행해 색상표·라벨 캐시를 건너뛴 다음 웹앱/위젯을 새로고침 |
| 색상 메타데이터 요청 실패 | 일정 목록은 유지되고 캐시 또는 캘린더 기본 색/중립색으로 표시됩니다. 실행 로그에는 개인정보와 토큰 없이 실패 종류만 남습니다. |

## 문제 해결

| 증상 | 해결 |
|---|---|
| 실행 로그에 `GITHUB_TOKEN이 없습니다` | 2)의 5번 스크립트 속성을 다시 확인 |
| `GitHub 읽기 실패 401` | 토큰 오타이거나 만료. 새로 만들어 스크립트 속성 값만 교체 |
| `GitHub 읽기 실패 403/404` | 토큰의 저장소가 `dashboard-data`인지, Contents 권한이 Read and write인지 확인 |
| 일정이 늦게 반영됨 | 최대 10분 걸립니다 + 웹앱은 1분마다 새로고침 |
| 동기화를 멈추고 싶음 | 함수 선택 칸에서 `uninstall` 실행 |
| 실행 기록 보기 | 왼쪽 ⏰ **트리거** 또는 ☰ **실행** 메뉴 |

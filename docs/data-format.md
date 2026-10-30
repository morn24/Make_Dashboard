# 데이터 형식 (공통 약속)

웹앱, PC 업데이트 스크립트, 안드로이드 위젯이 **모두 같은 파일을 읽고 씁니다.**
이 문서가 세 프로그램 사이의 "약속"이므로, 형식을 바꿀 때는 이 문서부터 고칩니다.

## 저장 위치

- 저장소: `morn24/dashboard-data` (비공개, private)
- 브랜치: `main`
- 파일 3개
  - `data/todos.json`: 할 일 목록 (주로 **폰**에서 수정)
  - `data/projects.json`: 프로젝트 진행 상황 (주로 **PC**의 Codex/Claude Code가 수정. 폰에서는 상태 변경과 삭제만)

- `data/calendar.json`: Google 캘린더 일정 (**Google Apps Script만** 씀. 웹앱과 위젯은 읽기만)

파일을 나눈 이유는 폰과 PC가 동시에 같은 파일을 고쳐 충돌하는 일을 줄이기 위해서입니다.

## 읽고 쓰는 방법 (GitHub Contents API)

```
읽기: GET https://api.github.com/repos/{owner}/{repo}/contents/{path}?ref=main
쓰기: PUT https://api.github.com/repos/{owner}/{repo}/contents/{path}
      body: { "message": "...", "content": "<UTF-8 → base64>", "sha": "<읽을 때 받은 sha>", "branch": "main" }
헤더: Authorization: Bearer <토큰>, Accept: application/vnd.github+json
```

- 쓸 때는 반드시 **방금 읽은 `sha`** 를 같이 보냅니다. 그 사이 누가 파일을 바꿨다면 GitHub가 `409`(또는 `422`)를 돌려줍니다.
- 이때는 **다시 읽고 → 내 변경을 다시 적용하고 → 다시 쓰기**를 최대 3~4번 반복합니다. (덮어쓰기 금지)
- JSON은 들여쓰기 2칸, UTF-8, 파일 끝 줄바꿈 1개로 저장합니다.

## 날짜 규칙

- 시각(`updatedAt`, `createdAt`): ISO 8601 문자열, 예 `2026-10-29T21:30:00+09:00`
- 날짜(`doneOn`): **기기 현지 날짜** `YYYY-MM-DD` (한국이면 KST 기준)

## `data/todos.json`

```json
{
  "version": 1,
  "updatedAt": "2026-10-29T09:00:00+09:00",
  "items": [
    { "id": "t_lq2x9k3a", "title": "아침 운동 30분", "repeat": "daily", "doneOn": "2026-10-29", "createdAt": "..." },
    { "id": "t_lq2xa01b", "title": "보고서 제출",   "repeat": "none",  "doneOn": null,         "createdAt": "..." }
  ]
}
```

| 필드 | 뜻 |
|---|---|
| `id` | 고유값. 새로 만들 때 `t_` + 시간(36진수) + 랜덤 |
| `title` | 할 일 내용 |
| `repeat` | `"daily"` = 매일 반복, `"none"` = 한 번만 |
| `doneOn` | 완료한 날짜. 안 했으면 `null` |
| `createdAt` | 만든 시각 |

**"완료됨" 판단 규칙 (가장 중요)**

- 매일 반복(`daily`): `doneOn == 오늘` 이면 완료. 날짜가 바뀌면 **자동으로 미완료**가 됩니다. (초기화 작업 필요 없음)
- 한 번만(`none`): `doneOn != null` 이면 완료.
- 화면 표시: 한 번만 할 일 중 **오늘 이전에 끝낸 것은 숨깁니다.** (웹앱의 "지난 완료 정리" 버튼으로 삭제)
- 오늘 진행률 = 화면에 보이는 항목 중 완료 개수 / 전체 개수

## `data/projects.json`

```json
{
  "version": 1,
  "updatedAt": "2026-10-29T09:00:00+09:00",
  "projects": [
    {
      "id": "make-dashboard",
      "name": "Make_Dashboard",
      "tool": "claude-code",
      "status": "active",
      "progress": 50,
      "current": "지금 하고 있는 일 한 줄",
      "next": ["다음 과제 1", "다음 과제 2"],
      "summary": "마지막 작업에서 한 일 한 줄",
      "updatedAt": "2026-10-29T09:00:00+09:00"
    }
  ]
}
```

| 필드 | 뜻 |
|---|---|
| `id` | 프로젝트 고유값. 보통 폴더/저장소 이름을 소문자-하이픈으로 |
| `name` | 화면에 보일 이름 |
| `tool` | `"claude-code"`, `"codex"`, `"other"` |
| `status` | `"active"`(진행 중), `"paused"`(홀드), `"done"`(완료) |
| `progress` | 0~100 정수 (%) |
| `current` | 지금 하는 일 |
| `next` | 다음 과제 목록. **첫 번째가 바로 다음 할 일** |
| `summary` | 최근 작업 요약 (선택) |
| `updatedAt` | 이 프로젝트를 마지막으로 갱신한 시각 |

정렬: `active` → `paused` → `done`, 같은 상태끼리는 `updatedAt` 최신순.

**상태 규칙**

- PC에서 작업 기록(`set`)을 하면 상태를 따로 지정하지 않는 한 `active`가 된다. (홀드였던 프로젝트도 다시 작업하면 자동으로 진행 중)
- 홀드, 완료, 삭제는 사용자가 정한다: 폰 웹앱 카드의 `⋯` 메뉴, 또는 PC에서 `set --status paused|done`, `remove`.
- 웹앱 표시: 홀드(`paused`)가 **2개 이상**이면 "홀드 n개" 묶음으로 접고, 완료(`done`)는 항상 "완료 n개" 묶음으로 접는다. 위젯은 `active`만 보여준다.
- 폰에서 상태를 바꿀 때는 `status`만 바꾸고 프로젝트의 `updatedAt`은 그대로 둔다. (`updatedAt` = 마지막 작업 시각)

## `data/calendar.json`

`integrations/google-calendar/Code.gs`(Google Apps Script)가 10분마다 만들고, 일정이 바뀌었을 때만 저장한다.
설치 방법은 [`docs/google-calendar-setup.md`](google-calendar-setup.md). **웹앱과 위젯은 읽기만 한다.**
파일이 없으면(404) "캘린더 연결 안 됨"으로 보고 연결 안내를 보여준다.

```json
{
  "version": 1,
  "updatedAt": "2026-10-30T08:30:00+09:00",
  "timeZone": "Asia/Seoul",
  "rangeStart": "2026-10-30",
  "rangeEnd": "2026-11-06",
  "events": [
    { "id": "abc@google.com@1761786000000", "calendar": "내 캘린더", "color": "#4986e7",
      "title": "팀 회의", "allDay": false,
      "start": "2026-10-30T10:00:00+09:00", "end": "2026-10-30T11:00:00+09:00", "location": "회의실 A" },
    { "id": "...", "calendar": "대한민국의 휴일", "color": "#16a765",
      "title": "종일 일정", "allDay": true, "start": "2026-10-30", "end": "2026-10-31" }
  ]
}
```

| 필드 | 뜻 |
|---|---|
| `rangeStart` / `rangeEnd` | 저장된 기간. `rangeEnd`는 **미포함** (기본 7일 치) |
| `id` | 일정 id + `@` + 시작 시각(ms). 반복 일정도 회차마다 다름 |
| `calendar`, `color` | 캘린더 이름과 색 |
| `allDay` | 종일 일정 여부 |
| `start`, `end` | 종일이면 `YYYY-MM-DD` (`end`는 다음 날, **미포함**), 아니면 시간대 포함 ISO 8601 |
| `location` | 장소 (있을 때만) |

**"오늘 일정" 판단 규칙**

- 종일: `start <= 오늘 < end`
- 시간 일정: 오늘 0시~24시와 겹치면 오늘 일정 (`start < 내일 0시` 그리고 `end > 오늘 0시`). 어제 밤에 시작해 오늘 새벽에 끝나는 일정도 포함
- 정렬: 종일 먼저, 그다음 시작 시각순
- 표시: 이미 끝난 일정은 흐리게, 지금 진행 중인 일정은 강조

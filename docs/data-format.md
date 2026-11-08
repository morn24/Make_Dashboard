# 데이터 형식 (공통 약속)

웹앱, PC 업데이트 스크립트, 안드로이드 위젯이 **모두 같은 파일을 읽고 씁니다.** (위젯은 `todos.json`을 쓰고, `projects.json`은 다음 과제 `checked`만 쓰며, `calendar.json`·`pmr-wiki.json`은 읽기만 합니다.)
이 문서가 세 프로그램 사이의 "약속"이므로, 형식을 바꿀 때는 이 문서부터 고칩니다.

## 저장 위치

- 저장소: `morn24/dashboard-data` (비공개, private)
- 브랜치: `main`
- 파일 3개
  - `data/todos.json`: 할 일 목록 (주로 **폰**에서 수정)
  - `data/projects.json`: 프로젝트 진행 상황 (주로 **PC**의 Codex/Claude Code가 수정. 폰에서는 상태 변경과 삭제만)

- `data/calendar.json`: Google 캘린더 **일정** (**Google Apps Script만** 씀. 웹앱과 위젯은 읽기만)
  - `data/push.json`: 폰 알림(Web Push) 구독 정보 (Apps Script가 공개키를, 폰이 구독을 씀)
  - `data/pmr-wiki.json`: PMR-Wiki 정기 작업·학습 후보 상태 (**PC의 PMR-Wiki `tools/dash_sync.py`만** 씀. 웹앱과 안드로이드 위젯은 읽기만)
  - `data/pmr-commands.json`: PMR-Wiki 탭에서 누른 버튼 명령 (폰이 추가, PC가 실행 후 결과 기록)
  - `data/pmr-alerts.json`: PMR-Wiki 알림 기록 (**Apps Script만** 씀. 웹앱 알림 기록 창이 읽음)

`data/todos.json`은 Apps Script도 씁니다 (Google 할 일 양방향 동기화). Google **할 일**은 `todos.json`에만 들어가고 `calendar.json`에는 들어가지 않습니다.

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
    { "id": "t_lq2x9k3a", "title": "아침 운동 30분", "repeat": "daily", "doneOn": "2026-10-29",
      "history": ["2026-10-27", "2026-10-28", "2026-10-29"], "createdAt": "..." },
    { "id": "t_lq2xa01c", "title": "영어 단어",     "repeat": "weekly", "days": [1, 3, 5], "doneOn": null, "createdAt": "..." },
    { "id": "t_lq2xa01b", "title": "보고서 제출",   "repeat": "none",  "date": "2026-10-31", "doneOn": null, "createdAt": "..." }
  ]
}
```

| 필드 | 뜻 |
|---|---|
| `id` | 고유값. 새로 만들 때 `t_` + 시간(36진수) + 랜덤 |
| `title` | 할 일 내용 |
| `repeat` | `"daily"` = 매일, `"weekly"` = `days`의 요일만, `"none"` = 한 번만 |
| `days` | (`weekly`일 때) 반복 요일. `0`=일요일 … `6`=토요일. 평일은 `[1,2,3,4,5]` |
| `date` | (선택, `none`일 때) 할 날짜 `YYYY-MM-DD`. 그날부터 보이고, 지나면 "n일 지남" 표시. 없으면 바로 보임 |
| `doneOn` | 마지막으로 완료한 날짜. 안 했으면 `null` |
| `history` | (선택, 반복 할 일) 완료한 날짜 목록, 최근 90일. 연속 기록(🔥)을 세는 데 쓴다 |
| `time` | (선택) 시작 시간 `HH:MM`. 있으면 시작 10분 전에 알림 |
| `gtask` | (Apps Script가 씀) 연결된 Google 할 일 id. 웹앱은 건드리지 않는다 |
| `gsync` | (Apps Script가 씀) 마지막으로 양쪽을 맞춘 상태 `[제목, 날짜, 시간, 완료]`. 어느 쪽이 바뀌었는지 비교하는 데 쓴다 |
| `createdAt` | 만든 시각 |

**"완료됨" 판단 규칙 (가장 중요)**

- 반복(`daily`, `weekly`): `doneOn == 오늘` 이면 완료. 날짜가 바뀌면 **자동으로 미완료**가 됩니다. (초기화 작업 필요 없음)
- 한 번만(`none`): `doneOn != null` 이면 완료.
- 화면 표시
  - `weekly`는 `days`에 오늘 요일이 있을 때만 보입니다 (오늘 완료한 것은 계속 보임).
  - `none`에 `date`가 오늘보다 뒤면 오늘 목록에서 숨기고 **"예정 n개"** 에 보여줍니다. "내일로 미루기" = `date`를 내일로 바꾸기.
  - 한 번만 할 일 중 **오늘 이전에 끝낸 것은 숨깁니다.** (웹앱의 "지난 완료 정리" 버튼으로 삭제)
- 안드로이드 위젯도 웹앱 `web/rules.js`와 같은 규칙으로 표시하고 씁니다. 위젯이 쓰는 모든 변경은 읽은 최신 파일에 그 항목의 필요한 키만 바꾸고(`gtask`·`gsync`·`time` 등 모르는 키는 그대로), 최상위 `updatedAt`을 갱신하며, sha 충돌(409/422)이면 다시 읽고 다시 적용합니다. Kotlin과 JavaScript가 같은 답을 내는지는 `scripts/gen_rule_vectors.js`가 만든 벡터로 검증합니다.
- 완료/취소할 때 반복 할 일은 `history`에 오늘을 넣거나 뺍니다. 취소하면 `doneOn`은 `history`의 이전 날짜로 돌아갑니다.
- 연속 기록 = 오늘(아직 안 했으면 어제)부터 거꾸로, **해야 했던 날**(`weekly`면 그 요일만)마다 `history`에 있는지 세다가 처음 빠진 날에서 멈춘 수. 2 이상이면 🔥n 표시.
- 이 필드들을 모르는 프로그램(예: PC의 `todo-add`)은 다른 항목의 필드를 지우지 말고 그대로 둡니다.

**입력 문장에서 날짜·시간 읽기 (웹앱)**

- "내일 오후 3시 병원" → 제목 `병원`, `date` 내일, `time` `15:00`. 읽은 날짜·시간 글자는 제목에서 뺍니다.
- 날짜: 오늘/내일/모레/글피, (이번주/다음주) ○요일, `11/7`, `11월 7일`. 시간: `15:30`, (오전/오후/저녁…) `3시`, `3시 반`, `3시 20분`, 정오.
- 오전/오후가 없으면 1~6시는 오후, 7~12시는 그대로 봅니다. 시간만 있으면 날짜는 오늘.
- 한 번 할 일은 날짜를 안 적어도 **오늘 날짜**를 넣습니다 (Google 캘린더 할 일에 그날로 보이게).

**Google 할 일 동기화 규칙 (Apps Script, 3분마다)**

- 연동 대상: `repeat: "none"`인 할 일만. 매일·요일 반복은 올리지 않고, 반복으로 바꾸면 Google에서 지웁니다.
- 대응: `title` ↔ 제목 (`time`이 있으면 Google 제목 앞에 `"15:00 "`), `date` ↔ 마감일(`due`), `doneOn` ↔ 완료.
- 양쪽을 `gsync`와 비교해서 바뀐 쪽을 반대쪽에 반영합니다. 둘 다 바뀌었으면 대시보드가 이깁니다.
- 삭제: 대시보드에서 지우면 Google에서도 지웁니다 (단, **이미 완료한** 할 일은 Google 기록으로 남김). Google에서 지우면 대시보드에서도 지웁니다.
- Google에서 새로 만든 할 일은 대시보드 맨 뒤에 추가합니다. 오늘 이전에 완료된 Google 할 일은 가져오지 않습니다.
- 날짜가 없는 **안 끝난 한 번 할 일**은 (어느 쪽에서 만들었든) 동기화 때 `date`를 오늘로 넣고 Google 마감일도 오늘로 맞춥니다. Google 캘린더는 마감일이 있어야 날짜 칸에 보여주기 때문입니다.

**알림 규칙 (웹앱 `web/rules.js`와 Apps Script가 같은 규칙)**

- `time`이 있는 할 일: 그날 해야 하고 아직 안 끝났으면 시작 10분 전.
- 매일 17시: "오늘 아직 못 한 일"(오늘 보이는 미완료 전부)과 "내일 할 일"(내일 날짜의 한 번 할 일만)을 **각각 하나의 알림으로 묶어서**. 둘 다 없으면 보내지 않음.
- Apps Script는 때가 되면 **내용 없는 푸시**만 보내고, 폰의 서비스워커가 `todos.json`을 읽어 알림 내용을 만듭니다.

## `data/push.json`

```json
{
  "version": 1,
  "updatedAt": "2026-11-04T10:00:00+09:00",
  "vapidPublicKey": "BEl6...(base64url, 65바이트)",
  "subscriptions": [
    { "endpoint": "https://fcm.googleapis.com/fcm/send/...", "keys": { "p256dh": "...", "auth": "..." },
      "device": "SM-S931N", "createdAt": "2026-11-04T10:00:00+09:00" }
  ]
}
```

- `vapidPublicKey`: Apps Script `setup`이 만든 알림 공개키. 개인키는 Apps Script 스크립트 속성에만 있습니다.
- `subscriptions`: 폰 웹앱이 ⚙ → 알림 켜기에서 추가/삭제. 푸시 서버가 404/410을 주면 Apps Script가 지웁니다.
- 오늘 진행률 = 화면에 보이는 항목 중 완료 개수 / 전체 개수
- **순서**: `items` 배열 순서가 곧 표시 순서다 (웹앱에서 ⠿ 손잡이로 끌어 바꿈). 화면에는 미완료를 먼저, 완료를 나중에 보여주되 각각 배열 순서를 지킨다. 새 할 일은 배열 **맨 뒤**에 추가한다.

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
| `tool` | 마지막으로 기록한 AI: `"claude-code"`, `"codex"`, `"antigravity"`, `"gemini"`, `"other"` |
| `status` | `"active"`(진행 중), `"paused"`(홀드), `"done"`(완료) |
| `progress` | 0~100 정수 (%) |
| `current` | 사용자 관점의 전체 단계나 사용 가능한 결과 한 문장 |
| `next` | 사용자에게 의미 있는 다음 결과·행동 목록. **첫 번째가 바로 다음 할 일**. 새 과제는 중요한 1~3개만 선택하며, 없으면 추가하지 않음 |
| `checked` | (선택) 사용자가 폰에서 "확인 완료"로 체크한 다음 과제: `[{ "text": "...", "at": "ISO 시각" }]` |
| `summary` | 최근 작업으로 사용자에게 달라진 점과 중요한 미해결 사항 한 문장 (선택) |
| `updatedAt` | 이 프로젝트를 마지막으로 갱신한 시각 |

정렬: `active` → `paused` → `done`, **같은 상태끼리는 `projects` 배열 순서** (웹앱에서 ⠿ 손잡이로 끌어 바꿈).

- 새 프로젝트는 배열 **맨 앞**에 넣는다 (새로 시작한 프로젝트가 위에 보이도록).
- 기존 프로젝트를 갱신할 때는 **배열 안 위치를 바꾸지 않는다** (사용자가 정한 순서 유지).
- 순서 변경은 "몇 번째로"가 아니라 "어떤 항목 앞으로"로 적용한다. 그 사이 다른 기기가 항목을 추가해도 결과가 어긋나지 않는다.

**사용자 확인 체크 (`checked`) 규칙**

사용자가 AI에게 "확인했어"라고 말하지 않아도 되도록, 폰에서 다음 과제를 직접 체크할 수 있다.

- 웹앱은 `next`를 **건드리지 않고** `checked`에만 추가하거나 뺀다. (`next`는 PC/AI가 관리)
- 표시: `next` 중 `checked`에 있는 문구는 아래로 내리고 줄을 긋는다. `next`에서 사라진 체크 항목도 `checked`에 남아 있는 동안은 계속 보인다. 목록은 개수 제한 없이 모두 보여주고, 길면 카드 안에서 스크롤한다.
- **AI(다음 작업 시)**: `set` 전에 `show`로 `checked`를 읽고, 체크된 과제는 "사용자가 완료 확인함"으로 본다.
  - 실제로 완료됐으면 `next`에서 빼고 진행률에 반영한다.
  - 확인해 보니 미비하면 `next`에 다시 넣고 `--summary`에 이유를 짧게 남긴다.
  - 처리가 끝난 체크는 `checked`에서 지운다 (`set --clear-checked`).
- 체크를 모두 지우면 `checked` 필드 자체를 없앤다.
- 안드로이드 위젯(4×4 프로젝트의 `다음 ·` 줄)도 같은 규칙으로 `checked`에 `{text, at}`를 넣고 빼며, 다른 필드·다른 프로젝트는 건드리지 않는다.
- PC 스크립트는 `set` 할 때 자신이 모르는 필드를 **지우지 않고 보존**해야 한다.

**붙여넣기 갱신 (Gemini 채팅 등)**

PC에서 명령을 실행할 수 없는 AI(Gemini 웹·앱)는 웹앱의 **＋ 붙여넣기**로 기록한다.

1. 웹앱이 고른 프로젝트의 현재 상태를 담은 요청 문구를 만들고, 사용자가 AI에게 보낸다.
2. AI는 `{ name, progress, current, nextAdd[], nextDone[], summary, scopeGrew? }` JSON 하나로 답한다.
3. 웹앱은 답변에서 JSON을 꺼내 미리보기를 보여주고, PC 스크립트 `set`과 **같은 규칙**으로 적용한다: 준 값만 바꿈, 다음 과제는 합치기(`nextAdd`/`nextDone`), `scopeGrew`가 아니면 진행률을 낮추지 않음, 처리된 과제의 `checked` 정리, 새 프로젝트는 맨 앞, `id`는 이름을 slugify.

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

## `data/pmr-wiki.json` (PMR-Wiki 탭)

PMR-Wiki 보관함의 `tools/dash_sync.py`가 Windows 예약 작업 "PMR-Wiki dash sync"로 **30분마다** 만든다.
내용이 바뀌었을 때, 또는 바뀌지 않았어도 6시간마다 올린다. 그래서 `generatedAt`이 **7시간** 넘게 지났으면 웹앱은 "PC 꺼짐·동기화 멈춤" 경고를 보여준다.
파일이 없으면(404) 웹앱은 설정 안내를 보여준다. **웹앱은 읽기만 한다.**
안드로이드 위젯도 읽기만 한다: `decisions.need`(결정 대기), `jobs[].state == "error"`(실패 작업), `generatedAt`(하루 넘게 없으면 끊김)만 읽어 머리 줄에 `PMR 결정 n` / `PMR 실패 n` / `PMR 끊김` 칩을 보인다. 404나 읽기 실패는 정상(칩 없음)으로 처리하며 위젯의 오류 표시로 바꾸지 않는다. `decisions`는 PR #7(`origin/pmr-decisions`)의 필드다.

```json
{
  "version": 1,
  "generatedAt": "2026-11-06T20:53:03+09:00",
  "jobs": [
    { "id": "dtv-ingest", "name": "DTV 자동 ingest", "schedule": "매일 07:30", "state": "ok",
      "status": "오늘 실행 완료", "last": { "date": "2026-11-06", "text": "log.md 줄 요약" },
      "next": "2026-11-07T07:30:00+09:00", "detail": ["대기 논문 없음"] }
  ],
  "wiki": { "metrics": [{ "label": "위키 노트", "value": "431개 (reviewed 21 · draft 410)" }], "warnings": [] },
  "learning": {
    "nextAuto": "L18", "busy": false, "cooldownDays": 30, "topicsGenerated": "2026-11-06T07:42:23",
    "topics": [
      { "id": "L3", "status": "후보", "rank": 1, "topic": "주제", "trigger": "...", "why": "...", "detail": "...",
        "remaining": "prognostic block 예측 가치의 무작위 근거(McCormick 원문, 볼트에 없음)",
        "cooldownUntil": "2026-12-01",
        "request": { "status": "done", "source": "auto", "requestedAt": "...", "updatedAt": "...", "note": "..." } },
      { "id": "L11", "status": "보류", "topic": "주제", "held": "2026-11-02" }
    ]
  },
  "recent": [{ "date": "2026-11-06", "kind": "ingest (예약)", "text": "..." }],
  "decisions": { "need": 1, "auto": 3, "items": [
    { "id": "patch-20261105-dtv-auto", "kind": "patch", "title": "...", "created": "2026-11-05", "targets": ["노트"],
      "risk": "...", "state": "need", "reason": "덧붙이기가 아님: ...", "recommend": "승인 — ..." }
  ] },
  "reviewDue": { "windowDays": 60, "total": 21, "nearest": "2027-03-29",
    "items": [{ "note": "slug", "title": "...", "due": "2027-03-29", "daysLeft": 45, "reviewed": "2026-09-29" }] },
  "alerts": [{ "id": "job-weekly-lint-error-2026-11-10", "level": "error", "title": "주간 lint: 실패", "body": "..." }]
}
```

| 필드 | 뜻 |
|---|---|
| `jobs[].id` | `dtv-ingest`(매일 07:30), `weekly-lint`(화 19:10), `monthly-audit`(매월 첫 lint), `dashboard`(dashboard.md 갱신), `nightly-learning`(uptodate 야간 자동 학습 02:30) |
| `jobs[].state` | `ok` 정상, `running` 실행 중, `wait` 예정(아직 시각 전), `warn` 확인 필요, `error` 놓침·실패 |
| `jobs[].status` | 한 줄 판정. `last`는 마지막 기록(주로 `log.md`), `next`는 다음 예정 시각, `detail`은 보조 줄 |
| `wiki` | `dashboard.md` "한눈에 보기" 표와 ⚠ 경고 줄 |
| `learning.topics` | `learning.md`의 학습중 → 후보 → 보류 순. `rank`는 표 순서 = 우선순위 |
| `learning.nextAuto` | 다음 야간 자동 학습이 고를 후보 (최근 `cooldownDays`일 안에 요청한 후보는 건너뜀, `cooldownUntil`까지 쉼) |
| `topics[].remaining` | 행 본문의 마지막 `남은 것` 뒤 글(다음 날짜 표기 전까지, 위키 링크는 이름만, 160자). `dtv_bridge.py topics`가 뽑고 `dash_sync.py`가 싣는다. 없으면 필드를 넣지 않는다(이전 형식 데이터도 그대로 그려진다). 앱은 후보 제목 아래에 `남은 것: …`으로 두 줄까지 보인다 |
| `topics[].cooldownUntil` | 야간 자동 학습이 이 후보를 한 번 다룬 뒤 30일간 다시 고르지 않는 날짜. 앱은 `MM-DD부터 자동 재시도` 칩을 달고, 다음 자동 후보가 아니면 접힌 묶음 `AI가 한 번 본 후보 n개 (자동 재시도 대기)`로 옮긴다. `rank`는 표 순서 그대로 |
| `topics[].request` | uptodate 학습 AI 요청(`study/ai_requests.json`)의 그 후보 최신 상태: `pending`·`running`·`done`·`failed`. `done`은 그날 한 번 다뤘다는 뜻이지 공백이 다 채워졌다는 뜻이 아니다(칩: `AI 1차 MM-DD`, 30일 대기 중이면 `· 공백 남음` 덧붙임) |
| `recent` | `log.md` 최근 10건 (최신이 먼저) |
| `decisions.items[].state` | `need` 사람이 정할 것(버튼 표시: 자동 교차검증 불일치·덧붙이기 아닌 패치, 결정 묶음·상충), `auto` 예약 실행이 처리할 것(검증 대기·적용 대기·보류 정리), `info` 참고 자료(체크리스트·lint 보고) |
| `reviewDue` | `review_due`가 `windowDays`일 안인 확정(reviewed·applied) 노트. `daysLeft`가 음수면 지남 |
| `alerts` | 알림 후보. Apps Script가 처음 보는 `id`만 푸시한다(작업 실패는 작업·상태·날짜별 한 번, 결정 대기는 항목별 한 번) |

## `data/pmr-commands.json` (PMR-Wiki 탭 버튼)

```json
{
  "version": 1,
  "updatedAt": "2026-11-06T21:00:00+09:00",
  "items": [
    { "id": "c_lq2x9k3a", "action": "start", "target": "L18", "title": "주제", "status": "pending",
      "requestedAt": "2026-11-06T21:00:00+09:00" },
    { "id": "c_lq2xa01c", "action": "hold", "target": "L23", "title": "주제", "status": "done",
      "requestedAt": "...", "note": "learning.md 보류 표로 옮김", "doneAt": "..." }
  ]
}
```

| 필드 | 뜻 |
|---|---|
| `action` | `decide` = 결정 대기 항목에 `choice`(`approve` 추천대로·`hold` 보류)를 기록(PMR-Wiki `dash_sync.py`, `target`은 `90_review` 파일 이름). `start` = AI에게 맡기기(uptodate `study_ai_requests.py request-wiki`), `hold` = 공부 안 함, `unhold` = 되돌리기 (둘 다 PMR-Wiki `dtv_bridge.py hold/unhold`) |
| `target` | `decide`는 `90_review` 파일 이름(확장자 없이), 나머지는 학습 후보 번호 `L숫자` |
| `status` | 폰이 `pending`으로 추가 → PC가 실행 후 `done`/`failed`와 `note`, `doneAt`을 쓴다 |

- 폰은 같은 `action`+`target`의 `pending`이 이미 있으면 더하지 않는다. 아직 `pending`인 명령만 취소(삭제)할 수 있다.
- PC는 `pending`만 실행하고, 끝난 명령은 7일 지나면 지운다 (최대 50개 유지).

## `data/pmr-alerts.json` (PMR-Wiki 알림 기록)

Apps Script(`checkPmr_`, 10분마다)가 `data/pmr-wiki.json`의 `alerts`와, PC가 꺼져 예약 작업 시각이 90분 넘게 지났는데 동기화가 없는 경우(`pc-off-<generatedAt>`)를 보고 **처음 보는 id만** 여기에 남기고 푸시 신호를 보낸다.
폰의 서비스워커는 신호를 받으면 이 파일에서 그 기기가 아직 띄우지 않은 항목(최근 2일)을 알림으로 띄운다.
웹앱 오른쪽 위 종(알림 기록)은 이 파일과, 그 기기에 실제로 뜬 알림 기록(Cache Storage `dash-private`의 `notif-log`, 최근 100개)을 합쳐 보여 준다.

```json
{
  "version": 1,
  "updatedAt": "2026-11-07T09:10:00+09:00",
  "items": [
    { "at": "2026-11-07T09:10:00+09:00", "id": "pc-off-2026-11-07T05:00:00+09:00", "level": "warn",
      "title": "PC 꺼짐: 예약 작업 놓침", "body": "DTV 자동 ingest — PC 마지막 동기화 11-07 05:00", "source": "watchdog" }
  ]
}
```

- 최신이 먼저, 60일·100개까지. `source`: `pc`(PC가 올린 알림) 또는 `watchdog`(Apps Script가 PC 꺼짐을 감지).

# 수행지시문 07: PMR 탭 '학습 후보' 카드 정리

작성일: 2026-11-08 · **Claude Sonnet / high** · 계획·검토: Claude Opus 세션

## 목적 (사용자 관점)

폰 PMR 탭의 '학습 후보' 카드에서 사용자가 "AI 정리 완료인데 왜 아직 목록에 있지?", "~12-01 쉼은 뭐지?"라고 헷갈렸다. 실제 의미는 이렇다.

- 'AI 정리 완료 MM-DD': 야간 자동 학습이 그날 이 후보를 한 번 다뤘다. 공백이 다 채워진 것은 아니다.
- '~MM-DD 쉼': 30일 동안 자동 학습이 이 후보를 다시 고르지 않는다(`cooldownUntil`).

사용자가 승인한 개선 세 가지를 구현한다.

1. **칩 문구 변경**
   - 30일 대기 중인 완료 요청: `AI 정리 완료 11-06` → `AI 1차 11-06 · 공백 남음`
   - `~12-01 쉼` → `12-01부터 자동 재시도`
   - 대기가 끝난 완료 요청은 `AI 1차 MM-DD`만 표시한다.
   - 대기·진행·실패 칩(`AI 대기`, `AI 진행 중`, `AI 실패 MM-DD`)은 지금 그대로 둔다.
2. **목록 분리**
   - `cooldownUntil`이 있는 학습중·후보는 카드 아래쪽의 접힌 묶음 `AI가 한 번 본 후보 n개 (자동 재시도 대기)`로 옮긴다.
     - 기존 보류 묶음(`learn-held`)과 같은 `<details class="upcoming">` 형식을 쓰고, 보류 묶음보다 위에 둔다.
   - 위쪽 목록에는 대기 중이 아닌 후보만 남긴다. `다음 자동` 후보는 항상 위쪽에 있다.
   - 순번(`rank`)은 learning.md 표 순서 그대로 둔다. 다시 매기지 않는다.
   - 카드 머리의 개수는 `위쪽 n개 · 대기 m개`처럼 나눠 보인다(m이 0이면 `n개`).
   - 펼쳐 볼 수 있는 내용과 버튼('AI에게 맡기기', '공부 안 함')은 두 묶음에서 똑같이 동작한다.
3. **'남은 것' 한 줄**
   - 각 후보 제목 아래에 `남은 것: …`을 한 줄로 보인다. 펼치지 않아도 보이게 하고, 두 줄을 넘으면 말줄임한다.
   - 값이 없으면 줄을 만들지 않는다.

## 데이터 흐름과 바꿀 곳

`PMR-Wiki/learning.md` → `tools/dtv_bridge.py topics` → `tools/learning_topics.json` → `tools/dash_sync.py` → `dashboard-data/data/pmr-wiki.json` → 웹앱 `web/app.js` `renderPmr`·`renderTopic`.

### A. PMR-Wiki (`G:\내 드라이브\Obsidian\PMR-Wiki`, main 브랜치에 직접 커밋)

- `tools/dtv_bridge.py`의 `topics()`: 각 행에 `remaining` 필드를 더한다.
  - 규칙: 행 본문 **전체**(1200자로 자르기 전)에서 **마지막** `남은 것` 표기 뒤의 글을 꺼낸다.
    - `남은 것:`, `남은 것은`, `남은 것은 …뿐` 같은 형태를 모두 처리한다.
    - 끝은 다음 날짜 표기(` 20YY-MM-DD`)나 행 끝이다. 위키 링크 `[[x|y]]`는 `x`로 바꾸고, 160자에서 자른다.
  - `남은 것`이 없는 행은 `remaining`을 비우거나 넣지 않는다. 보류 행은 다루지 않는다.
- `tools/dash_sync.py`의 `learning_block`: `remaining`을 `short(..., 160)`으로 행에 실어 보낸다.
- 확인 방법
  - `python tools/dtv_bridge.py topics`의 결과(또는 파일 생성 방식은 `learning_topics()` 참고)에서 L3·L8·L12·L16~L19의 `remaining`을 눈으로 대조한다. 각 행 본문의 마지막 '남은 것'과 같은지 본다.
  - `python tools/dash_sync.py --help`로 업로드 없이 확인하는 옵션(dry-run·출력 전용)이 있는지 보고, 있으면 그것으로 `learning.topics[].remaining`을 확인한다. 없으면 `build()`를 직접 불러 JSON만 출력한다.
  - **실제 업로드는 하지 않는다.** 30분 예약 작업("PMR-Wiki dash sync")이 다음 회차에 올린다.
- 볼트 규칙
  - `learning.md`, `30_wiki/`, `log.md`의 기존 내용은 수정하지 않는다.
  - log.md에는 `## [2026-11-08] tool | dtv_bridge·dash_sync — 학습 후보 '남은 것' 필드 추가` 형식으로 한 줄만 추가한다.
  - 커밋 메시지는 `tool: 학습 후보 남은 것 필드`이다. 다른 세션이 바꾼 파일(`tools/queue.json` 등)은 커밋에 넣지 않는다. 자기가 바꾼 파일만 `git add`한다.

### B. Make_Dashboard 웹앱

- worktree: `C:/Users/morn/Desktop/Project/Make_Dashboard-learn`, 브랜치 `learning-card`(기준 `origin/main` cc5c883)
- 원래 checkout과 다른 worktree(`Make_Dashboard`, `-pmr`, `-widget`)는 건드리지 않는다.
- 바꿀 곳
  - `web/app.js`: `REQ_LABEL` 사용부, `renderPmr`의 학습 후보 부분, `renderTopic`
  - `web/index.html`: 대기 묶음 마크업
  - `web/style.css`: `남은 것` 줄 스타일. 기존 `.tag`, `.help`의 색과 크기를 재사용하고, 2줄 말줄임을 넣는다.
  - `docs/data-format.md`: `learning.topics[].remaining` 설명과 예시를 더한다.
- `remaining`이 없는 이전 형식의 `pmr-wiki.json`에서도 화면이 깨지지 않아야 한다.
- 서비스 워커·캐시 버전 문자열이 있으면 올려서 폰이 새 파일을 받게 한다.
- Android 위젯 코드는 이번 범위가 아니다.

## 검증

- 실제 `morn24/dashboard-data`와 사용자 토큰으로 읽기·쓰기를 하지 않는다. 폰과 실기기도 건드리지 않는다.
- 로컬 정적 서버와 가짜 데이터로 확인한다.
  - 예: `fetch`를 가로채는 디버그 경로나 테스트용 HTML. 이 저장소에 이미 있는 방식을 먼저 찾아서 쓴다.
  - 가짜 데이터는 익명 주제로 만든다. 학습 주제 이름은 의학 용어라 괜찮다.
- 다음 경우를 캡처해 `docs/learning-card-screenshots/`에 둔다.
  1. 대기 후보 3개 + 위쪽 후보 3개(`다음 자동` 포함), 대기 묶음 접힘
  2. 대기 묶음을 펼친 상태
  3. `remaining`이 없는 이전 형식 데이터
  4. 폭 360px
- 기존 테스트가 있으면 돌린다(`node scripts/test_rules.js` 등). 학습 후보를 나누는 로직을 순수 함수로 뺄 수 있으면 간단한 node 테스트를 더한다.

## 배포

- `learning-card` 브랜치를 push하고 PR을 만든다. 병합은 사용자가 한다.
  - gh 경로: `"/c/Program Files/GitHub CLI/gh.exe"`
  - push는 체인 없이 단독 명령으로 한다: `git -C <worktree> push -u origin learning-card`. 거부되면 우회하지 말고 보고한다.
- PR 본문은 한국어로 쓴다: 바뀐 점, 캡처, 확인한 것, 확인하지 못한 것. 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`를 붙인다.
- 커밋 메시지 끝에는 `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`를 붙인다.
- 작성자 오류가 나면 `git -c user.name=morn24 -c user.email=morngi24@gmail.com commit ...`을 쓴다.
- 강제 push, main 직접 커밋, PR 병합은 하지 않는다. 파일을 지우지 않는다.
- 쉘 heredoc에 작은따옴표가 있으면 실패할 수 있다. 긴 스크립트는 파일로 쓴다.

## 최종 보고 (한국어)

1. 1~3번 각각 완료·부분·미완료
2. PMR-Wiki 커밋 해시, L3·L18·L19의 실제 `remaining` 값
3. PR 주소, 캡처 경로, 테스트 결과
4. 확인하지 못한 것

대시보드 기록(`dash_update.py`)은 계획 세션이 하므로 이 세션에서는 하지 않는다.

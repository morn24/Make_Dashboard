# 수행지시문 01: PC 자동 업데이트 (단계 3)

> **사용법**: 로컬 PC에서 이 저장소(`Make_Dashboard`)를 연 Claude Code 또는 Codex에게
> "`docs/instructions/01-pc-update.md` 수행해줘"라고 말하면 됩니다.
> 아래 내용은 AI 에이전트가 읽는 지시문입니다.

---

## 역할

너는 이 저장소의 개발자다. PC에서 진행하는 프로젝트의 진행 상황을 대시보드 데이터 저장소에 기록하는 **명령줄 도구**와 **AI 에이전트용 규칙**을 만든다.

## 먼저 읽을 것

1. `docs/data-format.md`: 데이터 형식과 읽기/쓰기 규칙. **반드시 이것을 따른다.**
2. `docs/work-plan.md`: 전체 구조
3. `web/app.js`의 `readFile`, `writeFile`, `mutate`: 같은 규칙을 JavaScript로 구현한 예시

## 만들 것

### A. `scripts/dash_update.py`

- **Python 3.9+ 표준 라이브러리만** 사용한다 (`urllib`, `json`, `base64`, `argparse`). 추가 설치 없이 Windows, macOS, Linux에서 동작해야 한다.
- 설정 값 (우선순위: 명령줄 옵션 → 환경변수 → 설정 파일)
  - 토큰: `DASH_TOKEN` 환경변수, 없으면 `~/.dashboard/token` 파일 내용
  - 저장소: `DASH_REPO` (기본 `morn24/dashboard-data`), 브랜치: `DASH_BRANCH` (기본 `main`)
  - 토큰이 없으면 설정 방법을 한국어로 안내하고 종료 코드 2로 끝낸다.
- 하위 명령

```bash
# 프로젝트 갱신 (없으면 새로 만든다). 준 값만 바꾸고 나머지는 그대로 둔다.
python scripts/dash_update.py set \
  --id make-dashboard            # 생략하면 현재 폴더의 git 최상위 폴더 이름 → 소문자-하이픈
  --name "Make_Dashboard"        # 새로 만들 때 생략하면 폴더 이름
  --tool claude-code             # claude-code | codex | other
  --status active                # active | paused | done
  --progress 60                  # 0~100
  --current "지금 하는 일"
  --next "다음 과제1" --next "다음 과제2"   # 주면 목록 전체를 이 값으로 교체
  --summary "이번 작업 요약"

python scripts/dash_update.py show [--id ...]        # 현재 값을 보기 좋게 출력
python scripts/dash_update.py remove --id ...         # 프로젝트 삭제 (확인 질문, --yes로 생략)
python scripts/dash_update.py todo-add "할 일" [--daily]   # PC에서 할 일 추가
```

- `set`은 항상 프로젝트의 `updatedAt`과 파일 최상위 `updatedAt`을 현재 시각(ISO 8601, 현지 시간대 포함)으로 바꾼다.
- 쓰기는 `docs/data-format.md` 규칙대로 **읽기 → 변경 적용 → sha와 함께 PUT**. 409나 422가 오면 최대 4번 다시 시도한다. **덮어쓰기는 절대 하지 않는다.**
- 커밋 메시지 예시: `project: make-dashboard 60% - 지금 하는 일`
- 네트워크 오류는 한국어로 짧게 알리고 종료 코드 1로 끝낸다. AI 작업 흐름을 망가뜨리면 안 되므로 **예외 traceback을 쏟아내지 않는다.**
- 입력 검사: `progress`는 0~100 정수, `tool`/`status`는 허용된 값만 받는다.

### B. 테스트 `scripts/test_dash_update.py`

- `unittest`만 사용한다. GitHub API는 가짜(mock)로 대체한다.
- 최소 확인 항목: 새 프로젝트 생성, 일부 필드만 갱신, `--next` 교체, 409 충돌 후 재시도 성공, 한글 base64 왕복, 토큰 없음 안내.

### C. AI 에이전트용 전역 규칙

`docs/agent-rule.md`에 아래 취지의 규칙 문구를 만들고, 사용자가 복사해 넣을 위치를 안내한다.

- Claude Code: `~/.claude/CLAUDE.md` (모든 프로젝트에 적용)
- Codex: `~/.codex/AGENTS.md` (모든 프로젝트에 적용)

규칙 문구에 담을 내용:

1. 의미 있는 작업 단위를 끝냈으면 **최종 답변 전에** `dash_update.py set`을 한 번 실행한다. (`--tool`은 자신에 맞게)
2. `progress` 산정 방법: 프로젝트에 계획 문서나 체크리스트(`PLAN.md`, `TODO.md`, `docs/work-plan.md` 등)가 있으면 **완료 항목 수 / 전체 항목 수**로 계산한다. 없으면 근거를 한 줄로 `--summary`에 남기고 추정한다. 범위가 늘어난 경우가 아니면 진행률을 낮추지 않는다.
3. `--current`에는 지금 단계를, `--next`에는 바로 다음 과제 1~3개를 **구체적인 동사로** 적는다. (예: "로그인 API 테스트 작성")
4. 스크립트가 실패해도 본 작업은 실패로 처리하지 않는다. 사용자에게 한 줄로 알리기만 한다.

### D. Codex 네트워크 주의

Codex는 기본 샌드박스에서 **네트워크가 막혀 있을 수 있다.** 현재 Codex 공식 문서를 확인해서, 이 스크립트 실행에 필요한 네트워크 허용 방법(설정 파일이나 실행 시 승인)을 `docs/agent-rule.md`에 적어라. 추측으로 적지 말고, 확인이 안 되면 "확인 필요"라고 표시한다.

### E. 설치 안내 `docs/pc-setup.md`

사용자가 따라 할 순서를 고등학생도 이해할 수 있게 한국어로 적는다.

1. Fine-grained 토큰 만들기: GitHub → Settings → Developer settings → Fine-grained tokens → Only select repositories: `dashboard-data` → Contents: Read and write → 만료일 설정
2. 토큰 저장: `~/.dashboard/token` 파일 (Windows: `%USERPROFILE%\.dashboard\token`)
3. 스크립트 위치 고정: 이 저장소 경로 그대로 쓰거나 `~/.dashboard/dash_update.py`로 복사 (규칙 문구의 경로와 맞춘다)
4. 동작 확인: `python dash_update.py show`
5. 전역 규칙 붙여넣기

## 제약

- 토큰을 코드, 저장소, 로그에 절대 남기지 않는다. 오류 메시지에 출력하지 않는다.
- `web/`의 동작이나 `docs/data-format.md` 형식을 바꾸지 않는다. 형식 변경이 꼭 필요하면 멈추고 사용자에게 묻는다.

## 완료 기준

- [ ] `python -m unittest scripts/test_dash_update.py` 전부 통과
- [ ] 실제 토큰으로 `set` 실행 → 웹앱 새로고침 시 카드가 바뀜 (사용자 확인)
- [ ] 다른 프로젝트 폴더에서 Claude Code에게 작은 작업을 시켰을 때 작업 후 자동으로 갱신됨 (사용자 확인)
- [ ] `docs/pc-setup.md`, `docs/agent-rule.md` 작성
- [ ] `docs/work-plan.md`의 단계 3 상태를 ✅로 변경
- [ ] 작은 단위로 나눠 커밋 (예: 스크립트 → 테스트 → 문서)

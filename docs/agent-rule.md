# AI 에이전트 전역 규칙 (대시보드 자동 기록)

아래 **규칙 문구**를 복사해서 전역 규칙 파일 맨 끝에 붙여 넣으면, 어느 프로젝트에서 작업하든 AI가 작업을 마칠 때 대시보드를 갱신합니다.

| 도구 | 붙여 넣을 파일 (모든 프로젝트에 적용) | Windows 경로 |
|---|---|---|
| Claude Code | `~/.claude/CLAUDE.md` | `%USERPROFILE%\.claude\CLAUDE.md` |
| Codex | `~/.codex/AGENTS.md` | `%USERPROFILE%\.codex\AGENTS.md` |

파일이 없으면 새로 만들면 됩니다. 두 파일에 같은 문구를 넣되, Codex 쪽은 `--tool claude-code`를 `--tool codex`로 바꿉니다.
스크립트는 `~/.dashboard/dash_update.py` 한 곳에만 있으면 됩니다 ([설치 안내](pc-setup.md)). 각 프로젝트 폴더에는 아무것도 넣지 않습니다.

## 규칙 문구 (여기부터 복사)

````markdown
## 대시보드 진행 상황 기록

의미 있는 작업 단위(기능 추가, 버그 수정, 문서 작성 등)를 끝냈으면 **최종 답변 전에** 아래 명령을 한 번 실행한다.

```
python "$HOME/.dashboard/dash_update.py" set --tool claude-code --progress <0~100> --current "<지금 단계>" --next "<다음 과제1>" --next "<다음 과제2>" --summary "<이번 작업 요약>"
```

- `$HOME`은 bash와 PowerShell 모두에서 동작한다. Windows에서 `python`이 없으면 `py`를 쓴다.
- `--id`는 생략한다 (git 최상위 폴더, git이 없으면 현재 폴더 이름이 자동으로 쓰인다). 새 프로젝트면 `--name`에 보기 좋은 이름을 준다.
- `--status`를 생략하면 진행 중(`active`)이 된다.

**진행률(`--progress`) 정하는 법**
- 프로젝트에 계획 문서나 체크리스트(`PLAN.md`, `TODO.md`, `docs/work-plan.md` 등)가 있으면 **완료 항목 수 / 전체 항목 수 × 100**으로 계산한다.
- 없으면 추정하고, 그 근거를 한 줄로 `--summary`에 남긴다.
- 범위가 늘어난 경우가 아니면 진행률을 낮추지 않는다.

**`--current`와 `--next`**
- `--current`: 지금 단계 한 줄.
- `--next`: 바로 다음 과제 1~3개를 **구체적인 동사로** 적는다. 예: "로그인 API 테스트 작성" (O), "테스트" (X). 첫 번째가 바로 다음 할 일이다.

**기록하지 않는 경우**
- 단순 질문에 답하거나 파일을 읽기만 했을 때.
- 홈 폴더, 임시 폴더, 다운로드 폴더처럼 프로젝트가 아닌 곳에서 작업했을 때.

**git이 없는 폴더, 여러 프로젝트를 품은 폴더**
- git이 없는 폴더도 프로젝트로 다룬다 (예: Obsidian 보관함 안의 하위 프로젝트 폴더). 이때 `--id`는 현재 폴더 이름이다.
- 여러 프로젝트를 품은 상위 폴더(예: Obsidian 보관함 최상위)에서 작업했다면, 상위 폴더 이름으로 기록하지 말고 **실제로 작업한 하위 프로젝트 폴더 이름**을 `--id`와 `--name`으로 넘긴다. 어느 프로젝트인지 애매하면 기록 전에 사용자에게 묻는다.

**사용자가 요청하면**
- "이 프로젝트 홀드해줘" → `set --status paused`
- "완료 처리해줘" → `set --status done`
- "대시보드에서 빼줘" → `remove --yes`
- "대시보드에 이 프로젝트 등록해줘" → 계획 문서, README, 커밋 기록, TODO 주석을 살펴 진행률, 지금 단계, 다음 과제를 추정해 `set`으로 등록한다. "홀드로 등록"이면 `--status paused`를 붙인다.

**실패해도 본 작업은 성공이다**
- 스크립트가 실패해도(네트워크, 토큰 등) 본 작업을 실패로 처리하지 않는다. 사용자에게 한 줄로 알리기만 하고 다시 시도하느라 시간을 쓰지 않는다.
````

## Codex에서 네트워크 허용하기

Codex는 기본 샌드박스(`workspace-write`)에서 **밖으로 나가는 네트워크가 꺼져 있습니다.** 그대로 두면 `dash_update.py`가 GitHub에 접속하지 못해 "네트워크 연결 실패"가 납니다. 아래 중 하나를 고릅니다.

### 방법 1: 실행할 때마다 승인 (기본값, 설정 불필요)

승인 정책이 `on-request`(대화형 기본)이면 Codex는 샌드박스 밖이 필요할 때 사용자에게 묻습니다. 대시보드 기록 때 승인 요청이 뜨면 허용하면 됩니다. 번거롭지만 가장 안전합니다.

### 방법 2: 샌드박스 안 네트워크 켜기 (확인됨)

`~/.codex/config.toml` (Windows: `%USERPROFILE%\.codex\config.toml`)에 추가합니다.

```toml
sandbox_mode = "workspace-write"
approval_policy = "on-request"

[sandbox_workspace_write]
network_access = true
```

- 공식 설정 문서: `sandbox_workspace_write.network_access` = "Allow outbound network access inside the workspace-write sandbox." 사용자 설정 파일은 `~/.codex/config.toml`.
- 한 번만 켜려면 실행할 때 `codex -c sandbox_workspace_write.network_access=true` (출처: 제3자 가이드, **확인 필요**).
- 주의: 이 설정은 이 스크립트만이 아니라 Codex가 실행하는 **모든 명령**에 네트워크를 엽니다.

### 확인 필요

- **특정 명령만 허용하는 규칙(rules, `prefix_rule`)**: 공식 설정 문서에 규칙 관련 항목은 있지만, 규칙으로 허용한 명령이 샌드박스 밖(네트워크 포함)에서 실행되는지는 확인하지 못했습니다.
- **네이티브 Windows 샌드박스**: PowerShell에서 실행하면 Codex는 Windows 전용 샌드박스를 씁니다. 위 `network_access` 설정이 Windows 샌드박스에도 똑같이 적용되는지는 공식 Windows 문서에서 확인하지 못했습니다. 설정 후 `python "$HOME/.dashboard/dash_update.py" show`를 Codex에게 실행시켜 보면 바로 알 수 있습니다.

출처 (2026-10-30 확인)
- [Codex 설정 문서 (Configuration reference)](https://learn.chatgpt.com/docs/config-file/config-reference)
- [Codex 샌드박스 문서 (Sandboxing)](https://learn.chatgpt.com/docs/sandboxing)
- [Codex 보안 문서 (Security)](https://developers.openai.com/codex/security)

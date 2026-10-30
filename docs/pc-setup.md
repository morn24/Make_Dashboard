# PC 설정: 작업하면 대시보드가 저절로 바뀌게 하기

PC에서 Claude Code나 Codex로 작업을 마치면, AI가 `dash_update.py`를 실행해 폰 대시보드의 진행률과 다음 과제를 바꿉니다.
아래 순서를 **한 번만** 따라 하면 됩니다. (약 10분)

> 준비물: Python 3.9 이상. 명령 프롬프트나 PowerShell에서 `python --version` (안 되면 `py --version`)을 쳐서 버전이 나오면 됩니다.

## 1. 토큰 만들기 (GitHub 열쇠)

웹앱을 설정할 때 만든 토큰이 있으면 그것을 써도 됩니다. 새로 만든다면:

1. GitHub → 오른쪽 위 프로필 사진 → **Settings**
2. 왼쪽 맨 아래 **Developer settings** → **Personal access tokens** → **Fine-grained tokens** → **Generate new token**
3. **Expiration**(만료일): 예를 들어 1년. 만료되면 같은 방법으로 다시 만들어 2번 파일만 바꾸면 됩니다.
4. **Repository access**: **Only select repositories** → `dashboard-data` 하나만 고르기
5. **Permissions** → Repository permissions → **Contents: Read and write**
6. **Generate token** → 나온 `github_pat_...` 값을 복사 (창을 닫으면 다시 볼 수 없어요)

> 이 토큰은 `dashboard-data` 저장소 하나만 읽고 쓸 수 있어서, 혹시 유출돼도 피해가 작습니다. 그래도 남에게 보여 주거나 코드에 넣지 마세요.

## 2. 토큰 저장하기

홈 폴더 안에 `.dashboard` 폴더를 만들고, 그 안에 `token`이라는 파일(확장자 없음)에 토큰 한 줄만 저장합니다.

**Windows (PowerShell)**

```powershell
mkdir "$HOME\.dashboard" -Force
Set-Content -Path "$HOME\.dashboard\token" -Value "여기에_복사한_토큰" -NoNewline
```

위치: `%USERPROFILE%\.dashboard\token` (예: `C:\Users\내이름\.dashboard\token`)

**macOS / Linux**

```bash
mkdir -p ~/.dashboard
printf '%s' '여기에_복사한_토큰' > ~/.dashboard/token
chmod 600 ~/.dashboard/token
```

## 3. 스크립트 설치 (한 곳에만, 한 번만)

이 저장소(`Make_Dashboard`) 폴더에서 스크립트를 `~/.dashboard/` 로 복사합니다.

**Windows (PowerShell)**

```powershell
Copy-Item scripts\dash_update.py "$HOME\.dashboard\dash_update.py"
```

**macOS / Linux**

```bash
cp scripts/dash_update.py ~/.dashboard/dash_update.py
```

- 각 프로젝트 폴더에는 **아무것도 설치하거나 clone하지 않습니다.** 모든 프로젝트가 이 한 파일을 함께 씁니다.
- 나중에 이 저장소의 `scripts/dash_update.py`가 업데이트되면 **같은 명령으로 다시 복사**하세요.

## 4. 동작 확인

```
python "$HOME/.dashboard/dash_update.py" show --all
```

(Windows에서 `python`이 안 되면 `py`)

`연결 확인: morn24/dashboard-data (main) · 프로젝트 n개`가 나오면 성공입니다.

| 이런 메시지가 나오면 | 해결 |
|---|---|
| `GitHub 토큰이 없어요` | 2번의 파일 위치와 이름(`token`, 확장자 없음)을 확인 |
| `토큰이 올바르지 않거나 만료됐어요 (401)` | 토큰을 잘못 복사했거나 만료됨 → 1번부터 다시 |
| `권한이 없거나 ... (403)` | 토큰의 저장소 선택(`dashboard-data`)과 Contents 읽기/쓰기 권한 확인 |
| `저장소나 브랜치를 찾을 수 없어요 (404)` | 저장소 이름 확인. 다른 저장소를 쓰면 `DASH_REPO` 환경변수 설정 |
| `네트워크 연결 실패` | 인터넷 연결 확인. Codex에서만 그렇다면 [Codex 네트워크 설정](agent-rule.md#codex에서-네트워크-허용하기) |

직접 기록해 보기 (웹앱을 새로고침하면 카드가 생겨요):

```
python "$HOME/.dashboard/dash_update.py" set --id test --name "테스트" --progress 10 --current "설치 확인" --next "카드 지우기"
python "$HOME/.dashboard/dash_update.py" remove --id test --yes
```

## 5. AI에게 규칙 알려 주기

[`docs/agent-rule.md`](agent-rule.md)의 **규칙 문구**를 복사해서 붙여 넣습니다.

- Claude Code: `~/.claude/CLAUDE.md` (Windows: `%USERPROFILE%\.claude\CLAUDE.md`)
- Codex: `~/.codex/AGENTS.md` (Windows: `%USERPROFILE%\.codex\AGENTS.md`), 그리고 [네트워크 허용](agent-rule.md#codex에서-네트워크-허용하기)

이제 아무 프로젝트에서 AI에게 작업을 시키면, 작업이 끝날 때 대시보드가 저절로 바뀝니다.
이미 진행 중이거나 홀드해 둔 프로젝트를 올리려면 [`docs/existing-projects.md`](existing-projects.md)를 보세요.

## 참고: 명령 모음

```
python dash_update.py set [--id ID] [--name 이름] [--tool claude-code|codex|antigravity|gemini|other]
                          [--status active|paused|done] [--progress 0~100]
                          [--current "지금 하는 일"] [--summary "요약"]
                          [--next-add "과제" ...] [--next-done "끝낸 과제" ...]
                          [--next "과제" ...] [--scope-grew]
python dash_update.py show [--id ID] [--all]
python dash_update.py remove --id ID [--yes]
python dash_update.py todo-add "할 일" [--daily]
```

- `set`은 준 값만 바꿉니다. `--status`를 생략하면 진행 중이 됩니다.
- 다음 과제: `--next-add`는 끝에 더하고 `--next-done`은 뺍니다. 다른 세션이 적은 과제는 그대로 남습니다. `--next`는 목록 전체를 바꿉니다.
- 진행률은 기존 값보다 낮게 주면 무시됩니다. 범위가 늘어서 낮춰야 할 때만 `--scope-grew`를 붙입니다.
- 설정 우선순위: 명령줄 옵션(`--repo`, `--branch`, `--token-file`) → 환경변수(`DASH_TOKEN`, `DASH_REPO`, `DASH_BRANCH`) → `~/.dashboard/token`

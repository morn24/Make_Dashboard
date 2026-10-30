# Make_Dashboard

오늘 할 일과 PC에서 Codex나 Claude Code로 진행하는 프로젝트의 진행률을 **폰에서 한눈에** 보는 개인 대시보드입니다.

- `web/`: 웹앱 (PWA). 할 일 체크와 추가, 프로젝트 진행률 보기
- `docs/data-format.md`: 데이터 형식 (웹앱, PC 스크립트, 위젯이 공통으로 따르는 약속)
- `docs/work-plan.md`: **업무계획서**
- `docs/instructions/`: 로컬 PC에서 AI에게 맡길 **수행지시문**
  - `01-pc-update.md`: PC 자동 업데이트 스크립트
  - `02-android-widget.md`: 안드로이드 홈 화면 위젯
- `docs/google-calendar-setup.md`: Google 캘린더 오늘 일정 연결 (Apps Script, 약 10분)
- `integrations/google-calendar/`: 캘린더 동기화 Apps Script 코드

## 처음 설정 (한 번만, 약 10분)

### 1) 데이터 저장소 만들기 (비공개)

1. https://github.com/new 접속
2. Repository name: `dashboard-data`
3. **Private** 선택
4. **Add a README file 체크** (main 브랜치가 생기도록)
5. Create repository

데이터 파일(`data/todos.json`, `data/projects.json`)은 웹앱에서 처음 저장할 때 자동으로 만들어집니다.

### 2) 토큰 만들기 (Fine-grained personal access token)

1. GitHub → 오른쪽 위 프로필 → Settings → Developer settings → Personal access tokens → **Fine-grained tokens** → Generate new token
2. Expiration: 원하는 기간 (예: 1년)
3. Repository access: **Only select repositories** → `dashboard-data`
4. Permissions → Repository permissions → **Contents: Read and write**
5. 생성된 `github_pat_...` 값을 복사 (다시 볼 수 없으니 안전한 곳에 보관)

> 이 토큰은 `dashboard-data` 저장소 하나만 읽고 쓸 수 있어서, 유출되더라도 피해 범위가 작습니다.

### 3) 웹앱 배포 (GitHub Pages)

무료 계정의 GitHub Pages는 **공개 저장소**에서만 동작합니다.
이 저장소(`Make_Dashboard`)에는 코드만 있고 개인 데이터는 `dashboard-data`(비공개)에 있으므로, 이 저장소를 공개로 바꿔도 괜찮습니다.

1. 이 저장소 → Settings → General → 맨 아래 Danger Zone → **Change visibility → Public**
2. Settings → **Pages** → Build and deployment → Source: **GitHub Actions**
3. 작업 브랜치를 `main`에 병합 (병합하면 자동 배포됨. 또는 Actions 탭 → "Deploy web app to GitHub Pages" → Run workflow)
4. 주소: `https://morn24.github.io/Make_Dashboard/`

### 4) 폰에 설치 (갤럭시 S25)

1. 삼성 인터넷이나 Chrome으로 위 주소 열기
2. 메뉴 → **홈 화면에 추가** (또는 "앱 설치")
3. 앱을 열고 ⚙ → 소유자 `morn24`, 저장소 `dashboard-data`, 브랜치 `main`, 토큰 붙여넣기 → **연결 확인 후 저장**

이제 할 일을 체크하고 추가할 수 있습니다. 웹앱은 열려 있는 동안 1분마다, 다시 열 때마다 새로고침됩니다.

## 웹앱 로컬에서 보기

```bash
cd web && python3 -m http.server 8000
# http://localhost:8000 (설정 전에는 데모 데이터로 표시)
```

## 다음 단계 (로컬 PC)

`docs/work-plan.md`의 단계 3과 4를 각 수행지시문으로 진행합니다.

# Make_Dashboard

오늘 할 일과 PC에서 Codex나 Claude Code로 진행하는 프로젝트의 진행률을 **폰에서 한눈에** 보는 개인 대시보드입니다.

- `web/`: 웹앱 (PWA). 할 일 체크와 추가, 프로젝트 진행률 보기, **PMR-Wiki 탭**(정기 작업 실행 여부·학습 후보·AI에게 맡기기 — 데이터는 PMR-Wiki `tools/dash_sync.py`가 올림)
- `docs/data-format.md`: 데이터 형식 (웹앱, PC 스크립트, 위젯이 공통으로 따르는 약속)
- `docs/work-plan.md`: **업무계획서**
- `docs/instructions/`: 로컬 PC에서 AI에게 맡길 **수행지시문**
  - `01-pc-update.md`: PC 자동 업데이트 스크립트
  - `02-android-widget.md`: 안드로이드 홈 화면 위젯
  - `02-android-widget-luna.md`: 구체 설계를 따른 Luna(max) 구현 수행지시문 ([업무계획서](docs/android-work-plan.md))
  - `03-android-widget-ux-luna.md`: 꾸미기·반응 속도·UI/UX 개선 전체 구현 지시문 ([개선 업무계획서](docs/android-ux-work-plan.md), [검토서](docs/android-ui-ux-review.md))
  - `04-android-layout-colors-luna.md`: 2단 레이아웃·일정 원색·영역 배경·다크 조합 개선 지시문 ([업무계획서](docs/android-layout-colors-work-plan.md))
  - `05-android-scroll-colors-luna.md`: 높이 확장·영역별 실제 스크롤·캘린더 색상 복구·따뜻한 팔레트 지시문 ([업무계획서](docs/android-scroll-colors-work-plan.md))
- `scripts/dash_update.py`: PC에서 프로젝트 진행 상황을 기록하는 스크립트 ([설치 안내](docs/pc-setup.md), [AI 전역 규칙](docs/agent-rule.md), [기존 프로젝트 올리기](docs/existing-projects.md))
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

Android 위젯 개선판의 설치·실기기 확인은 [설치 안내](docs/android-setup.md), [기존 UX 검증](docs/android-ux-verification.md), [레이아웃·색상 검증](docs/android-layout-colors-verification.md)을 참고하세요. 폰 대시보드의 사용자 확인을 반영해 Galaxy S25 기본 표시·개선판 사용성과 위젯↔웹앱 상호 반영, 개인 캘린더 연결 설정을 완료 처리했습니다. 최신 목록 확장과 실제 일정 색상은 폰에서 최종 확인할 항목입니다.

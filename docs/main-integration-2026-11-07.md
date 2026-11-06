# 로컬 main·원격 통합 기록 (2026-11-07)

- 통합 전 로컬: bfdc5b4 (원격 대비 13개 커밋).
- 원격 main: f1a99c5, PMR-Wiki 탭: c4eb2fc (아직 원격 main에는 포함되지 않은 별도 브랜치).
- 로컬 이력은 재작성하지 않고 두 원격 브랜치를 순서대로 merge했다. push는 사용자 결과 확인 후 진행한다.
- 원본 main은 codex/backup-main-before-integration-20261107 브랜치에, 미커밋 수정과 미추적 파일 전체는 pre-integration local edits 2026-11-07 stash에 보관했다. stash는 복원 후에도 삭제하지 않았다.
- 로컬 계획·설치/인계 문서·사용자 관점 기록 규칙과 웹 요청 문구를 복원했다. 개인 PC 경로가 든 PMR-Wiki.code-workspace는 프로젝트 커밋에서 제외하고 stash로 보관했다.

## 충돌 해결

- Code.gs: 개별 일정/라벨 색상, 캐시, 색상 강제 갱신과 진단을 유지하면서 Google 할 일 양방향 동기화, 푸시 알림, 1분 tick 트리거를 함께 유지했다.
- 파일 API가 경로 인자를 받도록 바뀐 데 맞춰 색상 동기화와 진단의 읽기도 CONFIG.paths.calendar를 넘기도록 수정했다. 색상 테스트에서 읽기·쓰기 URL이 calendar.json을 가리키는지 확인한다.
- 매니페스트의 Calendar 읽기 전용 권한과 Tasks 읽기/수정 서비스·권한을 모두 유지했다. 설치 안내의 Calendar REST API 활성화 절차도 보존했다.
- 계획 문서에는 로컬 Android 구현/사용자 확인 기록을 남기고 원격의 Android 후속 개선 보류(PWA 집중) 방침을 유지했다.
- web/은 PMR-Wiki 브랜치와 비교했을 때 app.js의 기존 사용자 관점 요청 문구 수정만 다르다. PMR-Wiki 탭, 명령 버튼, 데모, 스타일, 서비스 워커는 원격 내용 그대로다.

## 최종 검증

- node --test integrations/google-calendar/tests/Code.test.cjs: 9개 통과.
- node scripts/test_apps_script.js: Google 할 일·알림·암호화 9개 통과.
- node scripts/test_rules.js: 입력 문장 11개 및 시작 전/묶음 알림 검증 통과.
- node --check web/app.js, node --check web/sw.js: 통과.
- python -m unittest scripts/test_dash_update.py: 29개 통과. 첫 샌드박스 실행은 Windows 임시 폴더 정리 권한 문제로 실패하여 샌드박스 밖에서 재검증했다.
- git diff --check 및 충돌 마커 검색: 문제 없음.

실제 개인 Google 계정의 Apps Script 교체·트리거 실행, 폰 알림 수신은 이번 로컬 통합 검증에 포함하지 않았다. GitHub Pages와 기존 개인 Apps Script는 아직 변경되지 않는다.

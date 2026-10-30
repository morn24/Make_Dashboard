# PLAN

대시보드 진행률은 이 체크리스트의 **완료 항목 수 / 전체 항목 수**로 계산합니다. 전체 계획은 [docs/work-plan.md](docs/work-plan.md) 참고.

## 단계 1: 데이터 구조

- [x] 데이터 형식 정하기 (`docs/data-format.md`)
- [x] 비공개 데이터 저장소 `dashboard-data` 만들기

## 단계 2: 웹앱

- [x] 할 일 체크·추가, 프로젝트 진행률을 보여 주는 웹앱(PWA) 만들기
- [x] GitHub Pages 배포와 폰 설치
- [x] 프로젝트 상태 바꾸기(진행 중/홀드/완료/빼기), 홀드·완료 묶음 표시
- [x] 할 일·프로젝트 순서를 끌어서 바꾸기

## 단계 3: PC 자동 업데이트

- [x] `scripts/dash_update.py`와 테스트 작성
- [x] 설치 안내, AI 전역 규칙, 기존 프로젝트 등록 안내 작성
- [x] `~/.dashboard/`에 설치하고 Claude Code·Codex 전역 규칙과 Codex 네트워크 설정 반영
- [x] 여러 세션이 동시에 기록해도 카드 하나에 일관되게 쌓이게 하기 (worktree id, 다음 과제 합치기, 진행률 낮추기 방지)
- [x] 다른 프로젝트에서 작업한 뒤 대시보드가 자동으로 갱신되는지 확인 (사용자)
- [x] Codex에서 `dash_update.py`가 네트워크에 접속되는지 확인 (사용자)

## 단계 4: 안드로이드 위젯 ([수행지시문 02](docs/instructions/02-android-widget.md))

- [ ] 위젯 앱 만들고 `./gradlew test assembleDebug` 통과
- [ ] 갤럭시 S25 홈 화면에 3가지 크기 위젯 표시 (사용자 확인)
- [ ] 위젯 체크와 웹앱 변경이 서로 반영되는지 확인 (사용자 확인)
- [ ] 오프라인에서 마지막 데이터와 오류 문구 표시, 자정에 매일 할 일 초기화
- [ ] `docs/android-setup.md` 작성

## 단계 5: Google 캘린더 연동

- [x] Apps Script 코드와 웹앱 오늘 일정 표시
- [x] Apps Script 설치하고 `calendar.json`이 만들어지는지 확인 (사용자, [설정 안내](docs/google-calendar-setup.md))

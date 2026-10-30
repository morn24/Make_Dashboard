#!/usr/bin/env python3
"""대시보드 데이터 저장소(morn24/dashboard-data)에 프로젝트 진행 상황을 기록하는 명령줄 도구.

데이터 형식과 읽기/쓰기 규칙은 docs/data-format.md 참고.
Python 3.9+ 표준 라이브러리만 사용한다.

사용 예:
  python dash_update.py set --tool claude-code --progress 60 --current "지금 하는 일" --next "다음 과제"
  python dash_update.py show
  python dash_update.py remove --id my-project --yes
  python dash_update.py todo-add "보고서 제출" [--daily]
"""

import argparse
import base64
import json
import os
import random
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

FILES = {'todos': 'data/todos.json', 'projects': 'data/projects.json'}
EMPTY = {
    'todos': lambda: {'version': 1, 'updatedAt': None, 'items': []},
    'projects': lambda: {'version': 1, 'updatedAt': None, 'projects': []},
}
DEFAULT_REPO = 'morn24/dashboard-data'
DEFAULT_BRANCH = 'main'
TOOLS = ('claude-code', 'codex', 'other')
STATUSES = ('active', 'paused', 'done')
MAX_RETRIES = 4  # 409/422 충돌 시 다시 시도하는 횟수
TIMEOUT = 20

TOKEN_HELP = """\
GitHub 토큰이 없어요. 아래 둘 중 하나로 설정해 주세요.
  1) 파일: ~/.dashboard/token  (Windows: %USERPROFILE%\\.dashboard\\token) 에 토큰 한 줄 저장
  2) 환경변수: DASH_TOKEN
토큰 만드는 방법은 Make_Dashboard 저장소의 docs/pc-setup.md 를 보세요."""


class DashError(Exception):
    """사용자에게 한 줄로 보여 줄 오류. code는 종료 코드."""

    def __init__(self, message, code=1):
        super().__init__(message)
        self.code = code


class HttpError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


# ---------- 설정 ----------

def token_path():
    return Path.home() / '.dashboard' / 'token'


def load_config(args):
    token = os.environ.get('DASH_TOKEN', '').strip()
    if not token:
        path = Path(args.token_file) if args.token_file else token_path()
        try:
            token = path.read_text(encoding='utf-8-sig').strip()
        except OSError:
            token = ''
    if not token:
        raise DashError(TOKEN_HELP, code=2)
    repo = args.repo or os.environ.get('DASH_REPO') or DEFAULT_REPO
    branch = args.branch or os.environ.get('DASH_BRANCH') or DEFAULT_BRANCH
    if repo.count('/') != 1:
        raise DashError(f'저장소 이름은 "소유자/이름" 형식이어야 해요: {repo}', code=2)
    return {'token': token, 'repo': repo, 'branch': branch}


# ---------- 날짜, id ----------

def iso_now():
    return datetime.now().astimezone().isoformat(timespec='seconds')


def base36(n):
    digits = '0123456789abcdefghijklmnopqrstuvwxyz'
    out = ''
    while True:
        n, r = divmod(n, 36)
        out = digits[r] + out
        if n == 0:
            return out


def new_todo_id():
    rand = ''.join(random.choice('0123456789abcdefghijklmnopqrstuvwxyz') for _ in range(4))
    return 't_' + base36(int(time.time() * 1000)) + rand


def slugify(name):
    s = re.sub(r'[^\w]+|_', '-', name.strip().lower())
    return re.sub(r'-{2,}', '-', s).strip('-')


def git_rev_parse(flag):
    try:
        out = subprocess.run(
            ['git', 'rev-parse', flag],
            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=10,
        )  # git은 경로를 UTF-8로 출력한다 (Windows 기본 cp949로 읽으면 한글 경로에서 깨짐)
    except (OSError, subprocess.SubprocessError):
        return None
    if out.returncode == 0 and out.stdout.strip():
        return out.stdout.strip()
    return None


def project_folder():
    """원래 저장소 폴더 (worktree 안이어도), git 저장소가 아니면 현재 폴더."""
    top = git_rev_parse('--show-toplevel')
    if top is None:
        return Path.cwd()
    # worktree에서도 --git-common-dir은 원래 저장소의 .git을 가리킨다 (상대 경로면 현재 폴더 기준)
    common = git_rev_parse('--git-common-dir')
    if common:
        common_path = Path.cwd() / common
        if common_path.name == '.git':
            return common_path.resolve().parent
    return Path(top)  # bare 저장소, 서브모듈 등은 최상위 폴더 이름


# ---------- GitHub API ----------

def b64encode(text):
    return base64.b64encode(text.encode('utf-8')).decode('ascii')


def b64decode(b64):
    return base64.b64decode(''.join(b64.split())).decode('utf-8')


def api(cfg, method, path, body=None):
    """(status, json) 를 돌려준다. 404는 그대로 돌려주고, 그 밖의 오류는 HttpError."""
    url = f"https://api.github.com/repos/{cfg['repo']}/contents/{path}"
    if method == 'GET':
        url += '?ref=' + urllib.parse.quote(cfg['branch'])
    headers = {
        'Accept': 'application/vnd.github+json',
        'Authorization': f"Bearer {cfg['token']}",
        'X-GitHub-Api-Version': '2022-11-28',
        'User-Agent': 'dash-update',
    }
    data = None
    if body is not None:
        data = json.dumps(body).encode('utf-8')
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as res:
            return res.status, json.loads(res.read().decode('utf-8') or 'null')
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return 404, None
        msg = e.reason
        try:
            msg = json.loads(e.read().decode('utf-8')).get('message') or msg
        except Exception:
            pass
        raise HttpError(e.code, str(msg)) from None


def read_file(cfg, file):
    status, j = api(cfg, 'GET', FILES[file])
    if status == 404:
        return EMPTY[file](), None
    return json.loads(b64decode(j['content'])), j['sha']


def write_file(cfg, file, data, sha, message):
    body = {
        'message': message,
        'content': b64encode(json.dumps(data, ensure_ascii=False, indent=2) + '\n'),
        'branch': cfg['branch'],
    }
    if sha:
        body['sha'] = sha
    status, _ = api(cfg, 'PUT', FILES[file], body)
    if status == 404:
        raise DashError('저장소나 브랜치를 찾을 수 없어요 (404). 토큰 권한과 DASH_REPO를 확인하세요.')


def mutate(cfg, file, change):
    """최신본을 읽고 → change(data) 적용 → sha와 함께 저장. 409/422면 다시 읽고 다시 적용한다.

    change는 (커밋 메시지, 결과값)을 돌려준다. 메시지가 None이면 저장하지 않는다.
    """
    for attempt in range(MAX_RETRIES + 1):
        data, sha = read_file(cfg, file)
        message, result = change(data)
        if message is None:
            return result
        data['updatedAt'] = iso_now()
        try:
            write_file(cfg, file, data, sha, message)
            return result
        except HttpError as e:
            if e.status not in (409, 422) or attempt >= MAX_RETRIES:
                raise
            time.sleep(0.6 * (attempt + 1))


# ---------- 하위 명령 ----------

def resolve_id(args):
    if args.id:
        pid = slugify(args.id)
        if not pid:
            raise DashError(f'--id 값이 올바르지 않아요: {args.id}', code=2)
        return pid, args.id
    folder = project_folder().name
    return slugify(folder) or 'project', folder


def same_task(a, b):
    return ' '.join(a.split()).casefold() == ' '.join(b.split()).casefold()


def merge_next(current, add, done):
    """끝난 항목(done)을 빼고 새 항목(add)을 뒤에 더한다. 다른 세션이 적은 항목과 순서는 그대로 둔다."""
    missing = [d for d in done if not any(same_task(d, x) for x in current)]
    merged = [x for x in current if not any(same_task(x, d) for d in done)]
    for a in add:
        if not any(same_task(a, x) for x in merged):
            merged.append(a)
    return merged, missing


def clean(items):
    return [s for s in (x.strip() for x in items or []) if s]


def cmd_set(cfg, args):
    if args.next is not None and (args.next_add or args.next_done):
        raise DashError('--next(전체 교체)와 --next-add/--next-done(합치기)은 함께 쓸 수 없어요.', code=2)
    pid, folder = resolve_id(args)
    now = iso_now()
    notes = []

    def change(data):
        notes.clear()  # 충돌로 다시 적용할 때마다 새로 계산
        projects = data.setdefault('projects', [])
        p = next((x for x in projects if x.get('id') == pid), None)
        if p is None:
            p = {
                'id': pid,
                'name': folder,
                'tool': 'other',
                'status': 'active',
                'progress': 0,
                'current': '',
                'next': [],
                'summary': '',
                'updatedAt': now,
            }
            projects.insert(0, p)  # 새 프로젝트는 맨 앞
        # 기존 프로젝트는 배열 안 위치를 바꾸지 않는다
        if args.name is not None:
            p['name'] = args.name
        if args.tool is not None:
            p['tool'] = args.tool
        p['status'] = args.status or 'active'
        if args.progress is not None:
            old = p.get('progress', 0)
            if args.progress < old and not args.scope_grew:
                # 다른 세션이 먼저 올린 진행률을 낮추지 않는다 (범위가 늘었으면 --scope-grew)
                notes.append(f'진행률 {args.progress}%가 기존 {old}%보다 낮아 {old}%를 유지했어요. 범위가 늘었다면 --scope-grew를 붙이세요.')
            else:
                p['progress'] = args.progress
        if args.current is not None:
            p['current'] = args.current
        if args.next is not None:
            p['next'] = clean(args.next)
        if args.next_add or args.next_done:
            p['next'], missing = merge_next(p.get('next') or [], clean(args.next_add), clean(args.next_done))
            if missing:
                notes.append('다음 과제 목록에 없어서 뺄 수 없었어요: ' + ', '.join(missing))
        if args.summary is not None:
            p['summary'] = args.summary
        p['updatedAt'] = now
        label = p.get('current') or p.get('summary') or p['status']
        return f"project: {pid} {p.get('progress', 0)}% - {label}", dict(p)

    p = mutate(cfg, 'projects', change)
    print(f"대시보드 갱신: {p['name']} ({p['id']}) {p['progress']}% · {p['status']}")
    for note in notes:
        print(f'  참고: {note}')
    if p.get('next'):
        print('  다음 과제: ' + ' / '.join(p['next']))
    return 0


def print_project(p):
    status = {'active': '진행 중', 'paused': '홀드', 'done': '완료'}.get(p.get('status'), p.get('status'))
    print(f"{p.get('name')}  ({p.get('id')})")
    print(f"  상태     : {status}")
    print(f"  도구     : {p.get('tool')}")
    print(f"  진행률   : {p.get('progress', 0)}%")
    print(f"  지금     : {p.get('current') or '-'}")
    nxt = p.get('next') or []
    if nxt:
        print('  다음 과제:')
        for i, s in enumerate(nxt, 1):
            print(f'    {i}. {s}')
    else:
        print('  다음 과제: -')
    if p.get('summary'):
        print(f"  요약     : {p['summary']}")
    print(f"  갱신     : {p.get('updatedAt') or '-'}")


def cmd_show(cfg, args):
    data, _ = read_file(cfg, 'projects')
    projects = data.get('projects', [])
    if not args.all:
        pid, _ = resolve_id(args)
        p = next((x for x in projects if x.get('id') == pid), None)
        if p:
            print_project(p)
            return 0
        print(f'"{pid}" 프로젝트는 대시보드에 없어요.')
        if args.id:
            return 1
    print(f"연결 확인: {cfg['repo']} ({cfg['branch']}) · 프로젝트 {len(projects)}개")
    for x in projects:
        print(f"  - {x.get('id')}: {x.get('progress', 0)}% · {x.get('status')} · {x.get('current') or ''}")
    return 0


def cmd_remove(cfg, args):
    pid, _ = resolve_id(args)
    if not args.yes:
        if not sys.stdin.isatty():
            raise DashError('확인 질문에 답할 수 없는 환경이에요. 지우려면 --yes를 붙이세요.', code=2)
        answer = input(f'"{pid}" 프로젝트를 대시보드에서 뺄까요? [y/N] ').strip().lower()
        if answer not in ('y', 'yes', 'ㅛ'):
            print('취소했어요.')
            return 0

    def change(data):
        projects = data.get('projects', [])
        kept = [x for x in projects if x.get('id') != pid]
        if len(kept) == len(projects):
            return None, False
        data['projects'] = kept
        return f'project: remove {pid}', True

    if not mutate(cfg, 'projects', change):
        print(f'"{pid}" 프로젝트는 대시보드에 없어요.')
        return 1
    print(f'대시보드에서 뺐어요: {pid}')
    return 0


def cmd_todo_add(cfg, args):
    title = args.title.strip()
    if not title:
        raise DashError('할 일 내용이 비어 있어요.', code=2)
    item = {
        'id': new_todo_id(),
        'title': title,
        'repeat': 'daily' if args.daily else 'none',
        'doneOn': None,
        'createdAt': iso_now(),
    }

    def change(data):
        data.setdefault('items', []).append(dict(item))  # 새 할 일은 맨 뒤
        return f'todo: add {title}', None

    mutate(cfg, 'todos', change)
    print(f"할 일 추가: {title}{' (매일)' if args.daily else ''}")
    return 0


# ---------- 명령줄 ----------

def progress_value(text):
    try:
        n = int(text)
    except ValueError:
        n = -1
    if not 0 <= n <= 100:
        raise argparse.ArgumentTypeError(f'0~100 사이 정수여야 해요: {text}')
    return n


def build_parser():
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument('--repo', help=f'데이터 저장소 (기본 DASH_REPO 또는 {DEFAULT_REPO})')
    common.add_argument('--branch', help=f'브랜치 (기본 DASH_BRANCH 또는 {DEFAULT_BRANCH})')
    common.add_argument('--token-file', help='토큰 파일 경로 (기본 ~/.dashboard/token)')

    parser = argparse.ArgumentParser(prog='dash_update.py', description='대시보드에 프로젝트 진행 상황을 기록합니다.')
    sub = parser.add_subparsers(dest='command', required=True)

    p = sub.add_parser('set', parents=[common], help='프로젝트 갱신 (없으면 새로 만듦)')
    p.add_argument('--id', help='생략하면 git 최상위 폴더(아니면 현재 폴더) 이름')
    p.add_argument('--name', help='화면에 보일 이름 (새로 만들 때 생략하면 폴더 이름)')
    p.add_argument('--tool', choices=TOOLS)
    p.add_argument('--status', choices=STATUSES, help='생략하면 active')
    p.add_argument('--progress', type=progress_value, help='0~100')
    p.add_argument('--current', help='지금 하는 일')
    p.add_argument('--scope-grew', action='store_true', help='범위가 늘어 진행률을 낮출 때')
    p.add_argument('--next', action='append', help='다음 과제 (여러 번 쓰면 목록, 주면 전체 교체)')
    p.add_argument('--next-add', action='append', help='다음 과제 하나를 목록 끝에 더함 (기존 항목 유지)')
    p.add_argument('--next-done', action='append', help='끝난 다음 과제 하나를 목록에서 뺌')
    p.add_argument('--summary', help='이번 작업 요약')
    p.set_defaults(func=cmd_set)

    p = sub.add_parser('show', parents=[common], help='현재 값 보기')
    p.add_argument('--id')
    p.add_argument('--all', action='store_true', help='모든 프로젝트 목록')
    p.set_defaults(func=cmd_show)

    p = sub.add_parser('remove', parents=[common], help='프로젝트 삭제')
    p.add_argument('--id')
    p.add_argument('--yes', action='store_true', help='확인 질문 생략')
    p.set_defaults(func=cmd_remove)

    p = sub.add_parser('todo-add', parents=[common], help='할 일 추가')
    p.add_argument('title')
    p.add_argument('--daily', action='store_true', help='매일 반복')
    p.set_defaults(func=cmd_todo_add)
    return parser


def setup_output():
    # 파이프로 실행될 때(AI 에이전트) Windows 기본 인코딩 때문에 한글이 깨지거나 오류 나지 않게
    for stream in (sys.stdout, sys.stderr):
        try:
            if not stream.isatty():
                stream.reconfigure(encoding='utf-8', errors='replace')
            else:
                stream.reconfigure(errors='replace')
        except (AttributeError, ValueError, OSError):
            pass


def error_text(e):
    if e.status == 401:
        return '토큰이 올바르지 않거나 만료됐어요 (401).'
    if e.status == 403:
        return '권한이 없거나 요청 한도를 넘었어요 (403). 토큰의 Contents 읽기/쓰기 권한을 확인하세요.'
    if e.status in (409, 422):
        return f'다른 기기와 동시에 고치는 충돌이 계속돼요 ({e.status}). 잠시 뒤 다시 실행하세요.'
    return f'GitHub 오류 {e.status}: {e}'


def main(argv=None):
    setup_output()
    args = build_parser().parse_args(argv)
    try:
        cfg = load_config(args)
        return args.func(cfg, args)
    except DashError as e:
        print(f'dash_update: {e}', file=sys.stderr)
        return e.code
    except HttpError as e:
        print(f'dash_update: {error_text(e)}', file=sys.stderr)
        return 1
    except (urllib.error.URLError, OSError) as e:
        reason = getattr(e, 'reason', e)
        print(f'dash_update: 네트워크 연결 실패 ({reason}). 인터넷 연결을 확인하세요.', file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    except Exception as e:  # AI 작업 흐름을 망가뜨리지 않도록 traceback 대신 한 줄
        print(f'dash_update: 예상하지 못한 오류 ({type(e).__name__}: {e})', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())

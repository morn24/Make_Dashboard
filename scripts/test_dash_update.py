"""dash_update.py 테스트. GitHub API는 가짜(FakeGitHub)로 대체한다.

실행: python -m unittest scripts/test_dash_update.py
"""

import base64
import contextlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dash_update  # noqa: E402


class FakeResponse:
    def __init__(self, status, body):
        self.status = status
        self._body = json.dumps(body).encode('utf-8')

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FakeGitHub:
    """Contents API 흉내. 파일 내용과 sha를 들고 있고, sha가 맞지 않으면 409를 돌려준다."""

    def __init__(self):
        self.files = {}  # path -> (text, sha)
        self.version = 0
        self.puts = []  # PUT 요청 본문 기록
        self.before_put = None  # PUT 직전에 한 번 실행할 함수 (다른 기기의 동시 수정 흉내)
        self.fail_with = None  # 모든 요청에 이 예외

    def set_json(self, path, data):
        self.version += 1
        self.files[path] = (json.dumps(data, ensure_ascii=False, indent=2) + '\n', f'sha{self.version}')

    def get_json(self, path):
        return json.loads(self.files[path][0])

    def _error(self, url, code, message):
        return urllib.error.HTTPError(url, code, message, {}, io.BytesIO(json.dumps({'message': message}).encode()))

    def urlopen(self, req, timeout=None):
        if self.fail_with:
            raise self.fail_with
        assert req.get_header('Authorization') == 'Bearer test-token'
        url = req.full_url
        path = url.split('/contents/', 1)[1].split('?', 1)[0]
        if req.get_method() == 'GET':
            if path not in self.files:
                raise self._error(url, 404, 'Not Found')
            text, sha = self.files[path]
            b64 = base64.b64encode(text.encode('utf-8')).decode()
            # GitHub는 base64를 60자마다 줄바꿈해서 준다
            b64 = '\n'.join(b64[i:i + 60] for i in range(0, len(b64), 60))
            return FakeResponse(200, {'content': b64, 'sha': sha, 'encoding': 'base64'})
        body = json.loads(req.data.decode('utf-8'))
        if self.before_put:
            hook, self.before_put = self.before_put, None
            hook()
        self.puts.append(body)
        current = self.files.get(path)
        if current and body.get('sha') != current[1]:
            raise self._error(url, 409, f'{path} does not match')
        if not current and body.get('sha'):
            raise self._error(url, 409, 'file was deleted')
        self.version += 1
        text = base64.b64decode(body['content']).decode('utf-8')
        self.files[path] = (text, f'sha{self.version}')
        return FakeResponse(200 if current else 201, {'content': {'sha': f'sha{self.version}'}})


@contextlib.contextmanager
def chdir(path):  # contextlib.chdir는 3.11부터라 직접 만든다
    old = os.getcwd()
    os.chdir(path)
    try:
        yield
    finally:
        os.chdir(old)


PROJECTS = 'data/projects.json'
TODOS = 'data/todos.json'


def sample_project(pid, **kw):
    p = {
        'id': pid, 'name': pid, 'tool': 'codex', 'status': 'paused', 'progress': 30,
        'current': '예전 작업', 'next': ['예전 과제'], 'summary': '예전 요약',
        'updatedAt': '2026-10-01T09:00:00+09:00',
    }
    p.update(kw)
    return p


class DashUpdateTest(unittest.TestCase):
    def setUp(self):
        self.gh = FakeGitHub()
        self.tmp = tempfile.TemporaryDirectory()
        patches = [
            mock.patch.object(dash_update.urllib.request, 'urlopen', self.gh.urlopen),
            mock.patch.object(dash_update.time, 'sleep', lambda s: None),
            mock.patch.dict(os.environ, {'DASH_TOKEN': 'test-token'}, clear=False),
            mock.patch.object(dash_update, 'token_path', lambda: Path(self.tmp.name) / 'token'),
        ]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        for key in ('DASH_REPO', 'DASH_BRANCH'):
            os.environ.pop(key, None)
        self.addCleanup(self.tmp.cleanup)

    def run_cli(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = dash_update.main(list(argv))
        return code, out.getvalue(), err.getvalue()

    # ---------- set ----------

    def test_set_creates_new_project_at_front(self):
        self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [sample_project('old')]})
        code, out, err = self.run_cli(
            'set', '--id', 'Make_Dashboard', '--tool', 'claude-code', '--progress', '60',
            '--current', '스크립트 작성', '--next', '테스트 작성', '--next', '문서 작성',
        )
        self.assertEqual(code, 0, err)
        data = self.gh.get_json(PROJECTS)
        self.assertEqual([p['id'] for p in data['projects']], ['make-dashboard', 'old'])
        p = data['projects'][0]
        self.assertEqual(p['name'], 'Make_Dashboard')
        self.assertEqual(p['tool'], 'claude-code')
        self.assertEqual(p['status'], 'active')
        self.assertEqual(p['progress'], 60)
        self.assertEqual(p['next'], ['테스트 작성', '문서 작성'])
        self.assertRegex(p['updatedAt'], r'^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d[+-]\d\d:\d\d$')
        self.assertEqual(data['updatedAt'], p['updatedAt'])
        self.assertEqual(self.gh.puts[-1]['message'], 'project: make-dashboard 60% - 스크립트 작성')
        self.assertEqual(self.gh.puts[-1]['branch'], 'main')

    def test_set_creates_file_when_missing(self):
        code, _, err = self.run_cli('set', '--id', 'new', '--progress', '5')
        self.assertEqual(code, 0, err)
        self.assertNotIn('sha', self.gh.puts[-1])
        data = self.gh.get_json(PROJECTS)
        self.assertEqual(data['version'], 1)
        self.assertEqual(data['projects'][0]['id'], 'new')

    def test_set_updates_only_given_fields_and_keeps_position(self):
        self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [
            sample_project('a'), sample_project('b'), sample_project('c'),
        ]})
        code, _, err = self.run_cli('set', '--id', 'b', '--progress', '45')
        self.assertEqual(code, 0, err)
        data = self.gh.get_json(PROJECTS)
        self.assertEqual([p['id'] for p in data['projects']], ['a', 'b', 'c'])
        b = data['projects'][1]
        self.assertEqual(b['progress'], 45)
        self.assertEqual(b['status'], 'active')  # --status 생략 → active
        self.assertEqual(b['tool'], 'codex')
        self.assertEqual(b['current'], '예전 작업')
        self.assertEqual(b['next'], ['예전 과제'])
        self.assertEqual(b['summary'], '예전 요약')
        self.assertNotEqual(b['updatedAt'], '2026-10-01T09:00:00+09:00')
        self.assertEqual(data['projects'][0]['updatedAt'], '2026-10-01T09:00:00+09:00')

    def test_set_next_replaces_whole_list(self):
        self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [
            sample_project('a', next=['하나', '둘', '셋']),
        ]})
        self.run_cli('set', '--id', 'a', '--next', '새 과제')
        self.assertEqual(self.gh.get_json(PROJECTS)['projects'][0]['next'], ['새 과제'])

    def test_next_add_and_done_merge_with_existing(self):
        self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [
            sample_project('a', next=['다른 세션 과제', '로그인 API 테스트 작성']),
        ]})
        code, out, err = self.run_cli('set', '--id', 'a', '--next-done', '로그인  api 테스트 작성',
                                      '--next-add', '배포 스크립트 작성', '--next-add', '다른 세션 과제')
        self.assertEqual(code, 0, err)
        self.assertEqual(self.gh.get_json(PROJECTS)['projects'][0]['next'], ['다른 세션 과제', '배포 스크립트 작성'])

    def test_user_checked_is_kept_and_shown(self):
        p = sample_project('a', next=['결제 연결', '문서 정리'])
        p['checked'] = [{'text': '결제 연결', 'at': '2026-10-30T10:00:00+09:00'}]
        self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [p]})
        code, out, _ = self.run_cli('show', '--id', 'a')
        self.assertEqual(code, 0)
        self.assertIn('사용자 확인', out)
        self.assertIn('결제 연결', out)
        # 관계없는 갱신은 체크를 보존한다
        self.run_cli('set', '--id', 'a', '--progress', '40')
        self.assertEqual(self.gh.get_json(PROJECTS)['projects'][0]['checked'][0]['text'], '결제 연결')

    def test_next_done_and_readd_clear_user_checked(self):
        p = sample_project('a', next=['결제 연결', '문서 정리', '배포'])
        p['checked'] = [{'text': '결제 연결', 'at': 'x'}, {'text': '문서 정리', 'at': 'x'}, {'text': '이미 빠진 과제', 'at': 'x'}]
        self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [p]})
        # 결제 연결: 확인 → 완료 처리 / 이미 빠진 과제: next에 없어도 '없음' 경고 없이 정리
        code, out, err = self.run_cli('set', '--id', 'a', '--next-done', '결제 연결', '--next-done', '이미 빠진 과제')
        self.assertEqual(code, 0, err)
        self.assertNotIn('뺄 수 없었어요', out)
        got = self.gh.get_json(PROJECTS)['projects'][0]
        self.assertEqual(got['next'], ['문서 정리', '배포'])
        self.assertEqual([c['text'] for c in got['checked']], ['문서 정리'])
        # 문서 정리: 확인해 보니 미비 → 다시 넣으면 체크가 풀린다
        self.run_cli('set', '--id', 'a', '--next-add', '문서 정리')
        got = self.gh.get_json(PROJECTS)['projects'][0]
        self.assertEqual(got['next'], ['문서 정리', '배포'])
        self.assertNotIn('checked', got)

    def test_clear_checked(self):
        p = sample_project('a')
        p['checked'] = [{'text': '예전 과제', 'at': 'x'}]
        self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [p]})
        self.run_cli('set', '--id', 'a', '--clear-checked')
        self.assertNotIn('checked', self.gh.get_json(PROJECTS)['projects'][0])

    def test_next_merge_keeps_item_added_by_other_session_during_conflict(self):
        self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [sample_project('a', next=['과제1'])]})
        # 이 세션이 쓰기 직전에 Codex 세션이 과제2를 더한 상황
        self.gh.before_put = lambda: self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [
            sample_project('a', next=['과제1', 'Codex 과제2']),
        ]})
        code, _, err = self.run_cli('set', '--id', 'a', '--next-done', '과제1', '--next-add', '과제3')
        self.assertEqual(code, 0, err)
        self.assertEqual(self.gh.get_json(PROJECTS)['projects'][0]['next'], ['Codex 과제2', '과제3'])

    def test_next_done_missing_item_is_reported(self):
        self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [sample_project('a')]})
        code, out, _ = self.run_cli('set', '--id', 'a', '--next-done', '없는 과제')
        self.assertEqual(code, 0)
        self.assertIn('없는 과제', out)
        self.assertEqual(self.gh.get_json(PROJECTS)['projects'][0]['next'], ['예전 과제'])

    def test_next_replace_and_merge_cannot_mix(self):
        code, _, err = self.run_cli('set', '--id', 'a', '--next', 'x', '--next-add', 'y')
        self.assertEqual(code, 2)
        self.assertEqual(self.gh.puts, [])

    def test_progress_not_lowered_unless_scope_grew(self):
        self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [sample_project('a', progress=60)]})
        code, out, _ = self.run_cli('set', '--id', 'a', '--progress', '40', '--current', '새 단계')
        self.assertEqual(code, 0)
        p = self.gh.get_json(PROJECTS)['projects'][0]
        self.assertEqual((p['progress'], p['current']), (60, '새 단계'))
        self.assertIn('--scope-grew', out)
        self.run_cli('set', '--id', 'a', '--progress', '40', '--scope-grew')
        self.assertEqual(self.gh.get_json(PROJECTS)['projects'][0]['progress'], 40)

    def test_set_status(self):
        self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [sample_project('a', status='active')]})
        self.run_cli('set', '--id', 'a', '--status', 'done')
        self.assertEqual(self.gh.get_json(PROJECTS)['projects'][0]['status'], 'done')

    def test_id_defaults_to_folder_name(self):
        with mock.patch.object(dash_update, 'project_folder', lambda: Path('/x/My Project_v2')):
            code, _, err = self.run_cli('set', '--progress', '10')
        self.assertEqual(code, 0, err)
        p = self.gh.get_json(PROJECTS)['projects'][0]
        self.assertEqual(p['id'], 'my-project-v2')
        self.assertEqual(p['name'], 'My Project_v2')

    def test_project_folder_reads_korean_git_path(self):
        done = mock.Mock(returncode=0, stdout='G:/내 드라이브/Obsidian/PMR-Wiki\n')
        with mock.patch.object(dash_update.subprocess, 'run', return_value=done) as run:
            self.assertEqual(dash_update.project_folder().name, 'PMR-Wiki')
        self.assertEqual(run.call_args.kwargs['encoding'], 'utf-8')

    @unittest.skipUnless(shutil.which('git'), 'git 없음')
    def test_project_folder_uses_main_repo_name_in_worktree(self):
        root = Path(self.tmp.name).resolve()
        repo, wt = root / 'Main_Repo', root / 'wt-feature'
        (repo / 'sub').mkdir(parents=True)
        git = ['git', '-c', 'user.name=t', '-c', 'user.email=t@t']
        for cmd in (['init', '-q'], ['commit', '-q', '--allow-empty', '-m', 'init'],
                    ['worktree', 'add', '-q', str(wt)]):
            subprocess.run(git + cmd, cwd=repo, check=True, capture_output=True)
        (wt / 'deep').mkdir()
        for cwd in (repo, repo / 'sub', wt, wt / 'deep'):
            with self.subTest(cwd=cwd), chdir(cwd):
                self.assertEqual(dash_update.project_folder().name, 'Main_Repo')

    def test_project_folder_outside_git_is_current_folder(self):
        folder = Path(self.tmp.name).resolve() / 'Obsidian Note'
        folder.mkdir()
        with chdir(folder), mock.patch.object(dash_update, 'git_rev_parse', lambda flag: None):
            self.assertEqual(dash_update.project_folder().name, 'Obsidian Note')

    def test_conflict_then_retry_succeeds(self):
        self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [sample_project('a')]})
        # 이 스크립트가 쓰기 직전에 폰이 프로젝트 b를 추가한 상황
        self.gh.before_put = lambda: self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [
            sample_project('a'), sample_project('phone'),
        ]})
        code, _, err = self.run_cli('set', '--id', 'a', '--progress', '70')
        self.assertEqual(code, 0, err)
        self.assertEqual(len(self.gh.puts), 2)
        data = self.gh.get_json(PROJECTS)
        self.assertEqual([p['id'] for p in data['projects']], ['a', 'phone'])  # 폰의 변경을 덮어쓰지 않음
        self.assertEqual(data['projects'][0]['progress'], 70)

    def test_conflict_gives_up_after_retries(self):
        self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': []})
        real_urlopen = self.gh.urlopen

        def always_conflict(req, timeout=None):
            if req.get_method() == 'PUT':
                self.gh.puts.append(None)
                raise self.gh._error(req.full_url, 409, 'conflict')
            return real_urlopen(req, timeout)

        with mock.patch.object(dash_update.urllib.request, 'urlopen', always_conflict):
            code, _, err = self.run_cli('set', '--id', 'a')
        self.assertEqual(code, 1)
        self.assertEqual(len(self.gh.puts), 1 + dash_update.MAX_RETRIES)
        self.assertIn('충돌', err)
        self.assertNotIn('Traceback', err)

    # ---------- 입력 검사 ----------

    def test_invalid_progress_and_tool_rejected(self):
        for argv in (['--progress', '101'], ['--progress', 'abc'], ['--tool', 'vim'], ['--status', 'x']):
            with self.subTest(argv=argv), self.assertRaises(SystemExit) as cm:
                with contextlib.redirect_stderr(io.StringIO()):
                    dash_update.main(['set', '--id', 'a', *argv])
            self.assertEqual(cm.exception.code, 2)
        self.assertEqual(self.gh.puts, [])

    # ---------- 인코딩 ----------

    def test_korean_base64_round_trip(self):
        text = '한글 제목 ✅ 이모지와 "따옴표"\n'
        self.assertEqual(dash_update.b64decode(dash_update.b64encode(text)), text)
        self.run_cli('set', '--id', 'a', '--current', '로그인 API 테스트 작성 🚀')
        raw = base64.b64decode(self.gh.puts[-1]['content']).decode('utf-8')
        self.assertIn('로그인 API 테스트 작성 🚀', raw)  # \uXXXX 로 바뀌지 않고 그대로 저장
        self.assertTrue(raw.endswith('}\n'))
        self.assertEqual(self.gh.get_json(PROJECTS)['projects'][0]['current'], '로그인 API 테스트 작성 🚀')

    # ---------- 토큰 ----------

    def test_missing_token_shows_help(self):
        os.environ.pop('DASH_TOKEN')
        code, out, err = self.run_cli('show')
        self.assertEqual(code, 2)
        self.assertIn('토큰', err)
        self.assertIn('.dashboard', err)
        self.assertEqual(self.gh.puts, [])

    def test_token_from_file(self):
        os.environ.pop('DASH_TOKEN')
        (Path(self.tmp.name) / 'token').write_text('\ufefftest-token\n', encoding='utf-8')
        code, _, err = self.run_cli('set', '--id', 'a')
        self.assertEqual(code, 0, err)

    def test_token_never_printed_on_error(self):
        os.environ['DASH_TOKEN'] = 'secret-xyz'
        self.gh.fail_with = urllib.error.HTTPError('u', 401, 'Bad credentials', {}, io.BytesIO(b'{}'))
        code, out, err = self.run_cli('set', '--id', 'a')
        self.assertEqual(code, 1)
        self.assertIn('401', err)
        self.assertNotIn('secret-xyz', out + err)

    def test_network_error_is_short(self):
        self.gh.fail_with = urllib.error.URLError('getaddrinfo failed')
        code, _, err = self.run_cli('set', '--id', 'a')
        self.assertEqual(code, 1)
        self.assertIn('네트워크', err)
        self.assertNotIn('Traceback', err)

    # ---------- show / remove / todo-add ----------

    def test_show(self):
        self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [sample_project('a')]})
        code, out, _ = self.run_cli('show', '--id', 'a')
        self.assertEqual(code, 0)
        self.assertIn('30%', out)
        self.assertIn('예전 과제', out)
        code, out, _ = self.run_cli('show', '--all')
        self.assertEqual(code, 0)
        self.assertIn('프로젝트 1개', out)

    def test_remove(self):
        self.gh.set_json(PROJECTS, {'version': 1, 'updatedAt': None, 'projects': [sample_project('a'), sample_project('b')]})
        code, _, err = self.run_cli('remove', '--id', 'a', '--yes')
        self.assertEqual(code, 0, err)
        self.assertEqual([p['id'] for p in self.gh.get_json(PROJECTS)['projects']], ['b'])
        code, out, _ = self.run_cli('remove', '--id', 'zzz', '--yes')
        self.assertEqual(code, 1)
        self.assertEqual(len(self.gh.puts), 1)  # 없는 프로젝트는 저장하지 않음

    def test_todo_add_appends_to_end(self):
        self.gh.set_json(TODOS, {'version': 1, 'updatedAt': None, 'items': [
            {'id': 't_1', 'title': '기존', 'repeat': 'none', 'doneOn': None, 'createdAt': '2026-10-01T09:00:00+09:00'},
        ]})
        code, _, err = self.run_cli('todo-add', '아침 운동', '--daily')
        self.assertEqual(code, 0, err)
        items = self.gh.get_json(TODOS)['items']
        self.assertEqual([i['title'] for i in items], ['기존', '아침 운동'])
        new = items[1]
        self.assertEqual(new['repeat'], 'daily')
        self.assertIsNone(new['doneOn'])
        self.assertRegex(new['id'], r'^t_[0-9a-z]+$')


if __name__ == '__main__':
    unittest.main()

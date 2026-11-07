"""검증용 가짜 GitHub Contents API 서버 (익명 데이터만, 실제 morn24/dashboard-data와 무관).

에뮬레이터의 디버그 앱이 이 서버로 읽기/쓰기를 하도록 해서, 위젯이 실제로 저장하는 JSON을 확인한다.
  python scripts/fake_github_server.py --port 8765 --data-dir <빈 폴더>
에뮬레이터에서는 http://10.0.2.2:8765/ 로 접속한다 (docs/android-widget-parity-verification.md 참고).

GitHub와 같은 규칙: GET은 base64 내용과 sha를 주고, PUT은 읽을 때 받은 sha가 맞아야 하며 다르면 409.
제어용 주소(검증 전용):
  POST /__control/set?path=data/x.json   본문으로 파일을 바꾼다(다른 기기가 고친 것처럼 sha도 바뀜)
  POST /__control/delete?path=data/x.json 파일을 지운다(404가 되게)
  POST /__control/conflict?n=1            다음 PUT n번을 409로 거절
  POST /__control/reseed                  익명 기본 데이터를 다시 만든다
  GET  /__control/file?path=...           현재 파일 내용
  GET  /__control/log                     요청·저장 기록(JSON 줄)
"""
import argparse
import base64
import hashlib
import json
import os
import sys
import threading
from datetime import date, datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

LOCK = threading.Lock()
STATE = {"conflicts": 0, "counter": 0, "log": []}
FILES = {}  # path -> (text, sha)


def now_iso():
    return datetime.now().astimezone().replace(microsecond=0).isoformat()


def sha_of(text):
    STATE["counter"] += 1
    return hashlib.sha1((text + str(STATE["counter"])).encode("utf-8")).hexdigest()


def put_file(path, text):
    FILES[path] = (text, sha_of(text))


def dump(obj):
    return json.dumps(obj, ensure_ascii=False, indent=2) + "\n"


def seed():
    today = date.today()
    d = lambda n: (today + timedelta(days=n)).isoformat()
    dow = (today.weekday() + 1) % 7  # JS getDay: 0=일요일
    rest_day = (dow + 1) % 7
    created = now_iso()

    def item(i, title, repeat, **kw):
        base = {"id": f"fx_{i:02d}", "title": title, "repeat": repeat}
        base.update(kw)
        base.setdefault("doneOn", None)
        base.setdefault("createdAt", created)
        return base

    todos = {
        "version": 1,
        "updatedAt": created,
        "items": [
            item(1, "아침 운동 30분", "daily", history=[d(-3), d(-2), d(-1)], doneOn=d(-1)),
            item(2, "영어 단어 (오늘은 쉬는 요일)", "weekly", days=[rest_day]),
            item(3, "요일 반복: 오늘 해야 함", "weekly", days=[dow, (dow + 3) % 7], time="09:30"),
            item(4, "보고서 제출", "none", date=d(-2)),
            item(5, "내일 병원 (예정)", "none", date=d(1), time="14:00"),
            item(6, "회의 준비", "none", date=d(0), time="15:00", gtask="g-123", gsync=["회의 준비", d(0), "15:00", False]),
            item(7, "영어 듣기 (오늘 완료)", "daily", doneOn=d(0), history=[d(-3), d(-2), d(-1), d(0)]),
            item(8, "평일 약 먹기", "weekly", days=[1, 2, 3, 4, 5], doneOn=d(-1), history=[d(-1)]) if 1 <= dow <= 5 else item(8, "주말 산책", "weekly", days=[0, 6]),
            item(9, "다음 주 세미나 자료 (예정)", "none", date=d(5)),
            item(10, "메모 정리", "none"),
        ],
        "futureTopLevelKey": {"keep": True},
    }
    projects = {
        "version": 1,
        "updatedAt": created,
        "projects": [
            {"id": "alpha", "name": "프로젝트 알파", "status": "active", "progress": 40, "current": "익명 검증용",
             "next": ["알파 다음 과제 1", "알파 다음 과제 2", "알파 다음 과제 3"],
             "checked": [{"text": "알파 다음 과제 1", "at": created}], "summary": "익명", "updatedAt": created},
            {"id": "beta", "name": "프로젝트 베타", "status": "active", "progress": 75, "current": "익명",
             "next": ["베타 확인 과제"], "updatedAt": created, "futureKey": [1, 2]},
            {"id": "gamma", "name": "프로젝트 감마", "status": "paused", "progress": 10, "next": ["감마"], "updatedAt": created},
        ],
    }
    tz = datetime.now().astimezone().tzinfo
    def at(h, m):
        return datetime(today.year, today.month, today.day, h, m, tzinfo=tz).isoformat()
    calendar = {
        "version": 1, "updatedAt": created, "timeZone": "Asia/Seoul", "rangeStart": d(0), "rangeEnd": d(7),
        "events": [
            {"id": "e1", "calendar": "익명", "color": "#E53935", "title": "팀 회의", "allDay": False, "start": at(10, 0), "end": at(11, 0)},
            {"id": "e2", "calendar": "익명", "color": "#1E88E5", "title": "점심 약속", "allDay": False, "start": at(12, 30), "end": at(13, 30)},
            {"id": "e3", "calendar": "익명", "color": "#43A047", "title": "종일 일정", "allDay": True, "start": d(0), "end": d(1)},
        ],
    }
    pmr = {
        "version": 1, "generatedAt": created,
        "jobs": [{"id": "dtv-ingest", "name": "DTV", "state": "ok"}, {"id": "weekly-lint", "name": "lint", "state": "error"}],
        "decisions": {"need": 2, "auto": 1, "items": []},
    }
    for path, doc in [("data/todos.json", todos), ("data/projects.json", projects), ("data/calendar.json", calendar), ("data/pmr-wiki.json", pmr)]:
        put_file(path, dump(doc))


class Handler(BaseHTTPRequestHandler):
    server_version = "FakeGitHub/1"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, body=b"", ctype="application/json"):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _record(self, **kw):
        kw["at"] = now_iso()
        STATE["log"].append(kw)
        print(json.dumps(kw, ensure_ascii=False), flush=True)

    def _file_path(self, url):
        parts = url.path.strip("/").split("/")
        # /repos/{owner}/{repo}/contents/{path...}
        if len(parts) >= 5 and parts[0] == "repos" and parts[3] == "contents":
            return "/".join(parts[4:])
        return None

    def do_GET(self):
        url = urlparse(self.path)
        with LOCK:
            if url.path == "/__control/log":
                return self._send(200, dump(STATE["log"]))
            if url.path == "/__control/file":
                path = parse_qs(url.query).get("path", [""])[0]
                if path not in FILES:
                    return self._send(404, "{}")
                return self._send(200, FILES[path][0], "text/plain; charset=utf-8")
            path = self._file_path(url)
            if path is None:
                return self._send(404, "{}")
            if path not in FILES:
                self._record(method="GET", path=path, status=404)
                return self._send(404, json.dumps({"message": "Not Found"}))
            text, sha = FILES[path]
            self._record(method="GET", path=path, status=200, sha=sha[:8])
            content = base64.encodebytes(text.encode("utf-8")).decode("ascii")
            return self._send(200, json.dumps({"sha": sha, "content": content, "encoding": "base64"}))

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def do_PUT(self):
        url = urlparse(self.path)
        with LOCK:
            path = self._file_path(url)
            body = json.loads(self._body().decode("utf-8") or "{}")
            if path is None:
                return self._send(404, "{}")
            if STATE["conflicts"] > 0:
                STATE["conflicts"] -= 1
                self._record(method="PUT", path=path, status=409, reason="injected conflict")
                return self._send(409, json.dumps({"message": "conflict"}))
            current = FILES.get(path)
            if current is not None and body.get("sha") != current[1]:
                self._record(method="PUT", path=path, status=409, reason="stale sha")
                return self._send(409, json.dumps({"message": "sha does not match"}))
            text = base64.b64decode(body["content"]).decode("utf-8")
            put_file(path, text)
            sha = FILES[path][1]
            self._record(method="PUT", path=path, status=200, message=body.get("message"), sha=sha[:8])
            if DATA_DIR:
                os.makedirs(DATA_DIR, exist_ok=True)
                name = path.replace("/", "_")
                with open(os.path.join(DATA_DIR, name), "w", encoding="utf-8") as f:
                    f.write(text)
            return self._send(200, json.dumps({"content": {"sha": sha}}))

    def do_POST(self):
        url = urlparse(self.path)
        q = parse_qs(url.query)
        with LOCK:
            if url.path == "/__control/set":
                path = q["path"][0]
                put_file(path, self._body().decode("utf-8"))
                self._record(control="set", path=path)
                return self._send(200, "{}")
            if url.path == "/__control/delete":
                FILES.pop(q["path"][0], None)
                self._record(control="delete", path=q["path"][0])
                return self._send(200, "{}")
            if url.path == "/__control/conflict":
                STATE["conflicts"] = int(q.get("n", ["1"])[0])
                self._record(control="conflict", n=STATE["conflicts"])
                return self._send(200, "{}")
            if url.path == "/__control/reseed":
                seed()
                self._record(control="reseed")
                return self._send(200, "{}")
        return self._send(404, "{}")


DATA_DIR = None


def main():
    global DATA_DIR
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--data-dir", default=None, help="저장된 파일을 이 폴더에도 복사해 둔다")
    args = ap.parse_args()
    DATA_DIR = args.data_dir
    seed()
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"fake github on :{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    sys.exit(main())

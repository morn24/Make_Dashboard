'use strict';

// 데이터 형식은 docs/data-format.md 참고
const FILES = { todos: 'data/todos.json', projects: 'data/projects.json' };
const EMPTY = {
  todos: () => ({ version: 1, updatedAt: null, items: [] }),
  projects: () => ({ version: 1, updatedAt: null, projects: [] }),
};
const CFG_KEY = 'dash.cfg';
const CACHE_KEY = 'dash.cache';
const DEFAULT_CFG = { owner: 'morn24', repo: 'dashboard-data', branch: 'main', token: '' };
const REFRESH_MS = 60_000;
// 방금 저장한 파일을 GitHub가 잠깐 옛 버전(저장 직전 sha)으로 돌려줄 수 있어서, 이 시간 동안은 그 옛 버전을 무시한다
const STALE_GUARD_MS = 90_000;

const state = {
  todos: EMPTY.todos(),
  projects: EMPTY.projects(),
  demo: false,
  lastWrite: {}, // file -> { oldSha, at }
  busy: 0,
};

const $ = (id) => document.getElementById(id);

// ---------- 저장소(localStorage) ----------

function load(key) {
  try { return JSON.parse(localStorage.getItem(key)); } catch { return null; }
}
function save(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* 저장 불가 환경이면 무시 */ }
}
function remove(key) {
  try { localStorage.removeItem(key); } catch { /* 무시 */ }
}
function getCfg() {
  const c = load(CFG_KEY);
  return c && c.token ? c : null;
}

// ---------- 날짜 ----------

function localDate(d = new Date()) {
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
function isoNow() {
  const d = new Date();
  const off = -d.getTimezoneOffset();
  const sign = off >= 0 ? '+' : '-';
  const p = (n) => String(Math.floor(Math.abs(n))).padStart(2, '0');
  return `${localDate(d)}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}${sign}${p(off / 60)}:${p(off % 60)}`;
}
function timeAgo(iso) {
  if (!iso) return '';
  const diff = (Date.now() - new Date(iso).getTime()) / 1000;
  if (Number.isNaN(diff)) return '';
  if (diff < 60) return '방금 전';
  if (diff < 3600) return `${Math.floor(diff / 60)}분 전`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}시간 전`;
  if (diff < 86400 * 7) return `${Math.floor(diff / 86400)}일 전`;
  return iso.slice(0, 10);
}

// ---------- GitHub API ----------

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

function b64encode(text) {
  const bytes = new TextEncoder().encode(text);
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}
function b64decode(b64) {
  const bin = atob(b64.replace(/\s/g, ''));
  return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
}

async function api(cfg, path, { method = 'GET', body } = {}) {
  const base = `https://api.github.com/repos/${encodeURIComponent(cfg.owner)}/${encodeURIComponent(cfg.repo)}/contents/${path}`;
  const url = method === 'GET' ? `${base}?ref=${encodeURIComponent(cfg.branch)}` : base;
  const res = await fetch(url, {
    method,
    cache: 'no-store',
    headers: {
      Accept: 'application/vnd.github+json',
      Authorization: `Bearer ${cfg.token}`,
      'X-GitHub-Api-Version': '2022-11-28',
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok && res.status !== 404) {
    let msg = res.statusText;
    try { msg = (await res.json()).message || msg; } catch { /* 본문 없음 */ }
    throw new HttpError(res.status, msg);
  }
  return res;
}

async function readFile(cfg, file) {
  const res = await api(cfg, FILES[file]);
  if (res.status === 404) return { data: EMPTY[file](), sha: null };
  const j = await res.json();
  return { data: JSON.parse(b64decode(j.content)), sha: j.sha };
}

async function writeFile(cfg, file, data, sha, message) {
  const res = await api(cfg, FILES[file], {
    method: 'PUT',
    body: {
      message,
      content: b64encode(JSON.stringify(data, null, 2) + '\n'),
      branch: cfg.branch,
      ...(sha ? { sha } : {}),
    },
  });
  if (res.status === 404) throw new HttpError(404, '저장소나 브랜치를 찾을 수 없어요');
}

// 최신본을 읽고 → 변경을 적용하고 → sha와 함께 저장. 충돌(409/422)이면 다시 시도.
async function mutate(file, change, message) {
  if (state.demo) {
    const next = change(structuredClone(state[file]));
    next.updatedAt = isoNow();
    return next;
  }
  const cfg = getCfg();
  for (let attempt = 0; ; attempt++) {
    const { data, sha } = await readFile(cfg, file);
    const next = change(data);
    next.updatedAt = isoNow();
    try {
      await writeFile(cfg, file, next, sha, message);
      state.lastWrite[file] = { oldSha: sha, at: Date.now() };
      return next;
    } catch (e) {
      const conflict = e.status === 409 || e.status === 422;
      if (!conflict || attempt >= 3) throw e;
      await new Promise((r) => setTimeout(r, 600 * (attempt + 1)));
    }
  }
}

// ---------- 데이터 불러오기 ----------

async function refresh({ quiet = false } = {}) {
  const cfg = getCfg();
  state.demo = !cfg;
  $('demo-banner').hidden = !state.demo;
  setBusy(+1);
  try {
    if (state.demo) {
      const [t, p] = await Promise.all(
        ['todos', 'projects'].map((f) => fetch(`demo/${f}.json`, { cache: 'no-store' }).then((r) => r.json())),
      );
      state.todos = t;
      state.projects = p;
      setSync('데모 데이터');
    } else {
      const [t, p] = await Promise.all([readFile(cfg, 'todos'), readFile(cfg, 'projects')]);
      for (const [file, got] of [['todos', t], ['projects', p]]) {
        const w = state.lastWrite[file];
        const stale = w && Date.now() - w.at < STALE_GUARD_MS && got.sha === w.oldSha;
        if (!stale) state[file] = got.data;
      }
      save(CACHE_KEY, { todos: state.todos, projects: state.projects, at: Date.now() });
      setSync(`동기화됨 · ${new Date().toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })}`);
    }
  } catch (e) {
    setSync(errorText(e), true);
    if (!quiet) toast(errorText(e));
  } finally {
    setBusy(-1);
    render();
  }
}

function errorText(e) {
  if (e instanceof HttpError) {
    if (e.status === 401) return '토큰이 올바르지 않아요 (401)';
    if (e.status === 403) return '권한이 없거나 요청 한도 초과 (403)';
    if (e.status === 404) return '저장소를 찾을 수 없어요 (404)';
    return `GitHub 오류 ${e.status}: ${e.message}`;
  }
  if (!navigator.onLine) return '오프라인이에요. 마지막 저장본을 보여줘요';
  return `연결 실패: ${e.message}`;
}

function setSync(text, isErr = false) {
  const el = $('sync');
  el.textContent = text;
  el.classList.toggle('err', isErr);
}
function setBusy(delta) {
  state.busy += delta;
  $('refresh').classList.toggle('spin', state.busy > 0);
}

let toastTimer;
function toast(text) {
  const el = $('toast');
  el.textContent = text;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 3000);
}

// ---------- 할 일 규칙 ----------

function isDone(item, today) {
  return item.repeat === 'daily' ? item.doneOn === today : item.doneOn != null;
}
function isVisible(item, today) {
  return item.repeat === 'daily' || item.doneOn == null || item.doneOn >= today;
}
function newId() {
  return 't_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}

// 화면을 먼저 바꾸고(낙관적 반영), 저장이 실패하면 서버 상태로 되돌린다
async function changeTodos(change, message) {
  const before = state.todos;
  state.todos = change(structuredClone(before));
  render();
  setBusy(+1);
  try {
    state.todos = await mutate('todos', change, message);
    if (!state.demo) setSync(`저장됨 · ${new Date().toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })}`);
  } catch (e) {
    state.todos = before;
    toast(`저장 실패: ${errorText(e)}`);
    refresh({ quiet: true });
  } finally {
    setBusy(-1);
    render();
  }
}

function toggleTodo(id) {
  const today = localDate();
  const target = state.todos.items.find((i) => i.id === id);
  const makeDone = target && !isDone(target, today);
  changeTodos((d) => {
    const it = d.items.find((i) => i.id === id);
    if (it) it.doneOn = makeDone ? today : null;
    return d;
  }, `todo: ${makeDone ? '완료' : '완료 취소'} - ${target ? target.title : id}`);
}

function addTodo(title, daily) {
  const item = { id: newId(), title, repeat: daily ? 'daily' : 'none', doneOn: null, createdAt: isoNow() };
  changeTodos((d) => { d.items.push(item); return d; }, `todo: 추가 - ${title}`);
}

function deleteTodo(id) {
  const target = state.todos.items.find((i) => i.id === id);
  if (!target || !confirm(`"${target.title}" 을(를) 삭제할까요?`)) return;
  changeTodos((d) => { d.items = d.items.filter((i) => i.id !== id); return d; }, `todo: 삭제 - ${target.title}`);
}

function cleanDone() {
  const today = localDate();
  changeTodos((d) => {
    d.items = d.items.filter((i) => isVisible(i, today));
    return d;
  }, 'todo: 지난 완료 항목 정리');
}

// ---------- 그리기 ----------

function render() {
  const today = localDate();
  const now = new Date();
  $('today').textContent = now.toLocaleDateString('ko-KR', { month: 'long', day: 'numeric', weekday: 'short' });

  // 할 일
  const all = state.todos.items || [];
  const visible = all.filter((i) => isVisible(i, today));
  // 미완료 먼저, 그 안에서 매일 반복 먼저
  visible.sort((a, b) => (isDone(a, today) - isDone(b, today)) || ((b.repeat === 'daily') - (a.repeat === 'daily')));
  const doneCount = visible.filter((i) => isDone(i, today)).length;
  $('todo-count').textContent = visible.length ? `${doneCount} / ${visible.length}` : '';
  const pct = visible.length ? Math.round((doneCount / visible.length) * 100) : 0;
  const bar = $('todo-bar');
  bar.style.width = `${pct}%`;
  bar.classList.toggle('full', pct === 100);
  $('todo-empty').hidden = visible.length > 0;
  $('clean-done').hidden = visible.length === all.length;

  const list = $('todo-list');
  list.replaceChildren(...visible.map((item) => {
    const done = isDone(item, today);
    const li = el('li', { class: `todo${done ? ' done' : ''}` });
    const label = el('label');
    const cb = el('input', { type: 'checkbox' });
    cb.checked = done;
    cb.addEventListener('change', () => toggleTodo(item.id));
    label.append(cb, el('span', { class: 'title' }, item.title));
    if (item.repeat === 'daily') label.append(el('span', { class: 'tag' }, '매일'));
    const del = el('button', { class: 'del', type: 'button', 'aria-label': `${item.title} 삭제`, title: '삭제' }, '✕');
    del.addEventListener('click', () => deleteTodo(item.id));
    li.append(label, del);
    return li;
  }));

  // 프로젝트
  const order = { active: 0, paused: 1, done: 2 };
  const projects = [...(state.projects.projects || [])].sort(
    (a, b) => ((order[a.status] ?? 3) - (order[b.status] ?? 3)) || String(b.updatedAt).localeCompare(String(a.updatedAt)),
  );
  const activeCount = projects.filter((p) => p.status === 'active').length;
  $('proj-count').textContent = projects.length ? `진행 중 ${activeCount}개` : '';
  $('proj-empty').hidden = projects.length > 0;
  $('proj-list').replaceChildren(...projects.map(renderProject));
}

const TOOL_LABEL = { 'claude-code': 'Claude Code', codex: 'Codex', other: '기타' };
const STATUS_LABEL = { active: '', paused: '멈춤', done: '완료' };

function renderProject(p) {
  const progress = Math.max(0, Math.min(100, Math.round(Number(p.progress) || 0)));
  const tool = TOOL_LABEL[p.tool] ? p.tool : 'other';
  const card = el('article', { class: `proj ${p.status || ''}` });
  const head = el('div', { class: 'proj-head' },
    el('span', { class: 'proj-name' }, p.name || p.id),
    el('span', { class: `tool ${tool}` }, TOOL_LABEL[tool]),
  );
  if (STATUS_LABEL[p.status]) head.append(el('span', { class: 'status' }, STATUS_LABEL[p.status]));
  head.append(el('span', { class: 'pct' }, `${progress}%`));
  const fill = el('div', { class: `bar-fill${progress === 100 ? ' full' : ''}` });
  fill.style.width = `${progress}%`;
  card.append(head, el('div', { class: 'bar' }, fill));

  if (p.current) {
    card.append(el('div', { class: 'row' }, el('span', { class: 'label' }, '지금'), p.current));
  }
  const next = Array.isArray(p.next) ? p.next.filter(Boolean) : [];
  if (next.length) {
    const ol = el('ol', {}, ...next.slice(0, 3).map((n, i) => el('li', i === 0 ? { class: 'next-first' } : {}, n)));
    card.append(el('div', { class: 'row' }, el('span', { class: 'label' }, '다음 과제'), ol));
  }
  const meta = [p.summary, timeAgo(p.updatedAt)].filter(Boolean).join(' · ');
  if (meta) card.append(el('div', { class: 'meta' }, meta));
  return card;
}

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  node.append(...children);
  return node;
}

// ---------- 설정 ----------

function openSettings() {
  const c = load(CFG_KEY) || DEFAULT_CFG;
  $('s-owner').value = c.owner || DEFAULT_CFG.owner;
  $('s-repo').value = c.repo || DEFAULT_CFG.repo;
  $('s-branch').value = c.branch || DEFAULT_CFG.branch;
  $('s-token').value = c.token || '';
  $('s-error').hidden = true;
  $('settings').showModal();
}

async function saveSettings(ev) {
  ev.preventDefault();
  const cfg = {
    owner: $('s-owner').value.trim(),
    repo: $('s-repo').value.trim(),
    branch: $('s-branch').value.trim(),
    token: $('s-token').value.trim(),
  };
  const btn = $('s-save');
  btn.disabled = true;
  btn.textContent = '확인 중…';
  try {
    // 읽기로 연결을 확인한다 (파일이 아직 없으면 404 → 빈 데이터로 정상 처리)
    await readFile(cfg, 'todos');
    save(CFG_KEY, cfg);
    state.lastWrite = {};
    $('settings').close();
    toast('연결됐어요');
    refresh();
  } catch (e) {
    const err = $('s-error');
    err.textContent = errorText(e);
    err.hidden = false;
  } finally {
    btn.disabled = false;
    btn.textContent = '연결 확인 후 저장';
  }
}

// ---------- 시작 ----------

function init() {
  const cached = load(CACHE_KEY);
  if (cached && getCfg()) {
    state.todos = cached.todos || state.todos;
    state.projects = cached.projects || state.projects;
  }
  render();

  $('add-form').addEventListener('submit', (ev) => {
    ev.preventDefault();
    const title = $('add-title').value.trim();
    if (!title) return;
    addTodo(title, $('add-daily').checked);
    $('add-title').value = '';
    $('add-daily').checked = false;
  });
  $('clean-done').addEventListener('click', cleanDone);
  $('refresh').addEventListener('click', () => refresh());
  $('open-settings').addEventListener('click', openSettings);
  $('banner-settings').addEventListener('click', openSettings);
  $('settings-form').addEventListener('submit', saveSettings);
  $('s-cancel').addEventListener('click', () => $('settings').close());
  $('s-clear').addEventListener('click', () => {
    if (!confirm('이 기기에서 연결 정보(토큰)를 지울까요?')) return;
    remove(CFG_KEY);
    remove(CACHE_KEY);
    $('settings').close();
    refresh();
  });

  // 앱으로 돌아오면 새로고침, 켜져 있는 동안 1분마다 새로고침
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') refresh({ quiet: true });
  });
  setInterval(() => {
    if (document.visibilityState === 'visible' && state.busy === 0) refresh({ quiet: true });
  }, REFRESH_MS);

  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
  refresh();
}

init();

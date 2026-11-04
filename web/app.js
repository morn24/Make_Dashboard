'use strict';

// 데이터 형식은 docs/data-format.md 참고
const FILES = { todos: 'data/todos.json', projects: 'data/projects.json', calendar: 'data/calendar.json', push: 'data/push.json' };
const EMPTY = {
  todos: () => ({ version: 1, updatedAt: null, items: [] }),
  projects: () => ({ version: 1, updatedAt: null, projects: [] }),
  // 캘린더 파일은 Google Apps Script가 만든다. 아직 없으면 missing=true (연결 안내 표시)
  calendar: () => ({ version: 1, updatedAt: null, events: [], missing: true }),
  // 알림(Web Push): vapidPublicKey는 Apps Script가 쓰고, subscriptions는 폰이 쓴다
  push: () => ({ version: 1, updatedAt: null, vapidPublicKey: null, subscriptions: [] }),
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
  calendar: EMPTY.calendar(),
  demo: false,
  lastWrite: {}, // file -> { oldSha, at }
  dragging: false, // 끌어서 순서 바꾸는 중이면 다시 그리기를 미룬다
  openGroups: { paused: false, done: false }, // 홀드/완료 묶음 펼침 상태 (다시 그려도 유지)
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
      const [t, p, c] = await Promise.all(
        ['todos', 'projects', 'calendar'].map((f) => fetch(`demo/${f}.json`, { cache: 'no-store' }).then((r) => r.json())),
      );
      state.todos = demoTodos(t);
      state.projects = p;
      state.calendar = demoCalendar(c);
      setSync('데모 데이터');
    } else {
      const [t, p, c] = await Promise.all([readFile(cfg, 'todos'), readFile(cfg, 'projects'), readFile(cfg, 'calendar')]);
      state.calendar = c.data;
      for (const [file, got] of [['todos', t], ['projects', p]]) {
        const w = state.lastWrite[file];
        const stale = w && Date.now() - w.at < STALE_GUARD_MS && got.sha === w.oldSha;
        if (!stale) state[file] = got.data;
      }
      save(CACHE_KEY, { todos: state.todos, projects: state.projects, calendar: state.calendar, at: Date.now() });
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
// undo를 주면 "되돌리기" 버튼을 함께 보여준다 (5초)
function toast(text, undo) {
  const box = $('toast');
  box.replaceChildren(el('span', {}, text));
  if (undo) {
    const btn = el('button', { type: 'button', class: 'toast-undo' }, '되돌리기');
    btn.addEventListener('click', () => { box.hidden = true; clearTimeout(toastTimer); undo(); });
    box.append(btn);
  }
  box.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { box.hidden = true; }, undo ? 5000 : 3000);
}

// ---------- 할 일 규칙 ----------

function newId() {
  return 't_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}

// 화면을 먼저 바꾸고(낙관적 반영), 저장이 실패하면 서버 상태로 되돌린다
async function changeFile(file, change, message) {
  const before = state[file];
  state[file] = change(structuredClone(before));
  render();
  setBusy(+1);
  try {
    // 데모는 저장할 곳이 없으니 화면에 반영한 것으로 끝 (change를 두 번 적용하면 추가가 중복된다)
    if (!state.demo) state[file] = await mutate(file, change, message);
    if (!state.demo) setSync(`저장됨 · ${new Date().toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })}`);
  } catch (e) {
    state[file] = before;
    toast(`저장 실패: ${errorText(e)}`);
    refresh({ quiet: true });
  } finally {
    setBusy(-1);
    render();
  }
}
const changeTodos = (change, message) => changeFile('todos', change, message);
const changeProjects = (change, message) => changeFile('projects', change, message);

function toggleTodo(id) {
  const today = localDate();
  const target = state.todos.items.find((i) => i.id === id);
  const makeDone = target && !isDone(target, today);
  changeTodos((d) => {
    const it = d.items.find((i) => i.id === id);
    if (!it) return d;
    if (isRepeating(it)) {
      recordHistory(it, today, makeDone);
      // 완료 취소하면 doneOn을 이전 완료일로 되돌린다 (연속 기록이 끊기지 않게)
      it.doneOn = makeDone ? today : (it.history.filter((x) => x < today).pop() || null);
    } else {
      it.doneOn = makeDone ? today : null;
    }
    return d;
  }, `todo: ${makeDone ? '완료' : '완료 취소'} - ${target ? target.title : id}`);
}

// repeat 값: 'none' | 'daily' | 'weekdays' | 'weekend' | 'custom'(days 직접)
function repeatFields(kind, days) {
  if (kind === 'daily') return { repeat: 'daily' };
  if (kind === 'weekdays') return { repeat: 'weekly', days: [1, 2, 3, 4, 5] };
  if (kind === 'weekend') return { repeat: 'weekly', days: [0, 6] };
  if (kind === 'custom' && days && days.length) return { repeat: 'weekly', days: [...days].sort((a, b) => a - b) };
  return { repeat: 'none' };
}

// 입력 문장에서 날짜·시간을 읽어서 추가한다 ("내일 오후 3시 병원" → 내일, 15:00, "병원").
// 한 번 할 일은 날짜가 없으면 오늘로 정한다 (Google 캘린더 할 일에 그날로 보이게).
function addTodo(text, kind) {
  const p = parseTodoInput(text);
  const r = repeatFields(kind);
  const item = { id: newId(), title: p.title, ...r, doneOn: null, createdAt: isoNow() };
  if (r.repeat === 'none') item.date = p.date || localDate();
  if (p.time) item.time = p.time;
  changeTodos((d) => { d.items.push(item); return d; }, `todo: 추가 - ${item.title}`);
  if (r.repeat === 'none' && item.date > localDate()) toast(`${fmtDay(item.date, localDate())}${item.time ? ` ${item.time}` : ''} 예정으로 추가했어요`);
}

// 입력하는 동안 읽어 낸 날짜·시간을 미리 보여준다
function addHint() {
  const text = $('add-title').value.trim();
  const p = text ? parseTodoInput(text) : null;
  const hint = $('add-hint');
  const show = p && (p.date || p.time);
  hint.hidden = !show;
  if (show) {
    const today = localDate();
    hint.textContent = `📅 ${p.date ? fmtDay(p.date, today) : '오늘'}${p.time ? ` ${p.time} · ${ALERT_BEFORE_MIN}분 전 알림` : ''} · "${p.title}"`;
  }
}

function updateTodo(id, patch, message, undoText) {
  const before = state.todos.items.find((i) => i.id === id);
  if (!before) return;
  const old = structuredClone(before);
  changeTodos((d) => {
    const it = d.items.find((i) => i.id === id);
    if (!it) return d;
    for (const [k, v] of Object.entries(patch)) {
      if (v === undefined) delete it[k]; else it[k] = v;
    }
    return d;
  }, `todo: ${message} - ${before.title}`);
  if (undoText) {
    const keys = Object.keys(patch);
    toast(undoText, () => updateTodo(id, Object.fromEntries(keys.map((k) => [k, old[k]])), '되돌리기'));
  }
}

function deleteTodo(id) {
  const list = state.todos.items;
  const idx = list.findIndex((i) => i.id === id);
  if (idx < 0) return;
  const item = structuredClone(list[idx]);
  const afterId = list[idx + 1] ? list[idx + 1].id : null;
  changeTodos((d) => { d.items = d.items.filter((i) => i.id !== id); return d; }, `todo: 삭제 - ${item.title}`);
  // 확인창 대신 되돌리기: 원래 자리(다음 항목 앞)에 다시 넣는다
  toast(`"${item.title}" 삭제됨`, () => changeTodos((d) => {
    if (d.items.some((i) => i.id === id)) return d;
    const to = afterId ? d.items.findIndex((i) => i.id === afterId) : -1;
    if (to < 0) d.items.push(item); else d.items.splice(to, 0, item);
    return d;
  }, `todo: 삭제 취소 - ${item.title}`));
}

function cleanDone() {
  const today = localDate();
  changeTodos((d) => {
    d.items = d.items.filter((i) => !isStale(i, today));
    return d;
  }, 'todo: 지난 완료 항목 정리');
}

// ---------- 할 일 메뉴 (이름 수정, 반복 요일, 날짜, 내일로, 삭제) ----------

const tm = { id: null, kind: 'none', days: new Set() };

function openTodoMenu(id) {
  const item = state.todos.items.find((i) => i.id === id);
  if (!item) return;
  const today = localDate();
  tm.id = id;
  tm.days = new Set(item.repeat === 'weekly' && Array.isArray(item.days) ? item.days : []);
  tm.kind = item.repeat === 'daily' ? 'daily' : tm.days.size ? 'custom' : 'none';
  $('tm-title').value = item.title;
  $('tm-date').value = item.date || '';
  $('tm-time').value = item.time || '';
  $('tm-sync').textContent = item.gtask ? '🔗 Google 캘린더 할 일과 연동돼 있어요 (수정·삭제가 함께 반영돼요)'
    : isRepeating(item) ? '반복 할 일은 Google 캘린더에 올리지 않아요' : '';
  $('tm-tomorrow').hidden = isRepeating(item) || isDone(item, today);
  const hist = $('tm-history');
  hist.hidden = !isRepeating(item);
  if (isRepeating(item)) {
    const from = addDays(today, -29);
    const days = new Set([...(item.history || []), item.doneOn].filter((d) => d && d >= from));
    hist.textContent = `최근 30일 중 ${days.size}일 완료 · 연속 ${streak(item, today)}번`;
  }
  renderTmDays();
  $('todo-menu').showModal();
}

function renderTmDays() {
  for (const b of $('tm-days').children) {
    const on = b.dataset.kind ? b.dataset.kind === tm.kind : tm.kind === 'custom' && tm.days.has(Number(b.dataset.day));
    b.classList.toggle('on', on);
    b.setAttribute('aria-pressed', String(on));
  }
  $('tm-date-row').hidden = tm.kind !== 'none';
}

function onTmDay(ev) {
  const b = ev.target.closest('button');
  if (!b) return;
  if (b.dataset.kind) {
    tm.kind = b.dataset.kind;
    tm.days.clear();
  } else {
    const d = Number(b.dataset.day);
    if (tm.kind !== 'custom') tm.days.clear();
    if (tm.days.has(d)) tm.days.delete(d); else tm.days.add(d);
    tm.kind = tm.days.size === 7 ? 'daily' : tm.days.size ? 'custom' : 'none';
    if (tm.kind === 'daily') tm.days.clear();
  }
  renderTmDays();
}

function saveTodoMenu(ev) {
  ev.preventDefault();
  const title = $('tm-title').value.trim();
  if (!title) return;
  const r = repeatFields(tm.kind, [...tm.days]);
  const date = r.repeat === 'none' ? $('tm-date').value || undefined : undefined;
  const item = state.todos.items.find((i) => i.id === tm.id);
  $('todo-menu').close();
  if (!item) return;
  const time = $('tm-time').value || undefined;
  const patch = { title, repeat: r.repeat, days: r.days, date, time };
  const same = Object.entries(patch).every(([k, v]) => JSON.stringify(item[k]) === JSON.stringify(v));
  if (!same) updateTodo(tm.id, patch, '수정');
}

function postponeTodo() {
  $('todo-menu').close();
  const tomorrow = addDays(localDate(), 1);
  updateTodo(tm.id, { date: tomorrow }, `내일(${tomorrow})로 미룸`, '내일로 미뤘어요');
}

// ---------- 화면을 아래로 당겨서 새로고침 ----------

function setupPullToRefresh() {
  const ind = $('ptr');
  const LIMIT = 70;
  let startY = null;
  let dist = 0;
  const reset = () => {
    startY = null;
    dist = 0;
    ind.style.transform = '';
    ind.classList.remove('ready', 'pulling');
  };
  document.addEventListener('touchstart', (e) => {
    if (window.scrollY > 0 || state.dragging || document.querySelector('dialog[open]') || e.touches.length > 1) return;
    startY = e.touches[0].clientY;
  }, { passive: true });
  document.addEventListener('touchmove', (e) => {
    if (startY == null) return;
    dist = e.touches[0].clientY - startY;
    if (dist <= 0 || window.scrollY > 0) { reset(); return; }
    const y = Math.min(dist * 0.5, LIMIT + 20);
    ind.classList.add('pulling');
    ind.classList.toggle('ready', dist * 0.5 >= LIMIT);
    ind.style.transform = `translate(-50%, ${y}px) rotate(${y * 4}deg)`;
  }, { passive: true });
  const end = () => {
    if (startY != null && dist * 0.5 >= LIMIT) refresh();
    reset();
  };
  document.addEventListener('touchend', end);
  document.addEventListener('touchcancel', reset);
}

// ---------- 그리기 ----------

function render() {
  if (state.dragging) return;
  const today = localDate();
  const now = new Date();
  $('today').textContent = now.toLocaleDateString('ko-KR', { month: 'long', day: 'numeric', weekday: 'short' });
  renderCalendar(today, now);

  // 할 일
  const all = state.todos.items || [];
  const visible = all.filter((i) => isVisible(i, today));
  // 미완료 먼저, 그 안에서는 저장된 순서(사용자가 끌어서 정한 순서) 그대로
  visible.sort((a, b) => isDone(a, today) - isDone(b, today));
  const doneCount = visible.filter((i) => isDone(i, today)).length;
  $('todo-count').textContent = visible.length ? `${doneCount} / ${visible.length}` : '';
  const pct = visible.length ? Math.round((doneCount / visible.length) * 100) : 0;
  const bar = $('todo-bar');
  bar.style.width = `${pct}%`;
  bar.classList.toggle('full', pct === 100);
  $('todo-empty').hidden = visible.length > 0;
  $('clean-done').hidden = !all.some((i) => isStale(i, today));

  const list = $('todo-list');
  list.replaceChildren(...visible.map((item) => {
    const done = isDone(item, today);
    const li = el('li', { class: `todo${done ? ' done' : ''}`, 'data-id': item.id, 'data-group': done ? 'done' : 'todo' });
    const label = el('label');
    const cb = el('input', { type: 'checkbox' });
    cb.checked = done;
    cb.addEventListener('change', () => toggleTodo(item.id));
    const text = el('span', { class: 'todo-text' });
    if (item.time) text.append(el('span', { class: 'todo-time' }, item.time));
    text.append(el('span', { class: 'title' }, item.title));
    const tags = el('span', { class: 'tags' });
    const rep = repeatLabel(item);
    if (rep) tags.append(el('span', { class: 'tag' }, rep));
    const s = streak(item, today);
    if (s >= 2) tags.append(el('span', { class: 'streak', title: `${s}번 연속` }, `🔥${s}`));
    if (!isRepeating(item) && !done && item.date && item.date < today) {
      const late = Math.round((new Date(`${today}T00:00:00`) - new Date(`${item.date}T00:00:00`)) / 86400000);
      tags.append(el('span', { class: 'late' }, `${late}일 지남`));
    }
    if (tags.childNodes.length) text.append(tags);
    label.append(cb, text);
    const more = el('button', { class: 'del', type: 'button', 'aria-label': `${item.title} 메뉴`, title: '수정·미루기·삭제' }, '⋯');
    more.addEventListener('click', () => openTodoMenu(item.id));
    // 끝낸 항목은 옮길 수 없지만 줄을 맞추려고 빈 자리를 둔다
    li.append(done ? el('span', { class: 'handle spacer', 'aria-hidden': 'true' }) : handle(item.title));
    li.append(label, more);
    return li;
  }));

  // 날짜를 정해 둔 할 일 (아직 날이 안 됨)
  const upcoming = all.filter((i) => isUpcoming(i, today)).sort((a, b) => a.date.localeCompare(b.date));
  const up = $('todo-upcoming');
  up.hidden = !upcoming.length;
  if (upcoming.length) {
    $('todo-upcoming-title').textContent = `예정 ${upcoming.length}개`;
    $('todo-upcoming-list').replaceChildren(...upcoming.map((item) => {
      const b = el('button', { type: 'button', class: 'up-item' },
        el('span', { class: 'up-date' }, `${fmtDay(item.date, today)}${item.time ? ` ${item.time}` : ''}`), el('span', { class: 'title' }, item.title));
      b.addEventListener('click', () => openTodoMenu(item.id));
      return el('li', {}, b);
    }));
  }

  // 프로젝트
  const order = { active: 0, paused: 1, done: 2 };
  const projects = [...(state.projects.projects || [])].sort(
    // 같은 상태끼리는 저장된 순서 그대로 (sort는 안정 정렬)
    (a, b) => (order[a.status] ?? 0) - (order[b.status] ?? 0),
  );
  const byStatus = (s) => projects.filter((p) => (s === 'active' ? !['paused', 'done'].includes(p.status) : p.status === s));
  const active = byStatus('active');
  const paused = byStatus('paused');
  const done = byStatus('done');
  $('proj-count').textContent = projects.length ? `진행 중 ${active.length}개` : '';
  $('proj-empty').hidden = projects.length > 0;
  // 홀드는 2개 이상일 때만 묶고, 완료는 1개라도 묶는다
  $('proj-list').replaceChildren(
    ...active.map(renderProject),
    ...(paused.length >= 2 ? [renderGroup('paused', `⏸ 홀드 ${paused.length}개`, paused)] : paused.map(renderProject)),
    ...(done.length ? [renderGroup('done', `✔ 완료 ${done.length}개`, done)] : []),
  );
}

function renderGroup(key, title, items) {
  const details = el('details', { class: 'group' });
  details.open = state.openGroups[key];
  details.addEventListener('toggle', () => { state.openGroups[key] = details.open; });
  details.append(el('summary', {}, title), el('div', { class: 'projects' }, ...items.map(renderProject)));
  return details;
}

const TOOL_LABEL = { 'claude-code': 'Claude Code', codex: 'Codex', antigravity: 'Antigravity', gemini: 'Gemini', other: '기타' };
const STATUS_LABEL = { active: '', paused: '홀드', done: '완료' };

function renderProject(p) {
  const progress = Math.max(0, Math.min(100, Math.round(Number(p.progress) || 0)));
  const tool = TOOL_LABEL[p.tool] ? p.tool : 'other';
  const group = ['paused', 'done'].includes(p.status) ? p.status : 'active';
  const card = el('article', { class: `proj ${p.status || ''}`, 'data-id': p.id, 'data-group': group });
  const head = el('div', { class: 'proj-head' },
    handle(p.name || p.id),
    el('span', { class: 'proj-name' }, p.name || p.id),
    el('span', { class: `tool ${tool}` }, TOOL_LABEL[tool]),
  );
  if (STATUS_LABEL[p.status]) head.append(el('span', { class: 'status' }, STATUS_LABEL[p.status]));
  head.append(el('span', { class: 'pct' }, `${progress}%`));
  const more = el('button', { class: 'del', type: 'button', 'aria-label': `${p.name || p.id} 상태 변경`, title: '상태 변경' }, '⋯');
  more.addEventListener('click', () => openProjectMenu(p.id));
  head.append(more);
  const fill = el('div', { class: `bar-fill${progress === 100 ? ' full' : ''}` });
  fill.style.width = `${progress}%`;
  card.append(head, el('div', { class: 'bar' }, fill));

  if (p.current) {
    card.append(el('div', { class: 'row' }, el('span', { class: 'label' }, '지금'), p.current));
  }
  // 다음 과제: PC(AI)가 쓰는 next 중 사용자가 폰에서 체크한 것(checked)은 아래로 내려 줄을 긋는다.
  // 체크는 next를 건드리지 않고 checked에만 기록 → 다음 작업 때 AI가 확인하고 정리한다.
  const next = Array.isArray(p.next) ? p.next.filter(Boolean) : [];
  const checked = (Array.isArray(p.checked) ? p.checked : []).filter((c) => c && c.text);
  const checkedSet = new Set(checked.map((c) => c.text));
  const open = next.filter((n) => !checkedSet.has(n));
  if (open.length || checked.length) {
    const item = (text, done, first) => {
      const cb = el('input', { type: 'checkbox' });
      cb.checked = done;
      cb.addEventListener('change', () => toggleNext(p.id, text, cb.checked));
      return el('li', { class: `${done ? 'done' : ''}${first ? ' next-first' : ''}` },
        el('label', {}, cb, el('span', {}, text)));
    };
    const list = el('ul', { class: 'next-list' },
      ...open.map((n, i) => item(n, false, i === 0)),
      ...checked.map((c) => item(c.text, true, false)),
    );
    const title = `다음 과제${open.length ? ` ${open.length}` : ''}${checked.length ? ` · 확인 ${checked.length}` : ''}`;
    card.append(el('div', { class: 'row' }, el('span', { class: 'label' }, title), list));
  }
  const meta = [p.summary, timeAgo(p.updatedAt)].filter(Boolean).join(' · ');
  if (meta) card.append(el('div', { class: 'meta' }, meta));
  return card;
}

// ---------- 오늘 일정 (Google 캘린더 → data/calendar.json) ----------

// 종일 일정: start <= 오늘 < end (end는 다음 날, 미포함). 시간 일정: 오늘 0시~24시와 겹치면 오늘 일정.
function eventsOn(day) {
  const dayStart = new Date(`${day}T00:00:00`);
  const dayEnd = new Date(`${addDays(day, 1)}T00:00:00`);
  return (state.calendar.events || [])
    .filter((e) => (e.allDay
      ? e.start <= day && day < (e.end || addDays(e.start, 1))
      : new Date(e.start) < dayEnd && new Date(e.end || e.start) > dayStart))
    .sort((a, b) => (b.allDay - a.allDay) || String(a.start).localeCompare(String(b.start)));
}

function fmtTime(iso) {
  return new Date(iso).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false });
}

function renderCalendar(today, now) {
  const card = $('cal-card');
  const missing = state.calendar.missing && !state.demo;
  $('cal-setup').hidden = !missing;
  const events = missing ? [] : eventsOn(today);
  $('cal-count').textContent = events.length ? `${events.length}개` : '';
  $('cal-empty').hidden = missing || events.length > 0;
  const tomorrow = missing ? [] : eventsOn(addDays(today, 1));
  $('cal-tomorrow').textContent = tomorrow.length
    ? `내일 ${tomorrow.length}개 · ${tomorrow.slice(0, 2).map((e) => (e.allDay ? '' : `${fmtTime(e.start)} `) + e.title).join(', ')}${tomorrow.length > 2 ? ' …' : ''}`
    : '';
  // Apps Script는 날짜가 바뀌면 10분 안에 파일을 새로 쓴다. 오늘 날짜가 아니면 자동 동기화가 멈춘 것.
  const stale = !missing && !state.demo && state.calendar.rangeStart && state.calendar.rangeStart < today
    && now.getHours() * 60 + now.getMinutes() >= 30;
  $('cal-stale').hidden = !stale;
  if (stale) $('cal-stale').textContent = `⚠ 캘린더 자동 동기화가 멈춘 것 같아요 (마지막 ${fmtDay(state.calendar.rangeStart, today)}). Apps Script 실행 기록을 확인해 주세요.`;
  card.hidden = false;
  $('cal-list').replaceChildren(...events.map((e) => {
    const past = !e.allDay && new Date(e.end || e.start) <= now;
    const ongoing = !e.allDay && new Date(e.start) <= now && now < new Date(e.end || e.start);
    const when = e.allDay ? '종일' : `${fmtTime(e.start)}${e.end ? `–${fmtTime(e.end)}` : ''}`;
    const li = el('li', { class: `event${past ? ' past' : ''}${ongoing ? ' now' : ''}` });
    const dot = el('span', { class: 'dot', title: e.calendar || '' });
    if (e.color) dot.style.background = e.color;
    const body = el('div', { class: 'ev-body' }, el('span', { class: 'ev-title' }, e.title || '(제목 없음)'));
    if (e.location) body.append(el('span', { class: 'ev-loc' }, e.location));
    li.append(el('span', { class: 'ev-time' }, when), dot, body);
    return li;
  }));
}

// 데모 할 일의 "D-1", "D+2" 같은 날짜를 오늘 기준 실제 날짜로 바꾼다
function demoTodos(t) {
  const today = localDate();
  const conv = (v) => (typeof v === 'string' && /^D[+-]\d+$/.test(v) ? addDays(today, Number(v.slice(1))) : v);
  return { ...t, items: t.items.map((i) => ({
    ...i, doneOn: conv(i.doneOn), date: conv(i.date), ...(i.history ? { history: i.history.map(conv) } : {}),
  })) };
}

// 데모 파일의 일정 날짜를 오늘/내일로 옮긴다
function demoCalendar(c) {
  const today = localDate();
  const shift = (v) => v && v.replace('2000-01-01', today).replace('2000-01-02', addDays(today, 1));
  return { ...c, events: c.events.map((e) => ({ ...e, start: shift(e.start), end: shift(e.end) })) };
}

// ---------- 끌어서 순서 바꾸기 ----------

function handle(name) {
  return el('span', { class: 'handle', 'aria-hidden': 'true', title: `${name} 끌어서 순서 바꾸기` }, '⠿');
}

// 저장된 배열에서 id 항목을 beforeId 앞으로 옮긴다 (beforeId가 없으면 맨 뒤로).
// "몇 번째로"가 아니라 "누구 앞으로"라서, 그 사이 다른 기기가 항목을 추가해도 결과가 어긋나지 않는다.
function moveBefore(list, id, beforeId) {
  const from = list.findIndex((x) => x.id === id);
  if (from < 0) return list;
  const [item] = list.splice(from, 1);
  const to = beforeId ? list.findIndex((x) => x.id === beforeId) : -1;
  if (to < 0) list.push(item);
  else list.splice(to, 0, item);
  return list;
}

function onReorder(item, beforeId) {
  const id = item.dataset.id;
  if (item.closest('#todo-list')) {
    const t = state.todos.items.find((i) => i.id === id);
    changeTodos((d) => { moveBefore(d.items, id, beforeId); return d; }, `todo: 순서 변경 - ${t ? t.title : id}`);
  } else {
    changeProjects((d) => { moveBefore(d.projects, id, beforeId); return d; }, `project: ${id} 순서 변경`);
  }
}

// 손잡이(⠿)를 누른 채 위아래로 끌면 같은 묶음(data-group) 안에서 자리를 바꾼다. 마우스와 터치 모두 동작.
function startDrag(ev) {
  const h = ev.target.closest('.handle:not(.spacer)');
  if (!h) return;
  const item = h.closest('[data-id]');
  const container = item && item.parentElement;
  if (!container) return;
  ev.preventDefault();
  const group = item.dataset.group;
  const peers = () => [...container.children].filter((c) => c !== item && c.dataset.group === group);
  const nextPeer = () => {
    const n = item.nextElementSibling;
    return n && n.dataset.group === group ? n.dataset.id : null;
  };
  const before = nextPeer();
  state.dragging = true;
  item.classList.add('dragging');
  // 항목을 옮기면 포인터 캡처가 풀리므로, 이동/놓기는 문서 전체에서 듣는다

  const move = (e) => {
    if (e.pointerId !== ev.pointerId) return;
    const list = peers();
    if (!list.length) return;
    const target = list.find((c) => { const r = c.getBoundingClientRect(); return e.clientY < r.top + r.height / 2; });
    if (target) container.insertBefore(item, target);
    else list[list.length - 1].after(item);
  };
  const end = (e) => {
    if (e.pointerId !== ev.pointerId) return;
    document.removeEventListener('pointermove', move);
    document.removeEventListener('pointerup', end);
    document.removeEventListener('pointercancel', end);
    item.classList.remove('dragging');
    state.dragging = false;
    const after = nextPeer();
    if (e.type === 'pointerup' && after !== before) onReorder(item, after);
    else render();
  };
  document.addEventListener('pointermove', move);
  document.addEventListener('pointerup', end);
  document.addEventListener('pointercancel', end);
}

// ---------- 프로젝트 상태 변경 (진행 중 / 홀드 / 완료 / 삭제) ----------

let menuProjectId = null;

function openProjectMenu(id) {
  const p = (state.projects.projects || []).find((x) => x.id === id);
  if (!p) return;
  menuProjectId = id;
  $('pm-title').textContent = p.name || p.id;
  for (const btn of document.querySelectorAll('#proj-menu [data-status]')) {
    btn.disabled = btn.dataset.status === (p.status || 'active');
  }
  $('proj-menu').showModal();
}

function setProjectStatus(status) {
  const id = menuProjectId;
  $('proj-menu').close();
  changeProjects((d) => {
    const p = d.projects.find((x) => x.id === id);
    if (p) p.status = status;
    return d;
  }, `project: ${id} 상태 → ${status}`);
}

// 사용자가 다음 과제를 직접 확인 처리 (AI에게 "확인했어"라고 말하지 않아도 되게)
function toggleNext(id, text, done) {
  changeProjects((d) => {
    const p = d.projects.find((x) => x.id === id);
    if (!p) return d;
    const rest = (Array.isArray(p.checked) ? p.checked : []).filter((c) => c && c.text !== text);
    p.checked = done ? [...rest, { text, at: isoNow() }] : rest;
    if (!p.checked.length) delete p.checked;
    return d;
  }, `project: ${id} 사용자 확인 ${done ? '체크' : '해제'} - ${text}`);
}

// ---------- 붙여넣기로 업데이트 (Gemini 채팅 등 PC에서 기록할 수 없는 AI) ----------

const NEW_PROJECT = '__new__';

// PC 스크립트(dash_update.py)의 slugify와 같은 규칙: 글자·숫자 외는 '-'로
function slugify(name) {
  return String(name).trim().toLowerCase().replace(/[^\p{L}\p{M}\p{N}]+|_/gu, '-').replace(/-{2,}/g, '-').replace(/^-|-$/g, '');
}
const sameTask = (a, b) => a.split(/\s+/).join(' ').toLowerCase() === b.split(/\s+/).join(' ').toLowerCase();

function openPaste(projectId) {
  const sel = $('pd-project');
  sel.replaceChildren(el('option', { value: NEW_PROJECT }, '➕ 새 프로젝트'),
    ...(state.projects.projects || []).map((p) => el('option', { value: p.id }, `${p.name || p.id} (${p.progress || 0}%)`)));
  sel.value = projectId || NEW_PROJECT;
  $('pd-text').value = '';
  $('pd-preview').hidden = true;
  $('pd-error').hidden = true;
  $('pd-apply').disabled = true;
  pasted = null;
  $('paste-dlg').showModal();
}

// AI에게 보낼 요청 문구. 고른 프로젝트의 현재 상태를 같이 넣어서 이어서 정리하게 한다.
function pastePrompt() {
  const p = (state.projects.projects || []).find((x) => x.id === $('pd-project').value);
  const current = p
    ? `현재 대시보드에 기록된 상태:\n- 이름: ${p.name || p.id}\n- 진행률: ${p.progress || 0}%\n- 지금: ${p.current || '-'}\n- 다음 과제: ${(p.next || []).join(' / ') || '-'}\n`
    : '이 프로젝트는 대시보드에 아직 없어요. 새로 등록할 거예요.\n';
  return `우리가 지금까지 나눈 이야기를 바탕으로, 내 개인 대시보드에 기록할 프로젝트 진행 상황을 정리해줘.
${current}
아래 JSON 형식 하나만 코드 블록으로 답해줘. 설명은 쓰지 마.
{
  "name": "프로젝트 이름 (짧게)",
  "progress": 0~100 정수 (전체 계획 대비 완료 비율),
  "current": "지금 하고 있는 단계 한 줄",
  "nextAdd": ["새로 생긴 다음 과제 (구체적인 동사로)"],
  "nextDone": ["기존 다음 과제 중 끝난 것 (위 목록의 문구 그대로)"],
  "summary": "이번 대화에서 한 일 한 줄"
}
- 범위가 늘어서 진행률이 기존보다 낮아져야 하면 "scopeGrew": true 를 넣어줘.
- 모르는 항목은 빼도 돼.`;
}

async function copyPastePrompt() {
  const text = pastePrompt();
  try {
    await navigator.clipboard.writeText(text);
    toast('요청 문구를 복사했어요. Gemini에 붙여넣으세요');
  } catch {
    // 클립보드 권한이 없으면 입력칸에 넣어서 직접 복사하게 한다
    const ta = $('pd-text');
    ta.value = text;
    ta.select();
    toast('자동 복사가 막혀 있어요. 선택된 문구를 직접 복사하세요');
  }
}

// AI 답변에서 JSON 객체 하나를 꺼낸다 (코드 블록, 앞뒤 설명 문장이 있어도 됨)
function parsePasted(text) {
  const body = text.replace(/```(?:json)?/gi, '');
  const start = body.indexOf('{');
  const end = body.lastIndexOf('}');
  if (start < 0 || end <= start) throw new Error('답변에서 { … } 형식을 찾지 못했어요');
  let obj;
  try {
    obj = JSON.parse(body.slice(start, end + 1).replace(/,\s*([}\]])/g, '$1')); // 끝 쉼표 허용
  } catch {
    throw new Error('JSON 형식이 올바르지 않아요. 답변 전체를 다시 복사해 보세요');
  }
  const str = (v) => (typeof v === 'string' ? v.trim() : '');
  const list = (v) => (Array.isArray(v) ? v : typeof v === 'string' ? [v] : []).map(str).filter(Boolean);
  const u = {
    name: str(obj.name),
    current: str(obj.current),
    summary: str(obj.summary),
    nextAdd: list(obj.nextAdd ?? obj.next),
    nextDone: list(obj.nextDone),
    scopeGrew: obj.scopeGrew === true,
  };
  if (obj.progress != null && obj.progress !== '') {
    const n = Math.round(Number(String(obj.progress).replace('%', '')));
    if (Number.isNaN(n) || n < 0 || n > 100) throw new Error('progress는 0~100 사이 숫자여야 해요');
    u.progress = n;
  }
  if (['active', 'paused', 'done'].includes(obj.status)) u.status = obj.status;
  return u;
}

let pasted = null;

function previewPaste() {
  const text = $('pd-text').value.trim();
  const pv = $('pd-preview');
  const err = $('pd-error');
  pasted = null;
  pv.hidden = true;
  err.hidden = true;
  $('pd-apply').disabled = true;
  if (!text) return;
  try {
    pasted = parsePasted(text);
  } catch (e) {
    err.textContent = e.message;
    err.hidden = false;
    return;
  }
  const isNew = $('pd-project').value === NEW_PROJECT;
  if (isNew && !pasted.name) {
    err.textContent = '새 프로젝트는 name(이름)이 필요해요';
    err.hidden = false;
    pasted = null;
    return;
  }
  const row = (k, v) => el('div', { class: 'pv-row' }, el('span', { class: 'pv-key' }, k), v);
  pv.replaceChildren(
    ...(isNew ? [row('새 카드', pasted.name)] : []),
    ...(pasted.progress != null ? [row('진행률', `${pasted.progress}%${pasted.scopeGrew ? ' (범위 늘어남)' : ''}`)] : []),
    ...(pasted.current ? [row('지금', pasted.current)] : []),
    ...pasted.nextDone.map((t) => row('끝남 ✔', t)),
    ...pasted.nextAdd.map((t) => row('추가 ＋', t)),
    ...(pasted.summary ? [row('요약', pasted.summary)] : []),
  );
  pv.hidden = false;
  $('pd-apply').disabled = false;
}

// PC 스크립트의 set 규칙과 같게: 준 값만 바꾸고, 다음 과제는 합치고, 진행률은 범위가 늘지 않았으면 낮추지 않는다
function applyPaste() {
  if (!pasted) return;
  const u = pasted;
  const tool = $('pd-tool').value;
  const selected = $('pd-project').value;
  const id = selected === NEW_PROJECT ? (slugify(u.name) || `p-${Date.now().toString(36)}`) : selected;
  let note = '';
  $('paste-dlg').close();
  changeProjects((d) => {
    const now = isoNow();
    note = '';
    let p = d.projects.find((x) => x.id === id);
    if (!p) {
      p = { id, name: u.name || id, tool, status: 'active', progress: 0, current: '', next: [], summary: '', updatedAt: now };
      d.projects.unshift(p); // 새 프로젝트는 맨 앞
    }
    p.tool = tool;
    p.status = u.status || 'active';
    if (u.progress != null) {
      if (u.progress < (p.progress || 0) && !u.scopeGrew) note = `진행률은 기존 ${p.progress}%를 유지했어요`;
      else p.progress = u.progress;
    }
    if (u.current) p.current = u.current;
    if (u.summary) p.summary = u.summary;
    const next = (p.next || []).filter((x) => !u.nextDone.some((t) => sameTask(x, t)));
    for (const t of u.nextAdd) if (!next.some((x) => sameTask(x, t))) next.push(t);
    p.next = next;
    // 끝났거나 다시 추가된 과제는 사용자 확인 체크도 정리
    const handled = [...u.nextDone, ...u.nextAdd];
    const checked = (p.checked || []).filter((c) => !handled.some((t) => sameTask(c.text, t)));
    if (checked.length) p.checked = checked;
    else delete p.checked;
    p.updatedAt = now;
    return d;
  }, `project: ${id} ${tool}에서 붙여넣기 갱신`).then(() => { if (note) toast(note); });
}

function removeProject() {
  const id = menuProjectId;
  const p = (state.projects.projects || []).find((x) => x.id === id);
  if (!p || !confirm(`"${p.name || p.id}" 을(를) 대시보드에서 뺄까요?\n(PC에서 이 프로젝트를 다시 작업하면 다시 나타나요)`)) return;
  $('proj-menu').close();
  changeProjects((d) => { d.projects = d.projects.filter((x) => x.id !== id); return d; }, `project: ${id} 삭제`);
}

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  node.append(...children);
  return node;
}

// ---------- 알림 (Web Push) ----------
// Apps Script가 알릴 시각에 "확인해"라는 신호만 보내면, 서비스워커(sw.js)가 할 일을 읽어서 알림을 띄운다.
// 서비스워커는 localStorage를 못 읽어서, 연결 정보를 Cache Storage에 같이 넣어 둔다.

const PRIVATE_CACHE = 'dash-private';
const PUSH_KEY = 'dash.push';
const pushSupported = () => 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;

async function syncSwCfg() {
  if (!('caches' in window)) return;
  try {
    const cfg = getCfg();
    const cache = await caches.open(PRIVATE_CACHE);
    if (cfg) await cache.put('cfg', new Response(JSON.stringify(cfg), { headers: { 'Content-Type': 'application/json' } }));
    else await cache.delete('cfg');
  } catch { /* 저장 불가 환경이면 무시 */ }
}

function b64urlToBytes(s) {
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

function deviceName() {
  const ua = navigator.userAgent;
  const m = ua.match(/;\s*([^;)]+)\s+Build\//) || ua.match(/\(([^;)]+)/);
  return (m ? m[1] : 'device').trim().slice(0, 40);
}

async function renderPushStatus() {
  const st = $('push-status');
  const on = $('push-on');
  const off = $('push-off');
  const test = $('push-test');
  on.hidden = off.hidden = test.hidden = true;
  if (!pushSupported()) { st.textContent = '이 브라우저는 알림을 지원하지 않아요. 홈 화면에 설치한 앱에서 켜 주세요.'; return; }
  if (!getCfg()) { st.textContent = '먼저 위에서 연결을 저장해 주세요.'; return; }
  if (Notification.permission === 'denied') { st.textContent = '알림이 차단돼 있어요. 폰 설정 → 앱 → 대시보드(또는 Chrome) → 알림에서 허용해 주세요.'; return; }
  let sub = null;
  try { sub = await (await navigator.serviceWorker.ready).pushManager.getSubscription(); } catch { /* 무시 */ }
  const subscribed = sub && load(PUSH_KEY) === sub.endpoint;
  st.textContent = subscribed
    ? `켜짐 · 시간 있는 할 일은 ${ALERT_BEFORE_MIN}분 전, 오후 ${SUMMARY_HOUR - 12}시에 못 한 일·내일 할 일`
    : '꺼짐';
  on.hidden = !!subscribed;
  off.hidden = !subscribed;
  test.hidden = Notification.permission !== 'granted';
}

async function enablePush() {
  const btn = $('push-on');
  btn.disabled = true;
  try {
    const cfg = getCfg();
    const { data } = await readFile(cfg, 'push');
    if (!data.vapidPublicKey) throw new Error('알림 키가 없어요. Apps Script에서 setup을 한 번 실행해 주세요 (설치 안내 참고)');
    if (await Notification.requestPermission() !== 'granted') throw new Error('알림 권한을 허용해야 해요');
    await syncSwCfg();
    const reg = await navigator.serviceWorker.ready;
    const key = b64urlToBytes(data.vapidPublicKey);
    let sub = await reg.pushManager.getSubscription();
    const sameKey = sub && sub.options.applicationServerKey
      && new Uint8Array(sub.options.applicationServerKey).join() === key.join();
    if (sub && !sameKey) { await sub.unsubscribe(); sub = null; }
    if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
    const j = sub.toJSON();
    await mutate('push', (d) => {
      d.subscriptions = (d.subscriptions || []).filter((x) => x.endpoint !== j.endpoint);
      d.subscriptions.push({ endpoint: j.endpoint, keys: j.keys, device: deviceName(), createdAt: isoNow() });
      return d;
    }, `push: 구독 추가 - ${deviceName()}`);
    save(PUSH_KEY, j.endpoint);
    toast('알림을 켰어요');
  } catch (e) {
    toast(e instanceof HttpError ? errorText(e) : e.message);
  } finally {
    btn.disabled = false;
    renderPushStatus();
  }
}

async function disablePush() {
  try {
    const reg = await navigator.serviceWorker.ready;
    const sub = await reg.pushManager.getSubscription();
    const endpoint = sub ? sub.endpoint : load(PUSH_KEY);
    if (sub) await sub.unsubscribe();
    if (endpoint) {
      await mutate('push', (d) => {
        d.subscriptions = (d.subscriptions || []).filter((x) => x.endpoint !== endpoint);
        return d;
      }, `push: 구독 해제 - ${deviceName()}`);
    }
    remove(PUSH_KEY);
    toast('알림을 껐어요');
  } catch (e) {
    toast(e instanceof HttpError ? errorText(e) : e.message);
  } finally {
    renderPushStatus();
  }
}

async function testPush() {
  const reg = await navigator.serviceWorker.ready;
  await reg.showNotification('대시보드 알림 테스트', {
    body: '이렇게 보여요. 상단바에는 테두리 안 체크 아이콘이 떠요.',
    icon: 'icons/icon-192.png',
    badge: 'icons/badge-96.png',
    tag: 'test',
  });
}

// ---------- 설정 ----------

function openSettings() {
  const c = load(CFG_KEY) || DEFAULT_CFG;
  $('s-owner').value = c.owner || DEFAULT_CFG.owner;
  $('s-repo').value = c.repo || DEFAULT_CFG.repo;
  $('s-branch').value = c.branch || DEFAULT_CFG.branch;
  $('s-token').value = c.token || '';
  $('s-error').hidden = true;
  renderPushStatus();
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
    syncSwCfg();
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
    state.calendar = cached.calendar || state.calendar;
  }
  render();

  $('add-form').addEventListener('submit', (ev) => {
    ev.preventDefault();
    const title = $('add-title').value.trim();
    if (!title) return;
    addTodo(title, $('add-repeat').value);
    $('add-title').value = '';
    $('add-repeat').value = 'none';
    addHint();
  });
  $('clean-done').addEventListener('click', cleanDone);
  $('add-title').addEventListener('input', addHint);
  $('refresh').addEventListener('click', () => refresh());
  $('open-settings').addEventListener('click', openSettings);
  $('banner-settings').addEventListener('click', openSettings);
  $('settings-form').addEventListener('submit', saveSettings);
  $('s-cancel').addEventListener('click', () => $('settings').close());
  for (const btn of document.querySelectorAll('#proj-menu [data-status]')) {
    btn.addEventListener('click', () => setProjectStatus(btn.dataset.status));
  }
  $('pm-remove').addEventListener('click', removeProject);
  $('tm-days').addEventListener('click', onTmDay);
  $('tm-form').addEventListener('submit', saveTodoMenu);
  $('tm-cancel').addEventListener('click', () => $('todo-menu').close());
  $('tm-tomorrow').addEventListener('click', postponeTodo);
  $('tm-delete').addEventListener('click', () => { $('todo-menu').close(); deleteTodo(tm.id); });
  setupPullToRefresh();
  $('push-on').addEventListener('click', enablePush);
  $('push-off').addEventListener('click', disablePush);
  $('push-test').addEventListener('click', testPush);
  syncSwCfg();
  document.addEventListener('pointerdown', startDrag);
  $('pm-close').addEventListener('click', () => $('proj-menu').close());
  $('pm-paste').addEventListener('click', () => { $('proj-menu').close(); openPaste(menuProjectId); });
  $('open-paste').addEventListener('click', () => openPaste());
  $('pd-copy').addEventListener('click', copyPastePrompt);
  $('pd-text').addEventListener('input', previewPaste);
  $('pd-project').addEventListener('change', previewPaste);
  $('pd-cancel').addEventListener('click', () => $('paste-dlg').close());
  $('pd-apply').addEventListener('click', applyPaste);
  $('s-clear').addEventListener('click', () => {
    if (!confirm('이 기기에서 연결 정보(토큰)를 지울까요?')) return;
    remove(CFG_KEY);
    remove(CACHE_KEY);
    syncSwCfg();
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

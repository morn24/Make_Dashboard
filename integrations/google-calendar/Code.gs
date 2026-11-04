/**
 * 대시보드 ⇄ Google 동기화 + 알림 (Google Apps Script)
 *
 * 1) Google 캘린더 "일정" → GitHub(dashboard-data/data/calendar.json)   [읽기 전용, 10분마다]
 * 2) 대시보드 "오늘 할 일"(data/todos.json) ⇄ Google 할 일(Tasks)        [양방향, 3분마다]
 *    - 만들기·삭제·완료·이름·날짜를 서로 반영한다. 매일/요일 반복 할 일은 연동하지 않는다.
 *    - Google 할 일은 시간을 저장하지 못해서, 시간은 제목 앞에 "15:00 병원"처럼 붙인다.
 * 3) 알림: 시간 있는 할 일은 10분 전, 매일 오후 5시에 "못 한 일"·"내일 할 일" → 폰 대시보드 앱에 푸시
 *
 * 설치 방법: docs/google-calendar-setup.md
 * 데이터 형식: docs/data-format.md
 */

const CONFIG = {
  owner: 'morn24',
  repo: 'dashboard-data',
  branch: 'main',
  paths: { calendar: 'data/calendar.json', todos: 'data/todos.json', push: 'data/push.json' },
  daysAhead: 7, // 오늘부터 며칠 치 일정을 저장할지
  excludeCalendars: [], // 빼고 싶은 캘린더 이름. 예: ['대한민국의 휴일']
  skipDeclined: true, // 내가 '거절'한 초대 일정은 빼기
  calendarEveryMinutes: 10, // 캘린더 일정 동기화 주기
  tasksEveryMinutes: 3, // Google 할 일 동기화 주기
  taskList: '@default', // 연동할 Google 할 일 목록 (기본 목록 "내 할 일")
  alertBeforeMinutes: 10, // 시간 있는 할 일 알림: 시작 몇 분 전
  summaryHour: 17, // 이 시각에 "못 한 일"과 "내일 할 일" 묶음 알림
  pushContact: 'https://morn24.github.io/Make_Dashboard/', // 푸시 서버에 알리는 연락처 (VAPID sub)
};

const PROPS = PropertiesService.getScriptProperties();

/** 처음 한 번(그리고 이 코드를 바꿀 때마다) 실행: 토큰 확인 + 알림 키 + 1분 트리거 + 첫 동기화 */
function setup() {
  getToken_();
  ensureVapidKeys_();
  removeTriggers_();
  ScriptApp.newTrigger('tick').timeBased().everyMinutes(1).create();
  syncCalendar();
  syncTasks();
  Logger.log('설치 완료: 1분마다 알림 확인, %s분마다 할 일, %s분마다 캘린더를 동기화합니다.',
    CONFIG.tasksEveryMinutes, CONFIG.calendarEveryMinutes);
}

/** 자동 실행을 멈추고 싶을 때 */
function uninstall() {
  removeTriggers_();
  Logger.log('자동 실행을 해제했습니다.');
}

function removeTriggers_() {
  ScriptApp.getProjectTriggers()
    .filter((t) => ['tick', 'syncCalendar'].indexOf(t.getHandlerFunction()) >= 0)
    .forEach((t) => ScriptApp.deleteTrigger(t));
}

/** 1분마다: 보낼 알림이 있으면 보내고, 주기가 된 동기화를 한다 */
function tick() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(1000)) return; // 앞 실행이 아직 안 끝났으면 건너뜀
  try {
    sendDuePushes_();
    const now = Date.now();
    if (now - Number(PROPS.getProperty('LAST_TASKS') || 0) >= (CONFIG.tasksEveryMinutes * 60 - 20) * 1000) {
      PROPS.setProperty('LAST_TASKS', String(now));
      syncTasks();
      sendDuePushes_(); // 방금 추가된 할 일 알림이 지금이면 바로 보낸다
    }
    if (now - Number(PROPS.getProperty('LAST_CAL') || 0) >= (CONFIG.calendarEveryMinutes * 60 - 20) * 1000) {
      PROPS.setProperty('LAST_CAL', String(now));
      syncCalendar();
    }
  } finally {
    lock.releaseLock();
  }
}

// ======================================================================
// 1) 캘린더 일정 → calendar.json
//    CalendarApp은 "일정"만 돌려준다. Google "할 일"은 여기 들어오지 않고 todos.json으로만 간다.
// ======================================================================

function syncCalendar() {
  const tz = Session.getScriptTimeZone();
  const now = new Date();
  const today = Utilities.formatDate(now, tz, 'yyyy-MM-dd');
  const startOfToday = parseLocal_(today, tz); // 스크립트 시간대 기준 오늘 0시
  const end = new Date(startOfToday.getTime() + CONFIG.daysAhead * 86400000);

  const events = collectEvents_(startOfToday, end, tz);
  const remote = readFile_(CONFIG.paths.calendar);
  const unchanged = remote.data
    && remote.data.rangeStart === today
    && JSON.stringify(remote.data.events) === JSON.stringify(events);
  if (unchanged) return;

  const data = {
    version: 1,
    updatedAt: Utilities.formatDate(now, tz, "yyyy-MM-dd'T'HH:mm:ssXXX"),
    timeZone: tz,
    rangeStart: today,
    rangeEnd: Utilities.formatDate(end, tz, 'yyyy-MM-dd'),
    events: events,
  };
  writeFile_(CONFIG.paths.calendar, data, remote.sha, 'calendar: ' + today + ' 일정 ' + events.length + '개 동기화');
}

function collectEvents_(start, end, tz) {
  const out = [];
  CalendarApp.getAllCalendars().forEach((cal) => {
    if (cal.isHidden() || CONFIG.excludeCalendars.indexOf(cal.getName()) >= 0) return;
    cal.getEvents(start, end).forEach((ev) => {
      if (CONFIG.skipDeclined && myStatus_(ev) === CalendarApp.GuestStatus.NO) return;
      const allDay = ev.isAllDayEvent();
      const item = {
        // 반복 일정은 같은 id를 공유하므로 시작 시각을 붙여 고유하게 만든다
        id: ev.getId() + '@' + ev.getStartTime().getTime(),
        calendar: cal.getName(),
        color: cal.getColor(),
        title: ev.getTitle(),
        allDay: allDay,
        start: allDay
          ? Utilities.formatDate(ev.getAllDayStartDate(), tz, 'yyyy-MM-dd')
          : Utilities.formatDate(ev.getStartTime(), tz, "yyyy-MM-dd'T'HH:mm:ssXXX"),
        end: allDay
          ? Utilities.formatDate(ev.getAllDayEndDate(), tz, 'yyyy-MM-dd')
          : Utilities.formatDate(ev.getEndTime(), tz, "yyyy-MM-dd'T'HH:mm:ssXXX"),
      };
      const loc = ev.getLocation();
      if (loc) item.location = loc;
      out.push(item);
    });
  });
  out.sort((a, b) => (a.start < b.start ? -1 : a.start > b.start ? 1 : 0) || (a.id < b.id ? -1 : 1));
  return out;
}

function myStatus_(ev) {
  try { return ev.getMyStatus(); } catch (e) { return null; }
}

// ======================================================================
// 2) 오늘 할 일 ⇄ Google 할 일
//    대시보드 항목에 gtask(Google 할 일 id)와 gsync(마지막으로 맞춘 상태)를 적어 둔다.
//    양쪽을 gsync와 비교해서 바뀐 쪽을 반대쪽에 반영한다. 둘 다 바뀌었으면 대시보드가 이긴다.
//    KNOWN_TASKS(스크립트 속성) = 지난번에 연결돼 있던 Google 할 일 id. 대시보드에서 지운 것을 알아내는 데 쓴다.
// ======================================================================

function syncTasks() {
  const tz = Session.getScriptTimeZone();
  const today = Utilities.formatDate(new Date(), tz, 'yyyy-MM-dd');
  const tasks = listTasks_();
  const byId = {};
  tasks.forEach((t) => { byId[t.id] = t; });
  const known = new Set(JSON.parse(PROPS.getProperty('KNOWN_TASKS') || '[]'));
  const remote = readFile_(CONFIG.paths.todos);
  const items = (remote.data && remote.data.items) || [];
  const ops = []; // 대시보드에 적용할 변경 (저장 충돌 시 최신본에 다시 적용)
  const nextKnown = new Set();
  const linked = new Set();

  items.forEach((item) => {
    const repeating = item.repeat === 'daily' || item.repeat === 'weekly';
    // 날짜 없는 한 번 할 일(아직 안 끝난 것)은 오늘 날짜를 넣는다 → Google 캘린더 오늘 칸에 보이게.
    // 이미 연결된 할 일이면 아래에서 "대시보드가 바뀜"으로 보고 Google 마감일도 오늘로 맞춘다.
    if (!repeating && !item.doneOn && !item.date) {
      item.date = today;
      ops.push({ op: 'set', id: item.id, fields: { date: today } });
    }
    if (item.gtask) {
      const task = byId[item.gtask];
      if (repeating) { // 반복으로 바뀜 → Google에서 빼고 연결 해제
        if (task) removeTask_(task.id);
        ops.push({ op: 'set', id: item.id, fields: { gtask: null, gsync: null } });
        return;
      }
      if (!task) { // Google에서 지움(또는 다른 목록으로 옮김) → 대시보드에서도 삭제
        ops.push({ op: 'remove', id: item.id });
        return;
      }
      linked.add(task.id);
      nextKnown.add(task.id);
      const dn = dashState_(item);
      const tn = taskState_(task);
      if (dn === tn) {
        if (item.gsync !== dn) ops.push({ op: 'set', id: item.id, fields: { gsync: dn } });
      } else if (dn !== item.gsync) { // 대시보드에서 바뀜 → Google에 반영
        patchTask_(task.id, item);
        ops.push({ op: 'set', id: item.id, fields: { gsync: dn } });
      } else { // Google에서 바뀜 → 대시보드에 반영
        const f = fieldsFromTask_(task, item, tz);
        f.gsync = tn;
        ops.push({ op: 'set', id: item.id, fields: f });
      }
      return;
    }
    if (repeating) return; // 매일·요일 반복은 연동하지 않음
    if (item.doneOn && item.doneOn < today) return; // 이미 지난 완료 항목은 올리지 않음
    const created = insertTask_(item);
    nextKnown.add(created.id);
    saveKnown_([...known, ...nextKnown]); // todos 저장이 실패해도 다음번에 중복으로 가져오지 않게
    ops.push({ op: 'set', id: item.id, fields: { gtask: created.id, gsync: dashState_(item) } });
  });

  tasks.forEach((task) => {
    if (linked.has(task.id) || nextKnown.has(task.id)) return;
    if (known.has(task.id)) { // 연결돼 있었는데 대시보드에서 지움 → Google에서도 삭제 (완료된 기록은 남김)
      if (task.status !== 'completed') removeTask_(task.id);
      return;
    }
    if (!task.title) return;
    if (task.status === 'completed' && localDay_(task.completed, tz) < today) return; // 지난 완료 기록은 가져오지 않음
    const f = fieldsFromTask_(task, {}, tz);
    const item = {
      id: 't_' + Date.now().toString(36) + Utilities.getUuid().slice(0, 4),
      title: f.title, repeat: 'none', doneOn: f.doneOn,
      createdAt: Utilities.formatDate(new Date(), tz, "yyyy-MM-dd'T'HH:mm:ssXXX"),
      gtask: task.id, gsync: taskState_(task),
    };
    if (f.date || !f.doneOn) item.date = f.date || today; // 날짜 없으면 오늘 (다음 동기화 때 Google 마감일도 오늘로)
    if (f.time) item.time = f.time;
    ops.push({ op: 'add', item: item });
    nextKnown.add(task.id);
  });

  let latest = items;
  if (ops.length) {
    latest = mutateFile_(CONFIG.paths.todos, (d) => applyOps_(d, ops), 'todo: Google 할 일 동기화 (' + ops.length + '건)').items;
  }
  saveKnown_([...nextKnown]);
  scheduleAlerts_(latest, tz);
  refreshSubscriptions_();
}

// 스크립트 속성 하나는 9KB까지라서 최근 것만 남긴다
function saveKnown_(ids) {
  PROPS.setProperty('KNOWN_TASKS', JSON.stringify(ids.slice(-300)));
}

function applyOps_(d, ops) {
  d.items = d.items || [];
  ops.forEach((o) => {
    if (o.op === 'remove') { d.items = d.items.filter((i) => i.id !== o.id); return; }
    if (o.op === 'add') {
      if (!d.items.some((i) => i.gtask === o.item.gtask)) d.items.push(o.item);
      return;
    }
    const it = d.items.find((i) => i.id === o.id);
    if (!it) return;
    Object.keys(o.fields).forEach((k) => {
      if (o.fields[k] === null || o.fields[k] === undefined) delete it[k];
      else it[k] = o.fields[k];
    });
  });
  return d;
}

// 양쪽을 비교하기 위한 상태 문자열 (제목·날짜·시간·완료)
function dashState_(item) {
  return JSON.stringify([item.title || '', item.date || '', item.time || '', item.doneOn ? 1 : 0]);
}
function taskState_(task) {
  const t = splitTime_(task.title || '');
  return JSON.stringify([t.title, task.due ? task.due.slice(0, 10) : '', t.time, task.status === 'completed' ? 1 : 0]);
}
// "15:00 병원" → { time: "15:00", title: "병원" }
function splitTime_(title) {
  const m = title.match(/^\s*(\d{1,2}):(\d{2})\s+(.+)$/);
  if (!m || Number(m[1]) > 23 || Number(m[2]) > 59) return { time: '', title: title.trim() };
  return { time: ('0' + m[1]).slice(-2) + ':' + m[2], title: m[3].trim() };
}
function fieldsFromTask_(task, item, tz) {
  const t = splitTime_(task.title || '');
  const done = task.status === 'completed';
  return {
    title: t.title,
    date: task.due ? task.due.slice(0, 10) : null,
    time: t.time || null,
    doneOn: done ? (item.doneOn || localDay_(task.completed, tz)) : null,
  };
}
function localDay_(iso, tz) {
  return iso ? Utilities.formatDate(new Date(iso), tz, 'yyyy-MM-dd')
    : Utilities.formatDate(new Date(), tz, 'yyyy-MM-dd');
}
function taskResource_(item) {
  return {
    title: (item.time ? item.time + ' ' : '') + item.title,
    due: item.date ? item.date + 'T00:00:00.000Z' : null,
    status: item.doneOn ? 'completed' : 'needsAction',
    completed: item.doneOn ? new Date().toISOString() : null,
  };
}

function listTasks_() {
  const out = [];
  let pageToken;
  do {
    const res = Tasks.Tasks.list(CONFIG.taskList, {
      showCompleted: true, showHidden: true, showDeleted: false, maxResults: 100, pageToken: pageToken,
    });
    (res.items || []).forEach((t) => { if (!t.deleted) out.push(t); });
    pageToken = res.nextPageToken;
  } while (pageToken);
  return out;
}
function insertTask_(item) {
  const r = taskResource_(item);
  if (!r.due) delete r.due;
  if (!r.completed) delete r.completed;
  return Tasks.Tasks.insert(r, CONFIG.taskList);
}
function patchTask_(id, item) {
  Tasks.Tasks.patch(taskResource_(item), CONFIG.taskList, id);
}
function removeTask_(id) {
  try { Tasks.Tasks.remove(CONFIG.taskList, id); } catch (e) { Logger.log('할 일 삭제 실패 %s: %s', id, e); }
}

// ======================================================================
// 3) 알림: 언제 보낼지 정해 두고(scheduleAlerts_), 1분마다 때가 된 것을 보낸다(sendDuePushes_).
//    푸시에는 내용을 싣지 않는다(암호화가 필요해서). 폰의 서비스워커가 받아서 할 일을 읽고 알림을 만든다.
// ======================================================================

function scheduleAlerts_(items, tz) {
  const today = Utilities.formatDate(new Date(), tz, 'yyyy-MM-dd');
  const tomorrow = addDays_(today, 1);
  const list = [];
  [today, tomorrow].forEach((day) => {
    items.forEach((it) => {
      if (!it.time || !dueOn_(it, day) || isDoneOn_(it, day)) return;
      const hm = it.time.split(':').map(Number);
      const at = parseLocal_(day, tz).getTime() + (hm[0] * 60 + hm[1] - CONFIG.alertBeforeMinutes) * 60000;
      list.push({ k: 'a:' + it.id + ':' + day + ':' + it.time, at: at });
    });
  });
  // 오후 5시 묶음: 오늘 못 한 일 또는 내일 할 일이 있을 때만
  const left = items.some((it) => visibleOn_(it, today) && !isDoneOn_(it, today));
  const next = items.some((it) => !isRepeating_(it) && !it.doneOn && it.date === tomorrow);
  if (left || next) list.push({ k: 's:' + today, at: parseLocal_(today, tz).getTime() + CONFIG.summaryHour * 3600000 });
  PROPS.setProperty('ALERTS', JSON.stringify(list));
}

function sendDuePushes_() {
  const now = Date.now();
  const alerts = JSON.parse(PROPS.getProperty('ALERTS') || '[]');
  const sent = JSON.parse(PROPS.getProperty('SENT') || '{}');
  const due = alerts.filter((a) => a.at <= now && now - a.at < 15 * 60000 && !sent[a.k]);
  if (!due.length) return;
  const subs = JSON.parse(PROPS.getProperty('PUSH_SUBS') || '[]');
  const gone = [];
  subs.forEach((s) => {
    const code = sendPush_(s.endpoint);
    if (code === 404 || code === 410) gone.push(s.endpoint); // 폰에서 구독이 사라짐
    else if (code >= 300) Logger.log('푸시 실패 %s: %s', code, s.endpoint.slice(0, 60));
  });
  due.forEach((a) => { sent[a.k] = now; });
  Object.keys(sent).forEach((k) => { if (now - sent[k] > 2 * 86400000) delete sent[k]; });
  PROPS.setProperty('SENT', JSON.stringify(sent));
  if (gone.length) {
    mutateFile_(CONFIG.paths.push, (d) => {
      d.subscriptions = (d.subscriptions || []).filter((s) => gone.indexOf(s.endpoint) < 0);
      return d;
    }, 'push: 만료된 구독 정리');
    refreshSubscriptions_();
  }
}

// push.json의 구독 목록을 스크립트 속성에 복사해 둔다 (1분마다 GitHub를 읽지 않으려고)
function refreshSubscriptions_() {
  const d = readFile_(CONFIG.paths.push).data || {};
  PROPS.setProperty('PUSH_SUBS', JSON.stringify((d.subscriptions || []).map((s) => ({ endpoint: s.endpoint }))));
}

// 같은 규칙을 웹앱 rules.js에도 둔다 (반복: daily/weekly+days, 한 번: date)
function isRepeating_(it) { return it.repeat === 'daily' || it.repeat === 'weekly'; }
function dueOn_(it, day) {
  if (it.repeat === 'daily') return true;
  if (it.repeat === 'weekly') return (it.days || []).indexOf(new Date(day + 'T12:00:00Z').getUTCDay()) >= 0;
  return it.date === day;
}
function isDoneOn_(it, day) { return isRepeating_(it) ? it.doneOn === day : !!it.doneOn; }
function visibleOn_(it, day) {
  if (isRepeating_(it)) return dueOn_(it, day) || it.doneOn === day;
  if (it.doneOn) return it.doneOn >= day;
  return !it.date || it.date <= day;
}
function addDays_(day, n) {
  return Utilities.formatDate(new Date(new Date(day + 'T12:00:00Z').getTime() + n * 86400000), 'UTC', 'yyyy-MM-dd');
}

// ---------- Web Push (VAPID, RFC 8292) ----------

function ensureVapidKeys_() {
  let pub = PROPS.getProperty('VAPID_PUBLIC');
  if (!pub || !PROPS.getProperty('VAPID_PRIVATE')) {
    const d = randomScalar_();
    pub = b64url_(P256.publicKey(d));
    PROPS.setProperty('VAPID_PRIVATE', d.toString(16));
    PROPS.setProperty('VAPID_PUBLIC', pub);
  }
  mutateFile_(CONFIG.paths.push, (d) => {
    d.version = 1;
    d.vapidPublicKey = pub;
    d.subscriptions = d.subscriptions || [];
    return d;
  }, 'push: 알림 공개키 등록');
}

function sendPush_(endpoint) {
  const aud = endpoint.match(/^https:\/\/[^/]+/)[0];
  const res = UrlFetchApp.fetch(endpoint, {
    method: 'post',
    muteHttpExceptions: true,
    payload: '',
    headers: {
      TTL: '900',
      Urgency: 'high',
      Authorization: 'vapid t=' + vapidJwt_(aud) + ', k=' + PROPS.getProperty('VAPID_PUBLIC'),
    },
  });
  return res.getResponseCode();
}

function vapidJwt_(aud) {
  const enc = (o) => b64url_(Utilities.newBlob(JSON.stringify(o)).getBytes());
  const unsigned = enc({ typ: 'JWT', alg: 'ES256' }) + '.'
    + enc({ aud: aud, exp: Math.floor(Date.now() / 1000) + 12 * 3600, sub: CONFIG.pushContact });
  const d = BigInt('0x' + PROPS.getProperty('VAPID_PRIVATE'));
  const sig = P256.sign(sha256_(Utilities.newBlob(unsigned).getBytes()), d, hmacSha256_);
  return unsigned + '.' + b64url_(sig);
}

/** 알림 키와 서명이 이 계정의 Apps Script에서 동작하는지 확인 (실행 로그에 "서명 확인 OK") */
function testPushCrypto() {
  const d = randomScalar_();
  const pub = P256.publicKey(d);
  const h = sha256_(Utilities.newBlob('test').getBytes());
  const sig = P256.sign(h, d, hmacSha256_);
  Logger.log(P256.verify(h, sig, pub) ? '서명 확인 OK' : '서명 확인 실패');
}

/** 지금 바로 모든 폰에 신호 보내기 (알림 연결 확인용. 보낼 할 일이 없으면 "새 알림이 없어요"가 떠요) */
function testPushNow() {
  refreshSubscriptions_();
  JSON.parse(PROPS.getProperty('PUSH_SUBS') || '[]').forEach((s) => {
    Logger.log('%s → %s', s.endpoint.slice(0, 50), sendPush_(s.endpoint));
  });
}

// 바이트: Apps Script는 -128~127, 계산은 0~255로 한다
function u8_(bytes) { return bytes.map((b) => (b + 256) % 256); }
function s8_(bytes) { return bytes.map((b) => (b > 127 ? b - 256 : b)); }
function b64url_(bytes) { return Utilities.base64EncodeWebSafe(s8_(Array.from(bytes))).replace(/=+$/, ''); }
function sha256_(bytes) { return u8_(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, s8_(Array.from(bytes)))); }
function hmacSha256_(key, msg) { return u8_(Utilities.computeHmacSha256Signature(s8_(Array.from(msg)), s8_(Array.from(key)))); }
function randomScalar_() {
  const seed = [Utilities.getUuid(), Utilities.getUuid(), Utilities.getUuid(), String(Date.now())].join('|');
  const h = sha256_(Utilities.newBlob(seed).getBytes());
  return P256.toScalar(h);
}

// ---------- P-256 ECDSA (BigInt) ----------
// 웹 푸시 VAPID 서명(ES256)에만 쓰는 최소 구현. 검증: scripts/test_p256.js (Node의 crypto로 서명 확인)
const P256 = (function () {
  const B = (h) => BigInt('0x' + h);
  const P = B('ffffffff00000001000000000000000000000000ffffffffffffffffffffffff');
  const N = B('ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551');
  const A = P - BigInt(3);
  const G = [B('6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296'),
    B('4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5')];
  const ZERO = BigInt(0);
  const ONE = BigInt(1);
  const TWO = BigInt(2);
  const THREE = BigInt(3);
  const mod = (a, m) => { const r = a % m; return r < ZERO ? r + m : r; };
  function inv(a, m) { // 확장 유클리드
    let r0 = mod(a, m); let r1 = m; let s0 = ONE; let s1 = ZERO;
    while (r1 !== ZERO) {
      const q = r0 / r1;
      [r0, r1] = [r1, r0 - q * r1];
      [s0, s1] = [s1, s0 - q * s1];
    }
    return mod(s0, m);
  }
  function add(p1, p2) { // 아핀 좌표, null = 무한원점
    if (!p1) return p2;
    if (!p2) return p1;
    if (p1[0] === p2[0]) {
      if (mod(p1[1] + p2[1], P) === ZERO) return null;
      const l = mod((THREE * p1[0] * p1[0] + A) * inv(TWO * p1[1], P), P);
      const x = mod(l * l - TWO * p1[0], P);
      return [x, mod(l * (p1[0] - x) - p1[1], P)];
    }
    const l = mod((p2[1] - p1[1]) * inv(p2[0] - p1[0], P), P);
    const x = mod(l * l - p1[0] - p2[0], P);
    return [x, mod(l * (p1[0] - x) - p1[1], P)];
  }
  function mul(k, pt) {
    let r = null;
    let q = pt;
    while (k > ZERO) {
      if (k & ONE) r = add(r, q);
      q = add(q, q);
      k >>= ONE;
    }
    return r;
  }
  const toInt = (bytes) => bytes.reduce((acc, b) => (acc << BigInt(8)) + BigInt(b), ZERO);
  const toBytes = (x) => { const out = []; for (let i = 0; i < 32; i++) { out.unshift(Number(x & BigInt(255))); x >>= BigInt(8); } return out; };
  const toScalar = (bytes) => mod(toInt(bytes), N - ONE) + ONE;
  return {
    toScalar: toScalar,
    publicKey(d) { const q = mul(d, G); return [4].concat(toBytes(q[0]), toBytes(q[1])); },
    // hash: 32바이트(0~255). k는 개인키와 해시, 시도 횟수로 HMAC해서 만든다 (같은 메시지면 같은 k → 안전)
    sign(hash, d, hmac) {
      const z = toInt(hash);
      for (let i = 0; i < 100; i++) {
        const k = toScalar(hmac(toBytes(d), hash.concat([i])));
        const r = mod(mul(k, G)[0], N);
        const s = mod(inv(k, N) * (z + r * d), N);
        if (r !== ZERO && s !== ZERO) return toBytes(r).concat(toBytes(s));
      }
      throw new Error('서명 실패');
    },
    verify(hash, sig, pub) {
      const r = toInt(sig.slice(0, 32)); const s = toInt(sig.slice(32));
      const q = [toInt(pub.slice(1, 33)), toInt(pub.slice(33))];
      const w = inv(s, N);
      const pt = add(mul(mod(toInt(hash) * w, N), G), mul(mod(r * w, N), q));
      return !!pt && mod(pt[0], N) === r;
    },
  };
})();

// ======================================================================
// 공통: 날짜, GitHub Contents API
// ======================================================================

// 'yyyy-MM-dd'를 스크립트 시간대의 그날 0시 Date로 바꾼다
function parseLocal_(day, tz) {
  const offset = Utilities.formatDate(new Date(day + 'T12:00:00Z'), tz, 'XXX'); // 예: +09:00
  return new Date(day + 'T00:00:00' + (offset === 'Z' ? '+00:00' : offset));
}

function getToken_() {
  const token = PROPS.getProperty('GITHUB_TOKEN');
  if (!token) {
    throw new Error('스크립트 속성에 GITHUB_TOKEN이 없습니다. 프로젝트 설정(톱니바퀴) → 스크립트 속성에서 추가하세요.');
  }
  return token;
}

function api_(method, path, payload) {
  const url = 'https://api.github.com/repos/' + CONFIG.owner + '/' + CONFIG.repo + '/contents/' + path
    + (method === 'get' ? '?ref=' + encodeURIComponent(CONFIG.branch) : '');
  const options = {
    method: method,
    muteHttpExceptions: true,
    headers: {
      Authorization: 'Bearer ' + getToken_(),
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
    },
  };
  if (payload) {
    options.contentType = 'application/json';
    options.payload = JSON.stringify(payload);
  }
  return UrlFetchApp.fetch(url, options);
}

function readFile_(path) {
  const res = api_('get', path);
  const code = res.getResponseCode();
  if (code === 404) return { data: null, sha: null };
  if (code !== 200) throw new Error('GitHub 읽기 실패 ' + code + ': ' + res.getContentText().slice(0, 200));
  const j = JSON.parse(res.getContentText());
  const text = Utilities.newBlob(Utilities.base64Decode(j.content.replace(/\s/g, ''))).getDataAsString('UTF-8');
  return { data: JSON.parse(text), sha: j.sha };
}

// 저장. 충돌(409/422)이면 false
function put_(path, data, sha, message) {
  const body = {
    message: message,
    content: Utilities.base64Encode(JSON.stringify(data, null, 2) + '\n', Utilities.Charset.UTF_8),
    branch: CONFIG.branch,
  };
  if (sha) body.sha = sha;
  const res = api_('put', path, body);
  const code = res.getResponseCode();
  if (code === 200 || code === 201) return true;
  if (code === 409 || code === 422) return false;
  throw new Error('GitHub 저장 실패 ' + code + ': ' + res.getContentText().slice(0, 200));
}

function writeFile_(path, data, sha, message) {
  // 캘린더 파일은 이 스크립트만 쓰지만, 혹시 충돌하면 최신 sha로 다시 시도한다
  for (let attempt = 0; attempt < 4; attempt++) {
    if (put_(path, data, sha, message)) return;
    Utilities.sleep(1000 * (attempt + 1));
    sha = readFile_(path).sha;
  }
  throw new Error('GitHub 저장 실패: 충돌이 계속됩니다.');
}

// 최신본 읽기 → change 적용 → 저장. 폰과 동시에 고쳐서 충돌하면 다시 읽고 다시 적용한다.
function mutateFile_(path, change, message) {
  for (let attempt = 0; attempt < 4; attempt++) {
    const cur = readFile_(path);
    const data = change(cur.data || { version: 1, items: [] });
    data.updatedAt = Utilities.formatDate(new Date(), Session.getScriptTimeZone(), "yyyy-MM-dd'T'HH:mm:ssXXX");
    if (put_(path, data, cur.sha, message)) return data;
    Utilities.sleep(800 * (attempt + 1));
  }
  throw new Error('GitHub 저장 실패: 충돌이 계속됩니다 (' + path + ')');
}

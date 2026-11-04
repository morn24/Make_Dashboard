// integrations/google-calendar/Code.gs 테스트 (Google·GitHub를 흉내 내서 Node에서 실행)
// 실행: node scripts/test_apps_script.js
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const crypto = require('crypto');
const assert = require('assert');

const code = fs.readFileSync(path.join(__dirname, '../integrations/google-calendar/Code.gs'), 'utf8');

const s8 = (a) => a.map((b) => (b > 127 ? b - 256 : b));
const u8buf = (a) => Buffer.from(a.map((b) => (b + 256) % 256));

function makeContext({ now = '2026-11-04T10:00:00+09:00' } = {}) {
  const props = {};
  const github = {}; // path -> { data, sha }
  const tasks = []; // Google 할 일
  const calls = [];
  let shaN = 0;
  let idN = 0;
  const fixedNow = new Date(now).getTime();
  class FakeDate extends Date {
    constructor(...a) { if (a.length) super(...a); else super(fixedNow); }
    static now() { return fixedNow; }
  }
  const pad = (n) => String(n).padStart(2, '0');
  const ctx = {
    console, BigInt, Set, JSON, Math, Number, String, Array, Object, Error, Buffer,
    Date: FakeDate,
    PropertiesService: { getScriptProperties: () => ({
      getProperty: (k) => (k in props ? props[k] : null),
      setProperty: (k, v) => { props[k] = String(v); },
    }) },
    Session: { getScriptTimeZone: () => 'Asia/Seoul' },
    Logger: { log: () => {} },
    Utilities: {
      // 이 테스트에서 쓰는 형식만 흉내 낸다 (시간대는 +09:00 고정)
      formatDate(d, tz, fmt) {
        const off = tz === 'UTC' ? 0 : 9;
        const k = new Date(d.getTime() + off * 3600000);
        const Y = k.getUTCFullYear(); const M = pad(k.getUTCMonth() + 1); const D = pad(k.getUTCDate());
        if (fmt === 'XXX') return off ? '+09:00' : 'Z';
        if (fmt === 'yyyy-MM-dd') return `${Y}-${M}-${D}`;
        return `${Y}-${M}-${D}T${pad(k.getUTCHours())}:${pad(k.getUTCMinutes())}:${pad(k.getUTCSeconds())}+09:00`;
      },
      getUuid: () => crypto.randomUUID(),
      newBlob: (s) => ({ getBytes: () => s8([...Buffer.from(s, 'utf8')]) }),
      base64EncodeWebSafe: (a) => u8buf(a).toString('base64').replace(/\+/g, '-').replace(/\//g, '_'),
      computeDigest: (_alg, a) => s8([...crypto.createHash('sha256').update(u8buf(a)).digest()]),
      computeHmacSha256Signature: (v, k) => s8([...crypto.createHmac('sha256', u8buf(k)).update(u8buf(v)).digest()]),
      DigestAlgorithm: { SHA_256: 'sha256' },
      sleep: () => {},
    },
    Tasks: { Tasks: {
      list: () => ({ items: tasks.map((t) => ({ ...t })) }),
      insert: (r) => { const t = { id: `g${++idN}`, ...r }; tasks.push(t); calls.push(['insert', t.title]); return t; },
      patch: (r, _l, id) => {
        const t = tasks.find((x) => x.id === id);
        Object.entries(r).forEach(([k, v]) => { if (v === null) delete t[k]; else t[k] = v; });
        calls.push(['patch', t.title]);
      },
      remove: (_l, id) => { tasks.splice(tasks.findIndex((x) => x.id === id), 1); calls.push(['remove', id]); },
    } },
  };
  vm.createContext(ctx);
  vm.runInContext(code + '\nthis.P256 = P256; this.CONFIG = CONFIG;', ctx); // const는 바깥에서 안 보여서 꺼내 둔다
  // GitHub 흉내
  ctx.readFile_ = (p) => (github[p] ? structuredClone(github[p]) : { data: null, sha: null });
  ctx.put_ = (p, data, sha) => {
    if ((github[p] && github[p].sha) !== (sha || undefined) && !(sha == null && !github[p])) return false;
    github[p] = { data: structuredClone(data), sha: `s${++shaN}` };
    return true;
  };
  ctx.sendPush_ = (endpoint) => { calls.push(['push', endpoint]); return 201; };
  props.GITHUB_TOKEN = 'x';
  return { ctx, props, github, tasks, calls };
}

let passed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log(`ok - ${name}`); } catch (e) { console.log(`FAIL - ${name}`); throw e; }
}

const todos = (items) => ({ data: { version: 1, items }, sha: 's0' });

test('P-256 서명을 Node crypto가 검증한다', () => {
  const { ctx } = makeContext();
  for (let i = 0; i < 5; i++) {
    const d = ctx.randomScalar_();
    const pub = ctx.P256.publicKey(d);
    const msg = Buffer.from(`hello ${i}`);
    const hash = [...crypto.createHash('sha256').update(msg).digest()];
    const sig = ctx.P256.sign(hash, d, ctx.hmacSha256_);
    const key = crypto.createPublicKey({ key: { kty: 'EC', crv: 'P-256',
      x: Buffer.from(pub.slice(1, 33)).toString('base64url'), y: Buffer.from(pub.slice(33)).toString('base64url') }, format: 'jwk' });
    assert.ok(crypto.verify('sha256', msg, { key, dsaEncoding: 'ieee-p1363' }, Buffer.from(sig)));
    assert.ok(ctx.P256.verify(hash, sig, pub));
  }
});

test('VAPID JWT가 올바른 ES256 서명이다', () => {
  const { ctx, props, github } = makeContext();
  ctx.ensureVapidKeys_();
  assert.strictEqual(github['data/push.json'].data.vapidPublicKey, props.VAPID_PUBLIC);
  const jwt = ctx.vapidJwt_('https://fcm.googleapis.com');
  const [h, p, s] = jwt.split('.');
  const pub = Buffer.from(props.VAPID_PUBLIC, 'base64url');
  assert.strictEqual(pub.length, 65);
  const key = crypto.createPublicKey({ key: { kty: 'EC', crv: 'P-256',
    x: pub.subarray(1, 33).toString('base64url'), y: pub.subarray(33).toString('base64url') }, format: 'jwk' });
  assert.ok(crypto.verify('sha256', Buffer.from(`${h}.${p}`), { key, dsaEncoding: 'ieee-p1363' }, Buffer.from(s, 'base64url')));
  const claims = JSON.parse(Buffer.from(p, 'base64url'));
  assert.strictEqual(claims.aud, 'https://fcm.googleapis.com');
});

test('대시보드 한 번 할 일 → Google 할 일 생성 (시간은 제목 앞), 매일 할 일은 제외', () => {
  const { ctx, github, tasks } = makeContext();
  github['data/todos.json'] = todos([
    { id: 't1', title: '병원', repeat: 'none', date: '2026-11-04', time: '15:00', doneOn: null },
    { id: 't2', title: '듀오링고', repeat: 'daily', doneOn: null },
    { id: 't3', title: '지난 일', repeat: 'none', date: '2026-11-01', doneOn: '2026-11-01' },
  ]);
  ctx.syncTasks();
  assert.strictEqual(tasks.length, 1);
  assert.strictEqual(tasks[0].title, '15:00 병원');
  assert.strictEqual(tasks[0].due, '2026-11-04T00:00:00.000Z');
  const t1 = github['data/todos.json'].data.items.find((i) => i.id === 't1');
  assert.strictEqual(t1.gtask, tasks[0].id);
  assert.ok(!github['data/todos.json'].data.items.find((i) => i.id === 't2').gtask);
});

test('Google에서 만든 할 일 → 대시보드에 추가 (시간·날짜 읽기), 지난 완료는 가져오지 않음', () => {
  const { ctx, github, tasks } = makeContext();
  github['data/todos.json'] = todos([]);
  tasks.push({ id: 'gA', title: '09:30 회의', due: '2026-11-05T00:00:00.000Z', status: 'needsAction' });
  tasks.push({ id: 'gB', title: '옛날 일', status: 'completed', completed: '2026-10-01T01:00:00.000Z' });
  ctx.syncTasks();
  const items = github['data/todos.json'].data.items;
  assert.strictEqual(items.length, 1);
  assert.deepStrictEqual([items[0].title, items[0].date, items[0].time, items[0].gtask], ['회의', '2026-11-05', '09:30', 'gA']);
});

test('완료·이름 변경이 양쪽으로 반영된다', () => {
  const { ctx, github, tasks } = makeContext();
  github['data/todos.json'] = todos([{ id: 't1', title: '보고서', repeat: 'none', date: '2026-11-04', doneOn: null }]);
  ctx.syncTasks();
  // Google에서 완료
  tasks[0].status = 'completed';
  tasks[0].completed = '2026-11-04T03:00:00.000Z';
  ctx.syncTasks();
  assert.strictEqual(github['data/todos.json'].data.items[0].doneOn, '2026-11-04');
  // 대시보드에서 이름 바꾸고 완료 취소
  const it = github['data/todos.json'].data.items[0];
  it.title = '보고서 제출';
  it.doneOn = null;
  ctx.syncTasks();
  assert.strictEqual(tasks[0].title, '보고서 제출');
  assert.strictEqual(tasks[0].status, 'needsAction');
  assert.ok(!('completed' in tasks[0]));
});

test('한쪽에서 지우면 다른 쪽에서도 지워진다', () => {
  const { ctx, github, tasks, calls } = makeContext();
  github['data/todos.json'] = todos([
    { id: 't1', title: 'A', repeat: 'none', date: '2026-11-04', doneOn: null },
    { id: 't2', title: 'B', repeat: 'none', date: '2026-11-04', doneOn: null },
  ]);
  ctx.syncTasks();
  assert.strictEqual(tasks.length, 2);
  // 대시보드에서 A 삭제
  github['data/todos.json'].data.items = github['data/todos.json'].data.items.filter((i) => i.id !== 't1');
  // Google에서 B 삭제
  const gB = github['data/todos.json'].data.items[0].gtask;
  tasks.splice(tasks.findIndex((t) => t.id === gB), 1);
  ctx.syncTasks();
  assert.strictEqual(tasks.length, 0);
  assert.strictEqual(github['data/todos.json'].data.items.length, 0);
  assert.ok(calls.some((c) => c[0] === 'remove'));
});

test('반복으로 바꾸면 Google에서 빠진다', () => {
  const { ctx, github, tasks } = makeContext();
  github['data/todos.json'] = todos([{ id: 't1', title: 'A', repeat: 'none', date: '2026-11-04', doneOn: null }]);
  ctx.syncTasks();
  github['data/todos.json'].data.items[0].repeat = 'daily';
  ctx.syncTasks();
  assert.strictEqual(tasks.length, 0);
  assert.ok(!github['data/todos.json'].data.items[0].gtask);
});

test('알림 일정: 10분 전 + 오후 5시 묶음, 때가 되면 한 번만 보낸다', () => {
  const { ctx, props, github, calls } = makeContext({ now: '2026-11-04T14:51:00+09:00' });
  github['data/push.json'] = { data: { subscriptions: [{ endpoint: 'https://fcm.googleapis.com/x' }] }, sha: 'p0' };
  github['data/todos.json'] = todos([
    { id: 't1', title: '병원', repeat: 'none', date: '2026-11-04', time: '15:00', doneOn: null },
    { id: 't2', title: '운동', repeat: 'daily', time: '07:00', doneOn: null },
  ]);
  ctx.syncTasks();
  const alerts = JSON.parse(props.ALERTS);
  const at = (k) => new Date(alerts.find((a) => a.k.startsWith(k)).at).toISOString();
  assert.strictEqual(at('a:t1:2026-11-04'), '2026-11-04T05:50:00.000Z'); // 14:50 KST
  assert.strictEqual(at('a:t2:2026-11-05'), '2026-11-04T21:50:00.000Z'); // 내일 06:50 KST
  assert.strictEqual(at('s:2026-11-04'), '2026-11-04T08:00:00.000Z'); // 17:00 KST
  ctx.sendDuePushes_();
  ctx.sendDuePushes_();
  assert.strictEqual(calls.filter((c) => c[0] === 'push').length, 1);
});

console.log(`\n${passed}개 통과`);

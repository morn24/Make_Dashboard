'use strict';
// 1) 앱 화면 파일을 캐시해서 오프라인에서도 열리게 한다. 데이터(api.github.com)는 캐시하지 않는다.
// 2) 알림: Apps Script가 알릴 시각에 내용 없는 푸시(신호)만 보내면, 여기서 할 일을 읽어 알림을 만든다.
importScripts('rules.js');

const CACHE = 'dash-shell-v12';
const PRIVATE_CACHE = 'dash-private'; // 앱이 넣어 둔 연결 정보(cfg). 버전이 바뀌어도 지우지 않는다
const SHELL = ['./', 'index.html', 'style.css', 'rules.js', 'app.js', 'manifest.webmanifest',
  'icons/icon.svg', 'icons/icon-192.png', 'icons/badge-96.png'];
const ICON = 'icons/icon-192.png';
const BADGE = 'icons/badge-96.png'; // 상단바 아이콘: 투명 배경 + 흰 선 (흰 네모로 보이지 않게)

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE && k !== PRIVATE_CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

// 네트워크 우선, 실패하면 캐시 (배포하면 바로 새 버전이 보이도록)
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== self.location.origin) return;
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        if (res.ok) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(e.request, copy));
        }
        return res;
      })
      .catch(() => caches.match(e.request).then((r) => r || caches.match('index.html'))),
  );
});

// ---------- 알림 ----------

async function readTodos() {
  const res = await (await caches.open(PRIVATE_CACHE)).match('cfg');
  if (!res) return null;
  const cfg = await res.json();
  const url = `https://api.github.com/repos/${encodeURIComponent(cfg.owner)}/${encodeURIComponent(cfg.repo)}`
    + `/contents/data/todos.json?ref=${encodeURIComponent(cfg.branch)}`;
  const r = await fetch(url, {
    cache: 'no-store',
    headers: { Accept: 'application/vnd.github.raw+json', Authorization: `Bearer ${cfg.token}`, 'X-GitHub-Api-Version': '2022-11-28' },
  });
  if (!r.ok) throw new Error(`GitHub ${r.status}`);
  return (await r.json()).items || [];
}

const list = (arr) => (arr.length > 6 ? [...arr.slice(0, 6), `외 ${arr.length - 6}개`] : arr).join('\n');

// 지금 띄울 알림을 고른다. 같은 tag는 덮어쓰므로 신호가 두 번 와도 알림이 겹치지 않는다.
function pick(items, now) {
  const out = [];
  // 시작 10분 전 알림: 신호가 조금 늦거나 일러도 잡히게 앞뒤 여유를 둔다
  for (const a of alertsBetween(items, new Date(now - 15 * 60000), new Date(now + 2 * 60000))) {
    out.push({
      title: `${ALERT_BEFORE_MIN}분 뒤 · ${a.item.time}`,
      body: a.item.title,
      tag: `alert-${a.item.id}-${a.day}`,
    });
  }
  // 오후 5시 묶음 알림 (5시~5시 30분 사이에 받은 신호)
  const mins = now.getHours() * 60 + now.getMinutes();
  if (mins >= SUMMARY_HOUR * 60 && mins < SUMMARY_HOUR * 60 + 30) {
    const today = localDate(now);
    const { left, next } = summaryLists(items, today);
    if (left.length) out.push({ title: `오늘 아직 못 한 일 ${left.length}개`, body: list(left), tag: `left-${today}` });
    if (next.length) out.push({ title: `내일 할 일 ${next.length}개`, body: list(next), tag: `next-${today}` });
  }
  return out;
}

self.addEventListener('push', (e) => {
  e.waitUntil((async () => {
    let notes = [];
    try {
      const items = await readTodos();
      if (items) notes = pick(items, new Date());
    } catch (err) {
      notes = [{ title: '대시보드', body: `할 일을 불러오지 못했어요 (${err.message})`, tag: 'error' }];
    }
    // 크롬은 푸시를 받고 알림을 하나도 안 띄우면 기본 알림을 띄운다. 이미 보낸 알림이면 조용히 다시 띄운다.
    if (!notes.length) {
      const shown = await self.registration.getNotifications();
      if (shown.length) return;
      notes = [{ title: '대시보드', body: '새 알림이 없어요', tag: 'empty' }];
    }
    await Promise.all(notes.map((n) => self.registration.showNotification(n.title, {
      body: n.body, tag: n.tag, icon: ICON, badge: BADGE, renotify: false, data: { url: './' },
    })));
  })());
});

// 알림을 누르면 열려 있는 앱으로 가거나 새로 연다
self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const win = wins.find((w) => w.url.startsWith(self.registration.scope));
    if (win) return win.focus();
    return self.clients.openWindow('./');
  })());
});

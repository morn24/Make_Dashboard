'use strict';
// 할 일 규칙 (웹앱 app.js와 서비스워커 sw.js가 함께 쓴다). 데이터 형식은 docs/data-format.md 참고

function localDate(d = new Date()) {
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
function addDays(dateStr, n) {
  const [y, m, d] = dateStr.split('-').map(Number);
  return localDate(new Date(y, m - 1, d + n));
}

// repeat: "daily"(매일) | "weekly"(days 요일만, 0=일요일) | "none"(한 번, date가 있으면 그날부터)
const isRepeating = (item) => item.repeat === 'daily' || item.repeat === 'weekly';
const dow = (day) => new Date(`${day}T00:00:00`).getDay();

// 그날 해야 하는 반복 할 일인가
function scheduledOn(item, day) {
  if (item.repeat === 'daily') return true;
  if (item.repeat === 'weekly') return Array.isArray(item.days) && item.days.includes(dow(day));
  return false;
}
function isDone(item, today) {
  return isRepeating(item) ? item.doneOn === today : item.doneOn != null;
}
function isVisible(item, today) {
  if (isRepeating(item)) return scheduledOn(item, today) || item.doneOn === today;
  if (item.doneOn != null) return item.doneOn >= today; // 지난 완료는 숨김
  return !item.date || item.date <= today; // 날짜를 정했으면 그날부터
}
// 오늘 이전에 끝낸 한 번 할 일 ("지난 완료 항목 정리" 대상)
function isStale(item, today) {
  return !isRepeating(item) && item.doneOn != null && item.doneOn < today;
}
// 날짜를 정한 한 번 할 일 중 아직 날이 안 된 것
function isUpcoming(item, today) {
  return !isRepeating(item) && item.doneOn == null && item.date && item.date > today;
}

// 연속 기록: 오늘(아직 안 했으면 어제)부터 거꾸로, 해야 했던 날마다 했는지 센다. 쉬는 요일은 건너뛴다.
function streak(item, today) {
  if (!isRepeating(item)) return 0;
  const done = new Set(Array.isArray(item.history) ? item.history : []);
  if (item.doneOn) done.add(item.doneOn);
  let day = done.has(today) ? today : addDays(today, -1);
  let n = 0;
  for (let i = 0; i < HISTORY_DAYS; i++, day = addDays(day, -1)) {
    if (!scheduledOn(item, day)) continue;
    if (!done.has(day)) break;
    n++;
  }
  return n;
}
const HISTORY_DAYS = 90;
function recordHistory(item, day, done) {
  const cutoff = addDays(day, -HISTORY_DAYS);
  const set = new Set((Array.isArray(item.history) ? item.history : []).filter((d) => d >= cutoff));
  if (!item.history && item.doneOn && item.doneOn !== day) set.add(item.doneOn); // 기록이 생기기 전 마지막 완료일
  if (done) set.add(day); else set.delete(day);
  item.history = [...set].sort();
}

const WEEK = ['일', '월', '화', '수', '목', '금', '토'];
function repeatLabel(item) {
  if (item.repeat === 'daily') return '매일';
  if (item.repeat !== 'weekly' || !Array.isArray(item.days)) return '';
  const d = [...item.days].sort((a, b) => a - b).join();
  if (d === '1,2,3,4,5') return '평일';
  if (d === '0,6') return '주말';
  return [...item.days].sort((a, b) => ((a + 6) % 7) - ((b + 6) % 7)).map((x) => WEEK[x]).join('·');
}
function fmtDay(day, today) {
  if (day === today) return '오늘';
  if (day === addDays(today, 1)) return '내일';
  const [, m, d] = day.split('-').map(Number);
  return `${m}/${d}(${WEEK[dow(day)]})`;
}

// ---------- 시간 알림 ----------

const ALERT_BEFORE_MIN = 10; // 시작 몇 분 전에 알릴지
const SUMMARY_HOUR = 17; // 이 시각에 "아직 못 한 일"과 "내일 할 일"을 묶어서 알림

// 그날 그 할 일을 해야 하는가 (반복은 요일, 한 번은 날짜)
function dueOn(item, day) {
  if (isRepeating(item)) return scheduledOn(item, day);
  return item.date ? item.date === day : false;
}

// 시간이 있는 할 일의 알림: 시작 10분 전이 [from, to) 안에 드는 것들
function alertsBetween(items, from, to) {
  const out = [];
  for (const day of [localDate(from), addDays(localDate(from), 1)]) {
    for (const item of items || []) {
      if (!item.time || !dueOn(item, day) || (isRepeating(item) ? item.doneOn === day : item.doneOn != null)) continue;
      const start = new Date(`${day}T${item.time}:00`);
      const at = new Date(start.getTime() - ALERT_BEFORE_MIN * 60000);
      if (at >= from && at < to) out.push({ item, day, start, at });
    }
  }
  return out;
}

// 오후 5시 묶음 알림 내용: 오늘 아직 못 한 일, 내일 할 일(한 번 할 일만. 매일 하는 일은 빼서 번잡하지 않게)
function summaryLists(items, today) {
  const tomorrow = addDays(today, 1);
  const label = (i) => (i.time ? `${i.time} ${i.title}` : i.title);
  const byTime = (a, b) => (a.time || '99').localeCompare(b.time || '99');
  const left = (items || []).filter((i) => isVisible(i, today) && !isDone(i, today)).sort(byTime).map(label);
  const next = (items || []).filter((i) => !isRepeating(i) && i.doneOn == null && i.date === tomorrow).sort(byTime).map(label);
  return { left, next };
}

// ---------- 입력 문장에서 날짜·시간 읽기 ----------
// 예: "내일 오후 3시 병원" → { title: "병원", date: 내일, time: "15:00" }
//     "15:30 회의", "금요일 10시반 미팅", "11/7 9시 세미나", "다음주 월요일 전화"

const KDAY = { 일: 0, 월: 1, 화: 2, 수: 3, 목: 4, 금: 5, 토: 6 };
const PARTICLE = '(?:에|부터|까지)?';

function parseTodoInput(text, now = new Date()) {
  const today = localDate(now);
  let rest = ` ${text} `;
  let date = null;
  let time = null;
  const take = (re, fn) => {
    if (date && time) return;
    const m = rest.match(re);
    if (!m) return;
    const v = fn(m);
    if (v == null) return;
    rest = rest.slice(0, m.index) + ' ' + rest.slice(m.index + m[0].length);
  };

  // 날짜
  take(new RegExp(`(?<=\\s)(오늘|내일|모레|글피)${PARTICLE}(?=\\s)`), (m) => {
    date = addDays(today, { 오늘: 0, 내일: 1, 모레: 2, 글피: 3 }[m[1]]);
    return true;
  });
  if (!date) take(new RegExp(`(?<=\\s)(?:(이번\\s*주|다음\\s*주|담주)\\s*)?([월화수목금토일])요일${PARTICLE}(?=\\s)`), (m) => {
    const target = KDAY[m[2]];
    const cur = dow(today);
    if (!m[1]) { date = addDays(today, (target - cur + 7) % 7); return true; }
    const monday = addDays(today, -((cur + 6) % 7));
    date = addDays(monday, ((target + 6) % 7) + (m[1].startsWith('이번') ? 0 : 7));
    return true;
  });
  const md = (mo, d) => {
    if (mo < 1 || mo > 12 || d < 1 || d > 31) return null;
    const y = now.getFullYear();
    let v = `${y}-${String(mo).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
    if (v < addDays(today, -180)) v = `${y + 1}${v.slice(4)}`; // 반년 넘게 지난 날짜면 내년
    date = v;
    return true;
  };
  if (!date) take(new RegExp(`(?<=\\s)(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일${PARTICLE}(?=\\s)`), (m) => md(+m[1], +m[2]));
  if (!date) take(new RegExp(`(?<=\\s)(\\d{1,2})\\/(\\d{1,2})${PARTICLE}(?=\\s)`), (m) => md(+m[1], +m[2]));

  // 시간
  const hm = (ampm, h, min) => {
    if (h > 24 || min > 59) return null;
    if (/오전|아침|새벽/.test(ampm || '')) { if (h === 12) h = 0; }
    else if (/오후|저녁|밤|낮/.test(ampm || '')) { if (h < 12) h += 12; }
    else if (h >= 1 && h <= 6) h += 12; // "3시"처럼 오전/오후가 없으면 1~6시는 오후로 본다
    if (h === 24) h = 0;
    time = `${String(h).padStart(2, '0')}:${String(min).padStart(2, '0')}`;
    return true;
  };
  const AMPM = '(?:(오전|오후|아침|낮|저녁|밤|새벽)\\s*)?';
  take(new RegExp(`(?<=\\s)${AMPM}(\\d{1,2}):(\\d{2})${PARTICLE}(?=\\s)`), (m) => hm(m[1], +m[2], +m[3]));
  if (!time) take(new RegExp(`(?<=\\s)${AMPM}(\\d{1,2})\\s*시(?:\\s*(\\d{1,2})\\s*분|\\s*(반))?${PARTICLE}(?=\\s)`),
    (m) => hm(m[1], +m[2], m[4] ? 30 : +(m[3] || 0)));
  if (!time) take(new RegExp(`(?<=\\s)정오${PARTICLE}(?=\\s)`), () => { time = '12:00'; return true; });

  const title = rest.replace(/\s+/g, ' ').trim();
  if (!title) return { title: text.trim(), date: null, time: null };
  if (time && !date) date = today;
  return { title, date, time };
}

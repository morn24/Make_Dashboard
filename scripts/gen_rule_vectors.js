// web/rules.js의 답을 안드로이드(Kotlin TodoRules·입력 문장 읽기)와 맞춰 보기 위한 교차 검증 벡터를 만든다.
// rules.js는 수정하지 않고 vm으로 불러와 기대값만 계산한다.
// 실행: node scripts/gen_rule_vectors.js   →  android/app/src/test/resources/rule-vectors.json
// 웹 규칙이 바뀌면 이 명령을 다시 실행하고, 깨지는 Kotlin 테스트(RuleVectorsTest)를 고친다.
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ctx = { console, Date, Math, Number, String, Array, Object, Set, JSON, RegExp };
vm.createContext(ctx);
vm.runInContext(`${fs.readFileSync(path.join(__dirname, '../web/rules.js'), 'utf8')}
this.R = { localDate, addDays, isRepeating, scheduledOn, isDone, isVisible, isStale, isUpcoming, streak,
  recordHistory, repeatLabel, fmtDay, parseTodoInput, HISTORY_DAYS };`, ctx);
const R = ctx.R;
const clone = (x) => JSON.parse(JSON.stringify(x));

// 2026-11-07은 토요일, 11-09 월, 11-04 수. 요일이 섞이도록 여러 오늘을 쓴다.
const TODAYS = ['2026-11-04', '2026-11-07', '2026-11-08', '2026-11-09', '2026-03-01', '2027-01-01'];

function histRange(start, count, skip = []) {
  const out = [];
  for (let i = 0; i < count; i++) {
    const d = R.addDays(start, i);
    if (!skip.includes(d)) out.push(d);
  }
  return out;
}

function itemsFor(today) {
  const y1 = R.addDays(today, -1);
  const y2 = R.addDays(today, -2);
  const y3 = R.addDays(today, -3);
  const tomorrow = R.addDays(today, 1);
  const nextWeek = R.addDays(today, 7);
  const items = [
    { repeat: 'daily', doneOn: null },
    { repeat: 'daily', doneOn: today, history: [y2, y1, today] },
    { repeat: 'daily', doneOn: y1, history: [y3, y2, y1] }, // 어제까지 연속, 오늘은 아직
    { repeat: 'daily', doneOn: y1 }, // history 없는 옛 항목
    { repeat: 'daily', doneOn: today }, // history 없는 옛 항목, 오늘 완료
    { repeat: 'daily', doneOn: null, history: [y3, y1] }, // 어제 하고 그저께 건너뜀
    { repeat: 'daily', doneOn: today, history: [] },
    { repeat: 'weekly', days: [1, 2, 3, 4, 5], doneOn: null }, // 평일
    { repeat: 'weekly', days: [1, 2, 3, 4, 5], doneOn: today, history: [today] },
    { repeat: 'weekly', days: [0, 6], doneOn: null }, // 주말
    { repeat: 'weekly', days: [0, 6], doneOn: today },
    { repeat: 'weekly', days: [1, 3, 5], doneOn: null },
    { repeat: 'weekly', days: [3, 1, 5], doneOn: y1, history: [y1] }, // 정렬되지 않은 요일
    { repeat: 'weekly', days: [new Date(`${today}T00:00:00`).getDay()], doneOn: null }, // 오늘만
    { repeat: 'weekly', days: [(new Date(`${today}T00:00:00`).getDay() + 1) % 7], doneOn: null }, // 내일만 (오늘은 쉬는 날)
    { repeat: 'weekly', days: [(new Date(`${today}T00:00:00`).getDay() + 1) % 7], doneOn: today, history: [today] }, // 쉬는 날에 했음
    { repeat: 'weekly', days: [], doneOn: null },
    { repeat: 'weekly', doneOn: null }, // days 없음
    { repeat: 'none', doneOn: null },
    { repeat: 'none', doneOn: today },
    { repeat: 'none', doneOn: y1 }, // 지난 완료 (숨김)
    { repeat: 'none', doneOn: tomorrow }, // 미래에 끝낸 것으로 기록
    { repeat: 'none', doneOn: null, date: today },
    { repeat: 'none', doneOn: null, date: tomorrow }, // 예정
    { repeat: 'none', doneOn: null, date: nextWeek },
    { repeat: 'none', doneOn: null, date: y1 }, // 지난 (1일 지남)
    { repeat: 'none', doneOn: null, date: R.addDays(today, -12) },
    { repeat: 'none', doneOn: today, date: y1 },
    { repeat: 'none', doneOn: null, date: tomorrow, time: '09:00' },
  ];
  // 연속 기록: 평일 반복이 금요일 → 월요일을 건너뛰어 이어지는 경우
  items.push({ repeat: 'weekly', days: [1, 2, 3, 4, 5], doneOn: null, history: histRange(R.addDays(today, -40), 40, []).filter((d) => ![0, 6].includes(new Date(`${d}T00:00:00`).getDay())) });
  items.push({ repeat: 'weekly', days: [1, 2, 3, 4, 5], doneOn: null, history: histRange(R.addDays(today, -40), 40, [R.addDays(today, -5)]) });
  // 90일 넘게 쌓인 매일 기록
  items.push({ repeat: 'daily', doneOn: today, history: histRange(R.addDays(today, -120), 121) });
  return items.map((item, i) => ({ id: `v${i}`, title: `항목 ${i}`, ...item }));
}

// ---- 규칙 벡터 ----
const rules = [];
for (const today of TODAYS) {
  for (const item of itemsFor(today)) {
    rules.push({
      item: clone(item),
      today,
      expect: {
        isRepeating: R.isRepeating(item),
        scheduledOn: R.scheduledOn(item, today),
        isDone: R.isDone(item, today),
        isVisible: R.isVisible(item, today),
        isUpcoming: !!R.isUpcoming(item, today),
        isStale: R.isStale(item, today),
        streak: R.streak(item, today),
        repeatLabel: R.repeatLabel(item),
      },
    });
  }
}

// ---- 체크/취소 (web/app.js toggleTodo와 같은 계산: rules.js의 recordHistory를 쓴다) ----
function toggle(item, today, makeDone) {
  const it = clone(item);
  if (R.isRepeating(it)) {
    R.recordHistory(it, today, makeDone);
    it.doneOn = makeDone ? today : (it.history.filter((x) => x < today).pop() || null);
  } else {
    it.doneOn = makeDone ? today : null;
  }
  return it;
}
const toggles = [];
for (const today of TODAYS) {
  for (const item of itemsFor(today)) {
    for (const makeDone of [true, false]) {
      const after = toggle(item, today, makeDone);
      toggles.push({
        item: clone(item),
        today,
        makeDone,
        expect: { doneOn: after.doneOn ?? null, history: Array.isArray(after.history) ? after.history : null },
      });
    }
  }
}
// 완료 → 취소 왕복 (같은 항목에 두 번 적용)
const roundTrips = [];
for (const today of TODAYS) {
  for (const item of itemsFor(today)) {
    const done = toggle(item, today, true);
    const undone = toggle(done, today, false);
    roundTrips.push({
      item: clone(item),
      today,
      expect: { doneOn: undone.doneOn ?? null, history: Array.isArray(undone.history) ? undone.history : null },
    });
  }
}

// ---- 날짜 표시 ----
const fmtDays = [];
for (const today of TODAYS) {
  for (const delta of [-30, -2, -1, 0, 1, 2, 3, 8, 40]) {
    const day = R.addDays(today, delta);
    fmtDays.push({ day, today, expect: R.fmtDay(day, today) });
  }
}

// ---- 입력 문장 (scripts/test_rules.js의 11개 + 더) ----
const now = [2026, 11, 4, 10, 0]; // 2026-11-04(수) 10:00
const mkNow = (n) => new Date(n[0], n[1] - 1, n[2], n[3], n[4]);
const sentences = [
  '내일 오후 3시 병원', '15:30 회의', '금요일 10시반 미팅', '11/7 9시 세미나', '다음주 월요일 전화',
  '오후 2시 30분에 회의', '3시 카페', '오늘 저녁 7시 운동', '12월 25일 크리스마스', '보고서 쓰기', '3일 동안 운동',
  '모레 아침 8시 약속', '글피 정오 점심', '이번주 금요일 보고', '담주 수요일 세미나', '오전 12시 야식',
  '밤 11시 30분 전화', '7시 기상', '12시 점심', '24:00 마감', '25:00 이상한 시간', '13/40 달력', '1월 1일 신년',
  '2/30 이상한 날', '토요일 오전 9시 반 등산', '일요일 쉬기', '다음 주 화요일 3시 반 미팅', '내일에 병원', '오늘부터 운동',
  '  공백   많은   문장  ', '시간 10시', '10시 20분', '새벽 5시 20분 조깅', '15:30', '내일', '8:05 출근',
  '책 3시간 읽기', '회의 11월 12일 오후 4시', '12/25 파티 7시',
];
const parse = [];
for (const text of sentences) {
  for (const n of [now, [2026, 12, 20, 9, 0], [2026, 6, 15, 23, 59]]) {
    const r = R.parseTodoInput(text, mkNow(n));
    parse.push({ text, now: n, expect: { title: r.title, date: r.date ?? null, time: r.time ?? null } });
  }
}

const out = {
  generatedBy: 'scripts/gen_rule_vectors.js',
  source: 'web/rules.js',
  note: '직접 고치지 말고 node scripts/gen_rule_vectors.js 로 다시 만든다',
  historyDays: R.HISTORY_DAYS,
  rules,
  toggles,
  roundTrips,
  fmtDays,
  parse,
};
const dest = path.join(__dirname, '../android/app/src/test/resources/rule-vectors.json');
fs.mkdirSync(path.dirname(dest), { recursive: true });
fs.writeFileSync(dest, JSON.stringify(out) + '\n', 'utf8');
console.log(`쓴 파일: ${path.relative(process.cwd(), dest)} · 규칙 ${rules.length} · 체크 ${toggles.length} · 왕복 ${roundTrips.length} · 날짜표시 ${fmtDays.length} · 입력문장 ${parse.length}`);

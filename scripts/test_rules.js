// web/rules.js 테스트: 입력 문장 날짜·시간 읽기, 알림 시각, 오후 5시 묶음
// 실행: node scripts/test_rules.js
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const ctx = { console, Date, Math, Number, String, Array, Object, Set, JSON, RegExp };
vm.createContext(ctx);
vm.runInContext(`${fs.readFileSync(path.join(__dirname, '../web/rules.js'), 'utf8')}
this.R = { parseTodoInput, alertsBetween, summaryLists, ALERT_BEFORE_MIN };`, ctx);
const { parseTodoInput, alertsBetween, summaryLists } = ctx.R;
const same = (a, b, msg) => assert.strictEqual(JSON.stringify(a), JSON.stringify(b), msg); // vm 안팎 배열 비교

const now = new Date(2026, 10, 4, 10, 0); // 2026-11-04(수) 10:00 (기기 현지 시각)
const cases = [
  ['내일 오후 3시 병원', '병원', '2026-11-05', '15:00'],
  ['15:30 회의', '회의', '2026-11-04', '15:30'],
  ['금요일 10시반 미팅', '미팅', '2026-11-06', '10:30'],
  ['11/7 9시 세미나', '세미나', '2026-11-07', '09:00'],
  ['다음주 월요일 전화', '전화', '2026-11-09', null],
  ['오후 2시 30분에 회의', '회의', '2026-11-04', '14:30'],
  ['3시 카페', '카페', '2026-11-04', '15:00'],
  ['오늘 저녁 7시 운동', '운동', '2026-11-04', '19:00'],
  ['12월 25일 크리스마스', '크리스마스', '2026-12-25', null],
  ['보고서 쓰기', '보고서 쓰기', null, null],
  ['3일 동안 운동', '3일 동안 운동', null, null],
];
for (const [text, title, date, time] of cases) {
  const r = parseTodoInput(text, now);
  same([r.title, r.date, r.time], [title, date, time], text);
}
console.log(`ok - 입력 문장 ${cases.length}개`);

const items = [
  { id: 'a', title: '병원', repeat: 'none', date: '2026-11-04', time: '15:00', doneOn: null },
  { id: 'b', title: '운동', repeat: 'daily', time: '07:00', doneOn: null },
  { id: 'c', title: '듀오링고', repeat: 'daily', doneOn: '2026-11-04' },
  { id: 'd', title: '보고서', repeat: 'none', date: '2026-11-04', doneOn: null },
  { id: 'e', title: '세미나', repeat: 'none', date: '2026-11-05', time: '09:00', doneOn: null },
  { id: 'f', title: '영어', repeat: 'weekly', days: [4], doneOn: null }, // 목요일만
];
const hits = alertsBetween(items, new Date(2026, 10, 4, 14, 45), new Date(2026, 10, 4, 14, 55));
same(hits.map((h) => h.item.id), ['a']);
assert.strictEqual(hits[0].at.getHours() * 60 + hits[0].at.getMinutes(), 14 * 60 + 50);
console.log('ok - 시작 10분 전 알림');

const { left, next } = summaryLists(items, '2026-11-04');
same(left, ['07:00 운동', '15:00 병원', '보고서']);
same(next, ['09:00 세미나']); // 반복(영어·운동)은 내일 목록에서 뺀다
console.log('ok - 오후 5시 묶음');

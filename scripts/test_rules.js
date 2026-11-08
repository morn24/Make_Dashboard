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
this.R = { parseTodoInput, alertsBetween, summaryLists, splitLearning, ALERT_BEFORE_MIN };`, ctx);
const { parseTodoInput, alertsBetween, summaryLists, splitLearning } = ctx.R;
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

const lt = [
  { id: 'L1', status: '후보', rank: 1, cooldownUntil: '2026-12-01' },
  { id: 'L2', status: '후보', rank: 2 },
  { id: 'L3', status: '학습중', rank: 3, cooldownUntil: '2026-12-02' },
  { id: 'L4', status: '후보', rank: 4, cooldownUntil: '2026-12-03' },
  { id: 'L9', status: '보류' },
];
let sp = splitLearning(lt, 'L2');
same(sp.upper.map((t) => t.id), ['L2']);
same(sp.waiting.map((t) => t.id), ['L1', 'L3', 'L4']); // 표 순서 그대로, rank도 그대로
same(sp.waiting.map((t) => t.rank), [1, 3, 4]);
same(sp.held.map((t) => t.id), ['L9']);
sp = splitLearning(lt, 'L4'); // 다음 자동 후보는 항상 위쪽
same(sp.upper.map((t) => t.id), ['L2', 'L4']);
sp = splitLearning(undefined, null); // 이전 형식·빈 데이터
same([sp.upper, sp.waiting, sp.held], [[], [], []]);
console.log('ok - 학습 후보 나누기');

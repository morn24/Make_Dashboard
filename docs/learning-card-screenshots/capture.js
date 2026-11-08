'use strict';
// 학습 후보 카드 캡처 (가짜 데이터 + 로컬 정적 서버 + 헤드리스 Chrome). 실제 저장소·토큰·폰은 쓰지 않는다.
// 실행: node docs/learning-card-screenshots/capture.js   (Chrome 경로는 CHROME 환경변수로 바꿀 수 있다)
// 앱은 연결 정보가 없으면 데모 모드로 demo/*.json을 읽는다. 서버가 demo/pmr-wiki.json만 이 파일의 가짜 데이터로 바꿔 준다.
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');
const { spawn } = require('child_process');

const WEB = path.join(__dirname, '../../web');
const OUT = __dirname;
const CHROME = process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.webmanifest': 'application/manifest+json' };

const req = (status, day, note) => ({ status, source: 'auto', requestedAt: `2026-${day}T02:30:00+09:00`, updatedAt: `2026-${day}T02:42:00+09:00`, note });
const topic = (id, rank, name, extra = {}) => ({ id, status: '후보', rank, topic: name, trigger: '① 진료 중 공백', why: '진료 빈도가 높음', detail: '', ...extra });
const topics = (withRemaining) => {
  const rem = (text) => (withRemaining ? { remaining: text } : {});
  return [
    topic('L3', 1, '무릎 OA의 genicular nerve block·RFA', {
      cooldownUntil: '2026-12-01', request: req('done', '11-01', '5편 반영'), detail: '시술 기법·prognostic block 기준 반영. 남은 것: prognostic block 예측 가치의 무작위 근거',
      ...rem('prognostic block 예측 가치의 무작위 근거(McCormick 원문, 볼트에 없음)'),
    }),
    topic('L8', 2, 'ESWT 적응증과 프로토콜', {
      cooldownUntil: '2026-11-22', request: req('done', '10-23', '2편 반영'),
      ...rem('guideline급 출처(국제 학회 consensus), plantar fasciitis·calcific tendinitis 에너지별 프로토콜. 기관마다 장비 기종이 달라 에너지 단위 환산표가 따로 필요함'),
    }),
    topic('L12', 3, 'lumbar 시술별 금기·합병증·약물 용량', { cooldownUntil: '2026-12-05', request: req('failed', '11-05', '원문 확보 실패'), ...rem('facet·MBB-RFA·SI(`10_raw`에 원문 없음)') }),
    topic('L16', 4, 'frozen shoulder 운동 처방 세부', { ...rem('동작별 유지 시간·반복 수치') }),
    topic('L19', 5, 'rotator cuff tendinopathy 운동 용량', { request: req('done', '10-20', '요약 게시'), ...rem('관절강 내(rotator cuff) 용량 비교, 출판본 Malliaras Table 1') }),
    topic('L20', 6, 'de Quervain 주사 기법'),
    { id: 'L11', status: '보류', topic: '보류한 주제', trigger: '④ 관심 방향', why: '', detail: '', held: '2026-11-02' },
  ];
};

const wiki = JSON.parse(fs.readFileSync(path.join(WEB, 'demo/pmr-wiki.json'), 'utf8'));
const fixture = (withRemaining) => JSON.stringify({ ...wiki, learning: { nextAuto: 'L16', busy: false, cooldownDays: 30, topicsGenerated: '2026-11-08T07:40:00', topics: topics(withRemaining) } });

let scenario = 'new';
const server = http.createServer((rq, rs) => {
  const url = decodeURIComponent(rq.url.split('?')[0]);
  if (url === '/demo/pmr-wiki.json') { rs.writeHead(200, { 'Content-Type': 'application/json' }); rs.end(fixture(scenario === 'new')); return; }
  const file = path.join(WEB, url === '/' ? 'index.html' : url);
  if (!file.startsWith(WEB) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { rs.writeHead(404); rs.end(); return; }
  rs.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
  rs.end(fs.readFileSync(file));
});

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'lc-chrome-'));
  const chrome = spawn(CHROME, ['--headless=new', '--remote-debugging-port=9333', `--user-data-dir=${profile}`, '--no-first-run', '--disable-gpu', 'about:blank'], { stdio: 'ignore' });
  let targets;
  for (let i = 0; i < 50 && !targets; i++) {
    try { targets = await (await fetch('http://127.0.0.1:9333/json')).json(); } catch { await sleep(200); }
  }
  const page = targets.find((t) => t.type === 'page');
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((r) => ws.addEventListener('open', r));
  let id = 0;
  const pending = new Map();
  ws.addEventListener('message', (m) => { const d = JSON.parse(m.data); if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); } });
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const n = ++id;
    pending.set(n, (d) => (d.error ? reject(new Error(JSON.stringify(d.error))) : resolve(d.result)));
    ws.send(JSON.stringify({ id: n, method, params }));
  });
  const evaluate = async (expression) => (await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result.value;

  async function shot(name, { sc, width, open }) {
    scenario = sc;
    await send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 2, mobile: true });
    await send('Page.navigate', { url: 'about:blank' });
    await sleep(300);
    await send('Page.navigate', { url: `http://127.0.0.1:${port}/#pmr` });
    for (let i = 0; i < 40 && !(await evaluate(`!document.getElementById('tab-pmr').hidden && document.querySelectorAll('#learn-list li').length > 0`)); i++) await sleep(250);
    await evaluate(`document.querySelectorAll('#learn-wait').forEach((d) => { d.open = ${open}; })`);
    const box = await evaluate(`(() => { const r = document.querySelector('[aria-labelledby="learn-title"]').getBoundingClientRect(); return { x: r.left, y: r.top + scrollY, width: r.width, height: r.height }; })()`);
    const { data } = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true, clip: { ...box, scale: 1 } });
    fs.writeFileSync(path.join(OUT, name), Buffer.from(data, 'base64'));
    const text = await evaluate(`document.querySelector('[aria-labelledby="learn-title"]').innerText`);
    console.log(`--- ${name}\n${text}\n`);
  }

  try {
    await shot('1-collapsed.png', { sc: 'new', width: 412, open: false });
    await shot('2-expanded.png', { sc: 'new', width: 412, open: true });
    await shot('3-old-format-no-remaining.png', { sc: 'old', width: 412, open: true });
    await shot('4-width-360.png', { sc: 'new', width: 360, open: true });
  } finally {
    try { await send('Browser.close'); } catch { /* 이미 닫힘 */ }
    ws.close();
    chrome.kill();
  }
  server.close();
  setTimeout(() => process.exit(0), 300);
}

main().catch((e) => { console.error(e); process.exit(1); });

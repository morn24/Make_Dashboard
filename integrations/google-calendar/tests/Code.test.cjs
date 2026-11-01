const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '..', 'Code.gs'), 'utf8');

function makeEvent({ uid, title, start, end, color = null, allDay = false, allDayStart, allDayEnd, location = null }) {
  const startDate = new Date(start);
  const endDate = new Date(end);
  const dateOf = (value) => new Date(`${value}T00:00:00Z`);
  return {
    getId: () => uid,
    getTitle: () => title,
    getColor: () => color,
    getStartTime: () => startDate,
    getEndTime: () => endDate,
    isAllDayEvent: () => allDay,
    getAllDayStartDate: () => dateOf(allDayStart),
    getAllDayEndDate: () => dateOf(allDayEnd),
    getLocation: () => location,
    getMyStatus: () => 'YES',
  };
}

function createScript({
  events = [],
  eventPages = [],
  labels = { labelProperties: { eventLabels: [] } },
  palette = { event: {} },
  remoteFile = null,
  apiStatus = {},
} = {}) {
  const props = new Map([['GITHUB_TOKEN', 'fixture-token']]);
  const scriptCache = new Map();
  const requests = [];
  const logs = [];
  const writes = [];
  let file = remoteFile;
  let eventPageIndex = 0;
  let currentApiStatus = { ...apiStatus };
  const calendar = {
    getId: () => 'team@example.test',
    getName: () => 'Fixture Calendar',
    isHidden: () => false,
    getColor: () => '#2468AC',
    getEvents: () => events,
  };
  const response = (body, status = 200) => ({
    getResponseCode: () => status,
    getContentText: () => typeof body === 'string' ? body : JSON.stringify(body),
  });
  const formatDate = (value, timeZone, pattern) => {
    const date = new Date(value);
    if (pattern === 'yyyy-MM-dd') {
      const parts = new Intl.DateTimeFormat('en-US', {
        timeZone,
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
      }).formatToParts(date);
      const part = Object.fromEntries(parts.map(({ type, value: item }) => [type, item]));
      return `${part.year}-${part.month}-${part.day}`;
    }
    if (pattern === 'XXX') return 'Z';
    return date.toISOString().replace('.000Z', '+00:00').replace('Z', '+00:00');
  };
  const utilities = {
    Charset: { UTF_8: 'UTF-8' },
    DigestAlgorithm: { SHA_256: 'SHA-256' },
    formatDate,
    computeDigest: (_algorithm, value) => crypto.createHash('sha256').update(String(value)).digest(),
    base64EncodeWebSafe: (bytes) => Buffer.from(bytes).toString('base64url'),
    base64Encode: (value) => Buffer.from(value).toString('base64'),
    base64Decode: (value) => Buffer.from(value, 'base64'),
    newBlob: (bytes) => ({ getDataAsString: () => Buffer.from(bytes).toString('utf8') }),
    sleep: () => {},
  };
  const context = {
    console,
    Date,
    Map,
    JSON,
    Math,
    Number,
    String,
    encodeURIComponent,
    Logger: { log: (...args) => logs.push(args.join(' ')) },
    Utilities: utilities,
    Session: { getScriptTimeZone: () => 'UTC' },
    CalendarApp: { GuestStatus: { NO: 'NO' }, getAllCalendars: () => [calendar] },
    ScriptApp: { getOAuthToken: () => 'calendar-fixture-token' },
    PropertiesService: {
      getScriptProperties: () => ({
        getProperty: (key) => props.get(key) ?? null,
        setProperty: (key, value) => { props.set(key, value); return null; },
      }),
    },
    CacheService: {
      getScriptCache: () => ({
        get: (key) => scriptCache.get(key) ?? null,
        put: (key, value) => { scriptCache.set(key, value); },
        remove: (key) => scriptCache.delete(key),
      }),
    },
    UrlFetchApp: {
      fetch: (url, options = {}) => {
        requests.push({ url: String(url), method: String(options.method || 'get').toLowerCase(), headers: options.headers || {} });
        const statusFor = (kind) => currentApiStatus[kind] ?? 200;
        if (String(url).startsWith('https://api.github.com/')) {
          if (String(options.method).toLowerCase() === 'put') {
            const payload = JSON.parse(options.payload);
            const content = Buffer.from(payload.content, 'base64').toString('utf8');
            file = { data: JSON.parse(content), sha: 'sha-updated' };
            writes.push(file.data);
            return response({ content: 'saved' }, 201);
          }
          if (!file) return response({ message: 'not found' }, 404);
          return response({
            sha: file.sha,
            content: Buffer.from(JSON.stringify(file.data)).toString('base64'),
          });
        }
        if (String(url).includes('/colors')) return response(palette, statusFor('palette'));
        if (String(url).includes('/events?')) {
          if (String(url).includes('eventLabelVersion=1') && currentApiStatus.eventLabels != null) {
            return response({ error: { message: 'Invalid eventLabelVersion', errors: [{ reason: 'invalidParameter' }] } }, statusFor('eventLabels'));
          }
          const index = eventPageIndex++;
          const pageStatus = currentApiStatus.eventsPages?.[index] ?? statusFor('events');
          return response(eventPages[index] || { items: [] }, pageStatus);
        }
        if (String(url).includes('/calendars/')) {
          const statusKind = String(url).includes('fields=') && currentApiStatus.labelFields != null ? 'labelFields' : 'labels';
          if (currentApiStatus[statusKind] != null) {
            return response({ error: { message: 'Invalid fields parameter', errors: [{ reason: 'invalidParameter' }] } }, statusFor(statusKind));
          }
          return response(labels, statusFor('labels'));
        }
        return response({ error: 'unhandled fixture URL' }, 404);
      },
    },
    CONFIG: undefined,
  };
  vm.createContext(context);
  vm.runInContext(source, context, { filename: 'Code.gs' });
  return {
    context,
    requests,
    logs,
    writes,
    setApiStatus: (value) => { currentApiStatus = { ...value }; },
    getFile: () => file,
    getCache: () => scriptCache,
    getProperties: () => props,
    setEventPages: (pages) => { eventPageIndex = 0; eventPages.splice(0, eventPages.length, ...pages); },
  };
}

test('event labels outrank legacy IDs; legacy IDs map through Colors.event with safe defaults', () => {
  const app = createScript({ palette: { event: { '1': { background: '#7986CB' }, '11': { background: '#E53935' } } } });
  const resolve = app.context.resolveEventColor_;
  const sources = { labels: { work: '#12aBcD' }, palette: { '11': { background: '#E53935' } } };

  assert.equal(resolve({ eventLabelId: 'work', colorId: '11' }, '11', '#2468AC', sources), '#12ABCD');
  assert.equal(resolve({ colorId: '11' }, null, '#2468AC', sources), '#E53935');
  assert.equal(resolve(null, '1', '#2468AC', { labels: {}, palette: { '1': { background: '#7986CB' } } }), '#7986CB');
  assert.equal(resolve(null, null, '#2468AC', sources), '#2468AC');
  assert.equal(resolve(null, 'not-an-id', 'bad-hex', sources), '#9AA0A6');
});

test('REST event pages match repeated, moved, all-day, and DST occurrences by iCalUID and actual start', () => {
  const pages = [
    {
      items: [
        { id: 'series-a-1', iCalUID: 'series-a@example.test', start: { dateTime: '2026-03-01T09:00:00+09:00' }, colorId: '1' },
        { id: 'series-a-moved', iCalUID: 'series-a@example.test', start: { dateTime: '2026-03-02T11:30:00+09:00' }, originalStartTime: { dateTime: '2026-03-02T09:00:00+09:00' }, eventLabelId: 'custom' },
        { id: 'all-day', iCalUID: 'holiday@example.test', start: { date: '2026-03-03' }, colorId: '11' },
        { id: 'cancelled', iCalUID: 'series-a@example.test', status: 'cancelled', start: { dateTime: '2026-03-04T09:00:00+09:00' } },
      ],
      nextPageToken: 'next-page',
    },
    {
      items: [
        { id: 'dst-before', iCalUID: 'dst@example.test', start: { dateTime: '2026-10-25T01:30:00+01:00' }, colorId: '2' },
        { id: 'dst-after', iCalUID: 'dst@example.test', start: { dateTime: '2026-11-01T01:30:00-05:00' }, colorId: '3' },
      ],
    },
  ];
  const app = createScript({ eventPages: pages });
  const start = new Date('2026-03-01T00:00:00Z');
  const end = new Date('2026-11-02T00:00:00Z');
  const events = app.context.getCalendarEventColors_('team@example.test', start, end, false);
  const key = (uid, actualStart) => app.context.calendarEventKey_('team@example.test', uid, actualStart);

  assert.equal(events.size, 5);
  assert.equal(events.get(key('series-a@example.test', 'time:' + Date.parse('2026-03-01T09:00:00+09:00'))).colorId, '1');
  assert.equal(events.get(key('series-a@example.test', 'time:' + Date.parse('2026-03-02T11:30:00+09:00'))).eventLabelId, 'custom');
  assert.ok(events.has(key('holiday@example.test', 'date:2026-03-03')));
  assert.ok(events.has(key('dst@example.test', 'time:' + Date.parse('2026-10-25T01:30:00+01:00'))));
  assert.ok(events.has(key('dst@example.test', 'time:' + Date.parse('2026-11-01T01:30:00-05:00'))));
  assert.equal(app.requests.filter((request) => request.url.includes('/events?')).length, 2);
  assert.ok(app.requests.every((request) => request.method === 'get'));
  assert.ok(app.requests[0].url.includes('eventLabelVersion=1'));
  assert.ok(app.requests[1].url.includes('pageToken=next-page'));
});

test('palette or event metadata failures preserve CalendarApp events and use calendar fallback', () => {
  const event = makeEvent({
    uid: 'meeting@example.test',
    title: 'Fixture title only',
    start: '2026-03-01T10:00:00Z',
    end: '2026-03-01T11:00:00Z',
    color: '11',
  });
  const app = createScript({ events: [event], apiStatus: { palette: 403, labels: 403, events: 403 } });
  const collected = app.context.collectEvents_(new Date('2026-03-01T00:00:00Z'), new Date('2026-03-02T00:00:00Z'), 'UTC', false);

  assert.equal(collected.length, 1);
  assert.equal(collected[0].title, 'Fixture title only');
  assert.equal(collected[0].color, '#2468AC');
  assert.ok(app.logs.every((line) => !line.includes('Fixture title only')));
  assert.ok(app.requests.filter((request) => request.url.includes('googleapis.com')).every((request) => request.method === 'get'));
});

test('a color-only calendar change writes the existing color field once, then unchanged sync is a no-op', () => {
  const now = new Date();
  const today = now.toISOString().slice(0, 10);
  const start = new Date(`${today}T12:00:00Z`);
  const end = new Date(start.getTime() + 60 * 60 * 1000);
  const event = makeEvent({
    uid: 'color-only@example.test',
    title: 'Repeated fixture meeting',
    start,
    end,
    color: '1',
  });
  const palette = { event: { '1': { background: '#E53935' } } };
  const eventPages = [{ items: [{
    id: 'rest-instance',
    iCalUID: 'color-only@example.test',
    start: { dateTime: start.toISOString() },
    colorId: '1',
  }] }];
  const app = createScript({ events: [event], eventPages, palette });
  const current = app.context.collectEvents_(new Date(`${today}T00:00:00Z`), new Date(`${today}T23:59:59Z`), 'UTC', false);
  const remoteEvents = current.map((item) => ({ ...item, color: '#0000FF' }));
  const setupScript = createScript({ events: [event], eventPages, palette, remoteFile: {
    sha: 'sha-before',
    data: {
      version: 1,
      rangeStart: today,
      events: remoteEvents,
    },
  } });

  setupScript.context.syncCalendar();
  assert.equal(setupScript.writes.length, 1);
  assert.equal(setupScript.writes[0].events[0].color, '#E53935');
  assert.equal(setupScript.writes[0].events[0].id, remoteEvents[0].id);
  assert.equal(setupScript.writes[0].version, 1);

  setupScript.context.syncCalendar();
  assert.equal(setupScript.writes.length, 1);
  assert.ok(setupScript.requests.filter((request) => request.url.includes('googleapis.com')).every((request) => request.method === 'get'));
});

test('expired cached palette remains available when a forced refresh fails', () => {
  const app = createScript({ palette: { event: { '2': { background: '#43A047' } } } });
  const first = app.context.getEventColorPalette_(false);
  assert.equal(first['2'].background, '#43A047');
  app.setApiStatus({ palette: 503 });
  const stale = app.context.getEventColorPalette_(true);
  assert.equal(stale['2'].background, '#43A047');
});

test('label fields and eventLabelVersion 400 responses fall back to legacy color IDs', () => {
  const event = makeEvent({
    uid: 'legacy@example.test',
    title: 'Private fixture title',
    start: '2026-03-01T10:00:00Z',
    end: '2026-03-01T11:00:00Z',
    color: '11',
  });
  const app = createScript({
    events: [event],
    eventPages: [{ items: [{ id: 'legacy', iCalUID: 'legacy@example.test', start: { dateTime: '2026-03-01T10:00:00Z' }, colorId: '11' }] }],
    palette: { event: { '11': { background: '#E53935' } } },
    apiStatus: { labelFields: 400, eventLabels: 400 },
  });

  const collected = app.context.collectEvents_(new Date('2026-03-01T00:00:00Z'), new Date('2026-03-02T00:00:00Z'), 'UTC', false);
  assert.equal(collected[0].color, '#E53935');
  assert.ok(app.requests.some((request) => request.url.includes('fields=')));
  assert.ok(app.requests.some((request) => request.url.includes('/calendars/') && !request.url.includes('fields=')));
  assert.ok(app.requests.some((request) => request.url.includes('eventLabelVersion=1')));
  assert.ok(app.requests.some((request) => request.url.includes('/events?') && !request.url.includes('eventLabelVersion=1')));
  assert.ok(app.requests.every((request) => request.method === 'get'));
  app.setEventPages([{ items: [] }]);
  assert.equal(app.context.diagnoseCalendarColors().google.compatibilityFallbacks.events, true);
  assert.ok(app.logs.every((line) => !line.includes('Private fixture title')));
});

test('diagnoseCalendarColors reports only safe HTTP stages and color counts', () => {
  const event = makeEvent({
    uid: 'diagnostic@example.test',
    title: 'Do not report this title',
    start: '2026-03-01T10:00:00Z',
    end: '2026-03-01T11:00:00Z',
    color: '11',
  });
  const app = createScript({
    events: [event],
    palette: { event: {} },
    apiStatus: { palette: 401, labels: 403, events: 429 },
    remoteFile: {
      sha: 'sha-private-fixture',
      data: { version: 1, events: [{ color: '#123abc' }, { color: '#ABCDEF' }, { color: 'bad' }] },
    },
  });

  const report = app.context.diagnoseCalendarColors();
  const statuses = report.google.apiRequests.map(({ stage, status }) => `${stage}:${status}`);
  assert.ok(statuses.includes('palette:401'));
  assert.ok(statuses.includes('labels:403'));
  assert.ok(statuses.includes('events_labels_page_1:429'));
  assert.equal(report.remoteCalendarJson.eventCount, 3);
  assert.equal(report.remoteCalendarJson.validHexColors, 2);
  assert.equal(report.remoteCalendarJson.distinctHexColors, 2);
  assert.equal(report.remoteCalendarJson.invalidOrMissing, 1);
  const safeOutput = JSON.stringify({ report, logs: app.logs });
  assert.ok(!safeOutput.includes('Do not report this title'));
  assert.ok(!safeOutput.includes('diagnostic@example.test'));
  assert.ok(!safeOutput.includes('fixture-token'));
  assert.ok(app.requests.filter((request) => request.url.includes('googleapis.com')).every((request) => request.method === 'get'));
});

test('a later events page failure keeps earlier matches and records the failing page', () => {
  const pages = [
    { items: [{ id: 'first', iCalUID: 'first@example.test', start: { dateTime: '2026-03-01T09:00:00Z' }, colorId: '1' }], nextPageToken: 'next' },
    { items: [{ id: 'second', iCalUID: 'second@example.test', start: { dateTime: '2026-03-01T10:00:00Z' }, colorId: '2' }] },
  ];
  const app = createScript({
    eventPages: pages,
    palette: { event: { '1': { background: '#7986CB' } } },
    apiStatus: { eventsPages: [200, 503] },
  });

  const report = app.context.diagnoseCalendarColors();
  const lastEventsRequest = report.google.apiRequests.filter(({ stage }) => stage.startsWith('events_')).at(-1);
  assert.equal(lastEventsRequest.stage, 'events_labels_page_2');
  assert.equal(lastEventsRequest.status, 503);
});

test('trigger event objects do not force color cache refresh; the explicit action does', () => {
  const app = createScript({ palette: { event: { '1': { background: '#7986CB' } } } });
  app.context.getEventColorPalette_(false);
  assert.equal(app.requests.filter((request) => request.url.includes('/colors')).length, 1);

  app.context.syncCalendar({ triggerUid: 'safe-fixture' });
  assert.equal(app.requests.filter((request) => request.url.includes('/colors')).length, 1);

  app.context.refreshCalendarColors();
  assert.equal(app.requests.filter((request) => request.url.includes('/colors')).length, 2);
  assert.ok(app.requests.filter((request) => request.url.includes('googleapis.com')).every((request) => request.method === 'get'));
});

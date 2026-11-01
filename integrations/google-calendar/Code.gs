/**
 * Google 캘린더 → GitHub(dashboard-data/data/calendar.json) 동기화
 *
 * 설치 방법: docs/google-calendar-setup.md
 * - 내 Google 계정에서 실행되므로 Google Cloud 설정이나 OAuth 앱이 필요 없다.
 * - 일정이 바뀌었을 때만 GitHub에 저장한다 (변경 없으면 커밋 안 함).
 * 데이터 형식: docs/data-format.md 의 data/calendar.json
 */

const CONFIG = {
  owner: 'morn24',
  repo: 'dashboard-data',
  branch: 'main',
  path: 'data/calendar.json',
  daysAhead: 7, // 오늘부터 며칠 치 일정을 저장할지 (위젯이 오프라인일 때도 다음 날 일정을 보여주려고 넉넉히)
  excludeCalendars: [], // 빼고 싶은 캘린더 이름. 예: ['대한민국의 휴일']
  skipDeclined: true, // 내가 '거절'한 초대 일정은 빼기
  intervalMinutes: 10, // 1, 5, 10, 15, 30 중 하나
  paletteCacheSeconds: 3600,
  labelCacheSeconds: 300,
  fallbackEventColor: '#9AA0A6',
};

// 실행 중 여러 캘린더가 같은 event palette를 요청해도 Colors.get은 한 번만 호출한다.
let EVENT_COLOR_PALETTE_ = null;

/** 처음 한 번 실행: 토큰 확인 + 자동 실행(트리거) 등록 + 첫 동기화 */
function setup() {
  getToken_();
  ScriptApp.getProjectTriggers()
    .filter((t) => t.getHandlerFunction() === 'syncCalendar')
    .forEach((t) => ScriptApp.deleteTrigger(t));
  ScriptApp.newTrigger('syncCalendar').timeBased().everyMinutes(CONFIG.intervalMinutes).create();
  syncCalendar();
  Logger.log('설치 완료: %s분마다 동기화합니다.', CONFIG.intervalMinutes);
}

/** 자동 실행을 멈추고 싶을 때 */
function uninstall() {
  ScriptApp.getProjectTriggers()
    .filter((t) => t.getHandlerFunction() === 'syncCalendar')
    .forEach((t) => ScriptApp.deleteTrigger(t));
  Logger.log('자동 실행을 해제했습니다.');
}

function syncCalendar(forceColorRefresh) {
  const tz = Session.getScriptTimeZone();
  const now = new Date();
  const today = Utilities.formatDate(now, tz, 'yyyy-MM-dd');
  const startOfToday = parseLocal_(today, tz); // 스크립트 시간대 기준 오늘 0시
  const end = new Date(startOfToday.getTime() + CONFIG.daysAhead * 86400000);

  const events = collectEvents_(startOfToday, end, tz, Boolean(forceColorRefresh));
  const remote = readFile_();
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
  writeFile_(data, remote.sha, 'calendar: ' + today + ' 일정 ' + events.length + '개 동기화');
}

/** 수동 색상 확인용: 캐시를 건너뛰어 Google의 최신 팔레트/라벨을 다시 읽는다. */
function refreshCalendarColors() {
  syncCalendar(true);
}

function collectEvents_(start, end, tz, forceColorRefresh) {
  const out = [];
  CalendarApp.getAllCalendars().forEach((cal) => {
    if (cal.isHidden() || CONFIG.excludeCalendars.indexOf(cal.getName()) >= 0) return;
    const calendarId = cal.getId();
    const calendarEvents = cal.getEvents(start, end);
    let colorSources;
    try {
      colorSources = readCalendarColorSources_(calendarId, start, end, forceColorRefresh);
    } catch (error) {
      logColorMetadataFailure_('cache');
      colorSources = { palette: {}, labels: {}, events: new Map() };
    }
    let calendarColor = null;
    try { calendarColor = cal.getColor(); } catch (error) { /* 일정의 지정 색상을 먼저 사용한다. */ }
    calendarEvents.forEach((ev) => {
      if (CONFIG.skipDeclined && myStatus_(ev) === CalendarApp.GuestStatus.NO) return;
      const allDay = ev.isAllDayEvent();
      const eventStart = allDay
        ? 'date:' + Utilities.formatDate(ev.getAllDayStartDate(), tz, 'yyyy-MM-dd')
        : 'time:' + ev.getStartTime().getTime();
      const restEvent = colorSources.events.get(calendarEventKey_(calendarId, ev.getId(), eventStart));
      let eventColorId = null;
      try { eventColorId = ev.getColor(); } catch (e) { /* 색상 메타데이터가 없어도 본문은 계속 수집한다. */ }
      const item = {
        // 반복 일정은 같은 id를 공유하므로 시작 시각을 붙여 고유하게 만든다
        id: ev.getId() + '@' + ev.getStartTime().getTime(),
        calendar: cal.getName(),
        color: resolveEventColor_(restEvent, eventColorId, calendarColor, colorSources),
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

function readCalendarColorSources_(calendarId, start, end, forceRefresh) {
  const palette = getEventColorPalette_(forceRefresh);
  const labels = getCalendarLabelColors_(calendarId, forceRefresh);
  const events = getCalendarEventColors_(calendarId, start, end, forceRefresh);
  return { palette: palette, labels: labels, events: events };
}

function getEventColorPalette_(forceRefresh) {
  if (!forceRefresh && EVENT_COLOR_PALETTE_) return EVENT_COLOR_PALETTE_;
  const value = fetchCachedCalendarJson_(
    'calendar_event_palette',
    'https://www.googleapis.com/calendar/v3/colors',
    CONFIG.paletteCacheSeconds,
    forceRefresh,
    'palette',
  );
  EVENT_COLOR_PALETTE_ = value && value.event ? value.event : {};
  return EVENT_COLOR_PALETTE_;
}

function getCalendarLabelColors_(calendarId, forceRefresh) {
  const key = 'calendar_event_labels_' + cacheHash_(calendarId);
  const url = 'https://www.googleapis.com/calendar/v3/calendars/' + encodeURIComponent(calendarId)
    + '?fields=' + encodeURIComponent('labelProperties(eventLabels(id,backgroundColor))');
  const value = fetchCachedCalendarJson_(key, url, CONFIG.labelCacheSeconds, forceRefresh, 'labels');
  const labels = {};
  const items = value && value.labelProperties && value.labelProperties.eventLabels;
  (items || []).forEach((label) => {
    const color = normalizeHexColor_(label && label.backgroundColor);
    if (label && label.id && color) labels[label.id] = color;
  });
  return labels;
}

function getCalendarEventColors_(calendarId, start, end, forceRefresh) {
  const byOccurrence = new Map();
  let pageToken = null;
  do {
    const params = [
      'timeMin=' + encodeURIComponent(start.toISOString()),
      'timeMax=' + encodeURIComponent(end.toISOString()),
      'singleEvents=true',
      'showDeleted=true',
      'maxResults=2500',
      'eventLabelVersion=1',
      'fields=' + encodeURIComponent('items(id,iCalUID,status,start,eventLabelId,colorId,recurringEventId,originalStartTime),nextPageToken'),
    ];
    if (pageToken) params.push('pageToken=' + encodeURIComponent(pageToken));
    const url = 'https://www.googleapis.com/calendar/v3/calendars/' + encodeURIComponent(calendarId)
      + '/events?' + params.join('&');
    let page;
    try {
      page = calendarApiGetJson_(url);
    } catch (error) {
      logColorMetadataFailure_('events');
      break;
    }
    (page.items || []).forEach((event) => {
      if (!event || event.status === 'cancelled' || !event.iCalUID || !event.start) return;
      const startKey = event.start.date
        ? 'date:' + event.start.date
        : event.start.dateTime ? 'time:' + new Date(event.start.dateTime).getTime() : null;
      if (!startKey) return;
      byOccurrence.set(calendarEventKey_(calendarId, event.iCalUID, startKey), event);
    });
    pageToken = page.nextPageToken || null;
  } while (pageToken);
  return byOccurrence;
}

function calendarEventKey_(calendarId, iCalUid, startKey) {
  return String(calendarId) + '\u0000' + String(iCalUid) + '\u0000' + String(startKey);
}

function resolveEventColor_(restEvent, calendarAppColorId, calendarColor, sources) {
  const labels = sources.labels || {};
  const palette = sources.palette || {};
  if (restEvent && restEvent.eventLabelId && normalizeHexColor_(labels[restEvent.eventLabelId])) {
    return normalizeHexColor_(labels[restEvent.eventLabelId]);
  }

  const colorIds = [restEvent && restEvent.colorId, calendarAppColorId];
  for (let i = 0; i < colorIds.length; i++) {
    const value = colorIds[i];
    if (!value) continue;
    const directHex = normalizeHexColor_(value);
    if (directHex) return directHex;
    const definition = palette[String(value)];
    const paletteHex = normalizeHexColor_(definition && definition.background);
    if (paletteHex) return paletteHex;
  }

  return normalizeHexColor_(calendarColor) || CONFIG.fallbackEventColor;
}

function normalizeHexColor_(value) {
  return typeof value === 'string' && /^#[0-9A-Fa-f]{6}$/.test(value)
    ? value.toUpperCase()
    : null;
}

function fetchCachedCalendarJson_(cacheKey, url, ttlSeconds, forceRefresh, failureKind) {
  const cache = CacheService.getScriptCache();
  const properties = PropertiesService.getScriptProperties();
  const dataKey = cacheKey + '_data';
  const timeKey = cacheKey + '_at';
  let cached = null;
  const cacheRaw = forceRefresh ? null : cache.get(cacheKey);
  if (cacheRaw) cached = safeJsonParse_(cacheRaw);
  if (!cached) {
    const storedRaw = properties.getProperty(dataKey);
    const storedAt = Number(properties.getProperty(timeKey) || 0);
    if (storedRaw) cached = { value: safeJsonParse_(storedRaw), fetchedAt: storedAt };
  }
  if (!forceRefresh && cached && cached.value && Date.now() - cached.fetchedAt < ttlSeconds * 1000) {
    return cached.value;
  }

  try {
    const value = calendarApiGetJson_(url);
    const entry = { value: value, fetchedAt: Date.now() };
    const encoded = JSON.stringify(entry);
    cache.put(cacheKey, encoded, ttlSeconds);
    properties.setProperty(dataKey, JSON.stringify(value));
    properties.setProperty(timeKey, String(entry.fetchedAt));
    return value;
  } catch (error) {
    logColorMetadataFailure_(failureKind);
    return cached && cached.value ? cached.value : null;
  }
}

function calendarApiGetJson_(url) {
  const token = ScriptApp.getOAuthToken();
  const response = UrlFetchApp.fetch(url, {
    method: 'get',
    muteHttpExceptions: true,
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/json' },
  });
  const status = response.getResponseCode();
  if (status < 200 || status >= 300) throw new Error('Calendar API returned ' + status);
  return JSON.parse(response.getContentText());
}

function safeJsonParse_(text) {
  if (!text) return null;
  try { return JSON.parse(text); } catch (error) { return null; }
}

function cacheHash_(value) {
  const bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, String(value));
  return Utilities.base64EncodeWebSafe(bytes).replace(/=+$/g, '');
}

function logColorMetadataFailure_(kind) {
  Logger.log('Calendar color metadata unavailable (%s); using cached or calendar fallback.', kind);
}

function myStatus_(ev) {
  try { return ev.getMyStatus(); } catch (e) { return null; }
}

// 'yyyy-MM-dd'를 스크립트 시간대의 그날 0시 Date로 바꾼다
function parseLocal_(day, tz) {
  const offset = Utilities.formatDate(new Date(day + 'T12:00:00Z'), tz, 'XXX'); // 예: +09:00
  return new Date(day + 'T00:00:00' + (offset === 'Z' ? '+00:00' : offset));
}

// ---------- GitHub Contents API ----------

function getToken_() {
  const token = PropertiesService.getScriptProperties().getProperty('GITHUB_TOKEN');
  if (!token) {
    throw new Error('스크립트 속성에 GITHUB_TOKEN이 없습니다. 프로젝트 설정(톱니바퀴) → 스크립트 속성에서 추가하세요.');
  }
  return token;
}

function api_(method, payload) {
  const url = 'https://api.github.com/repos/' + CONFIG.owner + '/' + CONFIG.repo + '/contents/' + CONFIG.path
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

function readFile_() {
  const res = api_('get');
  const code = res.getResponseCode();
  if (code === 404) return { data: null, sha: null };
  if (code !== 200) throw new Error('GitHub 읽기 실패 ' + code + ': ' + res.getContentText().slice(0, 200));
  const j = JSON.parse(res.getContentText());
  const text = Utilities.newBlob(Utilities.base64Decode(j.content.replace(/\s/g, ''))).getDataAsString('UTF-8');
  return { data: JSON.parse(text), sha: j.sha };
}

function writeFile_(data, sha, message) {
  // 캘린더 파일은 이 스크립트만 쓰지만, 혹시 충돌하면 최신 sha로 다시 시도한다
  for (let attempt = 0; attempt < 4; attempt++) {
    const body = {
      message: message,
      content: Utilities.base64Encode(JSON.stringify(data, null, 2) + '\n', Utilities.Charset.UTF_8),
      branch: CONFIG.branch,
    };
    if (sha) body.sha = sha;
    const res = api_('put', body);
    const code = res.getResponseCode();
    if (code === 200 || code === 201) return;
    if (code !== 409 && code !== 422) throw new Error('GitHub 저장 실패 ' + code + ': ' + res.getContentText().slice(0, 200));
    Utilities.sleep(1000 * (attempt + 1));
    sha = readFile_().sha;
  }
  throw new Error('GitHub 저장 실패: 충돌이 계속됩니다.');
}

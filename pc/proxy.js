/* =====================================================================
 * Sports Hub 独立本地代理服务（零第三方依赖，Node 18+ 内置 http/fetch）
 *
 * 作用：前端（网页 / Electron WebView / 手机 WebView）受浏览器跨域策略限制，
 *       无法直连不开放 CORS 的国内公开源；本服务在本机（或部署到后端）代为
 *       请求上游公开 JSON，解析成前端统一结构后返回，并补上 CORS 头。
 *
 * 上游均为公开免费、可公开访问的数据；不携带登录态、不抓取付费内容。
 *
 * 启动：  node server.js            （默认端口 8787）
 * 端口：  环境变量 PROXY_PORT=9000 node server.js
 * 健康检查： GET http://127.0.0.1:8787/
 *
 * 端点（返回前端统一结构，与内置 ESPN 适配器输出对齐）：
 *   GET /proxy/cba/games     CBA 赛程/比分（game[]）
 *   GET /proxy/cba/league    CBA 积分榜 + 赛程表
 *   GET /proxy/wcba/games    WCBA 赛程/比分（game[]）
 *   GET /proxy/wcba/league   WCBA 积分榜 + 赛程表
 *   GET /proxy/raw?url=<上游URL>  通用转发（备用，原样回传上游 body）
 * ===================================================================== */

const http = require('http');
const { URL } = require('url');

const PORT = Number(process.env.PROXY_PORT || 8787);
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.234 Safari/537.36';

/* 上游公开端点（如需切换赛季/赛事，只改这里） */
const UPSTREAM = {
  cbaSchedule: 'https://server.cbaleague.com/home/home_schedules',
  wcbaToday:   'https://www.cba.net.cn/datahub/cbamatch/home/todaymatches?competitionId=56',
  wcbaRank:    'https://www.cba.net.cn/datahub/cbamatch/home/matchoutrank?competitionId=56&seasonId=2286',
  txFeeds:     'https://matchweb.sports.qq.com/feeds/type/list',
  txStat:      'https://matchweb.sports.qq.com/kbs/matchStat',
  txDetail:    'https://matchweb.sports.qq.com/kbs/matchDetail',
  txRank:       'https://matchweb.sports.qq.com/team/rank'
};

/* 腾讯体育赛事映射：前端 leagueId → 腾讯 competitionId（mid 前缀）。
 * cid 为空表示该赛事当前赛季/档期在腾讯侧暂无公开赛程流，先留空待补；
 * sport 与前端 LEAGUES 对齐：basketball / football / tennis。 */
const TENCENT_LEAGUES = {
  cba:      { cid: '100009',  sport: 'basketball' },
  epl:      { cid: '10082',   sport: 'football' },
  laliga:   { cid: '10120',   sport: 'football' },
  seriea:   { cid: '10108',   sport: 'football' },
  bundes:   { cid: '10129',   sport: 'football' },
  csl:      { cid: '10542',   sport: 'football' },
  ucl:      { cid: '10046',   sport: 'football' },
  jleague:  { cid: '',        sport: 'football' },
  atp:      { cid: '100309',  sport: 'tennis' },
  wta:      { cid: '',        sport: 'tennis' },
  usopen:   { cid: '',        sport: 'tennis' },
  wimbledon:{ cid: '',        sport: 'tennis' },
  frenchopen:{cid: '',        sport: 'tennis' }
};

/* 代理侧短缓存：减少上游压力，避免刷新被限流（毫秒） */
const CACHE_TTL = 30 * 1000;
const cache = new Map();

async function fetchJson(url, referer) {
  const res = await fetch(url, {
    headers: {
      'User-Agent': UA,
      'Referer': referer || 'https://www.cba.net.cn/',
      'Accept': 'application/json, text/plain, */*'
    }
  });
  if (!res.ok) throw new Error('upstream HTTP ' + res.status);
  return res.json();
}

async function cached(key, ttl, loader) {
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < ttl) return hit.value;
  const value = await loader();
  cache.set(key, { at: Date.now(), value });
  return value;
}

/* 协议相对地址补 https；已是全址则原样返回 */
function secureUrl(u) {
  if (!u) return '';
  if (u.startsWith('//')) return 'https:' + u;
  return u;
}

/* 中文队名无官方缩写：缩写留空，前端只展示全名，避免徽章与全名重复 */
function abbr(name) { return ''; }

function pct(win, lose) {
  const total = Number(win) + Number(lose);
  if (!total) return '—';
  return (Number(win) / total).toFixed(3).replace(/0$/, '');
}

/* ----------------------------- CBA ----------------------------- */

function cbaStatus(row) {
  if (row.HomeTeamScore != null && row.VisitingTeamScore != null) return 'done';
  return 'upcoming';
}

function mapCbaGame(row) {
  const status = cbaStatus(row);
  return {
    id: 'cba-' + row.ScheduleID,
    leagueId: 'cba',
    sport: 'basketball',
    source: 'proxy',
    status,
    dateKey: row.dates || '',
    localTime: row.time || '',
    localTz: 'Asia/Shanghai',
    utc8: (row.dates || '') + (row.time ? ' ' + row.time : ''),
    quarter: status === 'done' ? '完场' : (row.Quarter ? '第' + row.Quarter + '节' : ''),
    clock: '',
    eta: '',
    score: { home: row.HomeTeamScore, away: row.VisitingTeamScore },
    home: {
      id: String(row.HomeTeamID || ''),
      name: row.HomeTeamName || '—',
      abbr: abbr(row.HomeTeamName),
      color: '#22D3EE',
      logo: secureUrl(row.HomeTeamSmallLogo)
    },
    away: {
      id: String(row.VisitingTeamID || ''),
      name: row.VisitingTeamName || '—',
      abbr: abbr(row.VisitingTeamName),
      color: '#F472B6',
      logo: secureUrl(row.VisitingTeamSmallLogo)
    },
    plays: [], shots: [], roster: []
  };
}

function cbaScheduleRows(games) {
  return games.map(g => ({
    date: g.dateKey,
    home: g.home.name,
    away: g.away.name,
    score: g.status === 'upcoming' ? '—' : `${g.score.home == null ? '—' : g.score.home} : ${g.score.away == null ? '—' : g.score.away}`,
    status: g.status
  }));
}

/* 从赛程聚合每队赛季胜/负（上游每条记录带该队当前累计战绩） */
function cbaStandings(rows) {
  const map = new Map();
  const put = (id, name, logo, win, lose) => {
    if (!id || !name) return;
    const w = Number(win) || 0, l = Number(lose) || 0;
    const prev = map.get(String(id));
    if (!prev) map.set(String(id), { name, logo, win: w, lose: l });
    else {
      prev.win = Math.max(prev.win, w);
      prev.lose = Math.max(prev.lose, l);
    }
  };
  rows.forEach(r => {
    put(r.HomeTeamID, r.HomeTeamName, secureUrl(r.HomeTeamSmallLogo), r.HomeTeamWins, r.HomeTeamLosses);
    put(r.VisitingTeamID, r.VisitingTeamName, secureUrl(r.VisitingTeamSmallLogo), r.VisitingTeamWins, r.VisitingTeamLosses);
  });
  return [...map.values()]
    .sort((a, b) => (b.win - a.win) || (a.lose - b.lose))
    .map((t, i) => ({
      rank: i + 1,
      name: t.name,
      abbr: abbr(t.name),
      color: '#22D3EE',
      win: t.win,
      lose: t.lose,
      pct: pct(t.win, t.lose),
      streak: '—'
    }));
}

async function cbaGames() {
  const data = await cached('cba:games', CACHE_TTL, async () => {
    const j = await fetchJson(UPSTREAM.cbaSchedule, 'https://cbaleague.com/');
    const rows = (j && j.data) || [];
    return rows.map(mapCbaGame).filter(Boolean);
  });
  return data;
}

async function cbaLeague() {
  const j = await cached('cba:raw', CACHE_TTL, async () => {
    const r = await fetchJson(UPSTREAM.cbaSchedule, 'https://cbaleague.com/');
    return (r && r.data) || [];
  });
  const games = j.map(mapCbaGame).filter(Boolean);
  return { standings: cbaStandings(j), schedule: cbaScheduleRows(games) };
}

/* ----------------------------- WCBA ----------------------------- */

function mapWcbaGame(g) {
  const hasScore = g.homeScore != null && g.awayScore != null;
  const status = hasScore ? 'done' : 'upcoming';
  return {
    id: 'wcba-' + g.gameId,
    leagueId: 'wcba',
    sport: 'basketball',
    source: 'proxy',
    status,
    dateKey: g.gameDate || '',
    localTime: g.startTime || '',
    localTz: 'Asia/Shanghai',
    utc8: (g.gameDate || '') + (g.startTime ? ' ' + g.startTime : ''),
    quarter: status === 'done' ? '完场' : '',
    clock: '',
    eta: '',
    score: { home: g.homeScore, away: g.awayScore },
    home: {
      id: String(g.homeId || ''),
      name: g.homeName || '—',
      abbr: abbr(g.homeAbbr || g.homeName),
      color: '#22D3EE',
      logo: secureUrl(g.homeLogo)
    },
    away: {
      id: String(g.awayId || ''),
      name: g.awayName || '—',
      abbr: abbr(g.awayAbbr || g.awayName),
      color: '#F472B6',
      logo: secureUrl(g.awayLogo)
    },
    plays: [], shots: [], roster: []
  };
}

function wcbaScheduleRows(games) {
  return games.map(g => ({
    date: g.dateKey,
    home: g.home.name,
    away: g.away.name,
    score: g.status === 'upcoming' ? '—' : `${g.score.home == null ? '—' : g.score.home} : ${g.score.away == null ? '—' : g.score.away}`,
    status: g.status
  }));
}

function wcbaStandings(ranks) {
  return (ranks || []).map((t, i) => ({
    rank: Number(t.rank) || i + 1,
    name: t.teamName || '—',
    abbr: abbr(t.teamName),
    color: '#22D3EE',
    win: Number(t.wins) || 0,
    lose: Number(t.loses) || 0,
    pct: t.winsPct != null && Number(t.winsPct) > 0 ? String(Number(t.winsPct).toFixed(3)) : pct(Number(t.wins) || 0, Number(t.loses) || 0),
    streak: '—'
  }));
}

async function wcbaGames() {
  const j = await cached('wcba:today', CACHE_TTL, async () => {
    const r = await fetchJson(UPSTREAM.wcbaToday, 'https://www.cba.net.cn/');
    return ((r && r.data && r.data.games) || []).map(mapWcbaGame).filter(Boolean);
  });
  return j;
}

async function wcbaLeague() {
  const [today, rank] = await Promise.all([
    cached('wcba:today-raw', CACHE_TTL, async () => {
      const r = await fetchJson(UPSTREAM.wcbaToday, 'https://www.cba.net.cn/');
      return (r && r.data && r.data.games) || [];
    }),
    cached('wcba:rank', CACHE_TTL, async () => {
      const r = await fetchJson(UPSTREAM.wcbaRank, 'https://www.cba.net.cn/');
      return (r && r.data && r.data.ranks) || [];
    })
  ]);
  const games = today.map(mapWcbaGame).filter(Boolean);
  return { standings: wcbaStandings(rank), schedule: wcbaScheduleRows(games) };
}

/* ----------------------------- 腾讯体育适配器 ----------------------------- */

/* 解析可能的 JSONP 包裹（callback({...})），返回 JSON 对象；纯 JSON 直接解析 */
function parseMaybeJsonp(text) {
  let t = String(text || '').trim();
  const m = t.match(/^[A-Za-z_$][\w$]*\(([\s\S]*)\)\s*;?$/);
  if (m) t = m[1];
  try { return JSON.parse(t); } catch (e) { return null; }
}

async function fetchTencent(url) {
  const res = await fetch(url, {
    headers: { 'User-Agent': UA, 'Referer': 'https://sports.qq.com/', 'Accept': 'application/json, text/plain, */*' }
  });
  if (!res.ok) throw new Error('tx upstream HTTP ' + res.status);
  const json = parseMaybeJsonp(await res.text());
  if (!json || json.code !== 0) throw new Error('tx upstream code ' + (json && json.code));
  return json.data || {};
}

/* 北京日期（YYYY-MM-DD），offset 天偏移 */
function txBjDate(offset) {
  const d = new Date(Date.now() + 8 * 3600 * 1000 + offset * 86400000);
  return d.toISOString().slice(0, 10);
}

/* matchType: 1 足球 / 2 篮球 / 10 网球；matchPeriod: 0 未开始 / 1 进行 / 2 完场 */
function txSportOfType(type) {
  if (type === '1') return 'football';
  if (type === '2') return 'basketball';
  if (type === '10') return 'tennis';
  return '';
}
function txStatus(mi) {
  const p = String(mi.matchPeriod || '');
  if (p === '1') return 'live';
  if (p === '2') return 'done';
  if (mi.win === 'left' || mi.win === 'right') return 'done';
  const q = String(mi.quarter || '');
  if (q && q !== '未开始' && !/预约/.test(q)) return 'live';
  return 'upcoming';
}
function txNum(s) { const n = parseInt(s, 10); return isNaN(n) ? null : n; }

function mapTencentGame(mi, lid, cfg) {
  if (!mi || !mi.mid) return null;
  const status = txStatus(mi);
  const sport = txSportOfType(mi.matchType) || cfg.sport;
  const startTime = mi.startTime || '';
  const dateKey = startTime.slice(0, 10);
  const localTime = startTime.slice(11, 16);
  const score = status === 'upcoming' ? { home: null, away: null } : { home: txNum(mi.leftGoal), away: txNum(mi.rightGoal) };
  return {
    id: 'tx-' + mi.mid,
    leagueId: lid,
    sport,
    source: 'tencent',
    status,
    dateKey,
    localTime,
    localTz: 'Asia/Shanghai',
    utc8: startTime,
    quarter: status === 'live' ? (mi.quarter || '') : (status === 'done' ? '完场' : ''),
    clock: '',
    eta: '',
    score,
    home: {
      id: String(mi.leftId || ''),
      name: mi.leftName || '—',
      abbr: '',
      color: '#22D3EE',
      logo: secureUrl(mi.leftBadge)
    },
    away: {
      id: String(mi.rightId || ''),
      name: mi.rightName || '—',
      abbr: '',
      color: '#F472B6',
      logo: secureUrl(mi.rightBadge)
    },
    plays: [], shots: [], roster: []
  };
}

/* 篮球球员：playerStats.left/right 为数组，首项 {head:[...]}，其余为球员 {playerId,pos,pic,onCrt,row:[...]} */
function txBasketballRoster(sideName, sideId, sideArr) {
  const rows = [];
  (sideArr || []).forEach(item => {
    if (!item || !Array.isArray(item.row)) return;
    const r = item.row;
    rows.push({
      id: String(item.playerId || ''),
      num: String(r[0] || ''),
      name: String(r[1] || '—'),
      pos: String(item.pos || ''),
      starter: item.onCrt === '1',
      didNotPlay: item.onCrt !== '1',
      team: sideName,
      teamId: String(sideId || ''),
      min: String(r[2] || '—'), pts: String(r[3] || '—'), reb: String(r[4] || '—'),
      ast: String(r[5] || '—'), stl: String(r[9] || '—'), blk: String(r[10] || '—'),
      logo: secureUrl(item.pic)
    });
  });
  return rows;
}

/* 足球名单：squard[] 首发（left/right），bench.{left,right} 替补 */
function txFootballRoster(squard, bench, leftName, rightName, leftId, rightId) {
  const rows = [];
  const push = (p, side, starter) => {
    if (!p) return;
    rows.push({
      id: String(p.playerId || ''), num: String(p.jerseyNum || ''), name: String(p.playerName || '—'),
      pos: String(p.position || ''), starter,
      team: side === 'left' ? leftName : rightName,
      teamId: String(side === 'left' ? leftId : rightId || ''),
      goals: '—', assists: '—', shots: '—', sot: '—', stl: '—', sv: '—',
      logo: secureUrl(p.playerIcon)
    });
  };
  (squard || []).forEach(it => { push(it.left, 'left', true); push(it.right, 'right', true); });
  ((bench && bench.left) || []).forEach(p => push(p, 'left', false));
  ((bench && bench.right) || []).forEach(p => push(p, 'right', false));
  return rows;
}

/* 足球事件流：进球 / 牌 / 换人 */
function txFootballEvents(evs) {
  return (evs || []).map(e => {
    const period = e.eventPeriod === 'SecondHalf' ? 2 : 1;
    const type = String(e.type || '');
    let result = 'other';
    if (type.includes('进球')) result = 'goal';
    else if (e.cardType) result = 'card';
    else if (type.includes('换人')) result = 'sub';
    return {
      period, clock: String(e.time || '') + "'",
      desc: type + (e.playerName ? ' ' + e.playerName : ''),
      type, result, side: e.side || '',
      scoreValue: 0, scoringPlay: type.includes('进球'), shootingPlay: false,
      score: e.score || '',
      actor: e.playerName ? { name: e.playerName, num: '', pos: '' } : null
    };
  });
}

/* 排名：兼容 list 为队对象数组、list 为分组对象（值为队数组）、或数组内嵌分组对象三种形态 */
function txRankStandings(data, sport) {
  const flat = [];
  const pushItem = item => {
    if (Array.isArray(item)) flat.push(...item);
    else if (item && typeof item === 'object') {
      const v = Object.values(item).find(x => Array.isArray(x));
      if (v) flat.push(...v); else flat.push(item);
    }
  };
  const raw = data.list;
  if (Array.isArray(raw)) raw.forEach(pushItem);
  else if (raw && typeof raw === 'object') Object.values(raw).forEach(pushItem);
  return flat.map((t, i) => {
    const win = Number(t.winMatchCount) || 0;
    const lose = Number(t.lostMatchCount) || 0;
    const pts = Number(t.score);
    return {
      rank: Number(t.serial) || i + 1,
      name: t.teamName || t.name || '—',
      abbr: '',
      color: '#22D3EE',
      win, lose,
      pct: sport === 'football' ? (isNaN(pts) ? '—' : String(pts)) : pct(win, lose),
      streak: '—'
    };
  });
}

async function txGames(lid) {
  const cfg = TENCENT_LEAGUES[lid];
  if (!cfg || !cfg.cid) return [];
  const all = [];
  for (const offset of [0, 1]) {
    const date = txBjDate(offset);
    const data = await cached('tx:feeds:' + cfg.cid + ':' + date, CACHE_TTL, async () => {
      return fetchTencent(UPSTREAM.txFeeds + '?indexID=' + date + '&type=1500&indexKey=' + cfg.cid);
    });
    const list = ((data && data.info && data.info.type1500) || {}).list || [];
    list.forEach(g => all.push(mapTencentGame(g.matchInfo, lid, cfg)));
  }
  const seen = new Set();
  return all.filter(g => { if (!g || seen.has(g.id)) return false; seen.add(g.id); return true; });
}

async function txLeague(lid) {
  const cfg = TENCENT_LEAGUES[lid];
  if (!cfg) return { standings: [], schedule: [] };
  let standings = [];
  if (cfg.cid) {
    try {
      const data = await cached('tx:rank:' + cfg.cid, CACHE_TTL, async () =>
        fetchTencent(UPSTREAM.txRank + '?competitionId=' + cfg.cid));
      standings = txRankStandings(data || {}, cfg.sport);
    } catch (e) { standings = []; }
  }
  const games = await txGames(lid);
  const schedule = games.map(g => ({
    date: g.dateKey, home: g.home.name, away: g.away.name,
    score: g.status === 'upcoming' ? '—' : `${g.score.home == null ? '—' : g.score.home} : ${g.score.away == null ? '—' : g.score.away}`,
    status: g.status
  }));
  return { standings, schedule };
}

/* mid 前缀（腾讯 competitionId）→ 前端 leagueId 反查表，供仅传 mid 的单场详情兜底 */
const CID_TO_LID = {};
Object.entries(TENCENT_LEAGUES).forEach(([lid, cfg]) => { if (cfg.cid) CID_TO_LID[cfg.cid] = lid; });

async function txGame(lid, mid) {
  if (!lid && mid) lid = CID_TO_LID[String(mid).split(':')[0]];
  const cfg = TENCENT_LEAGUES[lid];
  if (!cfg || !mid) return null;
  const games = await txGames(lid);
  let base = games.find(g => g.id === 'tx-' + mid) || null;

  let roster = [], plays = [], shots = [];
  let stat = {};
  try {
    stat = await cached('tx:stat:' + mid, CACHE_TTL, async () =>
      fetchTencent(UPSTREAM.txStat + '?mid=' + mid));
  } catch (e) { stat = {}; }

  const ti = stat.teamInfo || {};
  if (!base && ti.leftName) {
    const mi = {
      mid, startTime: ti.startTime || '', quarter: '', matchPeriod: ti.liveType === '4' ? '2' : '0',
      leftId: ti.leftId, leftName: ti.leftName, leftBadge: ti.leftBadge, leftGoal: ti.leftGoal,
      rightId: ti.rightId, rightName: ti.rightName, rightBadge: ti.rightBadge, rightGoal: ti.rightGoal
    };
    base = mapTencentGame(mi, lid, cfg);
  }
  if (!base) return null;

  if (cfg.sport === 'basketball' && stat.playerStats) {
    roster = [
      ...txBasketballRoster(base.home.name, base.home.id, stat.playerStats.left),
      ...txBasketballRoster(base.away.name, base.away.id, stat.playerStats.right)
    ];
  } else if (cfg.sport === 'football') {
    roster = txFootballRoster(stat.squard, stat.bench, base.home.name, base.away.name, base.home.id, base.away.id);
    plays = txFootballEvents(stat.footballEvents);
  }

  const homeR = roster.filter(r => r.team === base.home.name);
  const awayR = roster.filter(r => r.team === base.away.name);
  return Object.assign({}, base, {
    roster, plays, shots,
    home: Object.assign({}, base.home, { roster: homeR }),
    away: Object.assign({}, base.away, { roster: awayR })
  });
}

/* ----------------------------- HTTP 服务 ----------------------------- */

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type'
  });
  res.end(body);
}

async function handleRaw(req, res, parsed) {
  const target = parsed.searchParams.get('url');
  if (!target) return sendJson(res, 400, { error: 'missing url' });
  try {
    const upstream = await fetch(target, { headers: { 'User-Agent': UA, 'Accept': '*/*' } });
    const text = await upstream.text();
    res.writeHead(upstream.status, {
      'Content-Type': upstream.headers.get('content-type') || 'text/plain; charset=utf-8',
      'Access-Control-Allow-Origin': '*'
    });
    res.end(text);
  } catch (e) {
    sendJson(res, 502, { error: String(e && e.message || e) });
  }
}

const routes = {
  '/proxy/cba/games': () => cbaGames(),
  '/proxy/cba/league': () => cbaLeague(),
  '/proxy/wcba/games': () => wcbaGames(),
  '/proxy/wcba/league': () => wcbaLeague()
};

const server = http.createServer(async (req, res) => {
  const parsed = new URL(req.url, 'http://127.0.0.1');
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type'
    });
    return res.end();
  }
  if (parsed.pathname === '/') return sendJson(res, 200, { ok: true, service: 'sports-hub-proxy', time: new Date().toISOString() });
  if (parsed.pathname === '/proxy/raw') return handleRaw(req, res, parsed);

  /* 腾讯体育转发适配器：league=前端联赛ID；game 另需 mid=<联赛ID:比赛ID> */
  if (parsed.pathname === '/proxy/tencent/games') {
    try { return sendJson(res, 200, { ok: true, data: await txGames(parsed.searchParams.get('league') || '') }); }
    catch (e) { return sendJson(res, 502, { ok: false, error: String(e && e.message || e) }); }
  }
  if (parsed.pathname === '/proxy/tencent/league') {
    try { return sendJson(res, 200, { ok: true, data: await txLeague(parsed.searchParams.get('league') || '') }); }
    catch (e) { return sendJson(res, 502, { ok: false, error: String(e && e.message || e) }); }
  }
  if (parsed.pathname === '/proxy/tencent/game') {
    try {
      const g = await txGame(parsed.searchParams.get('league') || '', parsed.searchParams.get('mid') || '');
      return g ? sendJson(res, 200, { ok: true, data: g }) : sendJson(res, 404, { ok: false, error: 'game not found' });
    } catch (e) { return sendJson(res, 502, { ok: false, error: String(e && e.message || e) }); }
  }

  const handler = routes[parsed.pathname];
  if (!handler) return sendJson(res, 404, { error: 'not found: ' + parsed.pathname });

  try {
    const data = await handler();
    sendJson(res, 200, { ok: true, data });
  } catch (e) {
    sendJson(res, 502, { ok: false, error: String(e && e.message || e) });
  }
});

function startProxy(port) {
  const p = Number(port || PORT);
  return server.listen(p, '127.0.0.1', () => {
    console.log(`[sports-hub-proxy] listening on http://127.0.0.1:${p}`);
  });
}

/* 直接 `node server.js` 时自行启动；被 Electron 主进程 require 时由调用方 startProxy */
if (require.main === module) startProxy(PORT);

module.exports = { startProxy, server };

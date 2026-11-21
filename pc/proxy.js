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
const crypto = require('crypto');

const PORT = Number(process.env.PROXY_PORT || 8787);
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.234 Safari/537.36';

/* 上游公开端点（如需切换赛季/赛事，只改这里） */
const UPSTREAM = {
  cbaSchedule: 'https://server.cbaleague.com/home/home_schedules',
  cbaData:     'https://data-server.cbaleague.com',
  wcbaToday:   'https://www.cba.net.cn/datahub/cbamatch/home/todaymatches?competitionId=56',
  wcbaRank:    'https://www.cba.net.cn/datahub/cbamatch/home/matchoutrank?competitionId=56&seasonId=2286',
  txFeeds:     'https://matchweb.sports.qq.com/feeds/type/list',
  txStat:      'https://matchweb.sports.qq.com/kbs/matchStat',
  txDetail:    'https://matchweb.sports.qq.com/kbs/matchDetail',
  txRank:       'https://matchweb.sports.qq.com/team/rank',
  txRankings:   'https://matchweb.sports.qq.com/match/api/v2/statistic/rankings',
  dqData:       'https://www.dongqiudi.com/data',
  zbRank:       'https://data.zhibo8.cc/manage/public/app.php?_url=/basketball/paihang',
  zbTop:        'https://data.zhibo8.cc/manage/public/app.php?_url=/nba/league_top_data',
  wstMatches:   'https://matches.snooker.web.gc.wstservices.co.uk/v2',
  wstRankings:  'https://rankings.snooker.web.gc.wstservices.co.uk/v2',
  wstImg:       'https://images.gc.wstservices.co.uk/fit-in/700x700/'
};

/* 懂球帝联赛 cid 映射（前端 leagueId → 站点 cid）；仅本轮已验证可解析的足球联赛 */
const DQ_CID = {
  epl: 4, laliga: 3, seriea: 9, bundes: 5, csl: 43, ucl: 6, worldcup: 61
};
/* 懂球帝球员榜 type → 前端排名 key（仅接已验证有效值；其余 type 站点回退射手榜，暂不接） */
const DQ_PERSON_TYPE = { goals: 'goals', assists: 'assists', shots: 'shots' };
/* 直播吧篮球排行/球员榜覆盖的联赛 */
const ZB_BASKET = { nba: 1 };

/* 腾讯体育赛事映射：前端 leagueId → 腾讯 competitionId（mid 前缀）。
 * cid 为空表示该赛事当前赛季/档期在腾讯侧暂无公开赛程流，先留空待补；
 * sport 与前端 LEAGUES 对齐：basketball / football / tennis。 */
const TENCENT_LEAGUES = {
  nba:      { cid: '100000',  sport: 'basketball' },
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
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 60000);
  try {
    const res = await fetch(url, {
      signal: ctrl.signal,
      headers: {
        'User-Agent': UA,
        'Referer': referer || 'https://www.cba.net.cn/',
        'Accept': 'application/json, text/plain, */*'
      }
    });
    if (!res.ok) throw new Error('upstream HTTP ' + res.status);
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
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
    date: g.dateKey, dateRaw: g.dateRaw || g.dateKey || '', utc8: g.utc8 || '',
    home: g.home.name, homeEn: '',
    away: g.away.name, awayEn: '',
    city: g.city || '',
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
    date: g.dateKey, dateRaw: g.dateRaw || g.dateKey || '', utc8: g.utc8 || '',
    home: g.home.name, homeEn: '',
    away: g.away.name, awayEn: '',
    city: g.city || '',
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
    const name = String(r[1] || '—');
    const isTotal = /统计|合计|总分|全队|Total/i.test(name);
    rows.push({
      id: String(item.playerId || ''),
      num: String(r[0] || ''),
      name,
      pos: String(item.pos || ''),
      starter: item.onCrt === '1',
      didNotPlay: item.onCrt !== '1',
      isTotal,
      team: sideName,
      teamId: String(sideId || ''),
      min: String(r[2] || '—'), pts: String(r[3] || '—'), reb: String(r[4] || '—'),
      ast: String(r[5] || '—'), stl: String(r[9] || '—'), blk: String(r[10] || '—'),
      /* 失误列位待完场报文按 head 校准；效率由前端按 PIR 估算，不在此输出 */
      tov: '—',
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

/* 球员榜分组 code → 前端榜单 key（与 SPORT_CFG.rankTabs 对齐） */
const TX_RANK_MAP = {
  basketball: { point:'pts', assist:'ast', rebound:'reb', steal:'stl', block:'blk' },
  football:   { goals:'goals' },
  tennis:     {}
};
/* 解析腾讯 statistic/rankings：返回 { pts:[{name,team,val}], ... }，仅保留有数据的榜 */
function txPlayerRankings(data, sport) {
  const map = TX_RANK_MAP[sport] || {};
  const groups = (data && data.groups) || [];
  const out = {};
  groups.forEach(g => {
    const k = map[g.code]; if (!k) return;
    const rows = ((g.tables || [{}])[0] || {}).rows || [];
    out[k] = rows.map(r => {
      const v = {};
      (r.values || []).forEach(it => { v[it.code] = it.value; });
      const p = v.player || {}, t = v.team || {};
      const name = p.fullCnName || p.cnName || p.enName || '—';
      const en = p.fullEnName || p.enName || '';
      const team = t.cnName || '';
      const val = v.rankValue != null ? String(v.rankValue) : '—';
      return { name, en, team, val };
    }).filter(x => x.name && x.name !== '—');
  });
  return out;
}

async function txGames(lid) {
  const cfg = TENCENT_LEAGUES[lid];
  if (!cfg || !cfg.cid) return [];
  if (lid === 'nba') return []; /* NBA 赛程走 ESPN，腾讯侧仅用于补球员榜 */
  const dates = [0, 1, 2].map(txBjDate);
  const settled = await Promise.allSettled(dates.map(async date => {
    const data = await cached('tx:feeds:' + cfg.cid + ':' + date, CACHE_TTL, async () => {
      return fetchTencent(UPSTREAM.txFeeds + '?indexID=' + date + '&type=1500&indexKey=' + cfg.cid);
    });
    return ((data && data.info && data.info.type1500) || {}).list || [];
  }));
  const all = [];
  settled.forEach(r => { (r.value || []).forEach(g => all.push(mapTencentGame(g.matchInfo, lid, cfg))); });
  const seen = new Set();
  return all.filter(g => { if (!g || seen.has(g.id)) return false; seen.add(g.id); return true; });
}

async function txLeague(lid) {
  const cfg = TENCENT_LEAGUES[lid];
  if (!cfg) return { standings: [], schedule: [], rankings: {} };
  let standings = [], rankings = {};
  if (cfg.cid) {
    try {
      const data = await cached('tx:rank:' + cfg.cid, CACHE_TTL, async () =>
        fetchTencent(UPSTREAM.txRank + '?competitionId=' + cfg.cid));
      standings = txRankStandings(data || {}, cfg.sport);
    } catch (e) { standings = []; }
    try {
      const rk = await cached('tx:rankings:' + cfg.cid, CACHE_TTL, async () =>
        fetchTencent(UPSTREAM.txRankings + '?competition_id=' + cfg.cid + '&season_id=&season_type=&type=2&scene=pc'));
      rankings = txPlayerRankings(rk || {}, cfg.sport);
    } catch (e) { rankings = {}; }
  }
  const games = await txGames(lid);
  const schedule = games.map(g => ({
    date: g.dateKey, dateRaw: g.dateRaw || g.dateKey || '', utc8: g.utc8 || '',
    home: g.home.name, homeEn: '', away: g.away.name, awayEn: '', city: g.city || '',
    score: g.status === 'upcoming' ? '—' : `${g.score.home == null ? '—' : g.score.home} : ${g.score.away == null ? '—' : g.score.away}`,
    status: g.status
  }));
  return { standings, schedule, rankings };
}

/* ----------------------------- 懂球帝（SSR HTML 解析） ----------------------------- */
async function fetchHtml(url, referer) {
  const res = await fetch(url, {
    headers: { 'User-Agent': UA, 'Referer': referer || 'https://www.dongqiudi.com/', 'Accept': 'text/html,*/*' }
  });
  if (!res.ok) throw new Error('upstream HTTP ' + res.status);
  return res.text();
}
function htmlText(s) {
  return String(s || '').replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').trim();
}
/* 足球积分榜行：排名 / 球队 / 赛 / 胜 / 平 / 负 / 进失 / 净胜 / 积分 */
function dqStandings(html) {
  const rows = html.match(/<tr[^>]*dp-row[\s\S]*?<\/tr>/g) || [];
  return rows.map((row, i) => {
    const rank = (row.match(/dp-td--rank[^>]*>([^<]*)</) || [])[1];
    const name = (row.match(/dp-td--team[\s\S]*?dp-team-link[^>]*>([^<]*)</) || [])[1];
    const tds = [...row.matchAll(/dp-td(?:--[\w-]+)?[^>]*>([\s\S]*?)<\/td>/g)].map(m => htmlText(m[1]));
    const win = tds[3], draw = tds[4], lose = tds[5], pts = tds[8];
    return {
      rank: Number(rank) || i + 1,
      name: htmlText(name) || '—', abbr: '', color: '#22D3EE',
      win: Number(win) || 0, draw: Number(draw) || 0, lose: Number(lose) || 0,
      pct: pts || '—', streak: '—'
    };
  }).filter(r => r.name && r.name !== '—');
}
/* 球员榜行：排名 / 球员 / 球队 / 数值 */
function dqRankings(html) {
  const rows = html.match(/<tr[^>]*dp-row[\s\S]*?<\/tr>/g) || [];
  return rows.map(row => {
    const name = (row.match(/dp-td--player[\s\S]*?dp-team-link[^>]*>([^<]*)</) || [])[1];
    const team = (row.match(/dp-td--team[\s\S]*?dp-team-link[^>]*>([^<]*)</) || [])[1];
    const val = (row.match(/dp-td--count[^>]*>([^<]*)</) || [])[1];
    return { name: htmlText(name) || '—', en: '', team: htmlText(team) || '', val: htmlText(val) || '—' };
  }).filter(r => r.name && r.name !== '—');
}
async function dqLeague(lid) {
  const cid = DQ_CID[lid];
  if (!cid) return { standings: [], schedule: [], rankings: {} };
  let standings = [];
  try {
    const html = await cached('dq:st:' + cid, CACHE_TTL, async () =>
      fetchHtml(`${UPSTREAM.dqData}?cid=${cid}&tab=standings`));
    standings = dqStandings(html);
  } catch (e) { standings = []; }
  const rankings = {};
  for (const [k, type] of Object.entries(DQ_PERSON_TYPE)) {
    try {
      const html = await cached('dq:rk:' + cid + ':' + type, CACHE_TTL, async () =>
        fetchHtml(`${UPSTREAM.dqData}?cid=${cid}&tab=person&type=${type}`));
      const rows = dqRankings(html);
      if (rows.length) rankings[k] = rows;
    } catch (e) { /* 该榜暂缺：跳过 */ }
  }
  return { standings, schedule: [], rankings };
}

/* ----------------------------- 直播吧（JSON） ----------------------------- */
const ZB_RANK_MAP = { '得分': 'pts', '篮板': 'reb', '助攻': 'ast', '抢断': 'stl', '盖帽': 'blk' };
function zbStandings(j) {
  const groups = (j.data && j.data.paihang && j.data.paihang.list) || [];
  const out = [];
  groups.forEach(g => {
    (g.teams || []).forEach(t => {
      out.push({
        rank: Number(t['排名']) || 0,
        name: t['球队'] || '—', abbr: '', color: '#22D3EE',
        win: Number(t['胜']) || 0, lose: Number(t['负']) || 0,
        pct: (t['胜率'] != null ? t['胜率'] + '%' : '—'),
        streak: t['连续成绩'] || '—',
        group: g.displayConference || ''
      });
    });
  });
  return out;
}
function zbRankings(j) {
  const leader = (j.data && j.data.dailyplayerleader) || {};
  const data = leader.data || {};
  const out = {};
  Object.keys(data).forEach(zh => {
    const k = ZB_RANK_MAP[zh]; if (!k) return;
    out[k] = (data[zh] || []).map(p => ({
      name: p.playerName || '—', en: '', team: p.teamName || '',
      val: String(p.value == null ? '—' : p.value).replace(/[^\d.\-]/g, '') || '—'
    })).filter(r => r.name && r.name !== '—');
  });
  return out;
}
async function zbLeague(lid) {
  if (!ZB_BASKET[lid]) return { standings: [], schedule: [], rankings: {} };
  let standings = [], rankings = {};
  try {
    const j = await cached('zb:rank', CACHE_TTL, async () =>
      fetchJson(UPSTREAM.zbRank, 'https://www.zhibo8.cc/'));
    standings = zbStandings(j);
  } catch (e) { standings = []; }
  try {
    const j = await cached('zb:top', CACHE_TTL, async () =>
      fetchJson(UPSTREAM.zbTop, 'https://www.zhibo8.cc/'));
    rankings = zbRankings(j);
  } catch (e) { rankings = {}; }
  return { standings, schedule: [], rankings };
}

/* ----------------------------- 斯诺克（WST 官方源） ----------------------------- */
function wstName(p){ p=p||{}; const fn=p.customFirstName||p.firstName||''; const sn=p.customSurname||p.surname||''; return [fn,sn].filter(Boolean).join(' '); }
function wstLogo(p){ const prof=(p&&p.media&&p.media.profile)||''; if(!prof) return ''; const base=/\.(png|jpg|jpeg|webp)$/i.test(prof)?prof:prof+'.png'; return UPSTREAM.wstImg+base; }
function wstAbbr(p){ const sn=String((p&&p.surname)||'').replace(/[^A-Za-z]/g,''); return sn.slice(0,3).toUpperCase(); }
/* 英国本地时间(Europe/London) → 北京时间 ISO；简化 DST：4–10 月 BST(UTC+1)，其余 GMT(UTC+0) */
function wstLondonToBjIso(dt){
  const m=String(dt||'').match(/(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})/);
  if(!m) return '';
  const [,y,mo,da,h,mi,s]=m.map(Number);
  const dst = mo>=4 && mo<=10;
  const utcMs = Date.UTC(y,mo-1,da,h,mi,s) - (dst?3600e3:0);
  return new Date(utcMs + 8*3600e3).toISOString();
}
function wstStatus(a){
  const st=String((a&&a.status)||'').toLowerCase();
  if(/progress|live|playing|ongoing/.test(st)) return 'live';
  if(/complete|finish|done|won|played/.test(st)) return 'done';
  return 'upcoming';
}
function mapWstMatch(a){
  if(!a) return null;
  const at = a.attributes || a;
  const home=at.homePlayer||{}, away=at.awayPlayer||{};
  const iso=wstLondonToBjIso(at.startDateTime);
  const bj=iso?new Date(iso):null;
  const dateRaw=bj?bj.toISOString().slice(0,10):'';
  const localTime=bj?String(bj.getUTCHours()).padStart(2,'0')+':'+String(bj.getUTCMinutes()).padStart(2,'0'):'';
  const status=wstStatus(at);
  const hs=at.homePlayerScore==null?null:Number(at.homePlayerScore);
  const as=at.awayPlayerScore==null?null:Number(at.awayPlayerScore);
  return {
    id:'snooker-'+a.id, leagueId:'snooker', sport:'billiards', source:'wst',
    status, dateKey:dateRaw, localTime, localTz:'Asia/Shanghai', utc8:iso,
    quarter:'', clock:'', eta:'',
    score: status==='upcoming'?{home:null,away:null}:{home:hs,away:as},
    home:{id:String(home.playerID||''), name:wstName(home), abbr:wstAbbr(home), color:'#22D3EE', logo:wstLogo(home)},
    away:{id:String(away.playerID||''), name:wstName(away), abbr:wstAbbr(away), color:'#F472B6', logo:wstLogo(away)},
    venueCity:'', plays:[], shots:[], roster:[]
  };
}
async function wstGames(){
  const j=await cached('wst:matches', 3600*1000, async ()=> fetchJson(UPSTREAM.wstMatches, 'https://www.wst.tv/'));
  return ((j&&j.data)||[]).map(mapWstMatch).filter(Boolean);
}
function wstStandings(rank){
  const pos=(rank&&rank.attributes&&rank.attributes.positions)||[];
  return pos.map(p=>{ const pl=p.player||{}; return {
    rank:Number(p.position)||0, name:wstName(pl), abbr:wstAbbr(pl), color:'#22D3EE', logo:wstLogo(pl),
    win:null, lose:null, pct: p.prizeMoney!=null?('£'+Number(p.prizeMoney).toLocaleString('en-GB')):'—', streak:'—'
  };});
}
async function wstLeague(){
  const settled = await Promise.allSettled([
    cached('wst:rankings', 12*3600*1000, async ()=> fetchJson(UPSTREAM.wstRankings, 'https://www.wst.tv/')),
    wstGames()
  ]);
  const rkRaw = settled[0].status==='fulfilled' ? settled[0].value : null;
  const games = settled[1].status==='fulfilled' ? settled[1].value : [];
  const ranks=(rkRaw&&rkRaw.data)||[];
  const world=ranks.find(r=>/world/i.test(r.attributes&&r.attributes.name))||ranks[0];
  const standings=world?wstStandings(world):[];
  const schedule=games.map(g=>({date:g.dateKey,dateRaw:g.dateKey,utc8:g.utc8,home:g.home.name,away:g.away.name,city:'',score:g.status==='upcoming'?'—':`${g.score.home} : ${g.score.away}`,status:g.status}));
  return { standings, schedule, rankings:{} };
}

/* ----------------------------- CBA 官网数据站（data-server，AES-128-ECB 响应解密） ----------------------------- */
/* 密钥为前端 JS 公开硬编码；仅取公开免费数据，不带登录态、不触付费内容 */
const CBA_DATA_KEY = Buffer.from('uVayqL4ONKjFbVzQ', 'utf8');
const CBA_REFERER = 'https://www.cbaleague.com/';

function cbaDecrypt(raw) {
  if (raw == null) return null;
  const text = String(typeof raw === 'string' ? raw : JSON.stringify(raw)).trim();
  if (!text) return null;
  try {
    const d = crypto.createDecipheriv('aes-128-ecb', CBA_DATA_KEY, null);
    d.setAutoPadding(true);
    const out = Buffer.concat([d.update(Buffer.from(text, 'base64')), d.final()]).toString('utf8');
    return JSON.parse(out);
  } catch (e) {
    try { return JSON.parse(text); } catch (_) { return null; }
  }
}

async function cbaRequest(method, path, body) {
  const res = await fetch(UPSTREAM.cbaData + path, {
    method,
    headers: {
      'User-Agent': UA,
      'Referer': CBA_REFERER,
      'Accept': 'application/json, */*',
      'Content-Type': 'application/json'
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15000)
  });
  if (!res.ok) throw new Error('cba upstream HTTP ' + res.status);
  return cbaDecrypt(await res.text());
}

function cbaNormName(n) {
  return String(n || '').replace(/\s+/g, '').replace(/(俱乐部|篮球队|男篮|队)$/g, '');
}
function cbaNameMatch(a, b) {
  const x = cbaNormName(a), y = cbaNormName(b);
  if (!x || !y) return false;
  return x === y || x.includes(y) || y.includes(x);
}

async function cbaDay(day) {
  const j = await cached('cba:day:' + day, CACHE_TTL, async () =>
    cbaRequest('GET', '/api/schedules/day?day=' + day + '&auto=0'));
  return Array.isArray(j) ? j : ((j && j.records) || []);
}

/* 按日期 + 主客队名在官网当天赛程中定位 scheduleId（队名跨源为赞助名/简称，做宽松包含匹配） */
async function cbaFindSchedule(dateKey, homeName, awayName) {
  if (!dateKey) return null;
  const rows = await cbaDay(dateKey);
  return rows.find(r => cbaNameMatch(homeName, r.homeTeamName) && cbaNameMatch(awayName, r.visitingTeamName)) ||
         rows.find(r => cbaNameMatch(homeName, r.visitingTeamName) && cbaNameMatch(awayName, r.homeTeamName)) || null;
}

function cbaPlayResult(text) {
  if (/暂停/.test(text)) return 'timeout';
  if (/换人|换下|换上/.test(text)) return 'sub';
  if (/犯规/.test(text)) return 'foul';
  if (/失误/.test(text)) return 'turnover';
  if (/断下|抢断/.test(text)) return 'steal';
  if (/走步|三秒|24秒|8秒|5秒|出界|违例/.test(text)) return 'violation';
  if (/助攻/.test(text)) return 'assist';
  if (/篮板/.test(text)) return 'rebound';
  if (/不中|未进|打铁/.test(text)) return 'miss';
  if (/命中|得分|罚中|进球|入网/.test(text)) return 'made';
  return 'other';
}
function cbaClock(rec) {
  const m = String(rec.minutes == null ? '' : rec.minutes).padStart(2, '0');
  const s = String(rec.seconds == null ? '' : rec.seconds).padStart(2, '0');
  return (m && s !== 'NaN') ? m + ':' + s : '';
}
function mapCbaPlay(rec, homeColor, awayColor) {
  const names = Array.isArray(rec.playerCNNames) ? rec.playerCNNames.filter(Boolean) : [];
  const text = rec.textualFormat || rec.btTextualFormat || '';
  const side = rec.color === homeColor ? 'home' : rec.color === awayColor ? 'away' : '';
  return {
    period: Number(rec.quarterNum) || 1,
    clock: cbaClock(rec),
    desc: text, descZh: text,
    type: 'cba', result: cbaPlayResult(text), side,
    scoreValue: 0,
    scoringPlay: /命中|得分|罚中|入网/.test(text),
    shootingPlay: /跳投|上篮|扣篮|三分|两分|罚球|投篮/.test(text),
    score: (rec.homeScore != null && rec.visitorScore != null) ? rec.homeScore + ':' + rec.visitorScore : '',
    actor: names[0] ? { name: names[0], num: '', pos: '' } : null
  };
}

async function cbaEvents(sid) {
  const plays = [];
  for (let q = 1; q <= 4; q++) {
    let j;
    try {
      j = await cached('cba:ev:' + sid + ':' + q, CACHE_TTL, async () =>
        cbaRequest('POST', '/api/schedule-eventses/filterPage?startNum=0', { scheduleId: sid, quarterNum: q }));
    } catch (e) { continue; }
    const records = (j && j.records) || (Array.isArray(j) ? j : []);
    (records || []).forEach(r => plays.push(r));
  }
  return plays;
}

function mapCbaPlayer(p, teamName, teamId) {
  return {
    id: String(p.playerId || p.id || ''),
    num: String(p.number || ''),
    name: p.cnAlias || p.cnName || '—',
    pos: p.positionDescription || p.position || '',
    starter: null, didNotPlay: false,
    team: teamName, teamId: String(teamId || ''),
    min: '—', pts: '—', reb: '—', ast: '—', stl: '—', blk: '—', tov: '—',
    logo: secureUrl(p.playerPic)
  };
}

async function cbaRoster(sid, homeName, awayName) {
  const j = await cached('cba:roster:' + sid, CACHE_TTL, async () =>
    cbaRequest('GET', '/api/schedules/getPlayersByScheduleId/' + sid));
  const rows = [];
  (j.hostPlayers || []).forEach(p => rows.push(mapCbaPlayer(p, homeName, p.teamCbaId)));
  (j.guestPlayers || []).forEach(p => rows.push(mapCbaPlayer(p, awayName, p.teamCbaId)));
  return rows;
}

/* 聚合官网单场：动作流 + 兜底名单 + 基本信息校准；无投篮坐标 */
async function cbaDetail(base) {
  const sched = await cbaFindSchedule(base.dateKey, base.home.name, base.away.name);
  if (!sched) return null;
  const sid = sched.scheduleId;
  const baseInfo = await cbaRequest('GET', '/api/team-match-datas/getMatchDataReport/baseInfo/' + sid).catch(() => null);
  const rawEvents = await cbaEvents(sid);
  const homeColor = (baseInfo && baseInfo.hostColor) || sched.homeColor || base.home.color;
  const awayColor = (baseInfo && baseInfo.guestColor) || sched.visitingColor || base.away.color;
  const plays = rawEvents.map(r => mapCbaPlay(r, homeColor, awayColor));
  let roster = [];
  try { roster = await cbaRoster(sid, base.home.name, base.away.name); } catch (e) { roster = []; }
  return { scheduleId: sid, baseInfo, plays, roster, homeColor, awayColor };
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
    /* 完场标志以 matchPeriod 为准（2=完场，1=进行中，0=未开始）；liveType 仅作兜底 */
    const mi = {
      mid, startTime: ti.startTime || '', quarter: ti.quarterDesc || '',
      matchPeriod: ti.matchPeriod || (ti.liveType === '4' ? '2' : '0'),
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

  /* CBA：腾讯无动作流，回退官网数据站（AES 解密）补中文动作流；名单/队标/队色仅在腾讯缺失时兜底 */
  if (lid === 'cba') {
    try {
      const cd = await cbaDetail(base);
      if (cd) {
        if (!plays.length && cd.plays.length) plays = cd.plays;
        if (!roster.length && cd.roster.length) roster = cd.roster;
        if (cd.baseInfo) {
          if (!base.home.logo && cd.baseInfo.hostTeamLogo) base.home.logo = secureUrl(cd.baseInfo.hostTeamLogo);
          if (!base.away.logo && cd.baseInfo.guestTeamLogo) base.away.logo = secureUrl(cd.baseInfo.guestTeamLogo);
          if (!base.home.color && cd.baseInfo.hostColor) base.home.color = cd.baseInfo.hostColor;
          if (!base.away.color && cd.baseInfo.guestColor) base.away.color = cd.baseInfo.guestColor;
        }
      }
    } catch (e) { /* 官网不可用：保留腾讯现状，不阻断 */ }
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

/* ESPN 公开 API 白名单透传：浏览器直连 ESPN 存在 SSL/CORS 受限，统一经本地代理出口 */
const ESPN_HOST = 'site.api.espn.com';
async function handleEspn(req, res, parsed) {
  const target = parsed.searchParams.get('url');
  if (!target) return sendJson(res, 400, { error: 'missing url' });
  let u;
  try { u = new URL(target); } catch (e) { return sendJson(res, 400, { error: 'bad url' }); }
  if (u.hostname !== ESPN_HOST) return sendJson(res, 403, { error: 'espn host only: ' + u.hostname });
  try {
    const upstream = await fetch(target, {
      headers: { 'User-Agent': UA, 'Referer': 'https://www.espn.com/', 'Accept': 'application/json, */*' }
    });
    const text = await upstream.text();
    res.writeHead(upstream.status, {
      'Content-Type': upstream.headers.get('content-type') || 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*'
    });
    res.end(text);
  } catch (e) {
    sendJson(res, 502, { error: String(e && e.message || e) });
  }
}

/* 聚合全量赛程：代理内部并发拉取腾讯各联赛 + CBA/WCBA/斯诺克，一次返回按 id 去重后的 game[]；
   前端冷启动只发一条请求，避免浏览器对本地代理的多连接并发排队。 */
async function allGames() {
  const txLids = Object.keys(TENCENT_LEAGUES).filter(l => l !== 'nba');
  /* 单源 3s 超时降级：慢联赛/上游不拖住整份聚合，超时按空列表处理 */
  const withTO = (p, ms) => Promise.race([Promise.resolve(p), new Promise(res => setTimeout(() => res([]), ms))]);
  const settled = await Promise.allSettled([
    ...txLids.map(lid => withTO(txGames(lid), 3000)),
    withTO(wcbaGames(), 3000), withTO(wstGames(), 3000)
  ]);
  const out = []; const seen = new Set();
  settled.forEach(r => {
    (r.value || []).forEach(g => { if (g && g.id && !seen.has(g.id)) { seen.add(g.id); out.push(g); } });
  });
  return out;
}

const routes = {
  '/proxy/cba/games': () => cbaGames(),
  '/proxy/cba/league': () => cbaLeague(),
  '/proxy/wcba/games': () => wcbaGames(),
  '/proxy/wcba/league': () => wcbaLeague(),
  '/proxy/snooker/games': () => wstGames(),
  '/proxy/snooker/league': () => wstLeague()
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
  if (parsed.pathname === '/proxy/espn') return handleEspn(req, res, parsed);

  /* 聚合全量赛程：一条请求返回代理侧全部联赛合并去重后的 game[] */
  if (parsed.pathname === '/proxy/games/all') {
    try { return sendJson(res, 200, { ok: true, data: await allGames() }); }
    catch (e) { return sendJson(res, 502, { ok: false, error: String(e && e.message || e) }); }
  }

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

  /* 懂球帝：足球积分榜 + 射手/助攻/射门榜（SSR HTML 解析） */
  if (parsed.pathname === '/proxy/dongqiudi/league') {
    try { return sendJson(res, 200, { ok: true, data: await dqLeague(parsed.searchParams.get('league') || '') }); }
    catch (e) { return sendJson(res, 502, { ok: false, error: String(e && e.message || e) }); }
  }
  /* 直播吧：NBA 东西部排行 + 得分/篮板/助攻/抢断/盖帽榜（JSON） */
  if (parsed.pathname === '/proxy/zhibo8/league') {
    try { return sendJson(res, 200, { ok: true, data: await zbLeague(parsed.searchParams.get('league') || '') }); }
    catch (e) { return sendJson(res, 502, { ok: false, error: String(e && e.message || e) }); }
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

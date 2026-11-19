# Sports Hub 本地/云端代理服务

## 用途

前端页面（网页、Electron、手机 WebView）受浏览器跨域策略限制，只能直连开放 CORS 的公开 JSON（如 ESPN）。国内公开源**不开放跨域头**，页面内直接请求会被拦截。本服务在本机（或部署到后端）代为请求上游公开数据，解析成前端统一结构后返回，并补上 CORS 头，供各端共用。

> 合规边界：仅请求公开免费、可公开访问的数据；不携带登录态，不抓取付费/受限内容。

## 运行环境

- Node.js 18+（内置 `http` / `fetch`，无第三方依赖，无需 `npm install`）

## 启动

```bash
cd proxy
node server.js
# 默认监听 http://127.0.0.1:8787
# 自定义端口：
PROXY_PORT=9000 node server.js
```

健康检查：`GET http://127.0.0.1:8787/` → `{ "ok": true, "service": "sports-hub-proxy" }`

模块两用：被 Electron 主进程 `require` 时导出 `startProxy(port)`，不自行监听；直接 `node server.js` 时自行启动。

## 端点

| 端点 | 说明 | 上游 |
|---|---|---|
| `GET /proxy/tencent/games?league=<id>` | 腾讯赛程/比分（统一 game[]） | 腾讯体育 feeds |
| `GET /proxy/tencent/league?league=<id>` | 腾讯积分榜 + 赛程表 | 腾讯体育排名 / feeds |
| `GET /proxy/tencent/game?mid=<联赛ID:比赛ID>` | 单场名单 / 技术统计 / 事件流（league 可省略，按 mid 前缀反查） | 腾讯体育单场统计 / 详情 |
| `GET /proxy/wcba/games` | WCBA 赛程/比分（旧官网回退） | WCBA 公开数据接口 |
| `GET /proxy/wcba/league` | WCBA 积分榜 + 赛程表 | 同上 |
| `GET /proxy/cba/games`、`/proxy/cba/league` | 旧官网 CBA 端点（保留备用，前端当前走腾讯） | CBA 旧官网 |
| `GET /proxy/raw?url=<上游URL>` | 通用转发（备用，原样回传上游 body） | 任意公开 URL |

响应统一为 `{ "ok": true, "data": ... }`；上游失败返回 `502` 与 `{ "ok": false, "error": ... }`。代理侧带短缓存。

## 与前端的关系

- 前端 `ProxyAdapter` / `TencentAdapter` 请求本服务；代理基址在「设置 → 通用 → 代理服务地址」配置，默认 `http://127.0.0.1:8787`。
- ESPN 直连为主源；ESPN 未覆盖的国内联赛由代理回退，腾讯适配器覆盖 CBA、足球、网球相关项目。
- 切换到云端部署时，把前端代理地址改成云端域名即可，前端代码无需改动。

## 当前数据范围与限制

- **腾讯源**：CBA 赛程/比分/名单/个人统计；足球五大联赛、中超、欧冠积分榜与赛程；网球 ATP 等赛程。日职联、WTA、美网/温网/法网赛事 ID 待有赛程时补录。
- **WCBA**：今日赛程、比分、积分榜；完整历史赛程端点待补充。
- 腾讯源暂无投篮坐标与逐回合动作流；部分名单/事件字段随上游开放程度补充。

## 跨端说明

- PC 端：Electron 主进程启动时内嵌拉起本服务（`proxy.js`），或由用户手动 `node server.js`。
- 手机端：WebView 内无法运行 Node，需把本服务部署到可公网访问的后端，并在设置里填云端地址。

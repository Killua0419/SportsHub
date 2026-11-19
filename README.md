# 赛事中心 Sports Hub

跨端体育赛事信息应用（v0.1.0 初始基线）：单文件原生 JS 页面 + Electron 桌面壳 + 安卓 WebView 壳，数据经统一适配层多源接入。

覆盖篮球（NBA / CBA / NCAA / WNBA / WCBA）、足球（英超 / 西甲 / 意甲 / 德甲 / 中超 / 城市联赛 / 欧冠 / 日职联 / 世界杯）、网球（WTA / ATP / 美网 / 温网 / 法网）、桌球、电竞（英雄联盟 / Dota2 / 王者荣耀 / CS2 / 穿越火线 / 守望先锋 2 等）。

## 目录结构

```
SportsHub/
├── web/                  # 单文件响应式页面（数据源，PC / 手机壳都引用同源副本）
│   └── 赛事中心.html
├── proxy/                # 本地/云端代理服务（Node 零依赖，转发不开放 CORS 的公开源）
│   └── server.js
├── pc/                   # PC 端：Electron 桌面壳工程
├── mobile/android/       # 手机端：原生 WebView 混合壳 Android 工程
├── docs/                 # 架构说明、更新日志、GitHub 上传方案
├── releases/             # 打包产物（exe / zip / apk），大二进制不进 git，走 Release 附件
└── .gitignore
```

## 三端关系

| 端 | 形态 | 说明 |
| --- | --- | --- |
| Web 原型 | 单文件 HTML | hash 路由、原生 JS、内联三语（简中/繁中/英文），浏览器直接打开 |
| PC | Electron 桌面壳 | 加载本地页面；主进程内嵌拉起本地代理，宽屏窗口 |
| 手机 | WebView 混合壳 APK | 系统 WebView 离线加载本地资源，不常驻后台；界面复用响应式布局 |

## 本地运行

- **Web**：浏览器直接打开 `web/赛事中心.html`。
- **代理**（需代理的国内源）：Node 18+，无需 `npm install`：
  ```bash
  cd proxy
  node server.js          # 默认 http://127.0.0.1:8787
  ```
- **PC**：
  ```bash
  cd pc
  npm install
  npm start               # 开发预览
  ```
- **安卓**：用 Android Studio 打开 `mobile/android/`，或命令行 `./gradlew assembleRelease` 出 APK。

## 数据源与适配层

| 适配器 | 数据源 | 覆盖范围 | 取数方式 |
| --- | --- | --- | --- |
| EspnAdapter | ESPN 公开 API | NBA、WNBA、NCAA 等 | 直连 |
| ProxyAdapter | 本地代理回退 | WCBA 等旧官网源 | 经代理转发 |
| TencentAdapter | 本地代理转发腾讯体育 | CBA、五大联赛 / 欧冠 / 日职联、WTA / ATP / 大满贯 | 经代理转发 |

- 所有数据经 `SportsProvider` 统一出口；新增数据源只加 Adapter，不改视图。
- 仅取公开免费结构化数据，不携带登录态，不抓取付费内容与视频回放。
- 未接入或无当期赛程的项目显示空态「联赛数据待接入」。

## 打包产物

PC 安装包 / 便携 exe / 绿色 zip 与安卓 APK 统一放在 `releases/`，作为 GitHub Release 附件上传，不纳入仓库源码。

## 已知遗留

- 日职联、WTA、美网 / 温网 / 法网的腾讯赛事 ID 待有赛程时补录。
- CBA 等腾讯源暂无投篮坐标与逐回合动作流；足球球员名中文化待统一。
- 当前 APK 为 debug 签名，正式发布前替换自有 release 签名密钥。
